package dev.learning.mall.assistant.api;

import java.time.LocalDateTime;
import java.util.List;

/** 打开某个会话时的完整视图：元信息 + 全部消息。 */
public record ConversationDetail(String id, String title, LocalDateTime createdAt,
                                 List<ChatMessageView> messages) {}
