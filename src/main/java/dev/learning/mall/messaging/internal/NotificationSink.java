package dev.learning.mall.messaging.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class NotificationSink {
    private final JdbcClient jdbc;
    private final MessagingProperties properties;
    public NotificationSink(JdbcClient jdbc, MessagingProperties properties) { this.jdbc = jdbc; this.properties = properties; }
    public void deliver(EventRow event) {
        // 教学故障开关：启动时设置 MALL_MESSAGING_SIMULATE_FAILURE=true，可观察重试及死信。
        if (properties.simulateFailure()) throw new IllegalStateException("模拟通知服务不可用");
        // 只产生站内通知；event_id 唯一键是消费端幂等的数据库兜底。
        jdbc.sql("INSERT INTO notification(event_id,username,content) SELECT :id,:user,:content WHERE NOT EXISTS (SELECT 1 FROM notification WHERE event_id=:id)")
                .param("id", event.id()).param("user", event.username()).param("content", event.content()).update();
    }
}
