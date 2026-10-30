package dev.learning.mall.coupon.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户券实例 + 它所属模板的展示字段。
 *
 * <p>合成一条查询而不是「先查实例再逐张查模板」：我的券列表通常十几张，
 * 分开查就是 N+1；而券模板是低频写、高频读的表，JOIN 的代价可以忽略。
 */
record UserCouponDetail(String id, String couponId, String username, int claimSeq, String status,
                        LocalDateTime claimedAt, LocalDateTime expiresAt, String lockedOrderId,
                        LocalDateTime lockedAt, String usedOrderId, LocalDateTime usedAt,
                        BigDecimal couponDiscount, BigDecimal merchantDiscount, BigDecimal platformSubsidy,
                        String code, String name, String ownerType, Long merchantId, String merchantName,
                        String discountType, BigDecimal discountValue, BigDecimal maxDiscount,
                        BigDecimal minSpend, int platformShareBps, LocalDateTime startAt,
                        LocalDateTime endAt, String couponStatus) {}
