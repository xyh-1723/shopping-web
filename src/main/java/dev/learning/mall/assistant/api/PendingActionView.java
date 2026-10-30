package dev.learning.mall.assistant.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条「待确认动作」的对外视图：模型发起、人类确认，确认前不发生任何真实副作用。
 *
 * <p>{@code orderSubId} 只对 {@code ORDER_REFUND} 有意义（其余为 {@code null}）：
 * 退款的单位是子订单，卡片上写的金额就是「那一家店的那一份」，
 * 把它显示出来，用户才知道自己即将退的是哪一家。
 */
public record PendingActionView(String id, String conversationId, String kind, String orderId,
                                String orderSubId, BigDecimal amount, String method, String status,
                                LocalDateTime expiresAt) {}
