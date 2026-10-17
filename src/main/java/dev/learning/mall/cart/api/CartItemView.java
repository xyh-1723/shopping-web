package dev.learning.mall.cart.api;

import java.math.BigDecimal;

/**
 * 购物袋里的一行。
 *
 * <p>{@code stock} 与 {@code available} 一起返回给前端和模型，是为了让「库存变化」这件事
 * 在加购之后仍然可见：用户上午加的两件，下午可能只剩一件了。让模型拿到真实库存，
 * 它才有机会在结算前提醒用户，而不是等下单时才炸。
 */
public record CartItemView(long productId, String name, String illustration,
                           BigDecimal unitPrice, int quantity, int stock,
                           BigDecimal subtotal, boolean available) {
}
