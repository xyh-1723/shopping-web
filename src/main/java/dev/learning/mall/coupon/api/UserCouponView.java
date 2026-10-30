package dev.learning.mall.coupon.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户券实例的对外视图：我的券列表与领券结果都用它。 */
public record UserCouponView(String id, String couponId, String code, String name, String ownerType,
                             Long merchantId, String merchantName, String discountType,
                             BigDecimal discountValue, BigDecimal maxDiscount, BigDecimal minSpend,
                             String status, LocalDateTime claimedAt, LocalDateTime expiresAt,
                             String usedOrderId) {}
