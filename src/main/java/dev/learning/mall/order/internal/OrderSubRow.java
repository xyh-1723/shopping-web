package dev.learning.mall.order.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 子订单行。
 *
 * <p>与 {@link OrderRow} 同构：由 {@code JdbcClient} 直接映射（列名小写下划线、组件驼峰），
 * 再由 {@link OrderSubRepository} 翻译成对外的 {@code OrderView.SubView}。
 *
 * <p><b>它承载两件父单不再承载的东西</b>：
 * <ul>
 *   <li><b>金额</b>：这家店自己的小计 / 满减 / 运费 / 应付。父单上那四列是<b>买家付的总数</b>，
 *       子订单上这四列是<b>这家店该分到的数</b>——两个不同的事实，不能只留一份。</li>
 *   <li><b>履约</b>：发货、收货、完成三个时刻与 {@code fulfillment_status} 都在这一层。
 *       父单没有「发货」这个概念，因为没有任何一家店叫「这个订单」。</li>
 * </ul>
 */
record OrderSubRow(String id, String orderId, int subSeq, long merchantId, String merchantName,
                   BigDecimal subtotal, BigDecimal discount, BigDecimal shippingFee, BigDecimal payAmount,
                   String fulfillmentStatus, LocalDateTime shippedAt, String shippedBy, String shippingNo,
                   LocalDateTime receivedAt, LocalDateTime completedAt, LocalDateTime autoReceiveAt,
                   LocalDateTime createdAt) {}
