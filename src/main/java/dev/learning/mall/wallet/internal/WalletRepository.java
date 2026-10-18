package dev.learning.mall.wallet.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code wallet}（当前余额）的唯一读写口。
 *
 * <p>包可见是刻意的：余额与 {@code wallet_ledger} 的写入必须成对发生，
 * 而这对操作只有 {@link WalletService} 知道怎么排（懒开户 → 加行锁读前值 → 改余额 → 留痕）。
 * 把 Repository 暴露成公共 API，等于邀请调用方「只改余额不记账」——
 * 那正是流水存在的意义所反对的事。
 *
 * <p>所有金额参数与返回值都是 {@code long} 分：钱包内部根本不存在 {@code BigDecimal}，
 * 于是「绕过 {@code MoneyUnits} 自己算一遍」在类型层面就做不到。
 */
@Repository
class WalletRepository {

    private final JdbcClient jdbc;

    WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 懒开户：账户不存在则插入，已存在则影响 0 行。
     *
     * <p>用 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 而不是先 SELECT 再 INSERT：
     * 两步之间会有别人插进来，而这里给的是「一次性判断 + 插入」，
     * 重复调用天然幂等（返回 0 行），不需要额外的乐观锁或异常兜底。
     *
     * @param openingCents 开户即授予的额度（来自配置，迁移里不 seeding）
     * @return 1 表示这次真的开了户（调用方据此补一条 {@code OPENING} 流水），0 表示账户已存在
     */
    int ensureRow(String username, long openingCents) {
        return jdbc.sql("INSERT INTO wallet(username, balance_cents)"
                        + " SELECT :user, :grant WHERE NOT EXISTS"
                        + " (SELECT 1 FROM wallet WHERE username = :user)")
                .param("user", username)
                .param("grant", openingCents)
                .update();
    }

    /**
     * 取当前余额并加行锁：保证「读到的前值」与接下来的写入之间无人插队。
     *
     * <p><b>为什么行锁不能省</b>（条件 UPDATE 单独并不够）：
     * 两笔并发扣款 {@code a} / {@code b} 在初值 {@code X} 上，若无锁，两者都读到 {@code X}，
     * 条件 UPDATE 会把最终余额也算对（{@code X-a-b}），对账恒等式也成立，
     * {@code balance_after = balance_before + delta} 也成立（after 是同一个 before 算出来的）。
     * 唯一错的是<b>流水</b>：后一条的 {@code balance_before} 记成了 {@code X} 而不是 {@code X-a}，
     * 中间漏了一格。能抓住它的只有「相邻流水首尾相接」那条窗口查询。
     *
     * <p>调用方必须已经在事务里，且账户行已存在（先 {@link #ensureRow}）。
     */
    long balanceForUpdate(String username) {
        return jdbc.sql("SELECT balance_cents FROM wallet WHERE username = :user FOR UPDATE")
                .param("user", username)
                .query(Long.class)
                .single();
    }

    /**
     * 条件扣减：判断与扣减是同一条 SQL，影响 1 行才算这一笔真的发生了。
     *
     * <p>不要拆成「先查余额再无条件扣减」——那两步之间余额会被别人改掉，
     * 于是「看着够、扣成负数」。影响行数就是转换权：0 行 = 余额不足。
     */
    int tryDeduct(String username, long cents) {
        return jdbc.sql("UPDATE wallet SET balance_cents = balance_cents - :cents"
                        + " WHERE username = :user AND balance_cents >= :cents")
                .param("cents", cents)
                .param("user", username)
                .update();
    }

    /**
     * 入账（退款放款 / 演示充值）。入账不可能超扣，因此不需要条件。
     *
     * <p>刻意不检查影响行数：调用方（{@link WalletService}）已在同一个事务里跑过
     * {@link #ensureRow}，账户行必然存在；为一个 by-construction 不会发生的结果
     * 写一条抛异常分支，只会让读代码的人以为这里真有一种失败形态。
     */
    void add(String username, long cents) {
        jdbc.sql("UPDATE wallet SET balance_cents = balance_cents + :cents WHERE username = :user")
                .param("cents", cents)
                .param("user", username)
                .update();
    }

    /** 当前余额（不加锁，只用于展示）。调用方保证账户已存在。 */
    long balance(String username) {
        return jdbc.sql("SELECT balance_cents FROM wallet WHERE username = :user")
                .param("user", username)
                .query(Long.class)
                .single();
    }
}
