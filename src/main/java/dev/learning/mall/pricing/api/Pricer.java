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

    /**
     * 对若干「单价 × 数量」行做试算。空列表返回全零的 {@link Quote}。
     *
     * <p>默认实现 = 不带券的 {@link #quote(List, BigDecimal)}。这样「满减/运费/包邮」
     * 仍然只有一份算法，券减免只是被当作一个额外的、已经由优惠券模块算好的金额传进来。
     */
    default Quote quote(List<Line> lines) {
        return quote(lines, BigDecimal.ZERO);
    }

    /**
     * 带优惠券减免的试算。
     *
     * <p><b>为什么要让券减免进入定价器，而不是在订单里减一下？</b>
     * 包邮门槛按「折后金额」判断——券也是折扣，券后金额低于 ¥99 时运费应当重新出现。
     * 如果订单模块自己从 payable 里减券，运费会停留在券前的判断上，
     * 用户就会看到「用了券反而比不用券更贵」或「券后仍免运费」这种自相矛盾的结果。
     *
     * @param couponDiscount 优惠券减免（元，两位小数）；由优惠券模块保证不超过券前折后商品金额
     */
    Quote quote(List<Line> lines, BigDecimal couponDiscount);

    /**
     * 只要小计：这些行的 {@code Σ(单价 × 数量)}。
     *
     * <p>它是 {@code quote} 的一部分而不是另一种算法（默认实现直接取自 {@link Quote#subtotal()}），
     * 存在的意义是<b>让「按店拆子单」时各店的小计仍然走唯一的求和入口</b>：
     * 订单模块要算「这家店占了多少」，如果在那里另写一遍 {@code BigDecimal::add}，
     * 全项目就会出现第二个「商品小计」的定义——两边迟早算出不同的数，
     * 而那种差异不会让任何一条测试变红，只会让子订单金额加起来不等于实付。
     */
    default BigDecimal subtotalOf(List<Line> lines) {
        return quote(lines).subtotal();
    }

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
     *
     * <p>{@code discount} 在 {@link #quote(List, BigDecimal)} 里是
     * <b>满减 + 券减免</b>的合计；不需要区分两者，因为两者对用户都表现为「少付的钱」。
     * 需要分账时去订单模块：父单上的 {@code coupon_discount} 与子单结算六列才是资金的真相。
     */
    record Quote(BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee,
                 BigDecimal payable, List<String> notes) {}
}
