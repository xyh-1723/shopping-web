package dev.learning.mall.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponApplication;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 优惠券算法的纯单元验收。
 *
 * <p>这些断言全部在「分」上比对，因为分是券的唯一真相单位；用元比对会掩盖
 * 逐店取整的差异。三条线各由一组用例守：
 * <ul>
 *   <li><b>用户</b>：券减免等于各店减免之和，不多不少；</li>
 *   <li><b>商家</b>：单店承担不超过商品金额的 35%，超过就下调减免；</li>
 *   <li><b>平台</b>：补贴不超过佣金的 80%，所以 {@code platformIncome >= 0}。</li>
 * </ul>
 */
class CouponPricingTest {

    private static final CouponProperties PROPS = new CouponProperties();

    @Test
    @DisplayName("平台满减跨店分摊：Σ 各店减免 = 券总减免，商家与平台收入之和 = 券后商品金额")
    void platformAmountIsSplitExactly() {
        CouponPricing.Terms terms = amount("PLATFORM", null, 1000, 0, 6000);
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "满 10 减 10", terms,
                List.of(new CouponPricing.ShopBase(1, 6000, 0),
                        new CouponPricing.ShopBase(2, 4000, 0)), PROPS);

        assertThat(app.discount()).isEqualByComparingTo("10.00");
        assertThat(app.shops()).extracting(CouponApplication.ShopSettlement::couponDiscount)
                .containsExactly(new BigDecimal("6.00"), new BigDecimal("4.00"));
        assertThat(app.merchantDiscount().add(app.platformSubsidy()))
                .isEqualByComparingTo(app.discount());

        BigDecimal merchantIncome = BigDecimal.ZERO;
        BigDecimal platformIncome = BigDecimal.ZERO;
        for (CouponApplication.ShopSettlement shop : app.shops()) {
            BigDecimal goods = shop.merchantId() == 1L ? new BigDecimal("60.00") : new BigDecimal("40.00");
            assertThat(shop.couponDiscount())
                    .isEqualByComparingTo(shop.merchantDiscount().add(shop.platformSubsidy()));
            assertThat(shop.merchantIncome().add(shop.platformIncome()))
                    .isEqualByComparingTo(goods.subtract(shop.couponDiscount()));
            assertThat(shop.platformIncome()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            merchantIncome = merchantIncome.add(shop.merchantIncome());
            platformIncome = platformIncome.add(shop.platformIncome());
        }
        assertThat(merchantIncome.add(platformIncome)).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("最大余额法：分不尽的余数按余数从大到小补齐，Σ 仍然精确")
    void remainderIsScatteredNotDropped() {
        // 三家店各 1.99 元，券 1.00 元：100 × 199 / 597 = 33 余 1，三次共用 99 分，
        // 剩下 1 分给余数最大（并列时店序靠前）的那家。
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "分角券",
                amount("PLATFORM", null, 100, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 199, 0),
                        new CouponPricing.ShopBase(2, 199, 0),
                        new CouponPricing.ShopBase(3, 199, 0)), PROPS);

        assertThat(app.shops()).extracting(CouponApplication.ShopSettlement::couponDiscount)
                .containsExactly(new BigDecimal("0.34"), new BigDecimal("0.33"), new BigDecimal("0.33"));
        assertThat(app.discount()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("折扣券封顶：按折后金额占比重新分配封顶额，而不是逐店截断")
    void percentCouponRespectsMaxDiscount() {
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "9 折封顶 30",
                new CouponPricing.Terms("PLATFORM", null, CouponPricing.DiscountType.PERCENT,
                        0, 1000, 3000L, 0, 6000),
                List.of(new CouponPricing.ShopBase(1, 30000, 0),
                        new CouponPricing.ShopBase(2, 20000, 0)), PROPS);

        assertThat(app.discount()).isEqualByComparingTo("30.00");
        assertThat(app.shops()).extracting(CouponApplication.ShopSettlement::couponDiscount)
                .containsExactly(new BigDecimal("18.00"), new BigDecimal("12.00"));
    }

