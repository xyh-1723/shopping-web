package dev.learning.mall.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponApplication;
import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 优惠券算法的<b>边界</b>验收。
 *
 * <p>与 {@link CouponPricingTest} 的分工：那个类验「常规场景算得对」，这个类专门顶边界——
 * 门槛差 1 分、券额大于商品金额、商家承担正好等于封顶、补贴正好等于佣金上限、
 * 基数为 0 的店、空购物车、以及一个固定种子的随机性质测试。
 *
 * <p><b>为什么边界值得单独一个类？</b>券的 bug 几乎都长在边界上：减免多 1 分或少 1 分，
 * 在常规金额上看不出来，在「券额 = 商品金额」「封顶 = 承担额」这两个位置一定露出来。
 * 这里每条断言都落在「分」上，不做元的近似比较。
 */
class CouponBoundaryPricingTest {

    private static final CouponProperties PROPS = new CouponProperties();

    // ——————————————————— 门槛：差 1 分就是差 ———————————————————

    @Test
    @DisplayName("门槛正好相等可以用；差 1 分不能用，且提示差的就是那 1 分")
    void minSpendBoundaryIsExact() {
        CouponPricing.Terms exact = amount("PLATFORM", null, 500, 10_000, 6000);
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "满 100 减 5", exact,
                List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS);
        assertThat(app.discount()).isEqualByComparingTo("5.00");

