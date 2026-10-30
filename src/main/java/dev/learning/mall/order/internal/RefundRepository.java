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
    private static final String SELECT = "SELECT id, order_id, order_sub_id, username, amount, reason_code, "
            + "reason_text, route, status, requested_by, decided_by, decided_at, decision_note, "
            + "stock_returned, created_at FROM refund ";

    private final JdbcClient jdbc;

    RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RefundRow> find(String id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(RefundRow.class).optional();
    }

    /**
     * 全站待审批队列（店员 / 运营视角）。
     *
     * <p><b>调用方必须先确认这个人是店员</b>：商家看队列要走 {@link #pendingQueueOf}。
     * 这里没有第二道过滤，是因为「全站」本身就是它的语义——
     * 把两种可见范围塞进一个方法、再用一个开关切换，等于让「谁能看多少」这件事
     * 分散在调用点各处，那正是加商家表之前的状态（只不过那时是「谁都能看全部」）。
     */
    List<RefundRow> pendingQueue(int limit) {
        return jdbc.sql(SELECT + "WHERE status = 'PENDING_APPROVAL' ORDER BY created_at, id LIMIT :limit")
                .param("limit", limit).query(RefundRow.class).list();
    }

    /**
     * 某一家店的待审批队列：只含<b>挂在这家店子订单上</b>的退款。
     *
     * <p>判据落在 {@code refund.order_sub_id} 上——退款直接挂在子订单上，
     * 而子订单在结构上只属于一家店（{@code order_sub.merchant_id}）。
     * 用 {@code EXISTS} 而不是 {@code JOIN}，是因为 {@code refund} 与 {@code order_sub}
     * 是一对一，JOIN 今天不会重复；真正会被 JOIN 放大的是
     * 「退款 → 订单 → 明细」这条链（明细一家店可能三行），
     * 而这条链在 V25 之后已经不存在于队列查询里了。
     *
     * <p><b>与 V24 版本的差别就是这次搬动的全部内容</b>：那时候判据是
     * {@code EXISTS (SELECT 1 FROM order_item i WHERE i.order_id = r.order_id AND i.merchant_id = :m)}，
     * 一笔跨店订单会被多家店同时判成「有我一份」。现在一笔退款只有一个子订单，
     * 于是「这笔退款是不是我的」第一次有了唯一答案。
     */
    List<RefundRow> pendingQueueOf(long merchantId, int limit) {
        return jdbc.sql(SELECT + "r WHERE r.status = 'PENDING_APPROVAL'"
                        + " AND EXISTS (SELECT 1 FROM order_sub s"
                        + "              WHERE s.id = r.order_sub_id AND s.merchant_id = :m)"
                        + " ORDER BY r.created_at, r.id LIMIT :limit")
                .param("m", merchantId).param("limit", limit).query(RefundRow.class).list();
    }

    List<RefundRow> ofUser(String username) {
        return jdbc.sql(SELECT + "WHERE username = :u ORDER BY created_at DESC, id LIMIT 50")
                .param("u", username).query(RefundRow.class).list();
    }

    /**
     * 这个<b>子订单</b>已经走到放款/放款后阶段的金额之和。
     *
     * <p>判据是 {@code status IN ('APPROVED','REFUNDED')}—— {@code APPROVED} 虽然钱还没到，
     * 但它已经不可撤销地承诺了这笔钱，把它漏掉会让两笔并发审批一起通过总额校验。
     * {@code PENDING_APPROVAL} 则相反：它还没被任何人同意，不该占额度，
     * 否则「先申请一笔大的」就能把别人堵死（同一时刻只允许一笔在途，本来就已经够挤了）。
     *
     * <p><b>范围是子订单，不是父订单</b>：上限因此是「这家店在这笔交易里分到的钱」，
     * 而不是整单实付。跨店订单里 A 店退掉自己那份，不该占用 B 店的可退额度。
     *
     * <p>调用方必须已经持有子订单行的行锁。
     */
    BigDecimal settledAmount(String subId) {
        BigDecimal sum = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM refund"
                        + " WHERE order_sub_id = :s AND status IN ('APPROVED','REFUNDED')")
                .param("s", subId).query(BigDecimal.class).single();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 这个子订单当前有没有在途退款（应用层预检用；真正的防线是数据库唯一索引）。
     *
     * <p>{@code uq_refund_inflight} 在 V25 里从 {@code order_id} 改挂到 {@code order_sub_id}：
     * 同一个父单下，A 店与 B 店各有一笔在途退款是<b>正常且必须允许</b>的
     * （两家店各自在处理自己的那一单），把它们互相堵死才是错的行为。
     */
    boolean hasInFlight(String subId) {
        Long n = jdbc.sql("SELECT COUNT(*) FROM refund"
                        + " WHERE order_sub_id = :s AND status IN ('PENDING_APPROVAL','APPROVED')")
                .param("s", subId).query(Long.class).single();
        return n != null && n > 0;
    }

    /**
     * 落一笔退款申请。<b>状态一律是 {@code PENDING_APPROVAL}</b>，
     * 包括 AUTO 路由——因为 {@code uq_refund_inflight} 只对在途态生效，
     * 直接插成 {@code REFUNDED} 会让这行从头到尾不进索引，
     * 「同一子订单两笔 AUTO 退款」就绕开了数据库约束。
     */
    void insert(String id, String orderId, String orderSubId, String username, BigDecimal amount,
                String reasonCode, String reasonText, String route, String requestedBy) {
        jdbc.sql("INSERT INTO refund(id,order_id,order_sub_id,username,amount,reason_code,reason_text,"
                        + "route,requested_by) VALUES(:id,:order,:sub,:user,:amount,:code,:text,:route,:by)")
                .param("id", id)
                .param("order", orderId)
                .param("sub", orderSubId)
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
        return new RefundView(row.id(), row.orderId(), row.orderSubId(), row.username(), row.amount(),
                row.reasonCode(), row.reasonText(), row.route(), row.status(), row.requestedBy(),
                row.decidedBy(), row.decidedAt(), row.decisionNote(), row.stockReturnedFlag(),
                row.createdAt());
    }
}
