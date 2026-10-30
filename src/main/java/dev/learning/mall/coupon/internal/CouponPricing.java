package dev.learning.mall.coupon.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.coupon.api.CouponApplication;
import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 优惠券的核心算法：<b>纯函数，只认整数分</b>。
 *
 * <p>它回答三个问题，顺序不能反：
 * <ol>
 *   <li><b>能减多少</b>：门槛 → 满减/折扣 → 跨店按占比分摊（最大余额法）；</li>
 *   <li><b>商家最多承担多少</b>：超过封顶就下调减免（二分），不让商家倒贴；</li>
 *   <li><b>平台补贴多少</b>：补贴不超过佣金的 {@code platformSubsidyCapBps}，平台永远不亏。</li>
 * </ol>
 *
 * <p><b>为什么只在整数分上算？</b>与 {@code SubOrderSplitter} 同一个理由：
 * 逐店四舍五入与「总和守恒」是两件事。这里每一步都向下取整，余数用最大余额法补，
 * 于是 {@code Σ 各店减免 = 券总减免} 恒成立，且同一输入永远得到同一结果。
 *
 * <p><b>为什么商家封顶用二分？</b>{@code 商家承担(d) = d - 补贴(d)} 在 d 上单调不减
 * （补贴先随 d 线性增长，触到佣金封顶后随佣金下降而回落，两段都让商家承担继续上升），
 * 所以「最大的 d 使商家承担 ≤ 封顶」可以直接二分。
 */
final class CouponPricing {

    private CouponPricing() {}

    enum DiscountType { AMOUNT, PERCENT }

    /**
     * 券模板的算法形态。
     *
     * @param ownerType          PLATFORM / MERCHANT
     * @param merchantId         商家券的店铺号；平台券为 null
     * @param amountCents        满减面额（分）；折扣券时为 0
     * @param percentBps         折扣比例（万分比，10% = 1000）；满减券时为 0
     * @param maxDiscountCents   折扣券封顶（分），可空
     * @param minSpendCents      门槛（分）
     * @param platformShareBps   平台共担比例（万分比）
     */
    record Terms(String ownerType, Long merchantId, DiscountType discountType,
                 long amountCents, long percentBps, Long maxDiscountCents,
                 long minSpendCents, int platformShareBps) {}

    /** 一家店参与算券的基数（全部为分）。 */
    record ShopBase(long merchantId, long subtotalCents, long promotionDiscountCents) {

        long goodsBaseCents() {
            return Math.max(0, subtotalCents - promotionDiscountCents);
        }
    }

    /** 一家店算完之后的结算（全部为分，且不含运费）。 */
    private record Settlement(long couponDiscountCents, long merchantDiscountCents, long subsidyCents,
                              long feeCents, long merchantIncomeCents, long platformIncomeCents) {

        CouponApplication.ShopSettlement toApi(long merchantId) {
            return new CouponApplication.ShopSettlement(merchantId,
                    MoneyUnits.toYuan(couponDiscountCents), MoneyUnits.toYuan(merchantDiscountCents),
                    MoneyUnits.toYuan(subsidyCents), MoneyUnits.toYuan(feeCents),
                    MoneyUnits.toYuan(merchantIncomeCents), MoneyUnits.toYuan(platformIncomeCents));
        }
    }

    /**
     * 算一张券。
     *
     * @throws BusinessException 门槛不足（{@code COUPON_MIN_SPEND_NOT_MET}）
     */
    static CouponApplication apply(String userCouponId, String couponId, String couponCode, String couponName,
                                   Terms terms, List<ShopBase> shops, CouponProperties properties) {
        long eligibleTotal = 0;
        for (ShopBase shop : shops) {
            if (eligible(shop, terms)) {
                eligibleTotal += shop.goodsBaseCents();
            }
        }
        if (eligibleTotal < terms.minSpendCents()) {
            long gap = terms.minSpendCents() - eligibleTotal;
            throw BusinessException.conflict("COUPON_MIN_SPEND_NOT_MET",
                    "还差 ¥" + plain(MoneyUnits.toYuan(gap)) + " 可用券（门槛 ¥"
                            + plain(MoneyUnits.toYuan(terms.minSpendCents())) + "）");
        }

        long[] raw = rawDiscounts(terms, shops, eligibleTotal);

        List<CouponApplication.ShopSettlement> settlements = new ArrayList<>(shops.size());
        long totalCoupon = 0;
        long totalMerchant = 0;
        long totalSubsidy = 0;
        for (int i = 0; i < shops.size(); i++) {
            ShopBase shop = shops.get(i);
            long discount = eligible(shop, terms) ? raw[i] : 0;
            discount = capForMerchant(shop, discount, terms.platformShareBps(), properties);
            Settlement settlement = settleShop(shop, discount, terms.platformShareBps(), properties);
            settlements.add(settlement.toApi(shop.merchantId()));
            totalCoupon += settlement.couponDiscountCents();
            totalMerchant += settlement.merchantDiscountCents();
            totalSubsidy += settlement.subsidyCents();
        }

        if (totalCoupon == 0) {
            // 门槛过了却减不了钱，只可能是配置把商家承担上限设成了 0。
            // 与其下一笔「有券但减 0 元」的订单，不如明确拒绝。
            throw BusinessException.conflict("COUPON_NOT_APPLICABLE", "这张券在当前订单上减不了钱");
        }

        return new CouponApplication(userCouponId, couponId, couponCode, couponName,
                MoneyUnits.toYuan(totalCoupon), MoneyUnits.toYuan(totalMerchant),
                MoneyUnits.toYuan(totalSubsidy), List.copyOf(settlements));
    }

