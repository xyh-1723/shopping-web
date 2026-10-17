package dev.learning.mall.order.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 订单模块的配置。
 *
 * <p>{@code paymentTimeoutMinutes} 是「下单时承诺给用户的支付时限」。它在下单那一刻
 * <b>落库成 expires_at</b>，而不是每次扫描时用「当前时间 - 创建时间」算。
 * 区别很实际：运营把 15 分钟改成 30 分钟后，用配置算会让所有存量订单的截止时间一起跳变——
 * 用户明明看到「还剩 2 分钟」，刷新一下变成「还剩 17 分钟」，而与此同时另一个用户
 * 看到的可能是相反的跳变。落库才能保证「当初承诺的那个时刻」不会中途改变。
 *
 * <p>写成可变类而不是 record，是为了让开关能在运行期与测试里被翻转。
 */
@Validated
@ConfigurationProperties(prefix = "mall.order")
public class OrderProperties {

    /** 下单后多久未支付视为超时。 */
    @Min(1)
    private int paymentTimeoutMinutes = 15;
    /** 是否启用超时自动关闭（关掉它 = 回到「库存被占死」的旧行为，只用于排障）。 */
    private boolean autoCloseEnabled = true;
    /** 定时扫描间隔（毫秒）：超时关闭晚 1 分钟，库存就多被占 1 分钟。 */
    @Min(1000)
    private long autoCloseScanDelayMs = 60_000;
    /** 启动后首次扫描延迟（毫秒）：错开启动期。 */
    @Min(0)
    private long autoCloseInitialDelayMs = 20_000;
    /**
     * 单轮最多关闭多少笔，给扫描本身封顶。
     *
     * <p>这里不需要像关注扫描那样引入游标：被关闭的订单会离开结果集（状态已不是 PENDING），
     * 队列天然收敛，不存在队尾饥饿。多出来的只是多等一轮。
     */
    @Min(1)
    private int autoCloseMaxPerScan = 200;

    public int getPaymentTimeoutMinutes() { return paymentTimeoutMinutes; }
    public void setPaymentTimeoutMinutes(int paymentTimeoutMinutes) { this.paymentTimeoutMinutes = paymentTimeoutMinutes; }
    public boolean isAutoCloseEnabled() { return autoCloseEnabled; }
    public void setAutoCloseEnabled(boolean autoCloseEnabled) { this.autoCloseEnabled = autoCloseEnabled; }
    public long getAutoCloseScanDelayMs() { return autoCloseScanDelayMs; }
    public void setAutoCloseScanDelayMs(long autoCloseScanDelayMs) { this.autoCloseScanDelayMs = autoCloseScanDelayMs; }
    public long getAutoCloseInitialDelayMs() { return autoCloseInitialDelayMs; }
    public void setAutoCloseInitialDelayMs(long autoCloseInitialDelayMs) { this.autoCloseInitialDelayMs = autoCloseInitialDelayMs; }
    public int getAutoCloseMaxPerScan() { return autoCloseMaxPerScan; }
    public void setAutoCloseMaxPerScan(int autoCloseMaxPerScan) { this.autoCloseMaxPerScan = autoCloseMaxPerScan; }
}
