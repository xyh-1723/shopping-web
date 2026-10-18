package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;

import dev.learning.mall.messaging.internal.MessageProcessor;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * T15 收尾：<b>各生命周期事件都要有留痕</b>。
 *
 * <p>留痕的核心契约是「降级要留痕」，而这条契约只对<b>真的经过 {@code NotificationSink}
 * 的事件</b>成立：某个事件类型若绕开 sink 直接写 {@code notification} 表，它就不会留痕，
 * 而用户照样收得到通知——于是「没留痕」这件事在功能上完全不可见，只有这类用例能抓住。
 *
 * <p>因此这里每一类事件都从<b>真实业务入口</b>触发（真实支付 / 真实
 * {@link FulfillmentService} / 真实 {@link RefundService}），再经
 * {@code outbox_event → MessageProcessor → NotificationSink} 消费，
 * 最后断言 {@code notification_delivery} 里多了一行。
 * 直接调 {@code sink.deliver} 的写法在这里是<b>不够</b>的：它绕过了「这个事件到底有没有
 * 被发布出来」这一整段，注回「某类事件不发事件、直接写表」的 bug 也不会红。
 *
 * <p>覆盖的四类事件（事件类型名以代码为准，不是照抄文档）：
 * {@code ORDER_SHIPPED}（发货）、{@code ORDER_RECEIVED}（收货）、
 * {@code ORDER_REFUNDED}（退款放款）、{@code REFUND_REQUESTED}（已发货的退款申请，商家确认路由）。
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationLifecycleTrailTest {

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired MessageProcessor processor;
    @Autowired JdbcClient jdbc;

    private final List<String> orderIds = new ArrayList<>();
    private final Set<String> users = new LinkedHashSet<>();
    private int stockBefore;

    @BeforeEach
    void setUp() {
        stockBefore = jdbc.sql("SELECT stock FROM product WHERE id = 1").query(Integer.class).single();
    }

    @AfterEach
    void cleanUp() {
        // 顺序按外键来：notification → outbox_event → order_item → mall_order。
        for (String order : orderIds) {
            jdbc.sql("DELETE FROM notification WHERE event_id IN "
                            + "(SELECT id FROM outbox_event WHERE aggregate_id = :o)")
                    .param("o", order).update();
            jdbc.sql("DELETE FROM notification_delivery WHERE order_id = :o").param("o", order).update();
            jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id = :o").param("o", order).update();
            jdbc.sql("DELETE FROM refund WHERE order_id = :o").param("o", order).update();
            jdbc.sql("DELETE FROM order_item WHERE order_id = :o").param("o", order).update();
            jdbc.sql("DELETE FROM mall_order WHERE id = :o").param("o", order).update();
        }
        for (String user : users) {
            WalletProbe.purge(jdbc, user);
        }
        jdbc.sql("UPDATE product SET stock = :s WHERE id = 1").param("s", stockBefore).update();
        orderIds.clear();
        users.clear();
    }

    // ——————————————————— N1：发货 ———————————————————

    @Test
    @DisplayName("发货：真实 FulfillmentService.ship → ORDER_SHIPPED 留痕一行（channel=IN_APP）")
    void shippedEventLeavesATrail() {
        String user = user("ship");
        OrderView paid = payWithWallet(user);
        fulfillment.ship(paid.id(), "admin", "SF-T15-LIFE");

        consume(paid.id(), "ORDER_SHIPPED");

        assertThat(trailOf(paid.id(), "ORDER_SHIPPED")).isEqualTo(1);
        assertThat(columnOf(paid.id(), "ORDER_SHIPPED", "channel")).isEqualTo("IN_APP");
        assertThat(columnOf(paid.id(), "ORDER_SHIPPED", "username")).isEqualTo(user);
    }

    // ——————————————————— N1：收货 ———————————————————

    @Test
    @DisplayName("收货：真实 FulfillmentService.receive → ORDER_RECEIVED 留痕一行")
    void receivedEventLeavesATrail() {
        String user = user("recv");
        OrderView paid = payWithWallet(user);
        fulfillment.ship(paid.id(), "admin", "SF-T15-RECV");
        fulfillment.receive(paid.id(), user);

        consume(paid.id(), "ORDER_RECEIVED");

        assertThat(trailOf(paid.id(), "ORDER_RECEIVED")).isEqualTo(1);
    }

    // ——————————————————— N2：退款放款 ———————————————————

    @Test
    @DisplayName("退款：真实 RefundService.apply（未发货 → AUTO 自动放款）→ ORDER_REFUNDED 留痕一行")
    void refundedEventLeavesATrail() {
        String user = user("refund");
        OrderView paid = payWithWallet(user);

        RefundView refund = refunds.apply(paid.id(), user, null, "NO_REASON", null);
        // 先确认走的是「自动放款」这条路：只有它才在同一笔里直接发 ORDER_REFUNDED。
        // 若将来分流规则变了（变成要商家批），这条会红而不是悄悄变成「没有留痕」。
        assertThat(refund.route()).isEqualTo(RefundView.ROUTE_AUTO);

        consume(paid.id(), "ORDER_REFUNDED");

        assertThat(trailOf(paid.id(), "ORDER_REFUNDED")).isEqualTo(1);
    }

    // ——————————————————— N2：已发货的退款申请 ———————————————————

    /**
     * 已发货的退款走商家确认路由，于是会先发一条 {@code REFUND_REQUESTED}
     * （AUTO 那条路径不发它，直接放款）。两类退款事件是两条不同的路径，各自都要有痕。
     */
    @Test
    @DisplayName("已发货的退款申请（商家确认路由）→ REFUND_REQUESTED 留痕一行")
    void refundRequestedEventLeavesATrail() {
        String user = user("request");
        OrderView paid = payWithWallet(user);
        fulfillment.ship(paid.id(), "admin", "SF-T15-REQ");

        RefundView refund = refunds.apply(paid.id(), user, null, "NO_REASON", null);
        assertThat(refund.route()).as("货已出门就必须等商家批，于是才有 REFUND_REQUESTED 这条事件")
                .isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);

        consume(paid.id(), "REFUND_REQUESTED");

        assertThat(trailOf(paid.id(), "REFUND_REQUESTED")).isEqualTo(1);
    }

    // ——————————————————— 工具 ———————————————————

    private static String user(String suffix) {
        return "t15-life-" + suffix;
    }

    /** 真实下单 + 真实支付：事件只能由真实入口发出，直接插 outbox 证明不了「这个事件被发布了」。 */
    private OrderView payWithWallet(String username) {
        users.add(username);
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        orderIds.add(order.id());
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    /**
     * 消费某一类事件：把重试时刻拨到过去，再走真实 {@link MessageProcessor}。
     *
     * <p>拨时刻的原因与 {@code MallIntegrationTest#dueNow} 相同：
     * {@code MessageProcessor} 用的是 JVM 时钟，而 H2 的 {@code CURRENT_TIMESTAMP}
     * 常比它快，不拨的话「刚发布就消费」会被整条跳过（时红时绿）。
     */
    private void consume(String orderId, String eventType) {
        String eventId = jdbc.sql("SELECT id FROM outbox_event WHERE aggregate_id = :o AND event_type = :t")
                .param("o", orderId).param("t", eventType).query(String.class).single();
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = :t WHERE id = :id")
                .param("t", LocalDateTime.now().minusSeconds(1)).param("id", eventId).update();
        processor.process(eventId);
    }

    private int trailOf(String orderId, String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE order_id = :o AND event_type = :t")
                .param("o", orderId).param("t", eventType).query(Integer.class).single();
    }

    private String columnOf(String orderId, String eventType, String column) {
        return jdbc.sql("SELECT " + column + " FROM notification_delivery "
                        + "WHERE order_id = :o AND event_type = :t")
                .param("o", orderId).param("t", eventType).query(String.class).single();
    }
}
