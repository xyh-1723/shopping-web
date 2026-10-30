package dev.learning.mall.order.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单行（父单）。
 *
 * <p>金额有两组：{@code total} 是历史列（过去由下单接口自行求和得出），
 * {@code subtotal / discount / shippingFee / payAmount} 是 V12 引入的明细。
 * 改造后 {@code total} 的语义被升级为「应付」，与 {@code payAmount} 恒等——
 * 它只为兼容既有读取方而保留，真正的语义看明细四列。
 *
 * <p><b>V25 之后这一行只回答「钱」的问题</b>：{@code status}（收没收到钱）、
 * {@code paidAt}（什么时候收到的）、{@code expiresAt}（还有多久作废）。
 * 发货 / 收货 / 完成三个时刻与 {@code fulfillment_status} 已下移到子订单
 * {@link OrderSubRow}——父单没有「发货」这个概念，因为没有任何一家店叫「这个订单」。
 */
record OrderRow(String id, String username, String idempotencyKey, String requestHash,
                BigDecimal total, BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee,
                BigDecimal payAmount, BigDecimal couponDiscount, String couponCode,
                String status, String paymentMethod, String paymentReceipt,
                LocalDateTime paidAt,
                LocalDateTime createdAt, LocalDateTime expiresAt, String closeReason,
                LocalDateTime closedAt) {}
