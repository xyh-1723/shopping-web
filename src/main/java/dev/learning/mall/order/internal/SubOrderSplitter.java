package dev.learning.mall.order.internal;

import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把一笔订单的钱拆到各家店头上：<b>纯函数，只认整数分</b>。
 *
 * <h3>为什么必须存在一个专门的拆分器</h3>
 *
 * <p>「按店拆子单」之后，父单的四个金额（小计 / 满减 / 运费 / 应付）要在 N 个子订单之间
 * 各留一份。拆得不好，最常见的后果不是报错，而是<b>钱数对不上</b>：
 * 三家店各自记 33.33，加起来 99.99，而用户实付 100.00——多出来的那一分没有主人，
 * 对账时它既不在任何一家店的账上，也从买家的付款里消失了。
 * 这类问题不会让任何一条测试变红（每家店的金额看着都合理），只会让月底对不上账。
 *
 * <p>所以拆分规则只有一条硬要求：<b>Σ 子单金额 = 父单金额，四列逐列相等，分毫不差。</b>
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>{@code subtotal}：<b>不参与分摊</b>。它本来就是各家店明细的和，逐店相加即是。</li>
 *   <li>{@code discount} / {@code shippingFee}：按各家店小计占整单的比例分摊，
 *       <b>向下取整到分</b>；</li>
 *   <li>取整余下来的分，按<b>余数从大到小</b>依次发给各家店，每家最多再拿 1 分
 *       （最大余额法 / largest remainder）；余数相同时按子订单序号升序——
 *       确定性优先，否则同一个订单两次拆分会得到不同结果。</li>
 * </ul>
 *
 * <p>最后一条是这条规则里唯一需要解释的取舍：<b>余数给谁？</b>这里给「余数最大」的那家店，
 * 而不是「最后一家店」。后者写起来更短（SQL 回填里就是这么写的：那里只有一次机会，
 * 前缀和减一下最简单），但它把误差系统性推给 seq 最大的店；最大余额法把误差摊给
 * 最「接近该拿这一分」的店，长期更公平。两处都满足「Σ 相等」这条硬要求，
 * 所以它们不一致不算分叉——<b>SQL 回填只跑一次、处理的是历史数据；这里是每一次下单</b>。
 *
 * <h3>为什么只在「分」上算</h3>
 *
 * <p>整数分是全项目唯一的金额真相（{@link MoneyUnits}）。在 {@code BigDecimal} 上做除法
 * 再 {@code setScale(2, HALF_UP)}，每家店各舍一次，舍出来的数加起来不保证等于原数——
 * 「逐店四舍五入」与「总和守恒」是两件不同的事，本项目要的是后者。
 */
final class SubOrderSplitter {

    private SubOrderSplitter() {}

    /** 一家店在本次拆分里的输入：<b>它自己那部分商品小计</b>。 */
    record Shop(long merchantId, BigDecimal subtotal) {}

    /** 一家店拆到的四个金额（全部是「分」）。 */
    record Share(long merchantId, long subtotalCents, long discountCents, long shippingCents, long payCents) {

        BigDecimal subtotal() { return MoneyUnits.toYuan(subtotalCents); }
        BigDecimal discount() { return MoneyUnits.toYuan(discountCents); }
        BigDecimal shippingFee() { return MoneyUnits.toYuan(shippingCents); }
        BigDecimal payAmount() { return MoneyUnits.toYuan(payCents); }
    }

    /**
     * 拆分。
     *
     * @param shops         按子订单序号排好序的各家店（顺序即 {@code sub_seq}）
     * @param discount      父单满减（元）
     * @param shippingFee   父单运费（元）
     * @return 与 {@code shops} 一一对应的份额；顺序不变
     */
    static List<Share> split(List<Shop> shops, BigDecimal discount, BigDecimal shippingFee) {
        List<Share> empty = List.of();
        if (shops.isEmpty()) return empty;

        int n = shops.size();
        long[] subtotals = new long[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            subtotals[i] = MoneyUnits.toCents(shops.get(i).subtotal());
            total += subtotals[i];
        }
        long discountCents = MoneyUnits.toCents(discount);
        long shippingCents = MoneyUnits.toCents(shippingFee);

        long[] disc = new long[n];
        long[] ship = new long[n];
        long[] discRemainder = new long[n];
        long[] shipRemainder = new long[n];
        long discUsed = 0;
        long shipUsed = 0;
        for (int i = 0; i < n; i++) {
            // 分子用 long 乘：subtotal 最多约 1e7 分（¥10 万）、discount 同理，
            // 乘积 1e14 远在 long 之内，不会溢出，也就不需要 BigDecimal。
            long discNumerator = discountCents * subtotals[i];
            long shipNumerator = shippingCents * subtotals[i];
            if (total > 0) {
                disc[i] = discNumerator / total;
                ship[i] = shipNumerator / total;
                discRemainder[i] = discNumerator % total;
                shipRemainder[i] = shipNumerator % total;
            }
            discUsed += disc[i];
            shipUsed += ship[i];
        }
        // 余下的分（0 .. n-1）发给余数最大的那几家，每家 1 分。
        scatter(disc, discRemainder, discountCents - discUsed);
        scatter(ship, shipRemainder, shippingCents - shipUsed);

        List<Share> shares = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            shares.add(new Share(shops.get(i).merchantId(), subtotals[i], disc[i], ship[i],
                    subtotals[i] - disc[i] + ship[i]));
        }
        return List.copyOf(shares);
    }

    /**
     * 把 {@code leftover} 个 1 分发给余数最大的 {@code leftover} 家。
     *
     * <p>排序键是「余数降序 + 下标升序」：余数相同时下标小的先拿，
     * 于是同一个输入永远得到同一个输出（可重放的拆分，测试才敢断言具体数值）。
     */
    private static void scatter(long[] base, long[] remainder, long leftover) {
        if (leftover <= 0) return;
        List<Integer> order = new ArrayList<>(base.length);
        for (int i = 0; i < base.length; i++) order.add(i);
        order.sort(Comparator.<Integer>comparingLong(i -> -remainder[i]).thenComparingInt(i -> i));
        for (int k = 0; k < leftover && k < order.size(); k++) base[order.get(k)] += 1;
    }
}
