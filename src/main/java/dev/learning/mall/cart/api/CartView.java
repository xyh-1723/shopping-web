package dev.learning.mall.cart.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物袋快照 + 结算试算结果。
 *
 * <p>试算和购物车查询<b>返回同一个结构</b>：用户在页面上看到的金额，就是助手在对话里
 * 报出的金额，两者不会算出两个结果——「同一个数字只有一处算法」是电商最基本的纪律。
 *
 * @param notes 给人和模型看的试算说明（已享优惠、还差多少包邮、再买多少能凑到下一档）
 */
public record CartView(List<CartItemView> items, int itemCount, BigDecimal subtotal,
                       BigDecimal discount, BigDecimal shippingFee, BigDecimal total,
                       List<String> notes) {

    public static CartView empty() {
        return new CartView(List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, List.of("购物袋是空的，先挑几件喜欢的吧。"));
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
