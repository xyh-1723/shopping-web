package dev.learning.mall.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/**
 * 下单请求。
 *
 * <p>{@code userCouponId} 是「我选哪张券」的唯一表达：用户券实例号，不是券码。
 * 券码是营销物料上印的东西，可能被重复领取成多张实例；只有实例号能唯一确定
 * 「这张券是我的、且现在还没被别的订单锁住」。
 *
 * <p>它被纳入幂等哈希：同一个请求编号换了券，属于不同的购物意图，必须判冲突
 * （否则会出现「换券重放却拿到旧订单」）。
 */
public record CreateOrderRequest(
        @NotEmpty(message = "购物车不能为空") @Size(max = 20, message = "一次最多购买 20 种商品")
        List<@NotNull @Valid Line> items,
        @Size(max = 36, message = "金额快照编号最长 36 位") String quoteId,
        @Size(max = 64, message = "优惠券编号最长 64 位") String userCouponId) {

    /** 兼容无需金额快照的既有调用点。 */
    public CreateOrderRequest(List<Line> items) {
        this(items, null, null);
    }

    /** 兼容带金额快照、但暂不选券的既有调用点。 */
    public CreateOrderRequest(List<Line> items, String quoteId) {
        this(items, quoteId, null);
    }

    public record Line(@Positive(message = "商品编号必须为正数") long productId,
                       @Min(value = 1, message = "数量至少为 1") @Max(value = 99, message = "每种商品最多购买 99 件") int quantity) {}
}
