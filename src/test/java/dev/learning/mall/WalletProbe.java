package dev.learning.mall;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 钱包对账用的几把尺子（测试专用）。
 *
 * <p>它们刻意<b>不住在生产代码里</b>：目前只有测试在问「余额对不对得上账」，
 * 放进 {@code wallet.internal} 就是「没有消费者的端口」。
 * 归宿与 {@code StockLedgerTest} 里那几条对账 SQL 一致——尺子归测试所有。
 *
 * <p>三条尺子各管一件事，缺一不可：
 * <ul>
 *   <li>{@link #reconciliationMismatch}：余额 == Σ流水 delta。它是「有人绕过服务改了余额」的唯一探测器。</li>
 *   <li>{@link #chainMismatch}：相邻流水首尾相接。它是「漏了行锁」的唯一探测器——
 *       漏锁时余额、对账恒等式、算术 CHECK <b>全都仍然正确</b>（推导见钱包方案 §3.2）。</li>
 *   <li>{@link #grantedCents}：累计已授予额度。它是「充值上限」那条断言的依据。</li>
 * </ul>
 */
final class WalletProbe {

    private WalletProbe() {
    }

    /**
     * 对账：{@code wallet.balance_cents} 是否等于该用户所有流水 delta 之和。返回 1 表示对不上。
     *
     * <p>开户时 {@code OPENING} 本身就是一条 delta，因此不需要像库存那样另带基线：
     * 账户一旦存在，恒等式就应该成立。
     */
    static int reconciliationMismatch(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT COUNT(*) FROM wallet w"
                        + " WHERE w.username = :u AND w.balance_cents <> COALESCE("
                        + "   (SELECT SUM(l.delta_cents) FROM wallet_ledger l WHERE l.username = :u), 0)")
                .param("u", username)
                .query(Integer.class)
                .single();
    }

    /**
     * 相邻流水的断链数：每一条的 {@code balance_after_cents} 必须等于下一条的 {@code balance_before_cents}。
     *
     * <p>为什么不是「断言最终余额正确」或者「断言 before 各不相同」：
     * 前者在漏锁时也成立（条件 UPDATE 会把余额算对），后者在串行执行时也成立。
     * 只有「首尾相接」要求中间一格都不丢。
     */
    static int chainMismatch(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT COUNT(*) FROM ("
                        + " SELECT balance_after_cents, LEAD(balance_before_cents)"
                        + "        OVER (PARTITION BY username ORDER BY id) AS next_before"
                        + "   FROM wallet_ledger WHERE username = :u) t"
                        + " WHERE t.next_before IS NOT NULL AND t.balance_after_cents <> t.next_before")
                .param("u", username)
                .query(Integer.class)
                .single();
    }

    /** 某类原因的流水 delta 列表（按 id 升序）。符号断言用。 */
    static List<Long> deltas(JdbcClient jdbc, String username, String reason) {
        return jdbc.sql("SELECT delta_cents FROM wallet_ledger"
                        + " WHERE username = :u AND reason = :reason ORDER BY id")
                .param("u", username)
                .param("reason", reason)
                .query(Long.class)
                .list();
    }

    /** 累计已授予额度（OPENING + TOPUP）。与服务里 {@code grantedCents} 同一口径。 */
    static long grantedCents(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT COALESCE(SUM(delta_cents), 0) FROM wallet_ledger"
                        + " WHERE username = :u AND reason IN ('OPENING','TOPUP')")
                .param("u", username)
                .query(Long.class)
                .single();
    }

    static long balanceCents(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT balance_cents FROM wallet WHERE username = :u")
                .param("u", username)
                .query(Long.class)
                .single();
    }

    /** 该用户有没有账户行。「迁移不 seeding」这条断言用它。 */
    static long walletRowCount(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT COUNT(*) FROM wallet WHERE username = :u")
                .param("u", username)
                .query(Long.class)
                .single();
    }

    static long ledgerRowCount(JdbcClient jdbc, String username) {
        return jdbc.sql("SELECT COUNT(*) FROM wallet_ledger WHERE username = :u")
                .param("u", username)
                .query(Long.class)
                .single();
    }

    /** 清理：用例之间不互相污染（H2 内存库在整个 JVM 内共享）。 */
    static void purge(JdbcClient jdbc, String username) {
        jdbc.sql("DELETE FROM wallet_ledger WHERE username = :u").param("u", username).update();
        jdbc.sql("DELETE FROM wallet WHERE username = :u").param("u", username).update();
    }
}
