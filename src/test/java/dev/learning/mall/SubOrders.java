package dev.learning.mall;

import dev.learning.mall.order.api.OrderView;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 子订单的测试夹具（阶段 3b）。
 *
 * <p>存在的理由：履约与退款的单位从「订单」变成「子订单」之后，几乎所有相关用例都要先回答
 * 「退/发的是哪一家、它的子订单号是什么」。如果每个测试类各自写一段
 * {@code SELECT id FROM order_sub WHERE order_id = …}，那么改一次 id 规则
 * （{@code 父单号 + "-S" + 序号}）会让十几处一起红，而那些红与被测性质毫无关系。
 * 集中在这里，改规则只改一处。
 *
 * <h3>{@link #only} 为什么要在「只有一家店」时抛</h3>
 *
 * <p>它是给「单店订单」的用例用的快捷方式。跨店订单走它必然是错的——
 * 而那种错误如果静默地返回「第一家」，用例会<b>绿得毫无意义</b>
 * （它以为自己在验 A 店，实际验的是排序第一的那家）。
 * 宁可让用例红，也不要让它在验别的东西时还显示通过。
 *
 * <h3>{@link #purge} 为什么必须存在</h3>
 *
 * <p>{@code order_sub} 与 {@code refund} 都<b>没有</b>指向 {@code mall_order} 的外键
 * （V25 / V17 刻意不加，否则每个测试类都要改清理顺序）。代价是：删订单带不走它们，
 * 于是「订单清了、子订单和退款还挂着」会悄悄污染下一个用例——
 * 尤其 {@code refund} 上的 {@code uq_refund_inflight} 唯一索引，
 * 残留的一笔在途退款会让下一个用例的退款以「约束冲突」的形式红，
 * 而报错信息完全指不到真正的成因。
 */
final class SubOrders {

    private SubOrders() {
    }

    /** 这笔订单只有一家店时，它的那个子订单号；多于一家就抛——见类注释。 */
    static String only(OrderView order) {
        List<OrderView.SubView> subs = subsOf(order);
        if (subs.size() != 1) {
            throw new AssertionError("这笔订单有 " + subs.size() + " 个子订单，不能只取一个："
                    + subs.stream().map(OrderView.SubView::id).toList());
        }
        return subs.get(0).id();
    }

    /** 某一家店在这笔订单里的子订单号。 */
    static String of(OrderView order, long merchantId) {
        return subsOf(order).stream().filter(s -> s.merchantId() == merchantId)
                .map(OrderView.SubView::id).findFirst()
                .orElseThrow(() -> new AssertionError("这笔订单里没有店 " + merchantId + " 的子订单"));
    }

    static List<OrderView.SubView> subsOf(OrderView order) {
        return order.subs() == null ? List.of() : order.subs();
    }

    /** 各家店的 id，按 sub_seq 顺序。 */
    static List<Long> merchantIdsOf(OrderView order) {
        return subsOf(order).stream().map(OrderView.SubView::merchantId).toList();
    }

    /** 子订单的履约状态（直接读库，用来验「Java 之外也没有第二处真相」）。 */
    static String statusOf(JdbcClient jdbc, String subId) {
        return jdbc.sql("SELECT fulfillment_status FROM order_sub WHERE id = :id")
                .param("id", subId).query(String.class).single();
    }

    /** 子订单的某个时刻；没写过就是 null。 */
    static LocalDateTime momentOf(JdbcClient jdbc, String subId, String column) {
        return jdbc.sql("SELECT " + column + " FROM order_sub WHERE id = :id")
                .param("id", subId).query(LocalDateTime.class).optional().orElse(null);
    }

    static String shippingNoOf(JdbcClient jdbc, String subId) {
        return jdbc.sql("SELECT shipping_no FROM order_sub WHERE id = :id")
                .param("id", subId).query(String.class).optional().orElse(null);
    }

    /**
     * 清掉这些订单的子订单与退款（按订单号）。
     *
     * <p>顺序：{@code refund} 先（它挂子订单号，但无外键，先删更安全），
     * 再 {@code order_sub}。子订单本身指向 {@code merchant} 有外键，
     * 但删子订单不会动 merchant，所以不受影响。
     */
    static void purge(JdbcClient jdbc, Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(orderIds);
        jdbc.sql("DELETE FROM refund WHERE order_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM order_sub WHERE order_id IN (:ids)").param("ids", ids).update();
    }

    /** 全量清（与多数测试类 {@code @BeforeEach} 的全表清同一个口径）。 */
    static void purgeAll(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("DELETE FROM order_sub").update();
    }

    /** 这些订单还剩几条子订单 / 退款（用于「清理被删掉就会红」的反向控制断言）。 */
    static int residueFor(JdbcClient jdbc, Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT (SELECT COUNT(*) FROM order_sub WHERE order_id IN (:ids))"
                        + " + (SELECT COUNT(*) FROM refund WHERE order_id IN (:ids))")
                .param("ids", List.copyOf(orderIds)).query(Integer.class).single();
    }
}
