package dev.learning.mall.wallet.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 内置钱包的配置：初始额度与演示充值的三条限制。
 *
 * <p><b>为什么不写进迁移脚本？</b>
 * 迁移读不到 {@code application.yml}，在 SQL 里写死 {@code 50000}
 * 就等于建立了第二个真相来源：改配置的人以为改了额度，已经开户的用户还是旧额度，
 * 新人却是新额度——这个差异不报错，只表现为「我的钱包比你多 50 块」。
 * 所以 V16 迁移<b>一个 INSERT 都不写</b>，初始额度走这里的配置 + 懒开户。
 *
 * <p><b>为什么充值必须有上限？</b>
 * 能无限充值的钱包会让「余额不足」这条约束永远走不到，
 * 于是扣款失败分支、余额校验、以及「退款到底有没有真的把钱退回去」全部形同虚设。
 * 上限独立于初始额度存在，因此即便有人把初始额度调大，也不等于能无限续杯。
 *
 * <p>写成可变类而不是 record，是为了让开关能在运行期与测试里被翻转。
 */
@Validated
@ConfigurationProperties(prefix = "mall.wallet")
public class WalletProperties {

    /**
     * 演示初始额度（分）：首次触达钱包时开户并一次性授予。
     *
     * <p>单位一律是「分」：钱包内部不存在 {@code BigDecimal}，
     * 元→分只允许经过 {@code pricing.api.MoneyUnits}。
     */
    @Min(0)
    private long initialGrantCents = 50_000;

    /**
     * 演示充值总开关。默认开：它有真实 UI 消费者（充值按钮），不是死代码。
     * 关掉它用于演示「没钱了」这个教学场景。
     */
    private boolean topupEnabled = true;

    /** 演示充值的固定档位（分）。它没有 {@code amount} 入参，「刷一波充值」不可能发生。 */
    @Min(1)
    private long topupAmountCents = 10_000;

    /** 累计授予上限（分，含初始额度）。达到后 {@code topUp} 一律拒绝。 */
    @Min(1)
    private long topupCeilingCents = 100_000;

    public long getInitialGrantCents() { return initialGrantCents; }
    public void setInitialGrantCents(long initialGrantCents) { this.initialGrantCents = initialGrantCents; }
    public boolean isTopupEnabled() { return topupEnabled; }
    public void setTopupEnabled(boolean topupEnabled) { this.topupEnabled = topupEnabled; }
    public long getTopupAmountCents() { return topupAmountCents; }
    public void setTopupAmountCents(long topupAmountCents) { this.topupAmountCents = topupAmountCents; }
    public long getTopupCeilingCents() { return topupCeilingCents; }
    public void setTopupCeilingCents(long topupCeilingCents) { this.topupCeilingCents = topupCeilingCents; }
}
