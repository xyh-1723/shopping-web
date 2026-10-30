package dev.learning.mall.coupon.api;

import java.util.List;

/** 券的读取 / 领取 / 创建 / 预览入口（与订单侧的 {@link CouponRedemptions} 分开）。 */
public interface CouponCatalog {

    /** 当前可领的券，带「我领过几张」。 */
    List<CouponView> available(String username);

    /** 我的券（含已用 / 已过期）。 */
    List<UserCouponView> mine(String username);

    /** 领一张券。 */
    UserCouponView claim(String username, String couponId);

    /**
     * 创建券模板。
     *
     * @param actor          操作账号
     * @param merchantActor  是否只有 MERCHANT 角色；商家只能给自己店建券
     */
    CouponView create(String actor, boolean merchantActor, CouponCreateRequest request);

    /** 用券预览：与下单同一算法，但不锁券、不占预算。 */
    CouponPreview preview(String username, String userCouponId, List<CouponShop> shops);
}
