package dev.learning.mall.messaging.internal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class OutboxDispatcher {
    private final OutboxRepository repository;
    private final MessageWorker worker;
    private final ThreadPoolTaskExecutor executor;
    private final MessagingProperties properties;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    public OutboxDispatcher(OutboxRepository repository, MessageWorker worker,
                            @Qualifier("messageExecutor") ThreadPoolTaskExecutor executor, MessagingProperties properties) {
        this.repository = repository; this.worker = worker; this.executor = executor; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${mall.messaging.poll-delay-ms:1000}", initialDelayString = "${mall.messaging.initial-delay-ms:1000}")
    public void dispatch() {
        if (!properties.enabled()) return;
        for (String id : repository.readyIds()) {
            if (!inFlight.add(id)) continue;
            try {
                executor.execute(() -> {
                    try { worker.handle(id); }
                    finally { inFlight.remove(id); }
                });
            } catch (TaskRejectedException full) {
                inFlight.remove(id);
                // 尚未修改数据库状态，没有丢消息；让下轮扫描再尝试。
                break;
            }
        }
    }
}
