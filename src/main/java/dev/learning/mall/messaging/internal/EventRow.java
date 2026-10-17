package dev.learning.mall.messaging.internal;

import java.time.LocalDateTime;

public record EventRow(String id, String aggregateId, String username, String eventType, String content,
                       String status, int attempts, LocalDateTime nextAttemptAt, String lastError,
                       LocalDateTime createdAt, LocalDateTime deliveredAt) {}
