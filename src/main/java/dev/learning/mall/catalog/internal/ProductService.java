package dev.learning.mall.catalog.internal;

import dev.learning.mall.catalog.api.*;
import dev.learning.mall.common.BusinessException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService implements ProductCatalog {
    /** 价格上限：防止手误把 39 输成 390000；下限 1 分，不允许白送。 */
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999.00");

    /** actor 列为 NOT NULL：拿不到操作者时用它占位，而不是让整笔事务被数据库拒掉。 */
    private static final String ACTOR_UNKNOWN = "unknown";

    private final ProductRepository repository;
    private final StockLedgerRepository ledger;
    private final ApplicationEventPublisher events;

    public ProductService(ProductRepository repository, StockLedgerRepository ledger,
            ApplicationEventPublisher events, MeterRegistry registry) {
        this.repository = repository;
        this.ledger = ledger;
        this.events = events;
        // 售罄 Gauge（D2）采用<b>读数时实时查库</b>，而不是「启动查一次 + 商品变更事件刷新」：
        // 下单预占 / 取消归还 / 退款回补也改 stock，但它们（刻意）不发 ProductChangedEvent——
        // 靠事件刷新会在每一次成交后静默漂移。一条 COUNT 在当前数据量下是亚毫秒级，
        // 用极小的读开销换掉「指标与真相不一致」这一整类事故。
        Gauge.builder("mall.catalog.sold_out", repository, repo -> (double) repo.soldOutCount())
                .description("售罄（stock = 0）的商品种数，读数时实时统计")
                .register(registry);
    }

    public ProductPage search(ProductQuery query) { return repository.search(query); }

    public ProductPage searchOfMerchant(long merchantId, ProductQuery query) {
        return repository.search(query, merchantId);
    }

    public MerchantStockSummary stockSummaryOfMerchant(long merchantId) {
        return repository.summaryOfMerchant(merchantId);
    }

    public List<ProductView> sampleAcrossCategories(int perCategory, int limit) {
        return repository.sampleAcrossCategories(perCategory, limit);
    }

    public LowStockReport lowStock(double threshold, int limit) {
        return repository.lowStock(threshold, limit);
    }

    public ProductView get(long id) {
        return repository.find(id).orElseThrow(() -> BusinessException.notFound("商品不存在"));
    }

    @Override
    public List<ProductView> findByIds(List<Long> ids) {
        return repository.findAll(ids);
    }

    /**
     * 改价，并广播 {@link ProductChangedEvent}。
     *
     * <p>发布事件而不是直接调用关注扫描，是为了让商品模块不依赖主动服务：
     * 商品模块不知道也不关心有谁在监听价格变化。新增玩法（降价提醒、比价、价格趋势）
     * 不需要再回来改这个类。
     */
    @Transactional
    public ProductView updatePrice(long productId, BigDecimal price) {
        if (price == null || price.compareTo(MIN_PRICE) < 0 || price.compareTo(MAX_PRICE) > 0) {
            throw BusinessException.badRequest("INVALID_PRICE",
                    "价格必须在 " + MIN_PRICE + " 到 " + MAX_PRICE + " 之间");
        }
        ProductView before = get(productId);
        BigDecimal normalized = price.setScale(2, RoundingMode.HALF_UP);
        repository.updatePrice(productId, normalized);
        ProductView after = get(productId);
        if (before.price().compareTo(after.price()) != 0) {
            events.publishEvent(new ProductChangedEvent(productId, ProductChangedEvent.Change.PRICE));
        }
        return after;
    }

    /**
     * 改库存（上架 / 补货），同样广播事件。
     *
     * <p>只有「从 0 变到有货」才会真正触发补货提醒，判断交给订阅方——
     * 商品模块只负责说「库存变了」。这条边界让「什么算补货」这种业务口径
     * 只存在于一个地方。
     *
     * <p>流水记在这里而不是调用方：全项目改 {@code product.stock} 的入口只有
     * {@link #reserve} / {@link #release} / 本方法三个，其中<b>补货最容易漏</b>——
     * 它不走订单，写在订单模块里根本记不到。而补货恰恰是最需要对账的变动：
     * 少了它，「现货 == 流水之和」这条恒等式会凭空少掉一大块。
     */
    @Transactional
    public ProductView updateStock(long productId, int stock, String actor) {
        if (stock < 0 || stock > 9999) {
            throw BusinessException.badRequest("INVALID_STOCK", "库存必须在 0 到 9999 之间");
        }
        ProductView before = get(productId);
        int beforeStock = repository.stockForUpdate(productId);
        repository.setStock(productId, stock);
        // 无变化就不写流水：delta = 0 的行会被 ck_ledger_delta_nonzero 拒绝，
        // 在这里挡掉比让数据库抛异常更诚实（这不是错误，只是没发生）。
        if (beforeStock != stock) {
            ledger.append(productId, stock - beforeStock, beforeStock, stock,
                    StockReason.RESTOCK, actor(actor), null);
        }
        ProductView after = get(productId);
        if (before.stock() != after.stock()) {
            events.publishEvent(new ProductChangedEvent(productId, ProductChangedEvent.Change.STOCK));
        }
        return after;
    }

    /**
     * 预占库存：库存不足直接抛 {@code OUT_OF_STOCK}，调用方的事务整体回滚。
     *
     * <p>先加行锁读前值、再条件扣减、最后留痕——三步在同一个事务里，
     * 于是「扣减成功却没有流水」和「有流水却没扣成」都不可能存在。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(long id, int quantity, String actor, String orderId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        int before = repository.stockForUpdate(id);
        if (repository.deduct(id, quantity) != 1)
            throw BusinessException.conflict("OUT_OF_STOCK", "库存不足，请减少数量或换一件商品");
        ledger.append(id, -quantity, before, before - quantity, StockReason.RESERVE, actor(actor), orderId);
    }

    /** 归还库存（取消 / 超时关单）。与 {@link #reserve} 严格对称。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(long id, int quantity, String actor, String orderId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        int before = repository.stockForUpdate(id);
        repository.restore(id, quantity);
        ledger.append(id, quantity, before, before + quantity, StockReason.RELEASE, actor(actor), orderId);
    }

    /**
     * 归还库存（退款成功后）。流水固定记成 {@link StockReason#REFUND}，理由见接口注释。
     *
     * <p>写法与 {@link #release} 只有一处不同：<b>先插流水、并且这条 INSERT 自带
     * {@code NOT EXISTS} 去重</b>（退款方案 §5.2 闸门 ③）。顺序不能反——
     * 「这一批货有没有为这笔订单退过」的判据就是「有没有那条流水」，
     * 所以让流水先落地；它插不进去＝已经退过，此时<b>现货也一步不动</b>，整件事安静跳过。
     *
     * <p>这是三道闸门的最后一道：前两道（一次性状态迁移、只有未发货才还货）都在应用层，
     * 被人绕过时在多数 servidor 路径上会留下错账；这道幂等保证即使有人用应急脚本
     * 直接 {@code UPDATE product} 再调这个方法，同一笔订单的同一件商品也只会回来一次。
     *
     * @param orderId <b>不能为空</b>：它就是这条幂等判据的键，丢了它就无法去重
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnStock(long id, int quantity, String actor, String orderId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (orderId == null || orderId.isBlank())
            throw new IllegalArgumentException("退款归还必须带订单号：它是「这笔订单退过没有」的唯一判据");
        int before = repository.stockForUpdate(id);
        int written = ledger.appendOnce(id, quantity, before, before + quantity,
                StockReason.REFUND, actor(actor), orderId);
        // 0 行 = 这笔订单已经为这件商品退过一次了。此刻再还一次就是凭空造货，直接跳过。
        if (written == 0) return;
        repository.restore(id, quantity);
    }

    /**
     * 归一化操作者：流水要能回答「谁动的」，拿不到时宁可写 unknown，
     * 也不要让 NOT NULL 约束把一笔已经扣成功的库存变更连带回滚。
     */
    private static String actor(String actor) {
        return actor == null || actor.isBlank() ? ACTOR_UNKNOWN : actor;
    }
}
