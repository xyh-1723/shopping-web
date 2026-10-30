package dev.learning.mall.catalog.api;

import java.math.BigDecimal;

/**
 * 商品视图。
 *
 * <p><b>{@code merchantId} 是 V24 之后商品自带的一个属性，不是下单时才算出来的</b>：
 * 库里 {@code product.merchant_id} 从 V24 起就是 NOT NULL。它在视图里出现，
 * 是因为「这件货属于哪家店」决定了——
 * <ul>
 *   <li>下单时按什么分出子订单（{@code OrderTransactions}）；</li>
 *   <li>谁能给它发货、谁能批它的退款（{@code order_sub.merchant_id}）。</li>
 * </ul>
 * 少了这一列，订单模块就得自己再查一次商品表去问归属，
 * 那是「同一件货有两处答案」的开始。
 */
public record ProductView(long id, String name, String description, String category,
                          String illustration, BigDecimal price, int stock, long merchantId) {}
