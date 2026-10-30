package dev.learning.mall.coupon.api;

import java.math.BigDecimal;

/**
 * 一家店在「算券之前」的金额画像。
 *
 * <p>优惠券模块只认这三个数，不认商品、库存、履约——于是它可以在下单与预览两条路径上
 * 复用同一份算法，也不需要把店铺/商品实体拖进券的世界。
 *
 * @param merchantId        店铺编号
 * @param subtotal          该店商品小计（元）
 * @param promotionDiscount 该店分摊到的满减（元，平台既有促销，商家承担）
 */
public record CouponShop(long merchantId, BigDecimal subtotal, BigDecimal promotionDiscount) {

    /** 券门槛与券减免的基数：满减之后的商品金额，不含运费。 */
    public BigDecimal goodsAfterPromotion() {
        return subtotal.subtract(promotionDiscount);
    }
}
