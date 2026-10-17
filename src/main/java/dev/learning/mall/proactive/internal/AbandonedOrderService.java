package dev.learning.mall.proactive.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.order.api.OrderAbandonedEvent;
import dev.learning.mall.proactive.api.RelatedProduct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 弃单挽回：订单超时关闭之后，把用户拉回来。
 *
 * <p><b>为什么只挽回「超时关闭」、不挽回「主动取消」。</b>这两者在数据上都是 CANCELLED，
 * 但含义相反：主动取消是「我不想买了」，再推荐就是打扰；超时关闭更可能是「忘了、犹豫、
 * 或者支付卡住了」，说一句「东西还在」才有用。区分它们的能力来自订单上的 close_reason。
 *
 * <p><b>提醒里必须有「还能不能买」。</b>召回最忌讳的是把用户拉回来却无货可买——
 * 那等于用一条提醒把用户对系统的信任又消耗一次。所以先查商品现状，再说推荐。
 *
 * <p><b>幂等。</b>关单扫描可能重复扫到同一笔订单（上一轮归还库存失败、下一轮重试），
 * 同一个订单不能因此提醒两次；唯一索引 {@code (username, source_key)} 是这件事的兜底。
 */
@Service
public class AbandonedOrderService {

    private static final Logger log = LoggerFactory.getLogger(AbandonedOrderService.class);
    /** 提醒里最多带几条推荐：召回不是货架，给太多就变成推销了。 */
    private static final int SUGGESTIONS = 2;

    private final ProactiveRepository repository;
    private final ProductCatalog catalog;
    private final RelatedRecommendationService related;
    private final ProactiveProperties properties;

    public AbandonedOrderService(ProactiveRepository repository, ProductCatalog catalog,
            RelatedRecommendationService related, ProactiveProperties properties) {
        this.repository = repository; this.catalog = catalog;
        this.related = related; this.properties = properties;
    }

    /**
     * 为一次超时关闭生成召回提醒。
     *
     * <p>这里<b>不向上抛异常</b>：关单事务已经提交了，召回失败不该让「关单」这件事看起来失败，
     * 更不该让扫描把它记为失败而下一轮再关一次。失败只记日志——丢一条提醒的代价，
     * 远小于把已提交的状态机搞乱。
     *
     * @return 是否真的产生了提醒；{@code false} 表示没提醒（已提醒过 / 开关关闭 / 没有商品）。
     */
    @Transactional
    public boolean recover(OrderAbandonedEvent event) {
        try {
            return remind(event);
        } catch (RuntimeException e) {
            log.warn("弃单召回失败，本次跳过：order={} user={} 原因={}", event.orderId(), event.username(), e.toString());
            return false;
        }
    }

    private boolean remind(OrderAbandonedEvent event) {
        if (!properties.isEnabled()) return false;
        if (event.productIds() == null || event.productIds().isEmpty()) return false;

        List<ProductView> available = new ArrayList<>();
        int unavailable = 0;
        for (long productId : event.productIds()) {
            ProductView product = productOrNull(productId);
            if (product == null || product.stock() <= 0) unavailable++;
            else available.add(product);
        }

        StringBuilder text = new StringBuilder("你有一笔订单超时未支付，已自动关闭，占用的库存已经归还。");
        if (!available.isEmpty()) {
            text.append(available.stream().limit(2)
                            .map(p -> "「" + p.name() + "」现在还有 " + p.stock() + " 件")
                            .collect(Collectors.joining("、")))
                    .append("，想买的话可以重新下单。");
        }
        if (unavailable > 0) text.append("其中 ").append(unavailable).append(" 件暂时缺货。");

        Set<Long> abandoned = Set.copyOf(event.productIds());
        List<RelatedProduct> suggestions = related.recommend(event.username(), SUGGESTIONS).stream()
                // 用户刚弃掉的那件不该出现在「你可能还想要」里——那是揭短，不是帮忙。
                .filter(item -> !abandoned.contains(item.id()))
                .toList();
        if (!suggestions.isEmpty()) {
            text.append("你可能还想要：").append(suggestions.stream()
                            .map(item -> item.name() + "（" + item.reason() + "）")
                            .collect(Collectors.joining("、")))
                    .append("。");
        }

        Long anchor = available.isEmpty() ? event.productIds().getFirst() : available.getFirst().id();
        String title = available.isEmpty() ? "有笔订单超时关闭了" : "订单超时关闭了，东西还在";
        return repository.insertAlertOnce(event.username(), anchor, "RELATED", title, text.toString(),
                "abandoned-order:" + event.orderId());
    }

    private ProductView productOrNull(long id) {
        try {
            return catalog.get(id);
        } catch (RuntimeException e) {
            // 商品已下架：召回里跳过它，别让一条脏数据把整条提醒打挂。
            return null;
        }
    }
}
