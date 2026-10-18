package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.wallet.api.WalletAccounts;
import dev.learning.mall.wallet.internal.WalletProperties;
import dev.learning.mall.wallet.internal.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 并发扣款与余额不足的语义（钱包方案 §3）。
 *
 * <p>本文件存在的理由只有一条：<b>行锁是唯一能守住流水 {@code balance_before} 的东西</b>，
 * 而它一旦被后来的优化者删掉（「反正条件 UPDATE 已经够了」），
 * 余额、对账恒等式、{@code ck_wallet_ledger_arithmetic} <b>全都仍然正确</b>——
 * 只有「相邻流水首尾相接」这条窗口查询会红。推导见 {@code WalletRepository#balanceForUpdate}。
 *
 * <p>因此这里刻意放了两条<b>对照</b>断言：
 * <ul>
 *   <li>{@link #concurrentDebitsKeepLedgerChainConsistent}：<b>是</b>行锁的证据。</li>
 *   <li>{@link #finalBalanceIsCorrectUnderRace}：断言最终余额。<b>不是</b>行锁的证据
 *       ——有没有行锁它都绿。它留在这里的作用是<b>反向定位</b>：
 *       一旦它跟着一起红了，说明问题出在别处（条件 UPDATE 坏了、并发根本没跑起来），
 *       而不是行锁。别把它删掉，也别把它当成行锁的守卫。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletConcurrencyTest {

    private static final String ACTOR = "tester";
    /** 并发线程数：要足够多，才可能真的撞上；又不至于把 H2 的锁超时跑满。 */
    private static final int THREADS = 6;
    /** 每笔扣多少分。THREADS * EACH 必须远小于初始额度，保证每笔都能成功。 */
    private static final long EACH = 1_000L;

    @Autowired WalletAccounts accounts;
    @Autowired WalletService wallet;
    @Autowired WalletProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    private String user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
    }

    private String user(String suffix) {
        user = "wallet-" + suffix;
        return user;
    }

    /**
     * 并发扣款：流水必须首尾相接。
     *
     * <p><b>这是本轮最关键、也最容易写错的一条。</b>
     * 漏了 {@code FOR UPDATE} 时：最终余额对、对账恒等式对、算术 CHECK 不报——
     * 唯一变红的是 {@code chainMismatch}。任何只断言最终余额的并发测试都守不住它。
     */
    @Test
    @DisplayName("并发扣款：相邻流水首尾相接，balance_before 不会记到别人的中间态")
    void concurrentDebitsKeepLedgerChainConsistent() throws Exception {
        String u = user("concurrent");
        accounts.ensureAccount(u);
        long grant = WalletProbe.balanceCents(jdbc, u);
        assertThat(grant).as("用例前提：初始额度够这 %d 笔扣", THREADS).isGreaterThanOrEqualTo(THREADS * EACH);

        runConcurrentDebits(u);

        assertThat(WalletProbe.chainMismatch(jdbc, u))
                .as("相邻流水必须首尾相接，中间不允许丢格——这是漏了行锁时唯一会红的断言").isZero();
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        // 整条链还得接在开户那一格上：第一条的前值就是开户额度。
        assertThat(jdbc.sql("SELECT balance_before_cents FROM wallet_ledger WHERE username = :u"
                        + " AND reason <> 'OPENING' ORDER BY id LIMIT 1")
                .param("u", u).query(Long.class).single()).isEqualTo(grant);
    }

    /**
     * 对照组：最终余额正确。
     *
     * <p><b>刻意标注为「非证据」</b>：有没有行锁它都绿，所以它不能用来证明行锁存在。
     * 它在这里的作用是反向定位——一旦它也红了，说明坏的是条件 UPDATE 或测试本身。
     */
    @Test
    @DisplayName("对照组（非行锁证据）：最终余额 == 初值 − 扣款之和")
    void finalBalanceIsCorrectUnderRace() throws Exception {
        String u = user("race-balance");
        accounts.ensureAccount(u);
        long grant = WalletProbe.balanceCents(jdbc, u);

        runConcurrentDebits(u);

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grant - THREADS * EACH);
        assertThat(WalletProbe.deltas(jdbc, u, "PAYMENT")).hasSize(THREADS);
    }

    /**
     * 余额不足必须是一次<b>干净的 409</b>，而不是从数据库漏出来的约束异常（500）。
     *
     * <p>断言落在 {@code BusinessException.code()} 上，刻意不写
     * {@code isInstanceOf(Exception.class)}——那样任何异常都能混过去。
     */
    @Test
    @DisplayName("余额不足：抛出 409 INSUFFICIENT_FUNDS，余额与流水零变化")
    void overdraftIsRejectedAsCleanConflict() {
        String u = user("overdraft");
        accounts.ensureAccount(u);
        long grant = WalletProbe.balanceCents(jdbc, u);
        // 先把余额压到 100 分，再试图扣 150 分。
        tx().executeWithoutResult(s -> wallet.debit(u, grant - 100, ACTOR, "order-drain"));
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(100L);
        long ledgerRows = WalletProbe.ledgerRowCount(jdbc, u);

        BusinessException rejected = catchThrowableOfType(
                () -> tx().executeWithoutResult(s -> wallet.debit(u, 150, ACTOR, "order-overdraw")),
                BusinessException.class);

        assertThat(rejected).as("余额不足必须是一次干净的 409，而不是从数据库漏出来的约束异常").isNotNull();
        assertThat(rejected.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected.status().value()).isEqualTo(409);
        assertThat(rejected.getMessage()).contains("余额不足");

        assertThat(WalletProbe.balanceCents(jdbc, u)).as("余额不足时一分钱都不许动").isEqualTo(100L);
        assertThat(WalletProbe.ledgerRowCount(jdbc, u)).as("失败了就不许留痕").isEqualTo(ledgerRows);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    /**
     * 条件 UPDATE 那一层：{@code AND balance_cents >= :cents} 被去掉后，
     * 超扣会以「数据库约束异常」的形态冒出来（500），而不是一次明确的业务拒绝。
     */
    @Test
    @DisplayName("不允许超扣：余额 100 分扣 150 分，既扣不成也不留痕")
    void debitCannotOverdraw() {
        String u = user("no-overdraw");
        accounts.ensureAccount(u);
        long grant = WalletProbe.balanceCents(jdbc, u);
        tx().executeWithoutResult(s -> wallet.debit(u, grant - 100, ACTOR, "order-drain-2"));

        BusinessException rejected = catchThrowableOfType(
                () -> tx().executeWithoutResult(s -> wallet.debit(u, 150, ACTOR, "order-overdraw-2")),
                BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.code()).isEqualTo("INSUFFICIENT_FUNDS");
        // 余额没有变成负数：这是「不能超扣」在数据库里的那一半（ck_wallet_balance_nonneg）
        // 加上 Java 侧条件 UPDATE 的双重结果。
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(100L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM wallet_ledger WHERE username = :u"
                        + " AND balance_after_cents < 0").param("u", u).query(Long.class).single())
                .as("流水里也不许出现负余额").isZero();
    }

    /**
     * 充值上限的 {@code SUM} 必须在钱包<b>行锁内</b>执行：否则两个并发充值各自读到「还没超」，
     * 一起放行就把上限捅穿。
     *
     * <p>断言刻意写成 {@code <= 上限} 而不是 {@code <= 上限 + 单次}：
     * 后者在 bug 存在时也会绿，是「断言强度不足导致假绿」的高发形态。
     */
    @Test
    @DisplayName("并发充值不会捅穿累计上限：最终累计授予 <= 上限，且只成功一次")
    void ceilingSurvivesConcurrentTopup() throws Exception {
        String u = user("ceiling-race");
        long grant = properties.getInitialGrantCents();
        long amount = properties.getTopupAmountCents();
        long ceiling = properties.getTopupCeilingCents();

        accounts.ensureAccount(u);
        long times = (ceiling - grant) / amount;
        assertThat(times).as("用例前提：额度足够让并发撞上上限").isGreaterThanOrEqualTo(2);
        // 先串行充到「只剩一次额度」：并发再来冲，只有一次该成功。
        for (int i = 0; i < times - 1; i++) {
            accounts.topUp(u);
        }
        assertThat(WalletProbe.grantedCents(jdbc, u)).isEqualTo(grant + (times - 1) * amount);

        int threads = 3;
        int succeeded = runConcurrentTopUps(u, threads);

        assertThat(succeeded).as("只剩一次额度，并发 %d 次里只能成功 1 次", threads).isEqualTo(1);
        assertThat(WalletProbe.grantedCents(jdbc, u))
                .as("累计授予不得超过上限：放宽成「上限 + 单次」会在 bug 存在时假绿")
                .isLessThanOrEqualTo(ceiling);
        assertThat(WalletProbe.grantedCents(jdbc, u)).isEqualTo(grant + times * amount);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 并发驱动 ———————————————————

    /** 起 {@code THREADS} 个线程，各自在独立事务里经真实 {@link WalletService#debit} 扣 {@code EACH} 分。 */
    private void runConcurrentDebits(String username) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            var ready = new CountDownLatch(THREADS);
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("等起跑信号超时");
                    // 每笔用不同的 order_id：这里要验的是并发，不是 (order_id, reason) 唯一索引。
                    tx.executeWithoutResult(s ->
                            wallet.debit(username, EACH, ACTOR + "-" + index, "order-" + index));
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("并发线程都已就位").isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
    }

    /**
     * 起 {@code threads} 个线程并发充值，返回<b>成功</b>的次数。
     *
     * <p>被 {@code TOPUP_LIMIT_EXCEEDED} 拒掉是预期结果之一，不算失败：
     * 这条用例要断言的正是「只有该成功的那一次成功了」。
     */
    private int runConcurrentTopUps(String username, int threads) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("等起跑信号超时");
                    try {
                        tx.executeWithoutResult(s -> accounts.topUp(username));
                        return Boolean.TRUE;
                    } catch (BusinessException expected) {
                        return Boolean.FALSE;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("并发线程都已就位").isTrue();
            start.countDown();
            int ok = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) ok++;
            }
            return ok;
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }
}
