package dev.learning.mall.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponCatalog;
import dev.learning.mall.coupon.api.CouponCreateRequest;
import dev.learning.mall.coupon.api.CouponView;
import dev.learning.mall.coupon.api.UserCouponView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>数量</b>与<b>并发</b>的验收：发行量、每人限领、平台预算。
 *
 * <p>这三个数字都有一个共同的风险——超发。超发不会让页面报错，只会让账在月底对不上，
 * 所以它们必须由数据库的条件更新守住，并且要有用例证明「条件真的生效」：
 * <ul>
 *   <li>发行量：{@code UPDATE ... WHERE issued_count < total_quantity}</li>
 *   <li>平台预算：{@code UPDATE ... WHERE spent + n <= budget}</li>
 *   <li>每人限领：模板行 {@code FOR UPDATE} + {@code (coupon_id, username, claim_seq)} 唯一键</li>
 * </ul>
 *
 * <p>并发用例断言的不是「一定不报错」，而是<b>成功的次数恰好等于库存/预算允许的次数</b>。
 * 这两件事差别很大：把所有请求都拒绝也能做到「不超发」，但那是另一种故障。
 */
@SpringBootTest
@ActiveProfiles("test")
class CouponQuantityTest {

    @Autowired CouponCatalog coupons;
    @Autowired CouponRepository repository;
    @Autowired JdbcClient jdbc;

    // ——————————————————— 发行量 ———————————————————

    @Test
    @DisplayName("发行量 0 张：谁都领不到，状态是「已领完」而不是「券不存在」")
    void zeroQuantityMeansSoldOut() {
        String couponId = insertCoupon("ZERO" + suffix(), 0, 1);
        assertCode(() -> coupons.claim("student", couponId), "COUPON_SOLD_OUT");
        assertThat(issued(couponId)).isZero();
    }

    @Test
    @DisplayName("每人限领 2 张：第 3 次被拒绝，两次的 claim_seq 必须递增且唯一")
    void perUserLimitTwoAllowsExactlyTwo() {
        CouponView coupon = createCoupon("SEQ" + suffix(), 5, 2);

        UserCouponView first = coupons.claim("student", coupon.id());
        UserCouponView second = coupons.claim("student", coupon.id());
        assertCode(() -> coupons.claim("student", coupon.id()), "COUPON_LIMIT_REACHED");

        assertThat(issued(coupon.id())).isEqualTo(2);
        assertThat(claimSeqs("student", coupon.id())).containsExactly(1, 2);
        assertThat(first.id()).isNotEqualTo(second.id());
    }