    /** 无券结算：券减免为 0，但每店仍要留下平台佣金。 */
    static CouponApplication settle(List<ShopBase> shops, CouponProperties properties) {
        List<CouponApplication.ShopSettlement> settlements = shops.stream()
                .map(shop -> settleShop(shop, 0, 0, properties).toApi(shop.merchantId()))
                .toList();
        return CouponApplication.none(settlements);
    }

    private static boolean eligible(ShopBase shop, Terms terms) {
        return "PLATFORM".equals(terms.ownerType())
                || (terms.merchantId() != null && terms.merchantId() == shop.merchantId());
    }

    /** 先算「不考虑商家封顶」的逐店减免。 */
    private static long[] rawDiscounts(Terms terms, List<ShopBase> shops, long eligibleTotal) {
        long[] raw = new long[shops.size()];
        if (terms.discountType() == DiscountType.AMOUNT) {
            allocate(Math.min(terms.amountCents(), eligibleTotal), terms, shops, raw);
            return raw;
        }

        long total = 0;
        for (int i = 0; i < shops.size(); i++) {
            ShopBase shop = shops.get(i);
            if (!eligible(shop, terms)) continue;
            // 每店先向下取整，再按封顶重分配；不能用 BigDecimal 再逐店四舍五入。
            raw[i] = Math.min(shop.goodsBaseCents(),
                    shop.goodsBaseCents() * terms.percentBps() / 10000);
            total += raw[i];
        }
        if (terms.maxDiscountCents() != null && total > terms.maxDiscountCents()) {
            Arrays.fill(raw, 0);
            allocate(Math.min(terms.maxDiscountCents(), eligibleTotal), terms, shops, raw);
        }
        return raw;
    }

    /**
     * 按各店基数占比把 {@code target} 分到各店，最大余额法补齐余数。
     *
     * <p>{@code target <= Σ 基数} 由调用方保证，因此每店分到的不会超过自己的基数。
     */
    private static void allocate(long target, Terms terms, List<ShopBase> shops, long[] out) {
        if (target <= 0) return;
        long totalBase = 0;
        for (ShopBase shop : shops) {
            if (eligible(shop, terms)) totalBase += shop.goodsBaseCents();
        }
        if (totalBase <= 0) return;

        long used = 0;
        long[] remainder = new long[shops.size()];
        for (int i = 0; i < shops.size(); i++) {
            ShopBase shop = shops.get(i);
            if (!eligible(shop, terms)) continue;
            long numerator = target * shop.goodsBaseCents();
            out[i] = numerator / totalBase;
            remainder[i] = numerator % totalBase;
            used += out[i];
        }
        scatter(out, remainder, target - used, terms, shops);
    }

    private static void scatter(long[] out, long[] remainder, long leftover, Terms terms,
                                List<ShopBase> shops) {
        if (leftover <= 0) return;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < shops.size(); i++) {
            if (eligible(shops.get(i), terms)) order.add(i);
        }
        order.sort(Comparator.<Integer>comparingLong(i -> -remainder[i]).thenComparingInt(i -> i));
        for (int k = 0; k < leftover && k < order.size(); k++) {
            out[order.get(k)] += 1;
        }
    }

    /** 商家承担封顶：找到最大的 {@code d <= raw}，使商家承担不超过封顶。 */
    private static long capForMerchant(ShopBase shop, long raw, int platformShareBps,
                                       CouponProperties properties) {
        if (raw <= 0) return 0;
        long cap = shop.goodsBaseCents() * properties.getMerchantMaxBearBps() / 10000;
        if (merchantBorne(shop, raw, platformShareBps, properties) <= cap) return raw;
        long lo = 0;
        long hi = raw - 1;
        while (lo < hi) {
            long mid = (lo + hi + 1) / 2;
            if (merchantBorne(shop, mid, platformShareBps, properties) <= cap) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private static long merchantBorne(ShopBase shop, long discount, int platformShareBps,
                                       CouponProperties properties) {
        return settleShop(shop, discount, platformShareBps, properties).merchantDiscountCents();
    }

    /** 一家店的券分摊与结算。参数与返回值全部是分。 */
    private static Settlement settleShop(ShopBase shop, long discount, int platformShareBps,
                                         CouponProperties properties) {
        long goods = shop.goodsBaseCents();
        if (discount < 0 || discount > goods) {
            throw new IllegalStateException("券减免越界：" + discount + " / " + goods);
        }
        long fee = (goods - discount) * properties.getCommissionRateBps() / 10000;
        long nominalSubsidy = discount * platformShareBps / 10000;
        long subsidyCap = fee * properties.getPlatformSubsidyCapBps() / 10000;
        long subsidy = Math.min(nominalSubsidy, subsidyCap);
        long merchantDiscount = discount - subsidy;
        long merchantIncome = goods - discount + subsidy - fee;
        long platformIncome = fee - subsidy;
        if (platformIncome < 0 || merchantIncome < 0) {
            throw new IllegalStateException("结算为负：merchant=" + merchantIncome
                    + ", platform=" + platformIncome + "（discount=" + discount + "）");
        }
        return new Settlement(discount, merchantDiscount, subsidy, fee, merchantIncome, platformIncome);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
