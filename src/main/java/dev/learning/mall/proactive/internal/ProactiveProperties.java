package dev.learning.mall.proactive.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 主动服务的配置。
 *
 * <p>{@code scanDelayMs} 决定了「降价提醒有多及时」与「扫描有多费」之间的取舍。
 * 默认 5 分钟：对一个教学项目足够及时，对数据库几乎没有压力。
 * 真实电商里这个值不会靠调大轮询间隔来省资源，而是由「商品变更事件」驱动——
 * 本项目也给了这条路径（改价/改库存后事件触发一次即时扫描），定时扫描只是兜底。
 *
 * <p>写成可变类而不是 record，是为了让开关能在运行期与测试里被翻转
 * （record 绑定后不可变，测试就没法在不重建上下文的前提下验证「关掉之后不扫描」）。
 */
@Validated
@ConfigurationProperties(prefix = "mall.proactive")
public class ProactiveProperties {

    /** 是否启用主动服务（定时扫描 + 商品变更事件触发扫描）。 */
    private boolean enabled = true;
    /** 定时扫描间隔（毫秒）。 */
    @Min(1000)
    private long scanDelayMs = 300_000;
    /** 启动后首次扫描的延迟（毫秒）：错开启动期，避免和应用初始化抢资源。 */
    @Min(0)
    private long initialDelayMs = 30_000;
    /**
     * 单次扫描最多处理多少条候选，给扫描本身封顶。
     *
     * <p>它<b>不是</b>「能服务多少人」的上限：扫描带游标（最近扫过的排到队尾），
     * 多出来的人只是多等一轮，不会被跳过。超出后日志会出现积压告警，按需调大即可。
     */
    @Min(1)
    private int maxAlertsPerScan = 500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getScanDelayMs() { return scanDelayMs; }
    public void setScanDelayMs(long scanDelayMs) { this.scanDelayMs = scanDelayMs; }
    public long getInitialDelayMs() { return initialDelayMs; }
    public void setInitialDelayMs(long initialDelayMs) { this.initialDelayMs = initialDelayMs; }
    public int getMaxAlertsPerScan() { return maxAlertsPerScan; }
    public void setMaxAlertsPerScan(int maxAlertsPerScan) { this.maxAlertsPerScan = maxAlertsPerScan; }
}
