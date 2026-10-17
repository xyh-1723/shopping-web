package dev.learning.mall.order.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单行。
 *
 * <p>金额有两组：{@code total} 是历史列（过去由下单接口自行求和得出），
 * {@code subtotal / discount / shippingFee / payAmount} 是 V12 引入的明细。
 * 改造后 {@code total} 的语义被升级为「应付」，与 {@code payAmount} 恒等——
 * 它只为兼容既有读取方而保留，真正的语义看明细四列。
 *
 * <p>后半段是 V15 起的履约事实：{@code paidAt} 与四个履约时刻都由状态转换的
 * 完成者写入（谁把状态改走，谁负责写时刻），{@code autoReceiveAt} 在发货那一刻算好，
 * 与 {@code expiresAt} 同一个道理——之后运营改配置，不该让在路上的订单跟着跳变。
 */
record OrderRow(String id, String username, String idempotencyKey, String requestHash,
                BigDecimal total, BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee,
                BigDecimal payAmount, String status, String paymentMethod, String paymentReceipt,
                LocalDateTime paidAt, String fulfillmentStatus, LocalDateTime shippedAt,
                String shippedBy, String shippingNo, LocalDateTime receivedAt,
                LocalDateTime completedAt, LocalDateTime autoReceiveAt,
                LocalDateTime createdAt, LocalDateTime expiresAt, String closeReason,
                LocalDateTime closedAt) {}
