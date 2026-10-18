package dev.learning.mall.order.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
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

    /**
     * 发货后多少天自动确认收货。
     *
     * <p>与 {@code paymentTimeoutMinutes} 同一个道理：它在发货那一刻算成
     * {@code auto_receive_at} <b>落库</b>，而不是每次扫描时用「当前时间 − 发货时间」算。
     * 否则运营把 10 天改成 7 天，所有在路上的订单会在改配置的那一瞬间一起到期——
     * 昨天才发货的订单今天就替用户点了收货。
     */
    @Min(1)
    private int autoReceiveDays = 10;
    /** 是否启用到期自动收货（关掉它 = 回到「买家不点收货就永远在途」的旧行为，只用于排障）。 */
    private boolean autoReceiveEnabled = true;
    /** 定时扫描间隔（毫秒）。 */
    @Min(1000)
    private long autoReceiveScanDelayMs = 60_000;
    /** 启动后首次扫描延迟（毫秒）：错开启动期。 */
    @Min(0)
    private long autoReceiveInitialDelayMs = 20_000;
    /** 单轮最多推进多少笔，给扫描本身封顶（与关单同理：被推进的订单会离开候选集，不存在队尾饥饿）。 */
    @Min(1)
    private int autoReceiveMaxPerScan = 200;

    /**
     * 退款（N2）的分流配置。
     *
     * <p><b>只有「时间」与「金额」在这里</b>，第三条判据「订单未发货」刻意<b>不</b>进来：
     * 它是硬规则，写在 {@link RefundService#routeOf} 里。把它做成可配，
     * 等于给「货已签收还能一键自助退款」留一个开关，而发生事故时开关一定是打开的
     * （因为运维改配置比改代码容易得多）。这与本项目一贯的写法相同：
     * 权限判定从不读配置，安全边界不进配置。
     *
     * <p><b>两个默认值怎么来的</b>：24 小时是「支付完成后一天之内可以反悔」的自然边界；
     * ¥200 取自既有配置——包邮门槛 ¥99、最高促销档 ¥199，取 200 作为
     * 「不超过最高促销档」的一档。两个值都要能被测试直接覆盖，
     * 于是 {@link RefundRoutingTest} 把它改成 1 小时 / ¥1 来验证<b>判定确实读了配置</b>。
     */
    @Valid
    private final Refund refund = new Refund();

    public Refund getRefund() {
        return refund;
    }

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
    public int getAutoReceiveDays() { return autoReceiveDays; }
    public void setAutoReceiveDays(int autoReceiveDays) { this.autoReceiveDays = autoReceiveDays; }
    public boolean isAutoReceiveEnabled() { return autoReceiveEnabled; }
    public void setAutoReceiveEnabled(boolean autoReceiveEnabled) { this.autoReceiveEnabled = autoReceiveEnabled; }
    public long getAutoReceiveScanDelayMs() { return autoReceiveScanDelayMs; }
    public void setAutoReceiveScanDelayMs(long autoReceiveScanDelayMs) { this.autoReceiveScanDelayMs = autoReceiveScanDelayMs; }
    public long getAutoReceiveInitialDelayMs() { return autoReceiveInitialDelayMs; }
    public void setAutoReceiveInitialDelayMs(long autoReceiveInitialDelayMs) { this.autoReceiveInitialDelayMs = autoReceiveInitialDelayMs; }
    public int getAutoReceiveMaxPerScan() { return autoReceiveMaxPerScan; }
    public void setAutoReceiveMaxPerScan(int autoReceiveMaxPerScan) { this.autoReceiveMaxPerScan = autoReceiveMaxPerScan; }

    /**
     * 退款配置。
     *
     * <p>字段<b>刻意只有三个有消费者</b>：没有 {@code max-active-per-order} 那个键，
     * 因为「同一订单只能有一笔在途退款」已经由数据库的 {@code uq_refund_inflight}
     * 唯一索引强制保证了——做成配置项，就变成一个改了也不生效的旋钮（比没有更坏）。
     */
    public static class Refund {

        /** 总开关：关掉它 = 回到「付完钱就是终态」的旧行为（仅用于排障与演示对比）。 */
        private boolean enabled = true;

        /**
         * 无理由退款窗口：支付完成后多少小时内可自助退。
         *
         * <p>按 {@code paid_at} 计，<b>不是</b> {@code created_at}——
         * 后者是下单时刻，那时候钱还没进来，谈不上退。
         */
        @Min(1)
        private int autoRefundWindowHours = 24;

        /**
         * 无理由退款的单笔金额上限（含边界）：超过就要商家点头。
         *
         * <p>单位是「元」，与商品价格、订单金额同一口径；
         * 需要比较大小时一律经 {@code MoneyUnits.toCents} 换算成整数分再比。
         */
        @DecimalMin("0.01")
        private BigDecimal autoRefundMaxAmount = new BigDecimal("200.00");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getAutoRefundWindowHours() { return autoRefundWindowHours; }
        public void setAutoRefundWindowHours(int autoRefundWindowHours) { this.autoRefundWindowHours = autoRefundWindowHours; }
        public BigDecimal getAutoRefundMaxAmount() { return autoRefundMaxAmount; }
        public void setAutoRefundMaxAmount(BigDecimal autoRefundMaxAmount) { this.autoRefundMaxAmount = autoRefundMaxAmount; }
    }
}