        CouponPricing.Terms oneCentShort = amount("PLATFORM", null, 500, 10_001, 6000);
        assertThatThrownBy(() -> CouponPricing.apply("uc", "c", "PLAT", "满 100.01 减 5",
                oneCentShort, List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("COUPON_MIN_SPEND_NOT_MET");
                    assertThat(error.getMessage()).contains("0.01");
                });
    }

    @Test
    @DisplayName("门槛只算券能作用的那家店：商家券不把别家店的金额算进门槛")
    void minSpendCountsOnlyEligibleShops() {
        List<CouponPricing.ShopBase> shops = List.of(
                new CouponPricing.ShopBase(1, 6_000, 0),
                new CouponPricing.ShopBase(2, 6_000, 0));

        assertThatThrownBy(() -> CouponPricing.apply("uc", "c", "SHOP1", "本店满 100 减 5",
                amount("MERCHANT", 1L, 500, 10_000, 0), shops, PROPS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("COUPON_MIN_SPEND_NOT_MET"));

        // 同样两笔货，平台券看的是合计 120 元，所以能过。
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "平台满 100 减 5",
                amount("PLATFORM", null, 500, 10_000, 6000), shops, PROPS);
        assertThat(app.discount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("满减门槛按券前折后金额算：促销把 100 减到 60 后，满 100 的门槛就过不了")
    void promotionDiscountShrinksTheThresholdBase() {
        assertThatThrownBy(() -> CouponPricing.apply("uc", "c", "PLAT", "满 100 减 5",
                amount("PLATFORM", null, 500, 10_000, 6000),
                List.of(new CouponPricing.ShopBase(1, 10_000, 4_000)), PROPS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("COUPON_MIN_SPEND_NOT_MET"));
    }

    // ——————————————————— 券额 vs 商品金额 ———————————————————

    @Test
    @DisplayName("券额大于商品金额：减免被商家封顶压住，永远不会减成负数")
    void couponLargerThanGoodsNeverGoesNegative() {
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "满 0 减 100",
                amount("PLATFORM", null, 10_000, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 6_000, 0)), PROPS);

        // 商品 60 元，商家最多承担 35% = 21 元；券 100 元被下调到 21 元。
        assertThat(app.discount()).isEqualByComparingTo("21.00");
        assertThat(app.shops().get(0).couponDiscount()).isEqualByComparingTo("21.00");
        assertThat(app.shops().get(0).merchantIncome()).isEqualByComparingTo("35.88");
        assertThat(app.shops().get(0).platformIncome()).isEqualByComparingTo("3.12");
    }

    @Test
    @DisplayName("券减去整单商品金额：0 元应付是算法边界，商家与平台收入都为 0，不出现负数")
    void fullDiscountProducesZeroIncomeButNotNegative() {
        CouponProperties aggressive = new CouponProperties();
        aggressive.setCommissionRateBps(0);
        aggressive.setMerchantMaxBearBps(10_000);

        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "减 100%",
                new CouponPricing.Terms("PLATFORM", null, CouponPricing.DiscountType.PERCENT,
                        0, 10_000, null, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 6_000, 0)), aggressive);

        assertThat(app.discount()).isEqualByComparingTo("60.00");
        CouponApplication.ShopSettlement shop = app.shops().get(0);
        assertThat(shop.merchantIncome()).isEqualByComparingTo("0.00");
        assertThat(shop.platformIncome()).isEqualByComparingTo("0.00");
        assertThat(shop.merchantDiscount()).isEqualByComparingTo("60.00");
    }

    // ——————————————————— 商家封顶：正好 35% 与多 1 分 ———————————————————

    @Test
    @DisplayName("商家承担正好等于 35% 封顶时不下调；多 1 分就被压回 35%")
    void merchantCapBoundaryIsInclusive() {
        CouponApplication atCap = CouponPricing.apply("uc", "c", "PLAT", "正好 35",
                amount("PLATFORM", null, 3_500, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS);
        assertThat(atCap.discount()).isEqualByComparingTo("35.00");

        CouponApplication overCap = CouponPricing.apply("uc", "c", "PLAT", "多 1 分",
                amount("PLATFORM", null, 3_501, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS);
        assertThat(overCap.discount()).isEqualByComparingTo("35.00");
        assertThat(overCap.merchantDiscount()).isEqualByComparingTo("35.00");
        assertThat(overCap.platformSubsidy()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("平台全担时补贴正好是佣金的 80%：平台留下 20% 佣金，一分不多")
    void subsidyCapIsExactlyEightyPercentOfFee() {
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "平台全担 10",
                amount("PLATFORM", null, 1_000, 0, 10_000),
                List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS);

        // 券后 90 元 → 佣金 7.20；补贴名义 10 元被压到 7.20 × 80% = 5.76。
        CouponApplication.ShopSettlement shop = app.shops().get(0);
        assertThat(shop.platformFee()).isEqualByComparingTo("7.20");
        assertThat(shop.platformSubsidy()).isEqualByComparingTo("5.76");
        assertThat(shop.platformIncome()).isEqualByComparingTo("1.44");
        assertThat(shop.platformIncome())
                .isEqualByComparingTo(shop.platformFee().multiply(new BigDecimal("0.20")));
    }

    @Test
    @DisplayName("平台共担比例为 0：券全部由商家承担，平台佣金一分不少")
    void zeroPlatformShareMeansMerchantBearsAll() {
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "商家全担",
                amount("PLATFORM", null, 1_000, 0, 0),
                List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS);

        CouponApplication.ShopSettlement shop = app.shops().get(0);
        assertThat(shop.platformSubsidy()).isEqualByComparingTo("0.00");
        assertThat(shop.merchantDiscount()).isEqualByComparingTo("10.00");
        assertThat(shop.platformIncome()).isEqualByComparingTo(shop.platformFee());
    }

    // ——————————————————— 基数为 0 的店 / 空购物车 ———————————————————

    @Test
    @DisplayName("促销把一家店减到 0 元：那家店一分钱券都分不到，减轻全落在另一家")
    void zeroBaseShopGetsNothing() {
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "平台满减",
                amount("PLATFORM", null, 1_000, 0, 6000),
                List.of(new CouponPricing.ShopBase(1, 10_000, 10_000),
                        new CouponPricing.ShopBase(2, 10_000, 0)), PROPS);

        assertThat(app.shops().get(0).couponDiscount()).isEqualByComparingTo("0.00");
        assertThat(app.shops().get(0).merchantIncome()).isEqualByComparingTo("0.00");
        assertThat(app.shops().get(0).platformIncome()).isEqualByComparingTo("0.00");
        assertThat(app.shops().get(1).couponDiscount()).isEqualByComparingTo("10.00");
        assertThat(app.discount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("空购物车用券：明确拒绝，而不是给出一张「减 0 元」的券")
    void emptyCartCannotUseCoupon() {
        assertThatThrownBy(() -> CouponPricing.apply("uc", "c", "PLAT", "空车",
                amount("PLATFORM", null, 1_000, 0, 6000), List.of(), PROPS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("COUPON_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("0.5% 的折扣在 0.50 元商品上取整为 0：四舍五入不会凭空变出一分钱")
    void roundingNeverInventsADiscount() {
        CouponApplication app = CouponPricing.apply("uc", "c", "PLAT", "极小额",
                new CouponPricing.Terms("PLATFORM", null, CouponPricing.DiscountType.PERCENT,
                        0, 50, null, 0, 6000),
                List.of(new CouponPricing.ShopBase(1, 10_000, 0)), PROPS);
        // 100 元商品的 0.5% = 0.50 元，正好落在分上，不多不少。
        assertThat(app.discount()).isEqualByComparingTo("0.50");

        // 0.50 元商品的 0.5% = 0.0025 元，向下取整 0 分 → 拒绝，而不是收 0.01。

        assertThatThrownBy(() -> CouponPricing.apply("uc", "c", "PLAT", "极小额",
                new CouponPricing.Terms("PLATFORM", null, CouponPricing.DiscountType.PERCENT,
                        0, 50, null, 0, 6000),
                List.of(new CouponPricing.ShopBase(1, 50, 0)), PROPS))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("COUPON_NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("无券结算也覆盖 0 元店：不抛异常，收入为 0 而不是负数")
    void settleHandlesZeroBaseShop() {
        CouponApplication app = CouponPricing.settle(
                List.of(new CouponPricing.ShopBase(1, 1_000, 1_000),
                        new CouponPricing.ShopBase(2, 1_000, 0)), PROPS);

        assertThat(app.hasCoupon()).isFalse();
        assertThat(app.shops().get(0).merchantIncome()).isEqualByComparingTo("0.00");
        assertThat(app.shops().get(0).platformIncome()).isEqualByComparingTo("0.00");
        assertThat(app.shops().get(1).platformIncome()).isEqualByComparingTo("0.80");
    }

    // ——————————————————— 随机性质测试 ———————————————————

    /**
     * 固定种子跑 1000 组随机券与随机购物车，检查四条恒等式。
     *
     * <p>这不是「多写几个例子」，而是<b>性质测试</b>：只要有一条不变量被破，
     * 它就会在某个随机组合上红掉。固定种子保证失败可复现——这一点比随机覆盖更重要，
     * 否则红一次之后再跑可能就绿了。
     */
    @Test
    @DisplayName("1000 组随机输入：分摊守恒、封顶不越界、平台收入非负、收入恒等式全部成立")
    void randomCasesKeepEveryInvariant() {
        Random random = new Random(20261027L);
        CouponProperties props = new CouponProperties();
        int applied = 0;
        int rejected = 0;

        for (int i = 0; i < 1000; i++) {
            props.setCommissionRateBps(pick(random, 0, 800, 2_000));
            props.setMerchantMaxBearBps(pick(random, 0, 1_000, 3_500, 10_000));
            props.setPlatformSubsidyCapBps(pick(random, 0, 5_000, 8_000, 10_000));

            List<CouponPricing.ShopBase> shops = randomShops(random);
            CouponPricing.Terms terms = randomTerms(random, shops);
            try {
                CouponApplication app = CouponPricing.apply("uc", "c", "RAND", "随机券",
                        terms, shops, props);
                assertInvariants(app, shops, terms, props);
                applied++;
            } catch (BusinessException expected) {
                assertThat(expected.code())
                        .as("随机用例只允许两种业务拒绝")
                        .isIn("COUPON_MIN_SPEND_NOT_MET", "COUPON_NOT_APPLICABLE");
                if ("COUPON_MIN_SPEND_NOT_MET".equals(expected.code())) {
                    assertThat(eligibleCents(shops, terms))
                            .as("门槛不足必须真的是基数不够")
                            .isLessThan(terms.minSpendCents());
                }
                rejected++;
            }
        }

        // 两个分支都必须真的走到，否则「全被拒绝」也能让这个测试假绿。
        assertThat(applied).as("可成交组数：%d", applied).isGreaterThan(100);
        assertThat(rejected).as("被拒绝组数：%d", rejected).isGreaterThan(100);
    }

    // ——————————————————— 工具 ———————————————————

    private static void assertInvariants(CouponApplication app, List<CouponPricing.ShopBase> shops,
                                         CouponPricing.Terms terms, CouponProperties props) {
        long coupon = MoneyUnits.toCents(app.discount());
        long merchant = MoneyUnits.toCents(app.merchantDiscount());
        long subsidy = MoneyUnits.toCents(app.platformSubsidy());
        long sumCoupon = 0;
        long sumMerchant = 0;
        long sumSubsidy = 0;

        assertThat(app.shops()).hasSize(shops.size());
        for (int i = 0; i < shops.size(); i++) {
            CouponPricing.ShopBase base = shops.get(i);
            CouponApplication.ShopSettlement shop = app.shops().get(i);
            long goods = base.goodsBaseCents();
            long shopCoupon = MoneyUnits.toCents(shop.couponDiscount());
            long shopMerchant = MoneyUnits.toCents(shop.merchantDiscount());
            long shopSubsidy = MoneyUnits.toCents(shop.platformSubsidy());
            long fee = MoneyUnits.toCents(shop.platformFee());
            long merchantIncome = MoneyUnits.toCents(shop.merchantIncome());
            long platformIncome = MoneyUnits.toCents(shop.platformIncome());

            assertThat(shop.merchantId()).as("分摊顺序不能乱").isEqualTo(base.merchantId());
            assertThat(shopCoupon).as("券减免不能超过该店可减金额").isBetween(0L, goods);
            assertThat(shopCoupon).as("券的每一分都要有人认账").isEqualTo(shopMerchant + shopSubsidy);
            assertThat(shopMerchant).as("商家承担不能超过封顶")
                    .isLessThanOrEqualTo(goods * props.getMerchantMaxBearBps() / 10000);
            assertThat(shopSubsidy).as("补贴不能超过名义份额")
                    .isLessThanOrEqualTo(shopCoupon * terms.platformShareBps() / 10000);
            assertThat(fee).as("佣金按券后金额算")
                    .isEqualTo((goods - shopCoupon) * props.getCommissionRateBps() / 10000);
            assertThat(platformIncome).as("平台收入不能为负").isGreaterThanOrEqualTo(0);
            assertThat(merchantIncome).as("商家收入不能为负").isGreaterThanOrEqualTo(0);
            assertThat(merchantIncome + platformIncome).as("收入之和 = 券后商品金额")
                    .isEqualTo(goods - shopCoupon);
            if (!"PLATFORM".equals(terms.ownerType()) && base.merchantId() != terms.merchantId()) {
                assertThat(shopCoupon).as("商家券不作用于别家店").isZero();
            }

            sumCoupon += shopCoupon;
            sumMerchant += shopMerchant;
            sumSubsidy += shopSubsidy;
        }
        assertThat(sumCoupon).as("Σ 各店券减 = 券总减免").isEqualTo(coupon);
        assertThat(sumMerchant).isEqualTo(merchant);
        assertThat(sumSubsidy).isEqualTo(subsidy);
        assertThat(coupon).as("券不能减超过可减金额").isLessThanOrEqualTo(eligibleCents(shops, terms));
        assertThat(coupon).isPositive();
    }

    private static long eligibleCents(List<CouponPricing.ShopBase> shops, CouponPricing.Terms terms) {
        long total = 0;
        for (CouponPricing.ShopBase shop : shops) {
            boolean eligible = "PLATFORM".equals(terms.ownerType())
                    || (terms.merchantId() != null && terms.merchantId() == shop.merchantId());
            if (eligible) total += shop.goodsBaseCents();
        }
        return total;
    }

    private static List<CouponPricing.ShopBase> randomShops(Random random) {
        int count = 1 + random.nextInt(4);
        List<CouponPricing.ShopBase> shops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long subtotal = random.nextInt(0, 50_001);
            long promotion = subtotal == 0 ? 0 : random.nextInt(0, (int) subtotal + 1);
            shops.add(new CouponPricing.ShopBase(i + 1, subtotal, promotion));
        }
        return shops;
    }

    private static CouponPricing.Terms randomTerms(Random random, List<CouponPricing.ShopBase> shops) {
        boolean platform = random.nextBoolean();
        // 商家券随机指向购物车里真实存在的一家店，否则大量用例会停在「券不作用于任何商品」，
        // 覆盖不到商家券的封顶与分摊。
        Long merchantId = platform ? null : shops.get(random.nextInt(shops.size())).merchantId();
        long minSpend = random.nextInt(0, 60_000);
        if (random.nextInt(3) == 0) {
            Long maxDiscount = random.nextBoolean() ? null : (long) random.nextInt(1, 30_000);
            return new CouponPricing.Terms(platform ? "PLATFORM" : "MERCHANT", merchantId,
                    CouponPricing.DiscountType.PERCENT, 0, random.nextInt(1, 10_001),
                    maxDiscount, minSpend, platform ? random.nextInt(0, 10_001) : 0);
        }
        return new CouponPricing.Terms(platform ? "PLATFORM" : "MERCHANT", merchantId,
                CouponPricing.DiscountType.AMOUNT, random.nextInt(1, 50_001), 0, null,
                minSpend, platform ? random.nextInt(0, 10_001) : 0);
    }

    private static int pick(Random random, int... values) {
        return values[random.nextInt(values.length)];
    }

    private static CouponPricing.Terms amount(String owner, Long merchantId, long cents, long minSpend,
                                              int shareBps) {
        return new CouponPricing.Terms(owner, merchantId, CouponPricing.DiscountType.AMOUNT,
                cents, 0, null, minSpend, shareBps);
    }
}
