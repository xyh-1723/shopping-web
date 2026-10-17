package dev.learning.mall.messaging.api;

public interface DomainEventPublisher {
    void publish(String orderId, String username, String type, String content);
}