    @Test
    @DisplayName("总发行量 2、每人限 5：一个人只能拿走 2 张，第三个人看到的是已领完")
    void quantityCanBindBeforePerUserLimit() {
        CouponView coupon = createCoupon("BIND" + suffix(), 2, 5);

        coupons.claim("student", coupon.id());
        coupons.claim("student", coupon.id());

        assertCode(() -> coupons.claim("student", coupon.id()), "COUPON_SOLD_OUT");
        assertCode(() -> coupons.claim("admin", coupon.id()), "COUPON_SOLD_OUT");
        assertThat(issued(coupon.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("并发抢 5 张：10 个线程同时领，成功次数必须恰好是 5，issued_count 不能超")
    void concurrentClaimsNeverOverIssue() throws Exception {
        String couponId = insertCoupon("RACE" + suffix(), 5, 1);

        int success = runConcurrently(10, () -> repository.increaseIssued(couponId) == 1);

        assertThat(success).as("成功次数必须等于库存").isEqualTo(5);
        assertThat(issued(couponId)).as("issued_count 必须等于库存").isEqualTo(5);
    }

    @Test
    @DisplayName("并发领券（服务层）：10 个不同用户抢 3 张，恰好 3 人成功、7 人看到已领完")
    void concurrentClaimByDifferentUsersStopsAtStock() throws Exception {
        CouponView coupon = createCoupon("RACEU" + suffix(), 3, 1);

        int success = runConcurrently(10, () -> claimOrReject("racer-" + suffix(), coupon.id(),
                "COUPON_SOLD_OUT"));

        assertThat(success).as("成功人数必须等于库存").isEqualTo(3);
        assertThat(issued(coupon.id())).isEqualTo(3);
        assertThat(claimRows(coupon.id())).isEqualTo(3);
    }

    @Test
    @DisplayName("并发领券（同一用户）：8 个线程抢限领 2 张，恰好成功 2 次，claim_seq 只能是 1、2")
    void concurrentClaimBySameUserRespectsPerUserLimit() throws Exception {
        CouponView coupon = createCoupon("RACEM" + suffix(), 100, 2);
        String username = "same-" + suffix();

        int success = runConcurrently(8, () -> claimOrReject(username, coupon.id(), "COUPON_LIMIT_REACHED"));

        assertThat(success).as("成功次数必须等于每人限领").isEqualTo(2);
        assertThat(issued(coupon.id())).isEqualTo(2);
        assertThat(claimSeqs(username, coupon.id())).containsExactly(1, 2);
    }

    // ——————————————————— 平台预算 ———————————————————

    @Test
    @DisplayName("预算边界：300 + 200 正好用满 500，再多 1 分就占不到")
    void budgetBoundaryIsExactToTheCent() {
        String couponId = insertCoupon("BUD" + suffix(), 10, 1, 500);

        assertThat(repository.reserveBudget(couponId, 300)).isEqualTo(1);
        assertThat(repository.reserveBudget(couponId, 200)).isEqualTo(1);
        assertThat(spent(couponId)).isEqualTo(500);

        assertThat(repository.reserveBudget(couponId, 1)).as("超出 1 分必须失败").isZero();
        assertThat(spent(couponId)).as("失败的预占不能留下痕迹").isEqualTo(500);

        assertThat(repository.refundBudget(couponId, 500)).isEqualTo(1);
        assertThat(spent(couponId)).isZero();
        assertThat(repository.refundBudget(couponId, 1)).as("不能退成负数").isZero();
        assertThat(spent(couponId)).isZero();
    }

    @Test
    @DisplayName("补贴为 0 时不占用预算：商家全担的券不会因为预算字段为 0 而下不了单")
    void zeroSubsidyNeedsNoBudget() {
        String couponId = insertCoupon("NOBUD" + suffix(), 10, 1);
        assertThat(repository.reserveBudget(couponId, 0)).isEqualTo(1);
        assertThat(spent(couponId)).isZero();
    }

    @Test
    @DisplayName("并发花 600 分预算：12 个线程各占 100 分，成功恰好 6 次，花费不超过预算")
    void concurrentBudgetNeverOverSpends() throws Exception {
        String couponId = insertCoupon("BRACE" + suffix(), 10, 1);
        jdbc.sql("UPDATE coupon SET platform_budget_cents = 600 WHERE id = :id")
                .param("id", couponId).update();

        int success = runConcurrently(12, () -> repository.reserveBudget(couponId, 100) == 1);

        assertThat(success).as("成功次数必须等于预算可容纳的次数").isEqualTo(6);
        assertThat(spent(couponId)).as("花费必须恰好等于预算").isEqualTo(600);
    }

    // ——————————————————— 券模板状态与时间窗 ———————————————————

    @Test
    @DisplayName("未开始的券：不出现在可领列表里，直接领也是明确的「不可领取」")
    void futureCouponIsNeitherListedNorClaimable() {
        LocalDateTime now = LocalDateTime.now();
        CouponView coupon = coupons.create("admin", false, new CouponCreateRequest(
                "FUT" + suffix(), "未开始券", "PLATFORM", null, "AMOUNT", new BigDecimal("5.00"),
                null, BigDecimal.ZERO, 0, 10, 1, 0L, now.plusHours(1), now.plusHours(2)));

        assertThat(coupons.available("student")).extracting(CouponView::id).doesNotContain(coupon.id());
        assertCode(() -> coupons.claim("student", coupon.id()), "COUPON_UNAVAILABLE");
    }

    @Test
    @DisplayName("已暂停的券：不出现在可领列表里，也不能领")
    void pausedCouponIsNotClaimable() {
        CouponView coupon = createCoupon("PAUSE" + suffix(), 10, 1);
        jdbc.sql("UPDATE coupon SET status = 'PAUSED' WHERE id = :id").param("id", coupon.id()).update();

        assertThat(coupons.available("student")).extracting(CouponView::id).doesNotContain(coupon.id());
        assertCode(() -> coupons.claim("student", coupon.id()), "COUPON_UNAVAILABLE");
    }

    @Test
    @DisplayName("已领完的券仍在列表里，但余量为 0 且按钮不可用——列表不替用户隐藏「抢光了」这个事实")
    void soldOutCouponStaysListedWithZeroRemaining() {
        CouponView coupon = createCoupon("GONE" + suffix(), 1, 1);
        coupons.claim("student", coupon.id());

        // 前端按 remainingQuantity <= 0 把按钮置灰（app.js#renderCoupons），
        // 所以后端保留这一行、只把余量归零，是两侧一致的契约。
        assertThat(coupons.available("student"))
                .filteredOn(view -> view.id().equals(coupon.id()))
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.remainingQuantity()).isZero();
                    assertThat(view.issuedCount()).isEqualTo(view.totalQuantity());
                });
        assertCode(() -> coupons.claim("admin", coupon.id()), "COUPON_SOLD_OUT");
    }

