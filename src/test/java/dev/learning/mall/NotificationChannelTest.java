package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;

import dev.learning.mall.messaging.internal.EventRow;
import dev.learning.mall.messaging.internal.MessageProcessor;
import dev.learning.mall.messaging.internal.MessagingProperties;
import dev.learning.mall.messaging.internal.NotificationChannel;
import dev.learning.mall.messaging.internal.NotificationSink;
import dev.learning.mall.messaging.internal.OutboxRepository;
import dev.learning.mall.messaging.internal.SmsGateway;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * T15 外发通道：<b>默认（IN_APP）</b>这一侧的验收。
 *
 * <p>本文件用<b>生产默认配置</b>跑（不覆盖 {@code mall.messaging.default-channel}），
 * 所以它同时守住了「什么都不配 = 站内信照常工作」这条底线——
 * 通道抽象上线之后，既有行为不许变。
 *
 * <p>两条关于「怎么造场景」的诚实说明，避免这里的绿被误读成比实际更强：
 * <ul>
 *   <li>EMAIL 与非法通道名两个用例（{@link #emailDegradesToInAppAndRecordsTheIntent()}、
 *       {@link #unknownChannelFallsBackToInAppWithoutClaimingDegradation()}）是
 *       <b>手工构造</b> {@link MessagingProperties} 后 {@code new NotificationSink(...)} 跑的。
 *       它们覆盖的是「从 {@code properties.defaultChannel()} 往后」的全部逻辑，
 *       唯独<b>没有</b>覆盖 Spring 把 {@code mall.messaging.default-channel=EMAIL} 绑成
 *       {@code "EMAIL"} 这一步——那一步由 {@link NotificationSmsChannelTest} 用真实
 *       {@code default-channel=SMS} 证明（同一条绑定路径，值不同而已）。
 *       不为两个额外取值各开一个 Spring 上下文，是刻意的：一个上下文约 2 秒，
 *       而它们要证明的分支与已覆盖的分支是同一处代码。</li>
 *   <li>订单行是<b>夹具</b>（直接插一行 {@code mall_order}），不是被测对象——
 *       {@code notification.event_id} 与 {@code outbox_event.aggregate_id} 都有外键，
 *       造不出凭空的事件行。真实下单路径由 {@link #realOrderEventLeavesAnAuditTrail()} 单独覆盖。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationChannelTest {

    private static final String USER = "t15-channel";
    private static final String ORDER = "t15-channel-order";
    private static final String CONTENT = "订单已发货，运单号 SF123456";

    @Autowired JdbcClient jdbc;
    @Autowired NotificationSink sink;
    @Autowired SmsGateway gateway;
    @Autowired MessageProcessor processor;
    @Autowired OutboxRepository outbox;
    @Autowired OrderFacade facade;
    @Autowired Environment env;

    private String createdOrderId;
    private int stockBefore;

    @BeforeEach
    void setUp() {
        stockBefore = jdbc.sql("SELECT stock FROM product WHERE id = 1").query(Integer.class).single();
        // 夹具订单：只为满足 outbox_event.aggregate_id → mall_order.id 的外键。
        jdbc.sql("INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status) "
                        + "VALUES(:id,'t15-fixture','t15-fixture-key','t15-fixture-hash',1.00,'PENDING')")
                .param("id", ORDER).update();
    }

    @AfterEach
    void cleanUp() {
        // 顺序按外键来：notification → outbox_event → mall_order。
        jdbc.sql("DELETE FROM notification WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM notification_delivery WHERE username = :u").param("u", USER).update();
        if (createdOrderId != null) {
            jdbc.sql("DELETE FROM notification WHERE event_id IN (SELECT id FROM outbox_event WHERE aggregate_id = :o)")
                    .param("o", createdOrderId).update();
            jdbc.sql("DELETE FROM notification_delivery WHERE order_id = :o").param("o", createdOrderId).update();
            jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id = :o").param("o", createdOrderId).update();
            jdbc.sql("DELETE FROM order_item WHERE order_id = :o").param("o", createdOrderId).update();
            jdbc.sql("DELETE FROM mall_order WHERE id = :o").param("o", createdOrderId).update();
            createdOrderId = null;
        }
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id = :o").param("o", ORDER).update();
        jdbc.sql("DELETE FROM mall_order WHERE id = :o").param("o", ORDER).update();
        // 下单会扣库存：把库存还原到本条用例开始前，不把副作用留给下一个测试类。
        jdbc.sql("UPDATE product SET stock = :s WHERE id = 1").param("s", stockBefore).update();
    }

    // ——————————————————— 默认通道 ———————————————————

    @Test
    @DisplayName("默认通道是 IN_APP：站内信照写，并留一条不降级的痕")
    void defaultChannelIsInApp() {
        sink.deliver(event("e-t15-default"));

        assertThat(notificationCount("e-t15-default")).as("既有行为不许变：站内信照样写").isEqualTo(1);
        assertThat(columnOf("e-t15-default", "channel")).isEqualTo("IN_APP");
        assertThat(columnOf("e-t15-default", "preferred_channel")).isEqualTo("IN_APP");
        assertThat(degradedOf("e-t15-default")).isFalse();
        assertThat(columnOf("e-t15-default", "event_type")).isEqualTo("ORDER_SHIPPED");
        assertThat(columnOf("e-t15-default", "order_id")).isEqualTo(ORDER);
        assertThat(columnOf("e-t15-default", "content")).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("密钥位真的存在且当前为空：是「有这一位、没填」，不是「压根没这一位」")
    void smsKeySlotsAreDeclaredButEmpty() {
        // getProperty 返回非 null 才证明 yml 里声明了它；返回空串才证明「没填」。
        assertThat(env.getProperty("mall.messaging.sms.api-key")).isNotNull().isEmpty();
        assertThat(env.getProperty("mall.messaging.sms.secret")).isNotNull().isEmpty();

        // 于是短信通道当前不可用 —— 但「不可用」的表现是降级，不是启动失败。
        assertThat(gateway.available()).isFalse();
        assertThat(sink).as("Sink 照常装配：缺密钥不影响上下文起来").isNotNull();
    }

    // ——————————————————— 长度：不许出现半成功 ———————————————————

    /**
     * 这个用例守的是 V19 修掉的那个隐患在<b>代码路径上</b>的后果。
     *
     * <p>{@code deliver} 是「先写站内信、再写留痕」两步。V19 之前
     * {@code notification_delivery.content} 只有 400 而 {@code notification.content} 有 500，
     * 于是文案一过 400 就变成「站内信写成功、留痕写失败」——用户收到通知但投递没有记录，
     * 而它不红也不报错：异常被 outbox 的重试吸收，滚几轮之后变成死信，
     * 症状出现在别处（「这条通知怎么没有留痕」）。
     * 库那一侧的长度不变量由 {@link V19MigrationTest} 守着，这里守的是两步的顺序后果。
     */
    @Test
    @DisplayName("500 字符的文案不会出现「站内信写成功、留痕写失败」的半成功")
    void longContentDoesNotHalfSucceed() {
        String content = "z".repeat(500);
        sink.deliver(eventRow("e-t15-long", content));

        assertThat(notificationCount("e-t15-long")).as("第一步：站内信写成功").isEqualTo(1);
        assertThat(deliveryCount()).as("第二步：留痕也必须写成功，不能停在半成功").isEqualTo(1);
        // 两侧都要断言「正文是完整的」：只断言留痕那侧的话，
        // 「把站内信的正文悄悄截断」这种写法会全绿——变异测试里它确实绿过一次（N7）。
        assertThat(notificationContentOf("e-t15-long")).as("站内信的正文不许被截断").hasSize(500);
        assertThat(columnOf("e-t15-long", "content")).as("留痕的正文不许被截断").hasSize(500);
    }

    // ——————————————————— 幂等：重试不许重复留痕 ———————————————————

    /**
     * 这是 {@code notification_delivery} 没有唯一键带来的坑：
     * {@code OutboxDispatcher} 重试会重复调用 {@code deliver}，不兜的话一次重试多一条痕，
     * 「这个事件投递过几次」立刻不可信——而留痕存在的意义就是这个数字。
     */
    @Test
    @DisplayName("同一事件被重复投递三次，留痕仍然只有一条（主键由 event_id 派生）")
    void retryDoesNotDuplicateTheAuditTrail() {
        // 同一个 EventRow 投三次：这正是 OutboxDispatcher 重试的形状（同一行、同一 id）。
        EventRow retried = event("e-t15-retry");
        sink.deliver(retried);
        sink.deliver(retried);
        sink.deliver(retried);

        assertThat(deliveryCount()).as("留痕行数 = 事件数 × 1，而不是 × 3").isEqualTo(1);
        assertThat(notificationCount("e-t15-retry")).as("站内信同样幂等").isEqualTo(1);
    }

    @Test
    @DisplayName("不同事件各自留一条痕：幂等键没把别的事件吃掉")
    void distinctEventsEachLeaveTheirOwnTrail() {
        sink.deliver(event("e-t15-a"));
        sink.deliver(event("e-t15-b"));

        assertThat(deliveryCount()).isEqualTo(2);
    }

    // ——————————————————— 端到端：V17 的预留表真的有 writer 了 ———————————————————

    /**
     * V17 的迁移注释写着「写入它的通道抽象属于 T15……这是有意为之的接缝，不是死表」。
     * 这条用例就是那条接缝的接通电报：从真实的<b>下单</b>入口出发，
     * 经 {@code outbox_event} → {@code MessageProcessor} → {@code NotificationSink}，
     * 断言 {@code notification_delivery} 里真的多了一行。
     */
    @Test
    @DisplayName("真实下单产生的事件经 MessageProcessor 消费后留痕：预留表不再是死表")
    void realOrderEventLeavesAnAuditTrail() {
        OrderView order = facade.create("student", UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        createdOrderId = order.id();
        String eventId = jdbc.sql("SELECT id FROM outbox_event WHERE aggregate_id = :o")
                .param("o", order.id()).query(String.class).single();
        // 把重试时间拨到过去：MessageProcessor 用的是 JVM 时钟，否则「刚建就好」可能被整条跳过。
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = :t WHERE id = :id")
                .param("t", LocalDateTime.now().minusSeconds(1)).param("id", eventId).update();

        processor.process(eventId);

        assertThat(columnOf(eventId, "channel")).isEqualTo("IN_APP");
        assertThat(columnOf(eventId, "event_type")).isEqualTo("ORDER_CREATED");
        assertThat(columnOf(eventId, "order_id")).isEqualTo(order.id());
        assertThat(columnOf(eventId, "username")).isEqualTo("student");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE id = :id")
                .param("id", NotificationSink.auditId(eventId)).query(Integer.class).single())
                .as("留痕行的主键确实由 event_id 派生：换一种查法也能查到它").isEqualTo(1);
    }

    // ——————————————————— 通道枚举与「认不出来」的兜底 ———————————————————

    /** 哨兵：枚举集合与 {@code ck_delivery_channel} 的 CHECK 集合必须一致，多一个少一个都会被数据库拒。 */
    @Test
    @DisplayName("哨兵：通道枚举 = CHECK 集合，且垃圾值解析为 null（而不是抛异常）")
    void channelEnumMatchesTheCheckConstraint() {
        assertThat(NotificationChannel.values()).containsExactlyInAnyOrder(
                NotificationChannel.IN_APP, NotificationChannel.SMS, NotificationChannel.EMAIL);
        assertThat(NotificationChannel.parse("sms")).isEqualTo(NotificationChannel.SMS);
        assertThat(NotificationChannel.parse(" in_app ")).isEqualTo(NotificationChannel.IN_APP);
        assertThat(NotificationChannel.parse("WHATSAPP")).isNull();
        assertThat(NotificationChannel.parse(null)).isNull();
    }

    @Test
    @DisplayName("配了 EMAIL（本阶段不实现）→ 降级到 IN_APP，preferred 记 EMAIL、degraded=true")
    void emailDegradesToInAppAndRecordsTheIntent() {
        new NotificationSink(jdbc, propertiesWith("EMAIL"), gateway).deliver(event("e-t15-email"));

        assertThat(columnOf("e-t15-email", "channel")).isEqualTo("IN_APP");
        // 「原本想走哪条」必须写原本想走的那条：写成 IN_APP 就等于把降级这件事抹掉了。
        assertThat(columnOf("e-t15-email", "preferred_channel")).isEqualTo("EMAIL");
        assertThat(degradedOf("e-t15-email")).isTrue();
        assertThat(notificationCount("e-t15-email")).as("降级也一定要让用户收得到").isEqualTo(1);
    }

    @Test
    @DisplayName("配了 CHECK 之外的通道名 → 兜到 IN_APP，且不谎报降级（preferred 不许写出非法值）")
    void unknownChannelFallsBackToInAppWithoutClaimingDegradation() {
        new NotificationSink(jdbc, propertiesWith("WHATSAPP"), gateway).deliver(event("e-t15-weird"));

        assertThat(columnOf("e-t15-weird", "channel")).isEqualTo("IN_APP");
        // 非法值不能落进 preferred_channel：ck_delivery_preferred 会拒，那会把投递变成抛异常。
        assertThat(columnOf("e-t15-weird", "preferred_channel")).isEqualTo("IN_APP");
        assertThat(degradedOf("e-t15-weird")).as("没说过要降级，就不许标降级").isFalse();
        assertThat(notificationCount("e-t15-weird")).isEqualTo(1);
    }

    // ——————————————————— 工具 ———————————————————

    /** 造一条真实的 outbox 事件行（走生产 writer），再包成 {@link EventRow} 交给 sink。 */
    private EventRow event(String id) {
        return eventRow(id, CONTENT);
    }

    /** 同上，但正文可指定（长文案用例要用 500 字符）。 */
    private EventRow eventRow(String id, String content) {
        outbox.insert(id, ORDER, USER, "ORDER_SHIPPED", content);
        return new EventRow(id, ORDER, USER, "ORDER_SHIPPED", content,
                "PENDING", 0, LocalDateTime.now(), null, LocalDateTime.now(), null);
    }

    /** 手工构造配置（说明见类注释）：只换默认通道，其余与生产默认一致。 */
    private static MessagingProperties propertiesWith(String defaultChannel) {
        return new MessagingProperties(true, 2, 4, 32, 5, 2, false, defaultChannel,
                new MessagingProperties.Sms(true, "", ""));
    }

    /** 取留痕行的某一列；主键由 event_id 派生，所以按 event_id 反查。 */
    private String columnOf(String eventId, String column) {
        return jdbc.sql("SELECT " + column + " FROM notification_delivery WHERE id = :id")
                .param("id", NotificationSink.auditId(eventId)).query(String.class).single();
    }

    private boolean degradedOf(String eventId) {
        return Boolean.parseBoolean(columnOf(eventId, "degraded"));
    }

    private int deliveryCount() {
        return jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE username = :u")
                .param("u", USER).query(Integer.class).single();
    }

    private int notificationCount(String eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM notification WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single();
    }

    private String notificationContentOf(String eventId) {
        return jdbc.sql("SELECT content FROM notification WHERE event_id = :id")
                .param("id", eventId).query(String.class).single();
    }
}
