package dev.learning.mall.assistant.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条「待确认动作」的对外视图。目前只有 ORDER_PAYMENT 一种：
 * 模型发起、人类确认，确认前不发生任何真实副作用。
 */
public record PendingActionView(String id, String conversationId, String kind, String orderId,
                                BigDecimal amount, String method, String status, LocalDateTime expiresAt) {}
