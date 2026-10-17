package dev.learning.mall.assistant.api;

import java.time.LocalDateTime;

/** 会话列表项：含消息条数，用于侧边栏预览。 */
public record ConversationView(String id, String title, LocalDateTime createdAt,
                               LocalDateTime updatedAt, int messageCount) {}
