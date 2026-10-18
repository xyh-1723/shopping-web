package dev.learning.mall.order.internal;

import dev.learning.mall.order.api.RefundView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code refund} 表与 {@link RefundRow} 之间的唯一通道。
 *
 * <p>包可见是刻意的：退款的状态迁移与「什么时候可以放款 / 还货」绑在一起，
 * 只有 {@link RefundService} 知道这个顺序。把 Repository 暴露出去，
 * 等于邀请调用方「自己把状态改成 REFUNDED」——那正是当前缀 *= 状态机要反对的事。
 *
 * <p><b>五条写语句全部带前置条件</b>，返回值是<b>影响行数</b>：
 * 1 表示「这次状态转换是我完成的」，0 表示别人已经处理过，调用方据此安静跳过。
 * 这是退款幂等的应用层那一半，数据库那一半是 {@code uq_refund_inflight} 唯一索引。
 *
 * <p><b>刻意不提供 update / delete</b>：退款记录同样是审计留痕。
 */
@Repository
class RefundRepository {

    /**
     * 取值清单里<b>不要</b>出现 {@code in_flight}：它是 {@code GENERATED ALWAYS AS} 的计算列，
     * H2 不允许写入（实测报错 {@code Generated column "PUBLIC.REFUND.IN_FLIGHT" cannot be assigned}）。
     */
    private static final String SELECT = "SELECT id, order_id, username, amount, reason_code, reason_text, "
            + "route, status, requested_by, decided_by, decided_at, decision_note, "
            + "stock_returned, created_at FROM refund ";

    private final JdbcClient jdbc;

    RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RefundRow> find(String id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(RefundRow.class).optional();
    }

    List<RefundRow> pendingQueue(int limit) {
        return jdbc.sql(SELECT + "WHERE status = 'PENDING_APPROVAL' ORDER BY created_at, id LIMIT :limit")
                .param("limit", limit).query(RefundRow.class).list();
    }

    List<RefundRow> ofUser(String username) {
        return jdbc.sql(SELECT + "WHERE username = :u ORDER BY created_at DESC, id LIMIT 50")
                .param("u", username).query(RefundRow.class).list();
    }

    /**
     * 该订单已经走到放款/放款后阶段的金额之和。
     *
     * <p>判据是 {@code status IN ('APPROVED','REFUNDED')}—— {@code APPROVED} 虽然钱还没到，
     * 但它已经不可撤销地承诺了这笔钱，把它漏掉会让两笔并发审批一起通过总额校验。
     * {@code PENDING_APPROVAL} 则相反：它还没被任何人同意，不该占额度，
     * 否则「先申请一笔大的」就能把别人堵死（同一时刻只允许一笔在途，本来就已经够挤了）。
     *
     * <p>调用方必须已经持有订单行的行锁。
     */
    BigDecimal settledAmount(String orderId) {
        BigDecimal sum = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refund"
                        + " WHERE order_id = :o AND status IN ('APPROVED','REFUNDED')")
                .param("o", orderId).query(BigDecimal.class).single();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 该订单当前有没有在途退款（应用层预检用；真正的防线是数据库唯一索引）。 */
    boolean hasInFlight(String orderId) {
        Long n = jdbc.sql("SELECT COUNT(*) FROM refund"
                        + " WHERE order_id = :o AND status IN ('PENDING_APPROVAL','APPROVED')")
                .param("o", orderId).query(Long.class).single();
        return n != null && n > 0;
    }

    /**
     * 落一笔退款申请。<b>状态一律是 {@code PENDING_APPROVAL}</b>，
     * 包括 AUTO 路由——因为 {@code uq_refund_inflight} 只对在途态生效，
     * 直接插成 {@code REFUNDED} 会让这行从头到尾不进索引，
     * 「同一订单两笔 AUTO 退款」就绕开了数据库约束。
     */
    void insert(String id, String orderId, String username, BigDecimal amount, String reasonCode,
                String reasonText, String route, String requestedBy) {
        jdbc.sql("INSERT INTO refund(id,order_id,username,amount,reason_code,reason_text,"
                        + "route,requested_by) VALUES(:id,:order,:user,:amount,:code,:text,:route,:by)")
                .param("id", id)
                .param("order", orderId)
                .param("user", username)
                .param("amount", amount)
                .param("code", reasonCode)
                .param("text", reasonText)
                .param("route", route)
                .param("by", requestedBy)
                .update();
    }

    /**
     * 第一段迁移：{@code PENDING_APPROVAL → APPROVED}，写审批人与审批时刻。
     *
     * <p>影响 1 行 = 「批准这件事是我完成的」，于是接下来的放款与还货也是我的责任。
     * 0 行 = 别人已经批过（并发双击 / 重放），调用方必须跳过而不是再放一次款。
     */
    int markApproved(String id, String decidedBy, String note, LocalDateTime decidedAt) {
        return jdbc.sql("UPDATE refund SET status='APPROVED',decided_by=:who,decided_at=:at,"
                        + "decision_note=:note,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=:id AND status='PENDING_APPROVAL'")
                .param("id", id).param("who", decidedBy).param("at", decidedAt)
                .param("note", note).update();
    }

    /**
     * 第二段迁移：{@code APPROVED → REFUNDED}，钱真正离开（回到买家钱包）的那一刻。
     *
     * <p><b>它为什么必须和 {@link #markApproved} 分开</b>：现实里「批准」与「钱到账」常常不是
     * 同一时刻。规则因此写死成「只有 {@code REFUNDED} 才允许归还库存」，
     * 否则「批了但转账失败」会变成「库存回来了、钱没退」。
     */
    int markRefunded(String id, LocalDateTime at) {
        return jdbc.sql("UPDATE refund SET status='REFUNDED',updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=:id AND status='APPROVED'")
                .param("id", id).update();
    }

    /** 驳回：{@code PENDING_APPROVAL → REJECTED}，同样是一次性迁移。 */
    int markRejected(String id, String decidedBy, String note, LocalDateTime decidedAt) {
        return jdbc.sql("UPDATE refund SET status='REJECTED',decided_by=:who,decided_at=:at,"
                        + "decision_note=:note,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=:id AND status='PENDING_APPROVAL'")
                .param("id", id).param("who", decidedBy).param("at", decidedAt)
                .param("note", note).update();
    }

    /**
     * 标记库存已归还。前置条件里带着 {@code status='REFUNDED'}：
     * 数据库有 {@code ck_refund_stock_only_when_refunded} 兜底，这里再写一遍，
     * 是为了让「还错状态」变成 0 行而不是一句约束报错——它本来就不是错误，只是别人抢先做了。
     */
    int markStockReturned(String id) {
        return jdbc.sql("UPDATE refund SET stock_returned=1,updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=:id AND stock_returned=0 AND status='REFUNDED'")
                .param("id", id).update();
    }

    RefundView view(RefundRow row) {
        return new RefundView(row.id(), row.orderId(), row.username(), row.amount(), row.reasonCode(),
                row.reasonText(), row.route(), row.status(), row.requestedBy(), row.decidedBy(),
                row.decidedAt(), row.decisionNote(), row.stockReturnedFlag(), row.createdAt());
    }
}
