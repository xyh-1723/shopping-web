package dev.learning.mall.pricing.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商城促销与运费规则。
 *
 * <p>这些数字放在配置里而不写死，是因为它们是<b>运营参数</b>——大促改门槛、换运费策略
 * 是常事。但更重要的原因是另一条：助手在对话里报出的优惠金额，必须和下单结算时算出的
 * 完全一致。既然规则只有一份（这里），「对话里承诺 10 元、结算只减 5 元」这种事故
 * 在结构上就不可能发生。助手绝不允许自己「心算」优惠。
 *
 * <p>本类原本住在 {@code cart.internal}，随 {@link CartPricing} 一起搬到定价包——
 * 规则是定价算法的输入，理应和算法同处一个包，而不是留在某一个调用方家里。
 */
@ConfigurationProperties("mall.shopping")
public class ShoppingProperties {

    /** 满多少包邮。 */
    private BigDecimal freeShippingThreshold = new BigDecimal("99");
    /** 未达包邮门槛时的运费。 */
    private BigDecimal shippingFee = new BigDecimal("8");
    /** 满减档位。默认只取最高一档，不叠加。 */
    private List<Promotion> promotions = List.of(
            new Promotion(new BigDecimal("99"), new BigDecimal("10")),
            new Promotion(new BigDecimal("199"), new BigDecimal("25")));

    public BigDecimal getFreeShippingThreshold() { return freeShippingThreshold; }
    public void setFreeShippingThreshold(BigDecimal freeShippingThreshold) {
        this.freeShippingThreshold = freeShippingThreshold;
    }
    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal shippingFee) { this.shippingFee = shippingFee; }
    public List<Promotion> getPromotions() { return promotions; }
    public void setPromotions(List<Promotion> promotions) { this.promotions = promotions; }

    /** 档位按门槛升序，取用时从高往低找第一档满足的。 */
    public record Promotion(BigDecimal threshold, BigDecimal discount) {}

    public List<Promotion> sortedPromotions() {
        List<Promotion> copy = new ArrayList<>(promotions == null ? List.of() : promotions);
        copy.sort(Comparator.comparing(Promotion::threshold));
        return List.copyOf(copy);
    }
}
