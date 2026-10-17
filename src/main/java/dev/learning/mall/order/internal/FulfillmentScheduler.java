package dev.learning.mall.order.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动收货的定时触发。
 *
 * <p>与 {@link OrderExpiryScheduler} 同一条约定：<b>开关判断集中在服务里</b>，这里不重复判
 * {@code enabled}，这样「关掉之后不扫描」这条规则只需要维护一处。
 *
 * <p>为什么是定时扫描而不是「发货时发一个延迟消息」：延迟消息在进程重启后需要有地方存着，
 * 那又是一套持久化与恢复机制；而扫描的输入是数据库里的事实
 * （{@code fulfillment_status} + {@code auto_receive_at}），它天然容忍重启、漏跑、重复跑——
 * 代价只是最多晚一个扫描间隔。对「买家不点收货也要能结单」这件事而言，
 * 晚一小时可以接受，少一套需要恢复的状态机更值。
 *
 * <p>扫描到的每一笔由 {@link FulfillmentService#autoReceiveScan()} 在自己的事务里走完
 * 「收货 → 完成」两步，因此这里不需要任何补偿逻辑。
 */
@Component
class FulfillmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentScheduler.class);

    private final FulfillmentService fulfillment;

    FulfillmentScheduler(FulfillmentService fulfillment) { this.fulfillment = fulfillment; }

    @Scheduled(fixedDelayString = "${mall.order.auto-receive-scan-delay-ms:60000}",
            initialDelayString = "${mall.order.auto-receive-initial-delay-ms:20000}")
    void scheduledAutoReceive() {
        try {
            FulfillmentService.AutoReceiveResult result = fulfillment.autoReceiveScan();
            if (result.received() > 0 || result.completed() > 0 || result.failed() > 0) {
                log.info("自动收货：候选 {} 笔，收货 {} 笔、完成 {} 笔、失败 {} 笔",
                        result.candidates(), result.received(), result.completed(), result.failed());
            }
        } catch (Exception e) {
            // 定时任务绝不能让任何请求失败，也不能让调度线程死掉。
            log.warn("自动收货扫描失败：{}", e.toString());
        }
    }
}
