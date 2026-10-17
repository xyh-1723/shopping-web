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
 */
record OrderRow(String id, String username, String idempotencyKey, String requestHash,
                BigDecimal total, BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee,
                BigDecimal payAmount, String status, String paymentMethod, String paymentReceipt,
                LocalDateTime createdAt, LocalDateTime expiresAt, String closeReason,
                LocalDateTime closedAt) {}
