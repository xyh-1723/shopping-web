package dev.learning.mall.coupon.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 优惠券的 SQL 全在这里。
 *
 * <p>两条不变式靠数据库而不是靠 Java：
 * <ul>
 *   <li>发行量：{@code issued_count < total_quantity} 写在 UPDATE 的 WHERE 里；</li>
 *   <li>平台预算：{@code platform_spent_cents + :subsidy <= platform_budget_cents} 同上。</li>
 * </ul>
 * 两者都是条件更新，返回 0 行就是「没抢到」，不需要先读后写，也不会在并发下超发。
 */
@Repository
class CouponRepository {

    private static final String DETAIL_COLUMNS = """
            SELECT uc.id, uc.coupon_id, uc.username, uc.claim_seq, uc.status,
                   uc.claimed_at, uc.expires_at, uc.locked_order_id, uc.locked_at,
                   uc.used_order_id, uc.used_at,
                   uc.coupon_discount, uc.merchant_discount, uc.platform_subsidy,
                   c.code, c.name, c.owner_type, c.merchant_id, m.name AS merchant_name,
                   c.discount_type, c.discount_value, c.max_discount, c.min_spend,
                   c.platform_share_bps, c.start_at, c.end_at, c.status AS coupon_status
              FROM user_coupon uc
              JOIN coupon c ON c.id = uc.coupon_id
              LEFT JOIN merchant m ON m.id = c.merchant_id
            """;

    private final JdbcClient jdbc;

    CouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CouponRow> find(String couponId) {
        return jdbc.sql("""
                        SELECT c.*, m.name AS merchant_name
                          FROM coupon c
                          LEFT JOIN merchant m ON m.id = c.merchant_id
                         WHERE c.id = :id
                        """)
                .param("id", couponId)
                .query(CouponRow.class)
                .optional();
    }

    /** 领券要「先锁模板行再数已领张数」，否则同一用户并发领取会越过限领。 */
    Optional<CouponRow> lock(String couponId) {
        return jdbc.sql("""
                        SELECT c.*, m.name AS merchant_name
                          FROM coupon c
                          LEFT JOIN merchant m ON m.id = c.merchant_id
                         WHERE c.id = :id
                           FOR UPDATE
                        """)
                .param("id", couponId)
                .query(CouponRow.class)
                .optional();
    }

    List<CouponRow> available(LocalDateTime now) {
        return jdbc.sql("""
                        SELECT c.*, m.name AS merchant_name
                          FROM coupon c
                          LEFT JOIN merchant m ON m.id = c.merchant_id
                         WHERE c.status = 'ACTIVE'
                           AND c.start_at <= :now
                           AND c.end_at > :now
                         ORDER BY c.end_at ASC, c.id ASC
                        """)
                .param("now", now)
                .query(CouponRow.class)
                .list();
    }

    List<UserCouponDetail> mine(String username) {
        return jdbc.sql(DETAIL_COLUMNS + " WHERE uc.username = :username ORDER BY uc.claimed_at DESC, uc.id ASC")
                .param("username", username)
                .query(UserCouponDetail.class)
                .list();
    }

    /** 下单锁券：锁住实例行，避免同一张券被两笔订单同时占用。 */
    Optional<UserCouponDetail> lockUserCoupon(String username, String userCouponId) {
        return jdbc.sql(DETAIL_COLUMNS + " WHERE uc.username = :username AND uc.id = :id FOR UPDATE")
                .param("username", username)
                .param("id", userCouponId)
                .query(UserCouponDetail.class)
                .optional();
    }

    Optional<UserCouponDetail> findUserCoupon(String username, String userCouponId) {
        return jdbc.sql(DETAIL_COLUMNS + " WHERE uc.username = :username AND uc.id = :id")
                .param("username", username)
                .param("id", userCouponId)
                .query(UserCouponDetail.class)
                .optional();
    }

