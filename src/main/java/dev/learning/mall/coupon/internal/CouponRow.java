package dev.learning.mall.coupon.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** {@code coupon} 表的一行（含 JOIN 出来的店名）。 */
record CouponRow(String id, String code, String name, String ownerType, Long merchantId,
                 String merchantName, String discountType, BigDecimal discountValue,
                 BigDecimal maxDiscount, BigDecimal minSpend, int platformShareBps,
                 int totalQuantity, int issuedCount, int perUserLimit,
                 long platformBudgetCents, long platformSpentCents,
                 LocalDateTime startAt, LocalDateTime endAt, String status,
                 LocalDateTime createdAt) {}
