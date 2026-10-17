package dev.learning.mall.pricing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 唯一的金额计算入口。
 *
 * <p><b>为什么它要独立成一个包，而不是待在购物车里？</b>
 * 金额是购物车与订单的<b>共同</b>领域，谁都不该依赖对方。把它放在
 * {@code cart.api} 里，订单就得依赖购物车——依赖方向是反的；而且它原来的返回值
 * {@code CartView} 是购物车的展示结构，订单用它等于把展示结构带进了落库路径。
 *
 * 抽出来之后，两边各自把「自己的行」翻译成 {@link Line}，拿回同一个
 * {@link Quote}。算法仍然只有一份，"同一车货算出两个价"在结构上不可能发生。
 *
 * <p><b>调用纪律</b>：任何模块要算钱，只能 {@code pricer.quote(lines)}。
 * 业务代码里出现 {@code BigDecimal::add} 的裸求和，就是绕过了唯一的钱袋。
 */
public interface Pricer {

    /** 对若干「单价 × 数量」行做试算。空列表返回全零的 {@link Quote}。 */
    Quote quote(List<Line> lines);

    /**
     * 一行待计价的商品。定价只认这三个字段，<b>不关心谁在调用</b>——
     * 这正是购物车与订单能共用它的原因。
     */
    record Line(long productId, BigDecimal unitPrice, int quantity) {}

    /**
     * 试算结果。
     *
     * <p>恒等式：{@code payable = subtotal − discount + shippingFee}，且四个金额
     * 都为两位小数（HALF_UP）。这条恒等式会被落库后的 SQL 断言检查。
     */
    record Quote(BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee,
                 BigDecimal payable, List<String> notes) {}
}
