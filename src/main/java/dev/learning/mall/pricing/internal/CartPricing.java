package dev.learning.mall.pricing.internal;

import dev.learning.mall.pricing.api.Pricer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 结算试算：小计 → 满减 → 运费 → 应付。
 *
 * <p><b>唯一的金额计算入口。</b>购物车页面、下单接口、助手对话三处都调用它。
 * 这句话曾经是不成立的——下单接口自己裸求和，把满减与运费全丢了，
 * 而 257 个测试没抓到，因为没有任何一条断言同时走过两条链路。
 * 现在它由 {@code CheckoutAmountConsistencyTest} 守住。
 *
 * <p>规则本身刻意保持简单（满减取最高一档、不叠加、按折后金额判断包邮），
 * 因为这是一个教学项目：把规则讲清楚，比把规则堆复杂更有价值。
 */
@Component
public class CartPricing implements Pricer {

    private final ShoppingProperties properties;

    public CartPricing(ShoppingProperties properties) {
        this.properties = properties;
    }

    @Override
    public Quote quote(List<Line> lines) {
        if (lines.isEmpty()) {
            return new Quote(scale(BigDecimal.ZERO), scale(BigDecimal.ZERO), scale(BigDecimal.ZERO),
                    scale(BigDecimal.ZERO), List.of());
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Line line : lines) {
            subtotal = subtotal.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
        }
        subtotal = scale(subtotal);

        List<ShoppingProperties.Promotion> tiers = properties.sortedPromotions();
        ShoppingProperties.Promotion applied = null;
        for (int i = tiers.size() - 1; i >= 0; i--) {
            if (subtotal.compareTo(tiers.get(i).threshold()) >= 0) {
                applied = tiers.get(i);
                break;
            }
        }
        BigDecimal discount = scale(applied == null ? BigDecimal.ZERO : applied.discount());
        // 包邮按「折后金额」判断：用户感知的门槛就是实际付的金额，而不是被优惠抬高的原价。
        BigDecimal afterDiscount = subtotal.subtract(discount);
        BigDecimal freeShipping = properties.getFreeShippingThreshold();
        boolean freeShip = afterDiscount.compareTo(freeShipping) >= 0;
        BigDecimal shippingFee = scale(freeShip ? BigDecimal.ZERO : properties.getShippingFee());
        BigDecimal payable = scale(afterDiscount.add(shippingFee));

        List<String> notes = new ArrayList<>();
        if (applied != null) {
            notes.add("已享满 " + plain(applied.threshold()) + " 减 " + plain(applied.discount()));
        }
        if (!freeShip) {
            notes.add("还差 " + plain(freeShipping.subtract(afterDiscount)) + " 元包邮（当前运费 "
                    + plain(shippingFee) + " 元）");
        } else {
            notes.add("已包邮");
        }
        // 凑单提示：下一个更高档位还差多少。这是导购场景里转化最高的一句话。
        ShoppingProperties.Promotion next = null;
        for (ShoppingProperties.Promotion tier : tiers) {
            if (applied == null || tier.threshold().compareTo(applied.threshold()) > 0) {
                next = tier;
                break;
            }
        }
        if (next != null) {
            notes.add("再买 " + plain(next.threshold().subtract(subtotal)) + " 元可减 "
                    + plain(next.discount()));
        }
        return new Quote(subtotal, discount, shippingFee, payable, List.copyOf(notes));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 金额展示去掉多余的零：99.00 显示成 99。 */
    private static String plain(BigDecimal value) {
        return scale(value).stripTrailingZeros().toPlainString();
    }
}
