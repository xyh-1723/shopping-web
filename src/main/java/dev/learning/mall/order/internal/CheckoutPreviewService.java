package dev.learning.mall.order.internal;

import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponCatalog;
import dev.learning.mall.coupon.api.CouponPreview;
import dev.learning.mall.coupon.api.CouponShop;
import dev.learning.mall.order.api.CheckoutPreview;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.pricing.api.Pricer;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 结算预览：用户选券后、提交前看到的最终金额。
 *
 * <p><b>为什么它不能简单地「购物袋金额 − 券面额」？</b>
 * 券可能不够门槛、可能只适用于某一家店、可能触发商家承担封顶（实际减免小于面额），
 * 券后金额还可能跌破包邮门槛而重新产生运费。这些都只有走过券算法与
 * {@link Pricer} 才能得到正确结果。预览走的就是下单那条路，只是不写库。
 */
@Service
public class CheckoutPreviewService {

    private final CartService cart;
    private final ProductCatalog catalog;
    private final Pricer pricer;
    private final CouponCatalog coupons;

    public CheckoutPreviewService(CartService cart, ProductCatalog catalog, Pricer pricer,
                                  CouponCatalog coupons) {
        this.cart = cart;
        this.catalog = catalog;
        this.pricer = pricer;
        this.coupons = coupons;
    }

    public CheckoutPreview preview(String username, String userCouponId) {
        List<CreateOrderRequest.Line> lines = cart.orderLines(username);
        if (lines.isEmpty()) {
            throw BusinessException.badRequest("CART_EMPTY", "购物袋是空的，无法预览结算");
        }
        Pricer.Quote baseQuote = OrderPricing.quoteOf(catalog, pricer, lines);
        List<SubOrderSplitter.Shop> shops = OrderPricing.shopsOf(catalog, pricer, lines);
        // 与下单同一步：先拆券前的满减分摊，构造券的适用基数。
        List<SubOrderSplitter.Share> promotionShares =
                SubOrderSplitter.split(shops, baseQuote.discount(), baseQuote.shippingFee());
        List<CouponShop> couponShops = promotionShares.stream()
                .map(share -> new CouponShop(share.merchantId(), share.subtotal(), share.discount()))
                .toList();

        BigDecimal couponDiscount;
        String couponName = null;
        String couponCode = null;
        List<CheckoutPreview.ShopPreview> shopPreviews;
        if (userCouponId == null || userCouponId.isBlank()) {
            couponDiscount = BigDecimal.ZERO;
            shopPreviews = couponShops.stream()
                    .map(shop -> new CheckoutPreview.ShopPreview(shop.merchantId(), BigDecimal.ZERO))
                    .toList();
        } else {
            CouponPreview preview = coupons.preview(username, userCouponId, couponShops);
            couponDiscount = preview.discount();
            couponName = preview.couponName();
            couponCode = preview.couponCode();
            shopPreviews = preview.shops().stream()
                    .map(shop -> new CheckoutPreview.ShopPreview(shop.merchantId(), shop.couponDiscount()))
                    .toList();
        }

        Pricer.Quote quote = OrderPricing.quoteOf(catalog, pricer, lines, couponDiscount);
        return new CheckoutPreview(quote.subtotal(), baseQuote.discount(), couponDiscount,
                quote.discount(), quote.shippingFee(), quote.payable(),
                couponName, couponCode, shopPreviews, quote.notes());
    }
}
