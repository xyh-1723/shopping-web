package dev.learning.mall.assistant.api;

import java.time.LocalDateTime;

/** 一条对话消息。role 为 USER 或 ASSISTANT；toolData 预留给商品卡片/下单卡片 JSON。 */
public record ChatMessageView(String id, String role, String content,
                              String toolData, LocalDateTime createdAt) {}
