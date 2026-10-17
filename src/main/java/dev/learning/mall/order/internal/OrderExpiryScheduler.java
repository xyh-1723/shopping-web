package dev.learning.mall.order.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时订单的定时关闭。
 *
 * <p>与主动服务的做法一致：<b>开关判断集中在服务里</b>，这里不重复判 {@code enabled}，
 * 这样「关掉之后不扫描」这条规则只需要维护一处。
 *
 * <p>为什么是定时扫描而不是「下单时发一个延迟消息」：延迟消息在进程重启后需要有地方存着，
 * 那又是一套持久化与恢复机制；而扫描的输入是数据库里的事实（status + expires_at），
 * 它天然容忍重启、容忍漏跑、容忍重复跑——代价只是最多晚一个扫描间隔。
 * 对「库存别被占死」这件事而言，晚一分钟可以接受，少一套需要恢复的状态机更值。
 */
@Component
class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderExpiryService expiry;

    OrderExpiryScheduler(OrderExpiryService expiry) { this.expiry = expiry; }

    @Scheduled(fixedDelayString = "${mall.order.auto-close-scan-delay-ms:60000}",
            initialDelayString = "${mall.order.auto-close-initial-delay-ms:20000}")
    void scheduledClose() {
        closeQuietly("定时扫描");
    }

    private void closeQuietly(String source) {
        try {
            OrderExpiryService.CloseResult result = expiry.closeExpired();
            if (result.closed() > 0 || result.failed() > 0) {
                log.info("超时关单[{}]：候选 {} 笔，关闭 {} 笔、失败 {} 笔，剩余积压 {} 笔",
                        source, result.candidates(), result.closed(), result.failed(), result.backlog());
            } else if (result.backlog() > 0) {
                log.warn("超时关单[{}]：本轮未关闭任何订单，但仍有 {} 笔到期未处理", source, result.backlog());
            }
        } catch (Exception e) {
            // 定时任务绝不能让任何请求失败，也不能让调度线程死掉。
            log.warn("超时关单扫描失败[{}]：{}", source, e.toString());
        }
    }
}
