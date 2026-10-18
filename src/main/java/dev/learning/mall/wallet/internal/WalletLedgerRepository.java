package dev.learning.mall.wallet.internal;

import dev.learning.mall.wallet.api.WalletLedgerEntry;
import dev.learning.mall.wallet.api.WalletReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 资金流水（{@code wallet_ledger}）的<b>唯一写入口</b>，以及页面流水视图的唯一读口。
 *
 * <p>包可见，理由与 {@code catalog.internal.StockLedgerRepository} 完全一致：
 * 流水与余额的写入必须成对发生，而这对操作只有 {@link WalletService} 知道怎么排。
 *
 * <p>这里<b>故意不提供 update / delete</b>：流水是审计留痕，错了就补一条反向记录，
 * 不允许改写历史。{@link #recent} 是唯一的读方法，且它只读——
 * 连同写入口收在一处，是为了让「页面想看流水就自己拼一条 SQL」这条后门不存在。
 *
 * <p>对账与断链查询刻意<b>不放</b>在这里：它们目前只有测试在用，
 * 放进生产代码就是「没有消费者的端口」（项目硬约定）。它们以 SQL 的形态
 * 住在 {@code WalletLedgerTest} 里——与 {@code StockLedgerTest} 的对账 SQL 同一处归宿。
 */
@Repository
class WalletLedgerRepository {

    private final JdbcClient jdbc;

    WalletLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条流水。
     *
     * <p>四个数字（前值、变化量、后值）全部由调用方传入，本方法不做任何推算：
     * {@code balance_after = balance_before + delta} 由数据库的
     * {@code ck_wallet_ledger_arithmetic} 兜底，一旦调用方算错，报错的是数据库而不是一句日志。
     *
     * @param deltaCents 变化量（正为入账，负为出账；不允许 0，无变化就不要记）
     * @param before     变动前的余额（必须在行锁内读到，否则会记成别人的中间态）
     * @param after      变动后的余额
     * @param reason     变动原因；它的方向同时被数据库的两条方向约束钉着
     * @param actor      谁动的：支付是买家账号，开户是 {@code SYSTEM}
     * @param orderId    关联订单号；{@code OPENING} / {@code TOPUP} 为 {@code null}
     */
    void append(String username, long deltaCents, long before, long after,
                WalletReason reason, String actor, String orderId) {
        jdbc.sql("INSERT INTO wallet_ledger"
                        + "(username, delta_cents, balance_before_cents, balance_after_cents,"
                        + " reason, actor, order_id)"
                        + " VALUES (:user, :delta, :before, :after, :reason, :actor, :orderId)")
                .param("user", username)
                .param("delta", deltaCents)
                .param("before", before)
                .param("after", after)
                .param("reason", reason.name())
                .param("actor", actor)
                .param("orderId", orderId)
                .update();
    }

    /**
     * 最近 {@code limit} 条流水，新的在前。
     *
     * <p>显式 {@link RowMapper} 而不是 {@code query(WalletLedgerEntry.class)}：
     * {@code deltaYuan} 不在表上，只能由 {@link WalletLedgerEntry#of} 现算，
     * 让框架按列名直接装配 record 会把它填成 {@code null}。
     *
     * <p>{@code ORDER BY id DESC} 而不是按 {@code created_at}：{@code created_at} 的默认值
     * 精度到秒，同一秒内的两笔分不出先后，而 {@code id} 是自增的——
     * 「顺序错一格」在这里看不出来，在首尾相接那条对账里就是一次断链。
     */
    List<WalletLedgerEntry> recent(String username, int limit) {
        return jdbc.sql("SELECT id, username, delta_cents, balance_before_cents,"
                        + " balance_after_cents, reason, actor, order_id, created_at"
                        + " FROM wallet_ledger WHERE username = :user ORDER BY id DESC LIMIT :limit")
                .param("user", username)
                .param("limit", limit)
                .query(ENTRY_MAPPER)
                .list();
    }

    private static final RowMapper<WalletLedgerEntry> ENTRY_MAPPER = (rs, rowNum) ->
            WalletLedgerEntry.of(
                    rs.getLong("id"),
                    rs.getString("username"),
                    rs.getLong("delta_cents"),
                    rs.getLong("balance_before_cents"),
                    rs.getLong("balance_after_cents"),
                    WalletReason.valueOf(rs.getString("reason")),
                    rs.getString("actor"),
                    rs.getString("order_id"),
                    rs.getObject("created_at", LocalDateTime.class));

    /**
     * 累计已授予额度（开户 + 演示充值）。一次 SUM 就够了，不需要额外的列去维护。
     *
     * <p><b>必须在钱包行锁内调用</b>：否则两个并发充值各自读到「还没超」，
     * 一起放行就把上限捅穿了。
     */
    long grantedCents(String username) {
        return jdbc.sql("SELECT COALESCE(SUM(delta_cents), 0) FROM wallet_ledger"
                        + " WHERE username = :user AND reason IN ('OPENING','TOPUP')")
                .param("user", username)
                .query(Long.class)
                .single();
    }
}
