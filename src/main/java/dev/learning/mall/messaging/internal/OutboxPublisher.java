package dev.learning.mall.messaging.internal;

import dev.learning.mall.messaging.api.DomainEventPublisher;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher implements DomainEventPublisher {
    private final OutboxRepository repository;
    public OutboxPublisher(OutboxRepository repository) { this.repository = repository; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String orderId, String user, String type, String content) {
        repository.insert(UUID.randomUUID().toString(), orderId, user, type, content);
    }
}
