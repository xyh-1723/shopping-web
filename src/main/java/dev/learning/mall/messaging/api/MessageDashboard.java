package dev.learning.mall.messaging.api;

import java.time.LocalDateTime;
import java.util.List;

/** Web 返回模型不暴露消息模块的内部数据库映射对象。 */
public record MessageDashboard(List<Event> events, List<Notice> notifications, Pool pool) {
    public record Event(String id, String aggregateId, String eventType, String content, String status,
                        int attempts, LocalDateTime nextAttemptAt, String lastError,
                        LocalDateTime createdAt, LocalDateTime deliveredAt) {}
    public record Notice(String eventId, String content, LocalDateTime createdAt) {}
    public record Pool(int size, int active, int queued, int maximum) {}
}
