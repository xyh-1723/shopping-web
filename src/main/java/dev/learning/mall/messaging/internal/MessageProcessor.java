package dev.learning.mall.messaging.internal;

import dev.learning.mall.common.Observed;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageProcessor {
    private final OutboxRepository outbox;
    private final NotificationSink sink;
    private final MessagingProperties properties;
    public MessageProcessor(OutboxRepository outbox, NotificationSink sink, MessagingProperties properties) {
        this.outbox = outbox; this.sink = sink; this.properties = properties;
    }
    @Transactional
    @Observed("consume-event")
    public void process(String id) {
        EventRow event = outbox.lock(id).orElse(null);
        if (event == null || !event.status().equals("PENDING") || event.nextAttemptAt().isAfter(LocalDateTime.now())) return;
        // 持行锁直到提交，重复任务会等待；通知与确认同一事务，避免本地半成功。
        sink.deliver(event);
        outbox.delivered(id);
    }
    @Transactional
    public void recordFailure(String id, Exception error) {
        EventRow event = outbox.lock(id).orElse(null);
        // 另一个任务可能已成功或已安排重试；此时不要覆盖它的结果。
        if (event == null || !event.status().equals("PENDING") || event.nextAttemptAt().isAfter(LocalDateTime.now())) return;
        int attempts = event.attempts() + 1;
        long delay = Math.min(60, (long) properties.retryDelaySeconds() * (1L << Math.min(attempts - 1, 10)));
        outbox.failed(id, attempts, attempts >= properties.maxAttempts(), LocalDateTime.now().plusSeconds(delay), error.getClass().getSimpleName());
    }
}
