package dev.learning.mall.order.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用券后的结算预览（只读）。
 *
 * <p>它与真正下单共用 {@code Pricer} 与券算法，所以页面上的数字就是提交后的数字。
 * 差异只有一个：预览<b>不锁券、不占预算</b>——用户还没决定，不能先把券扣住。
 *
 * @param promotionDiscount 满减（券前）
 * @param couponDiscount    券减免
 * @param discount          满减 + 券减免合计
 */
public record CheckoutPreview(BigDecimal subtotal, BigDecimal promotionDiscount, BigDecimal couponDiscount,
                              BigDecimal discount, BigDecimal shippingFee, BigDecimal payAmount,
                              String couponName, String couponCode,
                              List<ShopPreview> shops, List<String> notes) {

    /** 每家店分摊到的券减免。平台/商家的内部结算不对外暴露。 */
    public record ShopPreview(long merchantId, BigDecimal couponDiscount) {}
}
