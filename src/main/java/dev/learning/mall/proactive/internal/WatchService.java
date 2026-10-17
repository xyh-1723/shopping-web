package dev.learning.mall.proactive.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.proactive.api.WatchView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「关注 / 提醒」服务——主动服务的入口。
 *
 * <p><b>为什么值得做</b>：专业导购和搜索框的差别，很大一部分在于<b>谁先开口</b>。
 * 用户说「有点贵，我再看看」然后就走了，这是电商里最常见的流失路径；
 * 而「你上次看的那件降了 15 块」是一条能把人叫回来的消息。
 * 关注这个动作把「用户的兴趣」变成了一条可执行的信号，之后系统就有了先开口的理由。
 *
 * <p><b>为什么不能每次都提醒</b>：提醒的边际价值衰减得极快。第二次提醒还有用，
 * 第五次就是骚扰，第十次用户会关掉整个通知。所以这里的做法是「提醒一次就重新武装」——
 * 提醒后把基线价格重置为当时的现价，只有当价格<b>再次</b>下跌到新的基线之下才会再提醒。
 * 关注行因此同时是「用户的兴趣」和「已经提醒到什么程度」的记录。
 *
 * <p>写入与扫描都要求 username 显式传入（不接受模型自报），这是「我的关注」这类数据的基本要求。
 */
@Service
public class WatchService {
    /**
     * 一次扫描的结果，供测试断言与运营观测。
     *
     * @param remaining 本轮之后还在排队等下一轮的候选数。持续不为零 = 有人在被系统性跳过。
     */
    public record ScanResult(int scanned, int priceAlerts, int stockAlerts, int remaining) {}

    private static final int MAX_WATCHES_PER_USER = 30;

    private final ProactiveRepository repository;
    private final ProductCatalog catalog;
    private final ProactiveProperties properties;

    public WatchService(ProactiveRepository repository, ProductCatalog catalog, ProactiveProperties properties) {
        this.repository = repository;
        this.catalog = catalog;
        this.properties = properties;
    }

    public List<WatchView> watchesOf(String username) {
        return repository.watchesOf(username).stream().map(WatchService::toView).toList();
    }

    /**
     * 关注一件商品。以<b>关注时刻</b>的价格与库存作为基线：
     * 之后低于这个价 = 降价提醒；现在没货、之后有货 = 到货提醒。
     */
    @Transactional
    public WatchView watch(String username, long productId) {
        ProductView product = catalog.get(productId);
        if (repository.countWatches(username) >= MAX_WATCHES_PER_USER
                && repository.watchOf(username, productId).isEmpty()) {
            throw dev.learning.mall.common.BusinessException.badRequest("WATCH_LIMIT",
                    "最多同时关注 " + MAX_WATCHES_PER_USER + " 件商品");
        }
        boolean inStock = product.stock() > 0;
        repository.upsert(username, productId, product.price(), inStock);
        return repository.watchOf(username, productId).map(WatchService::toView).orElseThrow();
    }

    @Transactional
    public void unwatch(String username, long productId) {
        repository.delete(username, productId);
    }

    /**
     * 扫描并处理<b>一批</b>满足条件的关注。定时任务与「改价事件」都走这里。
     *
     * <p><b>为什么是一批而不是全部</b>：一次处理 10 万条会把事务拖成分钟级，期间锁住的行、
     * 占用的连接都会牵连正常交易。而 {@code maxAlertsPerScan} 封顶之后，
     * 每个用户最坏情况也只是「多等一轮」——延迟几分钟，而不是被永久跳过。
     * 这正是要配 {@code last_scanned_at} 游标的原因：封顶不能以牺牲队尾为代价。
     *
     * <p>整个扫描在一个事务里：提醒与「已提醒到什么程度」的更新必须同生共死，
     * 否则一旦中间失败，就会出现「提醒发过了但基线没重置」——下一次扫描重复提醒。
     * 这也是一处「事务边界应当贴合业务不变量」的例子。
     *
     * <p><b>关于「要不要加提醒冷却」：</b>不需要。每次降价提醒后基线会被重置为当时的现价，
     * 于是某件商品必须<b>跌破上次通知时的价位</b>才会再提醒——价格来回滚（100→95→100→96）
     * 天然会被过滤掉，只有真的创了新低才会发第二条。换句话说，重复抑制已经由基线机制承担了，
     * 再加一层时间冷却只会把「再跌一次再提醒一次」这条真实有用的语义一起吞掉。
     */
    @Transactional
    public ScanResult scan() {
        if (!properties.isEnabled()) return new ScanResult(0, 0, 0, 0);
        List<ProactiveRepository.WatchRow> rows = repository.watchesToScan(properties.getMaxAlertsPerScan());
        int priceAlerts = 0;
        int stockAlerts = 0;
        for (ProactiveRepository.WatchRow row : rows) {
            // 扫到就推进游标：不把「这次没发提醒」的人永远留在队首（详见 repository 说明）。
            repository.markScanned(row.username(), row.productId());
            if (row.price().compareTo(row.baselinePrice()) < 0) {
                repository.insertAlert(row.username(), row.productId(), "PRICE_DROP",
                        "「" + row.productName() + "」降价了",
                        "从 ¥" + plain(row.baselinePrice()) + " 降到 ¥" + plain(row.price()) + "。");
                repository.rearmPrice(row.username(), row.productId(), row.price());
                priceAlerts++;
            } else if (!row.inStockAtWatch() && row.stock() > 0) {
                repository.insertAlert(row.username(), row.productId(), "BACK_IN_STOCK",
                        "「" + row.productName() + "」补货了",
                        "之前售罄的商品现在有货了，剩余 " + row.stock() + " 件。");
                repository.markInStock(row.username(), row.productId());
                stockAlerts++;
            }
        }
        return new ScanResult(rows.size(), priceAlerts, stockAlerts, repository.countPendingScans());
    }

    private static WatchView toView(ProactiveRepository.WatchRow row) {
        return new WatchView(row.productId(), row.productName(), row.illustration(), row.category(),
                row.price(), row.baselinePrice(), row.stock(), row.stock() > 0);
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
