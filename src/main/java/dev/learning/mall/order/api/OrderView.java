package dev.learning.mall.order.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单的对外视图。
 *
 * <p><b>金额的四列与一列。</b>{@code subtotal / discount / shippingFee / payAmount}
 * 是 V12 之后落库的明细，满足 {@code payAmount = subtotal − discount + shippingFee}。
 * {@code total} 是历史列，语义已升级为「应付」——它与 {@code payAmount} 恒等，
 * 保留只为兼容既有读取方；新代码一律读 {@code payAmount}。
 *
 * <p>这两列由 {@code OrderRepository.insert} 用同一个值写入、写入后不可变，
 * 因此「订单金额 = 用户看到的金额」这条承诺可以被查询，也可以被断言。
 *
 * <p>{@code closeReason} 存在的意义：用户主动取消与系统超时关闭都落在 {@code CANCELLED}
 * 状态上，但两者是两件不同的事——运营口径不同（购买意愿变化 vs 支付转化漏斗）、
 * 用户文案不同、后续动作也不同（超时关闭是弃单挽回的入口，主动取消再去打扰就是骚扰）。
 * 状态列保留三态、用原因列区分，改动面最小而语义完整。
 */
public record OrderView(String id, String status, BigDecimal total, BigDecimal subtotal,
                        BigDecimal discount, BigDecimal shippingFee, BigDecimal payAmount,
                        String paymentMethod, String paymentReceipt, LocalDateTime createdAt,
                        List<Item> items, LocalDateTime expiresAt, String closeReason,
                        LocalDateTime closedAt) {
    public record Item(long productId, String productName, BigDecimal unitPrice, int quantity) {}
}
