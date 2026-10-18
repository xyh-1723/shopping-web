package dev.learning.mall.messaging.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 通知的投递口：把一条 outbox 事件变成「用户收到的一条消息」。
 *
 * <p>T15 之前这里只会往 {@code notification} 表插一行站内信；现在它多了两件事：
 * <ol>
 *   <li><b>选通道</b>：默认通道来自 {@code mall.messaging.default-channel}（默认 IN_APP）。
 *       通道只有 {@link NotificationChannel#SMS} 需要密钥，缺密钥就降级，
 *       <b>降级不抛异常</b>——调用方永远拿得到「已投递」。</li>
 *   <li><b>写留痕</b>：每次投递都在 {@code notification_delivery} 留一行，
 *       {@code preferred_channel} 记「原本想走哪条」，{@code channel} 记「实际走了哪条」，
 *       {@code degraded} 记「两者是否不同」。本地实现也写，降级也写——
 *       这张表是 V17 专门留给 T15 的接缝，它的语义就是「降级必须留痕」。</li>
 * </ol>
 */
@Component
public class NotificationSink {

    private static final Logger log = LoggerFactory.getLogger(NotificationSink.class);

    private final JdbcClient jdbc;
    private final MessagingProperties properties;
    private final SmsGateway sms;

    public NotificationSink(JdbcClient jdbc, MessagingProperties properties, SmsGateway sms) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.sms = sms;
    }

    /**
     * 投递一条事件。
     *
     * <p>契约：<b>要么投递成功，要么抛异常让 outbox 重试</b>；「通道不可用」不算失败，
     * 它走降级而不是抛异常。
     */
    public void deliver(EventRow event) {
        // 教学故障开关：启动时设置 MALL_MESSAGING_SIMULATE_FAILURE=true，可观察重试及死信。
        if (properties.simulateFailure()) {
            throw new IllegalStateException("模拟通知服务不可用");
        }
        NotificationChannel preferred = preferredChannel();
        NotificationChannel actual = send(preferred, event);
        audit(preferred, actual, event);
    }

    /** 配置里的默认通道。认不出来的值（含空串）一律兜到 IN_APP，绝不因为拼错一个字就起不来。 */
    private NotificationChannel preferredChannel() {
        NotificationChannel parsed = NotificationChannel.parse(properties.defaultChannel());
        if (parsed == null) {
            log.warn("mall.messaging.default-channel='{}' 不是合法通道，按 IN_APP 投递", properties.defaultChannel());
            return NotificationChannel.IN_APP;
        }
        return parsed;
    }

    /**
     * 真正投递一次，返回<b>实际</b>走的通道。
     *
     * <p>只有「想走 SMS 且 SMS 可用」才会走短信；其余全部落站内信。
     * 站内信是兜底而不是并列选项：它不需要任何密钥，所以「用户收不到通知」这件事
     * 不会由配置缺失造成。
     */
    private NotificationChannel send(NotificationChannel preferred, EventRow event) {
        if (preferred == NotificationChannel.SMS && sms.available()) {
            sms.send(event.username(), event.content());
            return NotificationChannel.SMS;
        }
        if (preferred != NotificationChannel.IN_APP) {
            // 两种情形会走到这里：SMS 缺密钥（降级），或选了一个本阶段没实现的通道（EMAIL）。
            // 两者都留痕，所以这里只做「人能看见」的那一份记录。
            log.warn("通道 {} 当前不可用或未实现，降级到 IN_APP（username={}，event={}）",
                    preferred, event.username(), event.eventType());
        }
        insertInApp(event);
        return NotificationChannel.IN_APP;
    }

    /** 站内信：{@code event_id} 是主键，重试时靠它幂等。 */
    private void insertInApp(EventRow event) {
        // event_id 唯一键是消费端幂等的数据库兜底；这里再加一次 WHERE NOT EXISTS，
        // 是为了别让「重试」靠唯一键冲突来兜——冲突会冒泡成异常，把一次可重试的失败
        // 变成「通知服务抛错」的假象，进而把事件推进死信。
        jdbc.sql("INSERT INTO notification(event_id,username,content) SELECT :id,:user,:content WHERE NOT EXISTS (SELECT 1 FROM notification WHERE event_id=:id)")
                .param("id", event.id())
                .param("user", event.username())
                .param("content", event.content())
                .update();
    }

    /**
     * 写一行投递留痕：本地实现也写，降级也写。
     *
     * <p>{@code degraded} 就是 {@code preferred != actual}——不需要第二个判据，
     * 「想走的」和「实际走的」不同就是降级，没有中间状态。
     */
    private void audit(NotificationChannel preferred, NotificationChannel actual, EventRow event) {
        jdbc.sql("INSERT INTO notification_delivery(id,username,channel,preferred_channel,degraded,event_type,order_id,content) "
                        + "SELECT :id,:user,:channel,:preferred,:degraded,:type,:order,:content "
                        + "WHERE NOT EXISTS (SELECT 1 FROM notification_delivery WHERE id=:id)")
                .param("id", auditId(event.id()))
                .param("user", event.username())
                .param("channel", actual.name())
                .param("preferred", preferred.name())
                .param("degraded", preferred != actual)
                .param("type", event.eventType())
                .param("order", event.aggregateId())
                .param("content", event.content())
                .update();
    }

    /**
     * 留痕行的主键，由 {@code event_id} 派生。
     *
     * <p><b>为什么主键要派生而不是随机 UUID：</b>V17 的 {@code notification_delivery} 只有主键，
     * 既没有 {@code event_id} 列也没有唯一键；而 {@link OutboxDispatcher} 失败重试会
     * <b>重复调用</b> {@link #deliver(EventRow)}。不兜的话，一次重试就多一条留痕，
     * 「这个事件投递过几次」立刻变成不可信的数字——留痕的意义恰恰是这个数字。
     *
     * <p><b>为什么选「派生主键」而不是新开迁移加唯一索引：</b>
     * <ul>
     *   <li>V17 已落盘且 Flyway 默认禁 out-of-order，改它要开 V19；而 V19 一旦开了，
     *       「表结构」这个已被 V17MigrationTest 固定下来的契约又要跟着动一次。</li>
     *   <li>派生主键把幂等键放在<b>写入侧</b>：同一个 event_id 无论重试多少次、
     *       走哪条通道，都只能算出同一个 id，主键冲突由 {@code WHERE NOT EXISTS} 显式吞掉，
     *       不依赖数据库约束，也不依赖调用方记得判重。</li>
     * </ul>
     * 代价是「一次事件只留一条痕」：若某次投递降级后密钥补齐、同一事件再重试，
     * 留痕保留的是<b>首次</b>投递的事实（主键已存在，第二次的 INSERT 被跳过）。
     * 这是刻意的取舍——留痕回答的是「这个事件有没有投递过」，不是「最后一次投递是什么结果」；
     * 后者该看 {@code outbox_event}，不是这张表。
     *
     * <p>将来若要「按 event_id 查留痕」，正确做法是开新迁移加列 + 唯一索引，
     * 而不是在这里猜——派生成 UUID 后，单看这一行是反推不出 event_id 的。
     */
    public static String auditId(String eventId) {
        return UUID.nameUUIDFromBytes(("notification-delivery:" + eventId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
