package dev.learning.mall;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 订单及其附属行的清理与自查（测试专用）。
 *
 * <p>与 {@link DeliveryTrail} 的关系：{@code DeliveryTrail} 只管留痕表
 * {@code notification_delivery}；这里管的是「一笔订单所带出来的一串行」——
 * {@code mall_order} 自己、{@code order_item}、{@code outbox_event}，
 * 以及挂在事件上的 {@code notification}。两者是<b>不同的键</b>（留痕行按
 * {@code order_id} 反查，而这里的主键就是订单号），所以分开成两个工具，
 * 而不是把留痕表的谓词塞进同一个类里。
 *
 * <p>为什么需要它（实测结论，见分支上的残留清单）：{@code WalletPaymentTest}
 * 只在 {@code @BeforeEach} 里清订单，{@code @AfterEach} 只清钱包与助手表，
 * 于是它跑完之后留下了最后一条用例的 1 条 {@code mall_order} +
 * 1 条 {@code order_item} + 2 条 {@code outbox_event}。
 * 眼下没有任何用例按全表计数断言这三张表，所以这些残迹不会让任何测试变红，
 * 只会等将来某条「目前有几笔订单」的断言踩上去。
 *
 * <h3>删除顺序：按外键自下而上</h3>
 *
 * <ul>
 *   <li>{@code notification.event_id} → {@code outbox_event.id}</li>
 *   <li>{@code outbox_event.aggregate_id} → {@code mall_order.id}</li>
 *   <li>{@code order_item.order_id} → {@code mall_order.id}</li>
 * </ul>
 *
 * <p>所以必须先删 {@code notification}，再删 {@code outbox_event}，最后才轮到
 * {@code order_item} 与 {@code mall_order}。顺序写反会在 H2 上撞外键约束，
 * 而且报错信息指向「被引用的那张表」，不看 DDL 很难反推出是顺序问题。
 *
 * <p><b>{@code order_sub} 与 {@code refund} 也要一起清</b>（V25 之后）：
 * 它们都刻意没有指向 {@code mall_order} 的外键，删订单带不走它们，
 * 于是「订单清了、子订单与退款还挂着」会污染下一个用例——
 * 尤其是 {@code refund} 的 {@code uq_refund_inflight}：残留的一笔在途退款
 * 会让下一个用例以「唯一索引冲突」的形式红，而报错完全指不到真正的成因。
 * 清理动作收在 {@link SubOrders#purge}。
 *
 * <p>{@code notification_delivery} 的 {@code order_id} <b>没有</b>外键（V17 刻意不加），
 * 所以它撞不动删除，但也正因为没有外键，删订单带不走它——
 * 这里顺带按订单号一起清掉，免得「订单清了、留痕还在」。
 */
final class OrderTrail {

    private OrderTrail() {
    }

    /**
     * 按外键顺序删掉这些订单及其附属行。
     *
     * <p>刻意不做全表删：项目里多数测试类的 {@code @BeforeEach} 是全表清，
     * 但 {@code @AfterEach} 按自己的数据收窄是这边的惯例——
     * 「只清本用例建的东西，别把别的测试的数据连带删掉」。
     */
    static void purge(JdbcClient jdbc, Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(orderIds);
        jdbc.sql("DELETE FROM notification WHERE event_id IN "
                        + "(SELECT id FROM outbox_event WHERE aggregate_id IN (:ids))")
                .param("ids", ids).update();
        jdbc.sql("DELETE FROM notification_delivery WHERE order_id IN (:ids)")
                .param("ids", ids).update();
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id IN (:ids)")
                .param("ids", ids).update();
        // 子订单与退款：无外键，必须显式带出来，否则会残留到下一个用例。
        SubOrders.purge(jdbc, ids);
        jdbc.sql("DELETE FROM order_item WHERE order_id IN (:ids)")
                .param("ids", ids).update();
        jdbc.sql("DELETE FROM mall_order WHERE id IN (:ids)")
                .param("ids", ids).update();
    }

    /**
     * 这些订单在「订单及其附属表」里还剩几行（三张表之和）。
     *
     * <p>在 {@code @AfterAll} 里断言它为 0，就把「清理被删掉」变成一条会红的断言：
     * 删掉 {@link #purge}（乃至整个 {@code @AfterEach}），这个数字立刻大于 0。
     * 没有它的话，删掉清理全量测试照样全绿。
     *
     * <p>只查 {@code mall_order / order_item / outbox_event} 三张：
     * {@code notification} 与 {@code notification_delivery} 由 {@link DeliveryTrail} 管，
     * 各管各的键，不要混在一句 SQL 里——混了之后「到底是哪张表没清干净」就看不出来了。
     */
    static int residueFor(JdbcClient jdbc, Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT (SELECT COUNT(*) FROM mall_order WHERE id IN (:ids))"
                        + " + (SELECT COUNT(*) FROM order_item WHERE order_id IN (:ids))"
                        + " + (SELECT COUNT(*) FROM outbox_event WHERE aggregate_id IN (:ids))")
                .param("ids", List.copyOf(orderIds))
                .query(Integer.class)
                .single();
    }
}
