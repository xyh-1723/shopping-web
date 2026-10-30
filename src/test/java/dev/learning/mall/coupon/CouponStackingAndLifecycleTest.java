package dev.learning.mall.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponCatalog;
import dev.learning.mall.coupon.api.CouponCreateRequest;
import dev.learning.mall.coupon.api.CouponPreview;
import dev.learning.mall.coupon.api.CouponShop;
import dev.learning.mall.coupon.api.CouponView;
import dev.learning.mall.coupon.api.UserCouponView;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderCancellation;
import dev.learning.mall.order.api.OrderCheckout;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>「能不能叠加」与生命周期边界</b>的验收。
 *
 * <p>这个类回答三个问题，每个都给出可执行证据而不是口头结论：
 * <ol>
 *   <li><b>券与促销能不能叠？</b>能——而且必须是「先满减、再减券」这个顺序，
 *       因为券的基数、门槛、包邮判断都建立在券前折后金额上；</li>
 *   <li><b>多张券能不能叠在一笔订单上？</b>不能——不是靠业务代码里的 if 挡的，
 *       而是 {@code user_coupon.locked_order_id} 上的唯一键让它<b>在库里就写不进去</b>；</li>
 *   <li><b>券的终态边界</b>：已支付不能再用、已取消可以再用、锁定期间过期只能变 EXPIRED
 *       不能「复活」成 UNUSED。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponStackingAndLifecycleTest {

    @Autowired CouponCatalog coupons;
    @Autowired OrderCheckout orders;
    @Autowired OrderPayments payments;
    @Autowired OrderCancellation transactions;
    @Autowired JdbcClient jdbc;

    // ——————————————————— 叠加：券 + 促销 ———————————————————

    @Test
    @DisplayName("券与满减叠加：118 元先减 10 满减、再减 10 券，合计减 20，应付 106")
    void couponStacksOnPromotionDiscount() {
        CouponView coupon = createCoupon("STK" + suffix(), "PLATFORM", null, "10.00", 6000, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());

        OrderView order = placeOrder(List.of(line(2), line(3)), mine.id());

        // 39 + 89 = 128？不：2 号 29 元、3 号 89 元，合计 118 元。
        assertThat(order.subtotal()).isEqualByComparingTo("118.00");
        assertThat(order.discount()).as("满减 10 + 券 10").isEqualByComparingTo("20.00");
        assertThat(order.couponDiscount()).isEqualByComparingTo("10.00");
        assertThat(order.shippingFee()).as("券后 98 元，不足 99 元包邮线").isEqualByComparingTo("8.00");
        assertThat(order.payAmount()).isEqualByComparingTo("106.00");
    }

    @Test
    @DisplayName("同一笔订单只能挂一张券：库里再挂第二张会被唯一键直接拒绝")
    void databaseRejectsSecondCouponOnSameOrder() {
        CouponView first = createCoupon("ONE" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        CouponView second = createCoupon("TWO" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        UserCouponView firstCoupon = coupons.claim("student", first.id());
        UserCouponView secondCoupon = coupons.claim("student", second.id());

        OrderView order = placeOrder(List.of(line(1)), firstCoupon.id());
        assertThat(couponStatus(firstCoupon.id())).isEqualTo("LOCKED");

        // 绕过服务层，直接把第二张券写到同一笔订单上——这是业务代码绝不允许的事。
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE user_coupon
                           SET status = 'LOCKED', locked_order_id = :order, locked_at = CURRENT_TIMESTAMP,
                               coupon_discount = 5.00, merchant_discount = 5.00, platform_subsidy = 0
                         WHERE id = :id
                        """)
                .param("order", order.id()).param("id", secondCoupon.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(lockedCouponsOf(order.id())).isEqualTo(1);
        assertThat(couponStatus(secondCoupon.id())).isEqualTo("UNUSED");
    }

    // ——————————————————— 生命周期边界 ———————————————————

    @Test
    @DisplayName("已支付的券不能再用：第二笔订单被拒绝，且不落单")
    void usedCouponCannotBeReused() {
        CouponView coupon = createCoupon("USED" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        OrderView first = placeOrder(List.of(line(1)), mine.id());
        payments.pay(first.id(), "student", "DEMO_CARD");

        String key = key();
        assertCode(() -> orders.create("student", key,
                new CreateOrderRequest(List.of(line(1)), null, mine.id())), "COUPON_UNAVAILABLE");
        assertThat(orderCount(key)).isZero();
        assertThat(couponStatus(mine.id())).isEqualTo("USED");
    }

    @Test
    @DisplayName("取消订单后券可以再用：这是「释放」存在的意义，不是一取消就作废")
    void releasedCouponCanBeUsedAgain() {
        CouponView coupon = createCoupon("REUSE" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        OrderView first = placeOrder(List.of(line(1)), mine.id());
        transactions.cancel(first.id(), "student");

        OrderView second = placeOrder(List.of(line(1)), mine.id());

        assertThat(second.couponDiscount()).isEqualByComparingTo("5.00");
        assertThat(couponStatus(mine.id())).isEqualTo("LOCKED");
        assertThat(lockedCouponsOf(second.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("锁定期间券过期：取消后只能变 EXPIRED，不能被「复活」成 UNUSED 再用")
    void lapsedLockedCouponBecomesExpiredNotUsable() {
        CouponView coupon = createCoupon("LAPSE" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        OrderView order = placeOrder(List.of(line(1)), mine.id());

        // 把这张券的到期时间推到过去，模拟「下单时还有效、关单时已过期」。
        jdbc.sql("UPDATE user_coupon SET expires_at = :past WHERE id = :id")
                .param("past", LocalDateTime.now().minusMinutes(1)).param("id", mine.id()).update();
        transactions.cancel(order.id(), "student");

        assertThat(couponStatus(mine.id())).isEqualTo("EXPIRED");
        assertThat(spent(coupon.id())).as("过期释放也要退预算").isZero();
        assertCode(() -> orders.create("student", key(),
                new CreateOrderRequest(List.of(line(1)), null, mine.id())), "COUPON_UNAVAILABLE");
    }

    @Test
    @DisplayName("读路径也会对齐事实：已过期但仍是 UNUSED 的券，在「我的券」里必须显示 EXPIRED")
    void lapsedCouponIsExpiredOnRead() {
        CouponView coupon = createCoupon("READ" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        jdbc.sql("UPDATE user_coupon SET expires_at = :past WHERE id = :id")
                .param("past", LocalDateTime.now().minusMinutes(1)).param("id", mine.id()).update();

        List<UserCouponView> visible = coupons.mine("student");

        assertThat(visible).filteredOn(view -> view.id().equals(mine.id()))
                .singleElement()
                .extracting(UserCouponView::status)
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("预览是只读的：算完券后，券仍是 UNUSED、没锁单、预算也没花")
    void previewNeverWrites() {
        CouponView coupon = createCoupon("PREV" + suffix(), "PLATFORM", null, "5.00", 6000, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());

        CouponPreview preview = coupons.preview("student", mine.id(),
                List.of(new CouponShop(1L, new BigDecimal("39.00"), BigDecimal.ZERO)));

        assertThat(preview.discount()).isEqualByComparingTo("5.00");
        assertThat(couponStatus(mine.id())).isEqualTo("UNUSED");
        assertThat(lockedOrderId(mine.id())).isNull();
        assertThat(spent(coupon.id())).isZero();
    }

    @Test
    @DisplayName("商家全担的券不花平台预算：用券前后 platform_spent_cents 都必须是 0")
    void merchantBorneCouponSpendsNoPlatformBudget() {
        CouponView coupon = createCoupon("SHOPB" + suffix(), "MERCHANT", 1L, "5.00", 0, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());

        OrderView order = placeOrder(List.of(line(1)), mine.id());

        assertThat(order.couponDiscount()).isEqualByComparingTo("5.00");
        assertThat(spent(coupon.id())).isZero();
    }

    // ——————————————————— 工具 ———————————————————

    private CouponView createCoupon(String code, String ownerType, Long merchantId, String value,
                                    int shareBps, long budgetCents) {
        LocalDateTime now = LocalDateTime.now();
        return coupons.create("admin", false, new CouponCreateRequest(code, code + " 叠加券", ownerType,
                merchantId, "AMOUNT", new BigDecimal(value), null, BigDecimal.ZERO,
                shareBps, 100, 1, budgetCents, now.minusHours(1), now.plusDays(1)));
    }

    private static CreateOrderRequest.Line line(long productId) {
        return new CreateOrderRequest.Line(productId, 1);
    }

    private OrderView placeOrder(List<CreateOrderRequest.Line> lines, String userCouponId) {
        return orders.create("student", key(), new CreateOrderRequest(lines, null, userCouponId));
    }

    private int lockedCouponsOf(String orderId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM user_coupon WHERE locked_order_id = :id")
                .param("id", orderId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private String couponStatus(String userCouponId) {
        return jdbc.sql("SELECT status FROM user_coupon WHERE id = :id")
                .param("id", userCouponId).query(String.class).single();
    }

    private String lockedOrderId(String userCouponId) {
        return jdbc.sql("SELECT locked_order_id FROM user_coupon WHERE id = :id")
                .param("id", userCouponId).query(String.class).optional().orElse(null);
    }

    private long spent(String couponId) {
        Long n = jdbc.sql("SELECT platform_spent_cents FROM coupon WHERE id = :id")
                .param("id", couponId).query(Long.class).single();
        return n == null ? 0 : n;
    }

    private int orderCount(String key) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE idempotency_key = :key")
                .param("key", key).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private static String key() {
        return "stack-" + UUID.randomUUID();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(code));
    }
}
