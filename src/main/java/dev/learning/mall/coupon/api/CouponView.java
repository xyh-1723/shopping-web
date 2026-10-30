package dev.learning.mall.coupon.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 券模板的对外视图：可领券列表与后台创建都用它。 */
public record CouponView(String id, String code, String name, String ownerType, Long merchantId,
                         String merchantName, String discountType, BigDecimal discountValue,
                         BigDecimal maxDiscount, BigDecimal minSpend, int platformShareBps,
                         int totalQuantity, int issuedCount, int remainingQuantity, int perUserLimit,
                         LocalDateTime startAt, LocalDateTime endAt, String status,
                         boolean claimedByMe) {}
