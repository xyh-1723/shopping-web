package dev.learning.mall.coupon.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 优惠券的资金参数。<b>三个比例决定三角平衡</b>：
 *
 * <ul>
 *   <li>{@code commissionRateBps}：平台对每笔子订单收取的佣金比例，默认 8%；</li>
 *   <li>{@code merchantMaxBearBps}：商家单笔最多承担券减免的比例，默认 35%；</li>
 *   <li>{@code platformSubsidyCapBps}：平台补贴最多占到佣金的多少，默认 80%。</li>
 * </ul>
 *
 * <p>第三条是「平台不亏」的硬闸门：补贴一旦超过佣金，平台就是在倒贴发券。
 * 把它做成可配而不是写死，是为了运营能调档；但上限 10000 由校验守住——
 * 调到 100% 时平台仍然只是不赚，不会亏。
 */
@Validated
@ConfigurationProperties(prefix = "mall.coupon")
public class CouponProperties {

    /** 总开关：关掉后领券与用券都返回明确错误，而不是静默失效。 */
    private boolean enabled = true;

    @Min(0)
    @Max(10000)
    private int commissionRateBps = 800;

    @Min(0)
    @Max(10000)
    private int merchantMaxBearBps = 3500;

    @Min(0)
    @Max(10000)
    private int platformSubsidyCapBps = 8000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getCommissionRateBps() { return commissionRateBps; }
    public void setCommissionRateBps(int commissionRateBps) { this.commissionRateBps = commissionRateBps; }
    public int getMerchantMaxBearBps() { return merchantMaxBearBps; }
    public void setMerchantMaxBearBps(int merchantMaxBearBps) { this.merchantMaxBearBps = merchantMaxBearBps; }
    public int getPlatformSubsidyCapBps() { return platformSubsidyCapBps; }
    public void setPlatformSubsidyCapBps(int platformSubsidyCapBps) { this.platformSubsidyCapBps = platformSubsidyCapBps; }
}
