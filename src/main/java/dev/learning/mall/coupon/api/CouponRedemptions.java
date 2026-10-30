package dev.learning.mall.coupon.api;

import java.util.List;

/**
 * 订单模块使用优惠券的唯一端口。
 *
 * <p><b>为什么把「算券」和「锁券」放在一个方法里？</b>
 * 用户看到的减免与真正占用的券必须是同一次计算。如果先 {@code preview} 再 {@code lock}，
 * 中间可能券被别的订单抢走、预算被耗光、或者券刚好过期——用户会看到「减 10 元」，
 * 提交后却变成「券不可用」。{@link #reserve} 在同一个事务里完成「算 → 占预算 → 锁券」，
 * 任何一步失败都整体回滚。
 *
 * <p>无券订单走 {@link #settle}：券减免为 0，但平台佣金仍要落到子单上。
 */
public interface CouponRedemptions {

    /**
     * 为某笔订单占用一张券。必须在下单事务里调用。
     *
     * @param username     下单用户
     * @param userCouponId 用户券实例号
     * @param orderId      即将落库的订单号（锁券时写入 {@code locked_order_id}）
     * @param shops        本次订单的各店金额画像
     * @throws dev.learning.mall.common.BusinessException 券不可用 / 门槛不足 / 预算耗尽
     */
    CouponApplication reserve(String username, String userCouponId, String orderId, List<CouponShop> shops);

    /** 无券订单的结算：只记平台佣金，不发生任何写操作。 */
    CouponApplication settle(List<CouponShop> shops);

    /** 支付成功：{@code LOCKED → USED}。重复调用安全。 */
    void commit(String orderId);

    /** 取消 / 超时关闭：{@code LOCKED → UNUSED|EXPIRED}，并退回平台补贴预算。重复调用安全。 */
    void release(String orderId);
}
