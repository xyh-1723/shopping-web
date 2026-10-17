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
 *
 * <p><b>履约字段（V15）。</b>{@code fulfillmentStatus} 是前端渲染「待发货 / 待收货 / 已完成」
 * 的那一列，{@code paidAt} 是「支付了多久」的唯一可信起点（退款窗口按它算，
 * 不能用 {@code createdAt} 凑——那是下单时刻）。四个时刻只暴露用户看得见的那三个
 * （发货 / 收货 / 完成），另两个列刻意不外泄：
 * <ul>
 *   <li>{@code auto_receive_at}：运营配置推导出来的内部调度时刻，用户不需要知道
 *       「系统打算哪天替你点收货」，说出来只会引来「能不能改」的误解；</li>
 *   <li>{@code shipped_by}：操作者账号，属于审计字段，放进买家视图没有意义。</li>
 * </ul>
 */
public record OrderView(String id, String status, BigDecimal total, BigDecimal subtotal,
                        BigDecimal discount, BigDecimal shippingFee, BigDecimal payAmount,
                        String paymentMethod, String paymentReceipt, LocalDateTime createdAt,
                        List<Item> items, LocalDateTime expiresAt, String closeReason,
                        LocalDateTime closedAt, LocalDateTime paidAt, String fulfillmentStatus,
                        LocalDateTime shippedAt, String shippingNo, LocalDateTime receivedAt,
                        LocalDateTime completedAt) {
    public record Item(long productId, String productName, BigDecimal unitPrice, int quantity) {}
}