    // ——————————————————— 工具 ———————————————————

    private CouponView createCoupon(String code, int total, int perUser) {
        LocalDateTime now = LocalDateTime.now();
        return coupons.create("admin", false, new CouponCreateRequest(code, code + " 数量券",
                "PLATFORM", null, "AMOUNT", new BigDecimal("5.00"), null, BigDecimal.ZERO,
                0, total, perUser, 0L, now.minusHours(1), now.plusDays(1)));
    }

    private String insertCoupon(String code, int total, int perUser) {
        return insertCoupon(code, total, perUser, 0L);
    }

    /** 绕过接口校验直接落库：发行量 0 只能在库这一层造出来（接口要求至少 1）。 */
    private String insertCoupon(String code, int total, int perUser, long budgetCents) {
        LocalDateTime now = LocalDateTime.now();
        String id = UUID.randomUUID().toString();
        repository.insert(id, code, code + " 数量券", "PLATFORM", null, "AMOUNT",
                new BigDecimal("5.00"), null, BigDecimal.ZERO, 0, total, perUser, budgetCents,
                now.minusHours(1), now.plusDays(1));
        return id;
    }

    /**
     * 领一张券，把「业务拒绝」翻译成 {@code false}。
     *
     * <p>并发用例里「没抢到」是预期结果，但<b>拒绝原因必须是预期的那个</b>——
     * 抢不到库存和撞上数据库报错是两件事，后者不该被算作一次正常的失败。
     *
     * @return true 表示领到了
     */
    private boolean claimOrReject(String username, String couponId, String expectedCode) {
        try {
            coupons.claim(username, couponId);
            return true;
        } catch (BusinessException rejected) {
            assertThat(rejected.code()).as("被拒绝的原因").isEqualTo(expectedCode);
            return false;
        }
    }

    private int claimRows(String couponId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM user_coupon WHERE coupon_id = :c")
                .param("c", couponId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private List<Integer> claimSeqs(String username, String couponId) {
        return jdbc.sql("""
                        SELECT claim_seq FROM user_coupon
                         WHERE coupon_id = :c AND username = :u
                         ORDER BY claim_seq
                        """)
                .param("c", couponId).param("u", username)
                .query(Integer.class).list();
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

    /**
     * 同时起跑 N 个任务，返回「成功」的次数。
     *
     * <p>用栅栏而不是 {@code sleep}：只保证它们尽量同时到达更新语句，
     * 这才是条件更新真正要面对的时序。任务本身抛出的异常不被吞掉——
     * 异常可能意味着「没抢到」，也可能意味着真出了问题，必须让它红。
     */
    private int runConcurrently(int attempts, Callable<Boolean> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("线程没能在 10 秒内就绪").isTrue();
            go.countDown();
            int success = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) success++;
            }
            return success;
        } finally {
            pool.shutdownNow();
        }
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
