package dev.learning.mall.coupon.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次「券算完了」的结果：总额 + 每家店的资金分摊。
 *
 * <p>它同时承担两件事：
 * <ul>
 *   <li>下单：订单模块拿 {@code discount()} 去重算运费，拿 {@code shops()} 落子单的结算六列；</li>
 *   <li>预览：接口把同一份结果原样返回给页面，用户看到的减免与下单时逐分一致。</li>
 * </ul>
 *
 * <p>无券订单也走这里（{@link #none(List)}）：平台佣金对所有订单都要记，
 * 否则「平台到底赚不赚钱」只能靠发券量猜。
 *
 * @param userCouponId     用户券实例号；无券为 {@code null}
 * @param couponId         券模板号；无券为 {@code null}
 * @param couponCode       券码；无券为 {@code null}
 * @param couponName       券名；无券为 {@code null}
 * @param discount         券总减免（元）
 * @param merchantDiscount 商家承担合计（元）
 * @param platformSubsidy  平台补贴合计（元）
 * @param shops            每家店的减免与结算（必须覆盖订单里的全部店铺，包括不适用券的店）
 */
public record CouponApplication(String userCouponId, String couponId, String couponCode, String couponName,
                                BigDecimal discount, BigDecimal merchantDiscount, BigDecimal platformSubsidy,
                                List<ShopSettlement> shops) {

    /**
     * 一家店的券减免与结算。
     *
     * <p>恒等式（全部为元、两位小数）：
     * {@code couponDiscount = merchantDiscount + platformSubsidy}，
     * {@code merchantIncome + platformIncome = goodsAfterPromotion - couponDiscount}。
     * 运费不在券模块里参与计算，订单模块会把该店分摊到的运费加进 {@code merchantIncome}。
     */
    public record ShopSettlement(long merchantId, BigDecimal couponDiscount, BigDecimal merchantDiscount,
                                 BigDecimal platformSubsidy, BigDecimal platformFee,
                                 BigDecimal merchantIncome, BigDecimal platformIncome) {}

    /** 无券订单：券减免为 0，但平台佣金照记。 */
    public static CouponApplication none(List<ShopSettlement> shops) {
        return new CouponApplication(null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.copyOf(shops));
    }

    /** 取某家店的结算；订单里的每一家店都必须在这里出现。 */
    public ShopSettlement shopOf(long merchantId) {
        return shops.stream()
                .filter(shop -> shop.merchantId() == merchantId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("券结算缺少店铺 " + merchantId));
    }

    public boolean hasCoupon() {
        return couponId != null;
    }
}