    @Test
    @DisplayName("门槛不足：错误码明确，且不带任何「悄悄减 0 元」的结果")
    void minSpendIsEnforced() {
        CouponPricing.Terms terms = amount("PLATFORM", null, 1000, 9900, 6000);
        assertThatThrownBy(() -> CouponPricing.apply("uc", "c", "PLAT", "满 99 减 10", terms,
                List.of(new CouponPricing.ShopBase(1, 5000, 0)), PROPS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("COUPON_MIN_SPEND_NOT_MET"));
    }

    @Test
    @DisplayName("商家承担封顶：商家券超出 35% 时下调减免，宁可少减也不让商家倒贴")
    void merchantBearingIsCapped() {
        CouponApplication app = CouponPricing.apply("uc", "c", "SHOP1", "满 100 减 50",
                amount("MERCHANT", 1L, 5000, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 10000, 0)), PROPS);

        // 商品 100 元，35% 封顶 = 35 元；原券 50 元，被下调到 35 元。
        assertThat(app.discount()).isEqualByComparingTo("35.00");
        assertThat(app.shops().get(0).merchantDiscount()).isEqualByComparingTo("35.00");
        assertThat(app.shops().get(0).platformSubsidy()).isEqualByComparingTo("0.00");
        // 商家收入 = 100 − 35 − 佣金 5.2 = 59.8 元；券的 35 元全部由商家承担。
        assertThat(app.shops().get(0).platformFee()).isEqualByComparingTo("5.20");
        assertThat(app.shops().get(0).merchantIncome()).isEqualByComparingTo("59.80");
    }

    @Test
    @DisplayName("平台补贴不超过佣金的 80%：平台每笔用券订单至少留下 20% 佣金")
    void platformSubsidyNeverExceedsCommissionCap() {
        // 商品 100 元、券 30 元、平台全担。券后佣金 = 70 × 8% = 5.6 元，
        // 补贴名义 30 元被压到 5.6 × 80% = 4.48 元；商家承担 25.52 元（未触 35% 封顶）。
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "平台全担 30",
                amount("PLATFORM", null, 3000, 0, 10000),
                List.of(new CouponPricing.ShopBase(1, 10000, 0)), PROPS);

        CouponApplication.ShopSettlement shop = app.shops().get(0);
        assertThat(shop.couponDiscount()).isEqualByComparingTo("30.00");
        assertThat(shop.platformFee()).isEqualByComparingTo("5.60");
        assertThat(shop.platformSubsidy()).isEqualByComparingTo("4.48");
        assertThat(shop.merchantDiscount()).isEqualByComparingTo("25.52");
        assertThat(shop.platformIncome()).isEqualByComparingTo("1.12");
        assertThat(shop.merchantIncome()).isEqualByComparingTo("68.88");
        // 补贴永远不超过佣金的 80%，所以平台收入永远非负。
        assertThat(shop.platformIncome()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("无券订单也记平台佣金：否则「平台赚不赚钱」只能靠发券量猜")
    void noCouponStillRecordsCommission() {
        CouponApplication app = CouponPricing.settle(
                List.of(new CouponPricing.ShopBase(1, 10000, 0)), PROPS);

        assertThat(app.hasCoupon()).isFalse();
        assertThat(app.discount()).isEqualByComparingTo("0.00");
        CouponApplication.ShopSettlement shop = app.shops().get(0);
        assertThat(shop.platformFee()).isEqualByComparingTo("8.00");
        assertThat(shop.merchantIncome()).isEqualByComparingTo("92.00");
        assertThat(shop.platformIncome()).isEqualByComparingTo("8.00");
    }

    @Test
    @DisplayName("商家券只作用于本店：不适用店铺的减免必须是 0，而不是按比例分摊")
    void merchantCouponOnlyCoversItsOwnShop() {
        CouponApplication app = CouponPricing.apply("uc", "c", "SHOP1", "1 号店满减",
                amount("MERCHANT", 1L, 500, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 3000, 0),
                        new CouponPricing.ShopBase(2, 3000, 0)), PROPS);

        assertThat(app.shops()).extracting(CouponApplication.ShopSettlement::couponDiscount)
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("0.00"));
        assertThat(app.discount()).isEqualByComparingTo("5.00");
    }

    private static CouponPricing.Terms amount(String owner, Long merchantId, long cents, long minSpend,
                                              int shareBps) {
        return new CouponPricing.Terms(owner, merchantId, CouponPricing.DiscountType.AMOUNT,
                cents, 0, null, minSpend, shareBps);
    }
}
