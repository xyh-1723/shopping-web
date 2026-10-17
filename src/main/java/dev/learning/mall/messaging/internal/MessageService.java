package dev.learning.mall.messaging.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.messaging.api.MessageDashboard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class MessageService {
    private final OutboxRepository outbox;
    private final JdbcClient jdbc;
    private final ThreadPoolTaskExecutor executor;
    public MessageService(OutboxRepository outbox, JdbcClient jdbc, @Qualifier("messageExecutor") ThreadPoolTaskExecutor executor) {
        this.outbox = outbox; this.jdbc = jdbc; this.executor = executor;
    }
    public MessageDashboard dashboard(String user) {
        var notices = jdbc.sql("SELECT event_id,content,created_at FROM notification WHERE username=:user ORDER BY created_at DESC,event_id LIMIT 50")
                .param("user", user).query(MessageDashboard.Notice.class).list();
        var events = outbox.list(user).stream().map(row -> new MessageDashboard.Event(row.id(), row.aggregateId(),
                row.eventType(), row.content(), row.status(), row.attempts(), row.nextAttemptAt(), row.lastError(), row.createdAt(), row.deliveredAt())).toList();
        return new MessageDashboard(events, notices, new MessageDashboard.Pool(executor.getPoolSize(), executor.getActiveCount(), executor.getQueueSize(), executor.getMaxPoolSize()));
    }
    public void retry(String id, String user) {
        // 状态和所有者的校验合并在一个原子 UPDATE，重复点击不会覆盖正在进行的消费。
        if (outbox.retryDead(id, user) != 1)
            throw BusinessException.conflict("NOT_RETRYABLE", "仅可重新投递你自己的死信消息");
    }
}
