package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import dev.learning.mall.messaging.internal.EventRow;
import dev.learning.mall.messaging.internal.LocalSmsGateway;
import dev.learning.mall.messaging.internal.MessagingProperties;
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

/**
 * T15 外发通道：<b>缺密钥</b>这一侧的验收。
 *
 * <p>这一组用例守的是用户那句话里最要紧的一半：「先做无需密钥的本地实现……
 * 预留一个密钥位方便我后续使用」。翻译成可断言的契约：
 * <ol>
 *   <li>想走 SMS 但密钥没填 → <b>不抛异常</b>，降级到 IN_APP；</li>
 *   <li>降级必须留痕（{@code degraded=TRUE}、{@code preferred_channel='SMS'}）；</li>
 *   <li>降级后用户仍然收得到（站内信照写）——「不许把功能变死」。</li>
 * </ol>
 *
 * <p>对照物是视觉模型那次事故：缺密钥时三方库抛 {@code apiKey cannot be null or empty}，
 * 整个应用起不来。这里的形状必须相反——缺密钥是<b>可运行的降级状态</b>，不是故障。
 */
@SpringBootTest(properties = {"mall.messaging.default-channel=SMS"})
@ActiveProfiles("test")
class NotificationMissingKeyTest {

    private static final String USER = "t15-nokey";
    private static final String ORDER = "t15-nokey-order";
    private static final String CONTENT = "订单已发货，运单号 SF123456";

    @Autowired JdbcClient jdbc;
    @Autowired NotificationSink sink;
    @Autowired OutboxRepository outbox;
    @MockitoSpyBean SmsGateway gateway;

    @BeforeEach
    void setUp() {
        reset(gateway);
        // 夹具订单：只为满足 outbox_event.aggregate_id → mall_order.id 的外键。
        jdbc.sql("INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status) "
                        + "VALUES(:id,'t15-fixture','t15-nokey-key','t15-fixture-hash',1.00,'PENDING')")
                .param("id", ORDER).update();
    }

    @AfterEach
    void cleanUp() {
        // 顺序按外键来：notification → outbox_event → mall_order。
        jdbc.sql("DELETE FROM notification WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM notification_delivery WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id = :o").param("o", ORDER).update();
        jdbc.sql("DELETE FROM mall_order WHERE id = :o").param("o", ORDER).update();
    }

    @Test
    @DisplayName("缺密钥时降级到站内信：不抛异常、留痕记 degraded=TRUE、用户仍然收得到")
    void missingKeysDegradeInsteadOfFailing() {
        assertThat(gateway.available()).as("密钥留空 = 通道不可用，但这是状态不是异常").isFalse();

        // 「不许把功能变死」：这条调用必须安静地成功。抛任何异常都会让事件滚进重试与死信。
        assertThatCode(() -> sink.deliver(event("e-nokey-1"))).doesNotThrowAnyException();

        assertThat(columnOf("e-nokey-1", "channel")).isEqualTo("IN_APP");
        // 「原本想走的通道」必须写 SMS —— 写成 IN_APP 就等于把「这次降级」从审计里抹掉了。
        assertThat(columnOf("e-nokey-1", "preferred_channel")).isEqualTo("SMS");
        assertThat(degradedOf("e-nokey-1")).isTrue();
        assertThat(notificationCount("e-nokey-1")).as("降级后用户仍然收得到消息").isEqualTo(1);

        verify(gateway, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("降级也会被留痕：重试三次仍然只有一条痕，且降级标记没被冲掉")
    void degradedTrailIsIdempotentToo() {
        EventRow retried = event("e-nokey-2");
        sink.deliver(retried);
        sink.deliver(retried);

        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(columnOf("e-nokey-2", "preferred_channel")).isEqualTo("SMS");
        assertThat(degradedOf("e-nokey-2")).isTrue();
    }

    /**
     * 「可用 / 不可用」的判据矩阵。
     *
     * <p>为什么单独测它：降级行为的<b>唯一</b>输入就是这一个布尔。
     * 判据写宽了（比如只看 {@code enabled}），缺密钥时会去调一个必被签名校验拒的通道；
     * 判据写严了（比如要求 {@code base-url} 也非空），密钥填齐了也走不通。
     * 四种组合各断言一次，矩阵就没有歧义。
     */
    @Test
    @DisplayName("可用判据：总开关关掉、密钥只填一半 → 都按不可用处理（宁可降级，不要每次调用都被拒）")
    void availabilityMatrix() {
        assertThat(new LocalSmsGateway(sms(true, "", "")).available()).isFalse();
        assertThat(new LocalSmsGateway(sms(true, "key", "")).available()).isFalse();
        assertThat(new LocalSmsGateway(sms(true, "", "secret")).available()).isFalse();
        assertThat(new LocalSmsGateway(sms(true, "key", "secret")).available()).isTrue();
        assertThat(new LocalSmsGateway(sms(false, "key", "secret")).available())
                .as("总开关是独立的第二道闸：密钥填着也能一键关掉").isFalse();
    }

    // ——————————————————— 工具 ———————————————————

    /** 造一条真实的 outbox 事件行（走生产 writer），再包成 {@link EventRow} 交给 sink。 */
    private EventRow event(String id) {
        outbox.insert(id, ORDER, USER, "ORDER_SHIPPED", CONTENT);
        return new EventRow(id, ORDER, USER, "ORDER_SHIPPED", CONTENT,
                "PENDING", 0, LocalDateTime.now(), null, LocalDateTime.now(), null);
    }

    private static MessagingProperties sms(boolean enabled, String apiKey, String secret) {
        return new MessagingProperties(true, 2, 4, 32, 5, 2, false, "SMS",
                new MessagingProperties.Sms(enabled, apiKey, secret));
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
