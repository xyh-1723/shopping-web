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
 * <h3>子订单（V25）</h3>
 *
 * <p>买家下一次单、付一次款，但货是<b>各家店各发各的</b>。所以：
 * <ul>
 *   <li>{@code status} 与 {@code paidAt} 仍然只在父单上——<b>钱是整笔交易的</b>，
 *       不存在「第二家店还没收到钱」这回事；</li>
 *   <li>{@code subs} 是每家店一份：各自的金额、各自的 {@code fulfillmentStatus}、各自的运单号。</li>
 * </ul>
 * 于是「待发货 / 待收货 / 已完成」这个问题必须先问「哪一家的」，
 * 父单上不再有 {@code fulfillmentStatus} 这一个字段——它去掉不是简化，
 * 是因为它从来就不是一个能成立的答案（三家店两家发了货，父单该写什么？）。
 *
 * <p>{@code items} 保留为扁平列表（前端按商品顺序渲染），{@link SubView#items()} 则是
 * 按店归好组的同一批明细。两者由同一处查询得出，不存在「两份明细对不上」。
 *
 * <p>三个时刻只暴露用户看得见的那三个（发货 / 收货 / 完成），另两个列刻意不外泄：
 * <ul>
 *   <li>{@code auto_receive_at}：运营配置推导出来的内部调度时刻，用户不需要知道
 *       「系统打算哪天替你点收货」，说出来只会引来「能不能改」的误解；</li>
 *   <li>{@code shipped_by}：操作者账号，属于审计字段，放进买家视图没有意义。</li>
 * </ul>
 */
public record OrderView(String id, String status, BigDecimal total, BigDecimal subtotal,
                        BigDecimal discount, BigDecimal shippingFee, BigDecimal payAmount,
                        BigDecimal couponDiscount, String couponCode,
                        String paymentMethod, String paymentReceipt, LocalDateTime createdAt,
                        List<Item> items, List<SubView> subs, LocalDateTime expiresAt,
                        String closeReason, LocalDateTime closedAt, LocalDateTime paidAt) {

    public record Item(long productId, String productName, BigDecimal unitPrice, int quantity) {}

    /**
     * 一家店在这笔订单里的那一单：买家看到的是「订单里第几家店」，商家看到的是「我的这一单」。
     *
     * <p>{@code payAmount} 是这家店该分到的钱，不是买家付的全部——
     * Σ 所有子订单的 {@code payAmount} = 父单的 {@code payAmount}，分毫不差。
     */
    public record SubView(String id, int seq, long merchantId, String merchantName,
                          BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee,
                          BigDecimal payAmount, String fulfillmentStatus, LocalDateTime shippedAt,
                          String shippingNo, LocalDateTime receivedAt, LocalDateTime completedAt,
                          List<Item> items) {}
}
