package dev.learning.mall.order.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.pricing.api.Pricer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return pricer.quote(toPricingLines(catalog, lines));
    }

    /**
     * 带上优惠券减免再算一次。
     *
     * <p>下单要算两次：先算<b>券前</b>（用于和金额快照比对），再算<b>券后</b>
     * （用于落库与拆子单）。两次都必须走同一个 {@link Pricer}——
     * 券后金额不是「券前金额减一下」得来的，包邮门槛会因为券而重新判断。
     */
    static Pricer.Quote quoteOf(ProductCatalog catalog, Pricer pricer,
                                List<CreateOrderRequest.Line> lines, BigDecimal couponDiscount) {
        return pricer.quote(toPricingLines(catalog, lines), couponDiscount);
    }

    /**
     * 这批行涉及哪几家店、各家店的<b>商品小计</b>是多少——<b>按店拆子单的输入</b>。
     *
     * <p>与 {@link #quoteOf} 同居一个类的理由：它们回答的是同一个问题的两半
     * （「这批货一共多少钱」与「每家店占了多少」）。分开两处定义，
     * 就等于允许「某家店的金额之和」与「整单金额」由两套代码算出——
     * 那正是子订单最危险的一种失败：每一家店的金额看着都合理，加起来却对不上实付。
     *
     * <p><b>顺序即 {@code sub_seq}</b>：按商品在订单里第一次出现所属的店排序，
     * 于是同一个购物车每次下单都得到同样的子订单顺序（可复现，可断言）。
     *
     * <p>各店的小计也走 {@link Pricer#subtotalOf}，不在订单模块里另写一遍求和。
     */
    static List<SubOrderSplitter.Shop> shopsOf(ProductCatalog catalog, Pricer pricer,
                                               List<CreateOrderRequest.Line> lines) {
        Map<Long, List<Pricer.Line>> byShop = new LinkedHashMap<>();
        for (CreateOrderRequest.Line line : lines) {
            var product = catalog.get(line.productId());
            byShop.computeIfAbsent(product.merchantId(), ignored -> new ArrayList<>())
                    .add(new Pricer.Line(product.id(), product.price(), line.quantity()));
        }
        return byShop.entrySet().stream()
                .map(shop -> new SubOrderSplitter.Shop(shop.getKey(), pricer.subtotalOf(shop.getValue())))
                .toList();
    }

    private static List<Pricer.Line> toPricingLines(ProductCatalog catalog,
                                                    List<CreateOrderRequest.Line> lines) {
        return lines.stream().map(line -> {
            var product = catalog.get(line.productId());
            return new Pricer.Line(product.id(), product.price(), line.quantity());
        }).toList();
    }
}
