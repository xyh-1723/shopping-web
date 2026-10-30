package dev.learning.mall.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponCatalog;
import dev.learning.mall.coupon.api.CouponCreateRequest;
import dev.learning.mall.coupon.api.CouponView;
import dev.learning.mall.coupon.api.UserCouponView;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderCheckout;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderTransactions;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 优惠券在真实链路（H2 + 真迁移 + 真订单表）上的验收。
 *
 * <p>这些用例刻意<b>不</b>清理订单与券：每个用例用独立的券码与幂等键，
 * 断言只看自己创建的那几行。共享库里的其他数据（种子商品、种子券）反而让
 * 「跨店」「限领」「预算」这些场景更接近真实环境。
 *
 * <p>所有金额断言都落在「分」上：元是展示单位，分才是账。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponIntegrationTest {

    @Autowired CouponCatalog coupons;
    @Autowired OrderCheckout orders;
    @Autowired OrderPayments payments;
    @Autowired OrderTransactions transactions;
    @Autowired ProductCatalog catalog;
    @Autowired JdbcClient jdbc;

    // ————————————————————— 领券 —————————————————————

    @Test
    @DisplayName("领券成功：发行量 +1，我的券里出现一张 UNUSED")
    void claimCreatesUserCoupon() {
        CouponView coupon = createCoupon("CLAIM" + suffix(), "PLATFORM", null,
                "AMOUNT", "10.00", null, "0.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());

        assertThat(mine.status()).isEqualTo("UNUSED");
        assertThat(mine.code()).isEqualTo(coupon.code());
        assertThat(issued(coupon.id())).isEqualTo(1);
        assertThat(mine.expiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("每人限领：第二张被拒绝，错误码 COUPON_LIMIT_REACHED")
    void perUserLimitIsEnforced() {
        CouponView coupon = createCoupon("LIMIT" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 0, 10, 1, 0);
        coupons.claim("student", coupon.id());

        assertCode(() -> coupons.claim("student", coupon.id()), "COUPON_LIMIT_REACHED");
        assertThat(issued(coupon.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("发行量：最后一张被另一个用户领走，第三个人看到 COUPON_SOLD_OUT")
    void soldOutIsReported() {
        CouponView coupon = createCoupon("SOLD" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 0, 1, 1, 0);
        coupons.claim("student", coupon.id());

        assertCode(() -> coupons.claim("admin", coupon.id()), "COUPON_SOLD_OUT");
        assertThat(issued(coupon.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("过期券：不能领取，状态必须是明确的 COUPON_UNAVAILABLE")
    void expiredCouponCannotBeClaimed() {
        LocalDateTime now = LocalDateTime.now();
        CouponView coupon = coupons.create("admin", false, new CouponCreateRequest(
                "EXP" + suffix(), "已过期券", "PLATFORM", null, "AMOUNT", new BigDecimal("5.00"),
                null, BigDecimal.ZERO, 0, 10, 1, 0L, now.minusHours(2), now.minusHours(1)));

        assertCode(() -> coupons.claim("student", coupon.id()), "COUPON_UNAVAILABLE");
    }

    // ————————————————————— 下单与结算 —————————————————————

    @Test
    @DisplayName("平台券跨店：子单券减免之和 = 父单券减免，且商家/平台收入恒等式成立")
    void platformCouponSplitsAcrossShops() {
        List<ProductView> twoShops = twoProductsFromDifferentShops();
        CouponView coupon = createCoupon("PLAT" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());

        OrderView order = placeOrder(twoShops, mine.id());

        assertThat(order.couponDiscount()).isEqualByComparingTo("5.00");
        assertThat(order.couponCode()).isEqualTo(coupon.code());
        assertThat(order.payAmount())
                .isEqualByComparingTo(order.subtotal().subtract(order.discount()).add(order.shippingFee()));

        Settlement settlement = settlement(order.id());
        assertThat(settlement.couponDiscount()).isEqualByComparingTo(order.couponDiscount());
        assertThat(settlement.merchantDiscount().add(settlement.platformSubsidy()))
                .isEqualByComparingTo(settlement.couponDiscount());
        // 金额恒等式：收入 = 券后商品金额 + 运费，再被佣金与补贴切开。
        assertThat(settlement.merchantIncome())
                .isEqualByComparingTo(settlement.payAmount().add(settlement.platformSubsidy())
                        .subtract(settlement.platformFee()));
        assertThat(settlement.platformFee())
                .isEqualByComparingTo(settlement.platformSubsidy().add(settlement.platformIncome()));
        assertThat(negativeIncomeRows(order.id())).isZero();
    }

    @Test
    @DisplayName("商家券只作用于本店：另一家店的券减免必须为 0")
    void merchantCouponOnlyAppliesToItsShop() {
        List<ProductView> twoShops = twoProductsFromDifferentShops();
        ProductView first = twoShops.get(0);
        CouponView coupon = createCoupon("SHOP" + suffix(), "MERCHANT", first.merchantId(),
                "AMOUNT", "5.00", null, "0.00", 0, 10, 1, 0);
        UserCouponView mine = coupons.claim("student", coupon.id());

        OrderView order = placeOrder(twoShops, mine.id());

        BigDecimal firstShopCoupon = jdbc.sql("""
                        SELECT COALESCE(SUM(coupon_discount), 0) FROM order_sub
                         WHERE order_id = :id AND merchant_id = :m""")
                .param("id", order.id()).param("m", first.merchantId())
                .query(BigDecimal.class).single();
        BigDecimal otherShopCoupon = jdbc.sql("""
                        SELECT COALESCE(SUM(coupon_discount), 0) FROM order_sub
                         WHERE order_id = :id AND merchant_id <> :m""")
                .param("id", order.id()).param("m", first.merchantId())
                .query(BigDecimal.class).single();

        assertThat(firstShopCoupon).isEqualByComparingTo("5.00");
        assertThat(otherShopCoupon).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("无券订单也落平台佣金：平台收入不依赖「有没有人用券」")
    void orderWithoutCouponStillHasPlatformIncome() {
        OrderView order = placeOrder(List.of(catalog.get(1)), null);

        Settlement settlement = settlement(order.id());
        assertThat(settlement.couponDiscount()).isEqualByComparingTo("0.00");
        assertThat(settlement.platformFee()).isGreaterThan(BigDecimal.ZERO);
        assertThat(settlement.platformIncome()).isEqualByComparingTo(settlement.platformFee());
        assertThat(negativeIncomeRows(order.id())).isZero();
    }

    @Test
    @DisplayName("门槛不足：拒绝下单，不落订单、不锁券")
    void minSpendRejectsBeforeAnyWrite() {
        CouponView coupon = createCoupon("MIN" + suffix(), "PLATFORM", null,
                "AMOUNT", "10.00", null, "99999.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        String key = key();

        assertCode(() -> orders.create("student", key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1)), null, mine.id())),
                "COUPON_MIN_SPEND_NOT_MET");
        assertThat(orderCount(key)).isZero();
        assertThat(couponStatus(mine.id())).isEqualTo("UNUSED");
    }

    @Test
    @DisplayName("平台补贴预算耗尽：拒绝下单并整体回滚，券与库存都不动")
    void exhaustedPlatformBudgetRollsEverythingBack() {
        // 券减 10 元，平台共担 100%；商品券后佣金远大于 1 元预算，所以占预算必然失败。
        CouponView coupon = createCoupon("BUDGET" + suffix(), "PLATFORM", null,
                "AMOUNT", "10.00", null, "0.00", 10000, 10, 1, 100);
        UserCouponView mine = coupons.claim("student", coupon.id());
        int stockBefore = catalog.get(1).stock();
        String key = key();

        assertCode(() -> orders.create("student", key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1)), null, mine.id())),
                "COUPON_BUDGET_EXHAUSTED");

        assertThat(orderCount(key)).isZero();
        assertThat(couponStatus(mine.id())).isEqualTo("UNUSED");
        assertThat(catalog.get(1).stock()).isEqualTo(stockBefore);
        assertThat(spent(coupon.id())).isZero();
    }

    // ————————————————————— 状态机 —————————————————————

    @Test
    @DisplayName("支付成功：券 LOCKED → USED，且绑定到该订单")
    void payCommitsCoupon() {
        CouponView coupon = createCoupon("PAY" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        OrderView order = placeOrder(List.of(catalog.get(1)), mine.id());

        assertThat(couponStatus(mine.id())).isEqualTo("LOCKED");
        OrderView paid = payments.pay(order.id(), "student", "DEMO_CARD");

        assertThat(paid.status()).isEqualTo("PAID");
        Map<String, Object> row = jdbc.sql("SELECT status, used_order_id FROM user_coupon WHERE id = :id")
                .param("id", mine.id()).query().singleRow();
        assertThat(row.get("STATUS")).isEqualTo("USED");
        assertThat(row.get("USED_ORDER_ID")).isEqualTo(order.id());
    }

    @Test
    @DisplayName("取消订单：券回到 UNUSED，平台补贴预算退回，锁单号清空")
    void cancelReleasesCouponAndRefundsBudget() {
        CouponView coupon = createCoupon("CANCEL" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        OrderView order = placeOrder(List.of(catalog.get(1)), mine.id());
        assertThat(spent(coupon.id())).isPositive();
        long spentAfterOrder = spent(coupon.id());

        OrderView cancelled = transactions.cancel(order.id(), "student");

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(couponStatus(mine.id())).isEqualTo("UNUSED");
        assertThat(lockedOrderId(mine.id())).isNull();
        assertThat(spent(coupon.id())).isZero();
        assertThat(spentAfterOrder).isPositive();
    }

    @Test
    @DisplayName("幂等：同键同券重放返回同一订单；同券换键被拒绝，不会出现一券两用")
    void idempotentReplayAndLockedCouponGuard() {
        CouponView coupon = createCoupon("IDEM" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        String key = key();
        CreateOrderRequest request =
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1)), null, mine.id());

        OrderView first = orders.create("student", key, request);
        OrderView replay = orders.create("student", key, request);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(orderCount(key)).isEqualTo(1);

        assertCode(() -> orders.create("student", key() + "B", request), "COUPON_UNAVAILABLE");
        assertThat(couponStatus(mine.id())).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("别人的券：用他人券实例下单直接拒绝，不泄露券的归属")
    void otherUsersCouponCannotBeUsed() {
        CouponView coupon = createCoupon("OWNER" + suffix(), "PLATFORM", null,
                "AMOUNT", "5.00", null, "0.00", 6000, 10, 1, 100_000);
        UserCouponView mine = coupons.claim("student", coupon.id());
        String key = key();

        assertCode(() -> orders.create("admin", key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1)), null, mine.id())),
                "NOT_FOUND");
        assertThat(orderCount(key)).isZero();
        assertThat(couponStatus(mine.id())).isEqualTo("UNUSED");
    }

    // ————————————————————— 工具 —————————————————————

    private CouponView createCoupon(String code, String ownerType, Long merchantId, String type,
                                    String value, String maxDiscount, String minSpend, int shareBps,
                                    int total, int perUser, long budgetCents) {
        LocalDateTime now = LocalDateTime.now();
        return coupons.create("admin", false, new CouponCreateRequest(code, code + " 测试券", ownerType,
                merchantId, type, new BigDecimal(value),
                maxDiscount == null ? null : new BigDecimal(maxDiscount), new BigDecimal(minSpend),
                shareBps, total, perUser, budgetCents, now.minusHours(1), now.plusDays(1)));
    }

    private OrderView placeOrder(List<ProductView> products, String userCouponId) {
        List<CreateOrderRequest.Line> lines = new ArrayList<>();
        for (ProductView product : products) {
            lines.add(new CreateOrderRequest.Line(product.id(), 1));
        }
        return orders.create("student", key(), new CreateOrderRequest(lines, null, userCouponId));
    }

    /**
     * 找两个来自不同店铺的商品：跨店分摊是平台券最容易出错的地方，
     * 单店用例证明不了它，必须真的有两家店。
     */
    private List<ProductView> twoProductsFromDifferentShops() {
        // 优先挑库存充足的商品：测试之间共享同一个 H2 库，库存会被其他用例消耗。
        Map<Long, ProductView> byShop = new LinkedHashMap<>();
        for (int floor : new int[] {10, 2}) {
            List<Long> ids = jdbc.sql("SELECT id FROM product WHERE stock >= :floor ORDER BY id LIMIT 80")
                    .param("floor", floor).query(Long.class).list();
            byShop.clear();
            for (Long id : ids) {
                ProductView product = catalog.get(id);
                byShop.putIfAbsent(product.merchantId(), product);
                if (byShop.size() == 2) break;
            }
            if (byShop.size() == 2) break;
        }
        if (byShop.size() < 2) throw new IllegalStateException("种子数据里没有两家店的商品，跨店用例无法运行");
        return List.copyOf(byShop.values());
    }

    private Settlement settlement(String orderId) {
        Map<String, Object> row = jdbc.sql("""
                        SELECT COALESCE(SUM(coupon_discount),0) AS coupon_discount,
                               COALESCE(SUM(merchant_discount),0) AS merchant_discount,
                               COALESCE(SUM(platform_subsidy),0) AS platform_subsidy,
                               COALESCE(SUM(platform_fee),0) AS platform_fee,
                               COALESCE(SUM(merchant_income),0) AS merchant_income,
                               COALESCE(SUM(platform_income),0) AS platform_income,
                               COALESCE(SUM(pay_amount),0) AS pay_amount
                          FROM order_sub WHERE order_id = :id""")
                .param("id", orderId).query().singleRow();
        return new Settlement(
                (BigDecimal) row.get("COUPON_DISCOUNT"), (BigDecimal) row.get("MERCHANT_DISCOUNT"),
                (BigDecimal) row.get("PLATFORM_SUBSIDY"), (BigDecimal) row.get("PLATFORM_FEE"),
                (BigDecimal) row.get("MERCHANT_INCOME"), (BigDecimal) row.get("PLATFORM_INCOME"),
                (BigDecimal) row.get("PAY_AMOUNT"));
    }

    private record Settlement(BigDecimal couponDiscount, BigDecimal merchantDiscount,
                              BigDecimal platformSubsidy, BigDecimal platformFee,
                              BigDecimal merchantIncome, BigDecimal platformIncome,
                              BigDecimal payAmount) {}

    private int negativeIncomeRows(String orderId) {
        Integer count = jdbc.sql("""
                        SELECT COUNT(*) FROM order_sub
                         WHERE order_id = :id AND (merchant_income < 0 OR platform_income < 0)""")
                .param("id", orderId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    private int issued(String couponId) {
        Integer n = jdbc.sql("SELECT issued_count FROM coupon WHERE id = :id")
                .param("id", couponId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private long spent(String couponId) {
        Long n = jdbc.sql("SELECT platform_spent_cents FROM coupon WHERE id = :id")
                .param("id", couponId).query(Long.class).single();
        return n == null ? 0 : n;
    }

    private String couponStatus(String userCouponId) {
        return jdbc.sql("SELECT status FROM user_coupon WHERE id = :id")
                .param("id", userCouponId).query(String.class).single();
    }

    private String lockedOrderId(String userCouponId) {
        Map<String, Object> row = jdbc.sql("SELECT locked_order_id FROM user_coupon WHERE id = :id")
                .param("id", userCouponId).query().singleRow();
        return (String) row.get("LOCKED_ORDER_ID");
    }

    private int orderCount(String key) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE idempotency_key = :key")
                .param("key", key).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
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