    int countClaimed(String couponId, String username) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM user_coupon
                         WHERE coupon_id = :couponId AND username = :username
                        """)
                .param("couponId", couponId)
                .param("username", username)
                .query(Integer.class)
                .single();
    }

    List<ClaimCount> claimedCounts(String username) {
        return jdbc.sql("""
                        SELECT coupon_id, COUNT(*) AS n
                          FROM user_coupon
                         WHERE username = :username
                         GROUP BY coupon_id
                        """)
                .param("username", username)
                .query(ClaimCount.class)
                .list();
    }

    /** 发行量条件更新；0 行 = 已售罄。 */
    int increaseIssued(String couponId) {
        return jdbc.sql("""
                        UPDATE coupon
                           SET issued_count = issued_count + 1
                         WHERE id = :id
                           AND issued_count < total_quantity
                        """)
                .param("id", couponId)
                .update();
    }

    /** 平台补贴预算条件更新；0 行 = 预算不足。 */
    int reserveBudget(String couponId, long subsidyCents) {
        if (subsidyCents <= 0) return 1;
        return jdbc.sql("""
                        UPDATE coupon
                           SET platform_spent_cents = platform_spent_cents + :subsidy
                         WHERE id = :id
                           AND platform_spent_cents + :subsidy <= platform_budget_cents
                        """)
                .param("id", couponId)
                .param("subsidy", subsidyCents)
                .update();
    }

    int refundBudget(String couponId, long subsidyCents) {
        if (subsidyCents <= 0) return 1;
        return jdbc.sql("""
                        UPDATE coupon
                           SET platform_spent_cents = platform_spent_cents - :subsidy
                         WHERE id = :id
                           AND platform_spent_cents >= :subsidy
                        """)
                .param("id", couponId)
                .param("subsidy", subsidyCents)
                .update();
    }

    void insert(String id, String code, String name, String ownerType, Long merchantId,
                String discountType, BigDecimal discountValue, BigDecimal maxDiscount, BigDecimal minSpend,
                int platformShareBps, int totalQuantity, int perUserLimit, long platformBudgetCents,
                LocalDateTime startAt, LocalDateTime endAt) {
        jdbc.sql("""
                        INSERT INTO coupon (id, code, name, owner_type, merchant_id, discount_type,
                                            discount_value, max_discount, min_spend, platform_share_bps,
                                            total_quantity, per_user_limit, platform_budget_cents,
                                            start_at, end_at)
                        VALUES (:id, :code, :name, :ownerType, :merchantId, :discountType,
                                :discountValue, :maxDiscount, :minSpend, :platformShareBps,
                                :totalQuantity, :perUserLimit, :budget, :startAt, :endAt)
                        """)
                .param("id", id)
                .param("code", code)
                .param("name", name)
                .param("ownerType", ownerType)
                .param("merchantId", merchantId)
                .param("discountType", discountType)
                .param("discountValue", discountValue)
                .param("maxDiscount", maxDiscount)
                .param("minSpend", minSpend)
                .param("platformShareBps", platformShareBps)
                .param("totalQuantity", totalQuantity)
                .param("perUserLimit", perUserLimit)
                .param("budget", platformBudgetCents)
                .param("startAt", startAt)
                .param("endAt", endAt)
                .update();
    }

    void insertUserCoupon(String id, String couponId, String username, int claimSeq,
                          LocalDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO user_coupon (id, coupon_id, username, claim_seq, expires_at)
                        VALUES (:id, :couponId, :username, :claimSeq, :expiresAt)
                        """)
                .param("id", id)
                .param("couponId", couponId)
                .param("username", username)
                .param("claimSeq", claimSeq)
                .param("expiresAt", expiresAt)
                .update();
    }

    /** 只允许 UNUSED（且未过期）→ LOCKED，0 行说明券已被占用或已过期。 */
    int lockForOrder(String userCouponId, String orderId, BigDecimal discount, BigDecimal merchantDiscount,
                     BigDecimal platformSubsidy, LocalDateTime now) {
        return jdbc.sql("""
                        UPDATE user_coupon
                           SET status = 'LOCKED',
                               locked_order_id = :orderId,
                               locked_at = :now,
                               coupon_discount = :discount,
                               merchant_discount = :merchantDiscount,
                               platform_subsidy = :subsidy
                         WHERE id = :id
                           AND status = 'UNUSED'
                           AND expires_at > :now
                        """)
                .param("id", userCouponId)
                .param("orderId", orderId)
                .param("now", now)
                .param("discount", discount)
                .param("merchantDiscount", merchantDiscount)
                .param("subsidy", platformSubsidy)
                .update();
    }

    /** 支付成功 → USED。重复调用返回 0 行，调用方忽略即可。 */
    int markUsed(String userCouponId, String orderId, LocalDateTime now) {
        return jdbc.sql("""
                        UPDATE user_coupon
                           SET status = 'USED', used_order_id = :orderId, used_at = :now,
                               locked_order_id = NULL, locked_at = NULL
                         WHERE id = :id
                           AND status = 'LOCKED'
                           AND locked_order_id = :orderId
                        """)
                .param("id", userCouponId)
                .param("orderId", orderId)
                .param("now", now)
                .update();
    }

    Optional<LockedCoupon> findLockedByOrder(String orderId) {
        return jdbc.sql("""
                        SELECT id, coupon_id, platform_subsidy
                          FROM user_coupon
                         WHERE locked_order_id = :orderId
                           AND status = 'LOCKED'
                        """)
                .param("orderId", orderId)
                .query(LockedCoupon.class)
                .optional();
    }

    /**
     * 释放锁定：未过期回 UNUSED，已过期直接 EXPIRED。
     *
     * <p>不能一律回 UNUSED——一张已经过期的券被「复活」就能再用，等于绕过有效期。
     */
    int releaseLocked(String userCouponId, LocalDateTime now) {
        return jdbc.sql("""
                        UPDATE user_coupon
                           SET status = CASE WHEN expires_at > :now THEN 'UNUSED' ELSE 'EXPIRED' END,
                               locked_order_id = NULL,
                               locked_at = NULL,
                               coupon_discount = NULL,
                               merchant_discount = NULL,
                               platform_subsidy = NULL
                         WHERE id = :id
                           AND status = 'LOCKED'
                        """)
                .param("id", userCouponId)
                .param("now", now)
                .update();
    }

    int expireLapsed(String username, LocalDateTime now) {
        return jdbc.sql("""
                        UPDATE user_coupon
                           SET status = 'EXPIRED'
                         WHERE username = :username
                           AND status = 'UNUSED'
                           AND expires_at <= :now
                        """)
                .param("username", username)
                .param("now", now)
                .update();
    }

    record ClaimCount(String couponId, int n) {}

    record LockedCoupon(String id, String couponId, BigDecimal platformSubsidy) {}
}
