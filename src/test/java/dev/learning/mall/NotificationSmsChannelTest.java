package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.learning.mall.messaging.internal.EventRow;
import dev.learning.mall.messaging.internal.NotificationSink;
import dev.learning.mall.messaging.internal.OutboxRepository;
import dev.learning.mall.messaging.internal.SmsGateway;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T15 外发通道：<b>短信可用</b>这一侧的验收（密钥齐备时）。
 *
 * <p>这里刻意用真实配置打开 SMS：{@code default-channel=SMS} + {@code api-key/secret}，
 * 于是「Spring 把 {@code mall.messaging.default-channel} 绑对了」这件事
 * 是被这条用例证明的，而不是靠手工构造 {@code MessagingProperties} 绕过去的。
 *
 * <p>{@link SmsGateway} 用 spy：不是为了造假，而是为了<b>数调用次数</b>——
 * 见 {@link #rolledBackAuditTrailCannotHideTheOutboundCall()} 的说明。
 */
@SpringBootTest(properties = {
        "mall.messaging.default-channel=SMS",
        "mall.messaging.sms.api-key=t15-test-key",
        "mall.messaging.sms.secret=t15-test-secret"
})
@ActiveProfiles("test")
class NotificationSmsChannelTest {

    private static final String USER = "t15-sms";
    private static final String ORDER = "t15-sms-order";
    private static final String CONTENT = "订单已发货，运单号 SF123456";

    @Autowired JdbcClient jdbc;
    @Autowired NotificationSink sink;
    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean SmsGateway gateway;

    @BeforeEach
    void setUp() {
        // spy 在同一上下文里跨用例复用；不 reset 的话上一条用例的调用会被数进去。
        reset(gateway);
        // 夹具订单：只为满足 outbox_event.aggregate_id → mall_order.id 的外键。
        jdbc.sql("INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status) "
                        + "VALUES(:id,'t15-fixture','t15-sms-key','t15-fixture-hash',1.00,'PENDING')")
                .param("id", ORDER).update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM notification_delivery WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM notification WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id = :o").param("o", ORDER).update();
        jdbc.sql("DELETE FROM mall_order WHERE id = :o").param("o", ORDER).update();
    }

    @Test
    @DisplayName("密钥齐备时真的走 SMS：留痕 channel=SMS、不降级，且不再写一条站内信")
    void smsIsUsedWhenKeysArePresent() {
        assertThat(gateway.available()).as("配置里的密钥被绑定进来了（这是 default-channel 绑定路径的哨兵）").isTrue();

        sink.deliver(event("e-sms-1"));

        assertThat(columnOf("e-sms-1", "channel")).isEqualTo("SMS");
        assertThat(columnOf("e-sms-1", "preferred_channel")).isEqualTo("SMS");
        assertThat(degradedOf("e-sms-1")).isFalse();
        assertThat(notificationCount("e-sms-1"))
                .as("短信已经送到了，再写一条站内信是重复打扰").isZero();
        verify(gateway, times(1)).send(USER, CONTENT);
    }

    /**
     * <b>为什么必须断言调用次数而不是只查表：</b>{@code send} 是事务之外的副作用。
     *
     * <p>留痕行写在事务里，事务回滚它就没了；但「短信已经发出去」这个事实回滚不掉。
     * 只查 {@code notification_delivery} 会得到「什么都没发生过」的结论——
     * 而真相是「发过了，但没留痕」。这类状态只有调用次数抓得住。
     */
    @Test
    @DisplayName("事务回滚抹掉了留痕，但抹不掉已发生的短信调用：只查表会漏判")
    void rolledBackAuditTrailCannotHideTheOutboundCall() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            sink.deliver(event("e-sms-2"));
            throw new IllegalStateException("投递之后炸了");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(deliveryCount()).as("留痕在事务里，跟着回滚").isZero();
        assertThat(notificationCount("e-sms-2")).isZero();

        // 真相是「发过了，但没留痕」：只查表看不见，调用次数看得见。
        verify(gateway, times(1)).send(USER, CONTENT);
    }

    // ——————————————————— 工具 ———————————————————

    /** 造一条真实的 outbox 事件行（走生产 writer），再包成 {@link EventRow} 交给 sink。 */
    private EventRow event(String id) {
        outbox.insert(id, ORDER, USER, "ORDER_SHIPPED", CONTENT);
        return new EventRow(id, ORDER, USER, "ORDER_SHIPPED", CONTENT,
                "PENDING", 0, LocalDateTime.now(), null, LocalDateTime.now(), null);
    }

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
}
