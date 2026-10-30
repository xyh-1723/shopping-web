package dev.learning.mall.coupon.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用券预览：<b>只算不写</b>。
 *
 * <p>它与下单时 {@link CouponRedemptions#reserve} 走同一个算法，所以预览里的数字
 * 就是提交后的数字。差异只有一个：预览不占预算、不锁券。
 */
public record CouponPreview(String userCouponId, String couponCode, String couponName,
                            BigDecimal discount, BigDecimal merchantDiscount,
                            BigDecimal platformSubsidy, List<ShopPreview> shops) {

    /** 一家店的预览：用户关心减免，运营关心平台/商家各出多少。 */
    public record ShopPreview(long merchantId, BigDecimal couponDiscount, BigDecimal merchantDiscount,
                              BigDecimal platformSubsidy, BigDecimal platformFee,
                              BigDecimal merchantIncome, BigDecimal platformIncome) {}
}
