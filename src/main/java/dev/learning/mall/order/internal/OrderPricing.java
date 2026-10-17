package dev.learning.mall.order.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.pricing.api.Pricer;
import java.util.List;

/**
 * 订单行到定价结果的唯一映射。
 *
 * <p>落单与金额快照共用此处，确保两条路径都从商品表读取实时价格，
 * 再交给同一个 {@link Pricer}，不会各自演化出不同算法。
 */
final class OrderPricing {

    private OrderPricing() {}

    /** 使用商品表中的当前价格计算这批订单行。 */
    static Pricer.Quote quoteOf(ProductCatalog catalog, Pricer pricer,
                                List<CreateOrderRequest.Line> lines) {
        List<Pricer.Line> pricingLines = lines.stream().map(line -> {
            var product = catalog.get(line.productId());
            return new Pricer.Line(product.id(), product.price(), line.quantity());
        }).toList();
        return pricer.quote(pricingLines);
    }
}
