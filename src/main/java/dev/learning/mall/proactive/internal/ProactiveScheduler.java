package dev.learning.mall.proactive.internal;

import dev.learning.mall.catalog.api.ProductChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 主动服务的触发器：定时扫描 + 改价事件即时扫描。
 *
 * <p><b>为什么要两条路</b>：
 * <ul>
 *   <li><b>定时扫描</b>是兜底——它保证「不管价格是怎么变的（改价、导入、运维手改 SQL），
 *       最终都会被注意到」。没有它，任何绕过改价接口的变更都会静默漏掉提醒。</li>
 *   <li><b>事件触发</b>是及时性——关注了商品的用户不该为了一个 5 分钟的轮询间隔而等。
 *       有了它，改价接口可以做到「改完立刻提醒」，而定时扫描只负责收拾漏网的。</li>
 * </ul>
 *
 * <p>这与消息模块处理订单事件的分工是同一种思路：<b>主链路给及时性，兜底任务给完整性</b>。
 * 只做前者会漏，只做后者会让用户等。
 */
@Component
class ProactiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProactiveScheduler.class);

    private final WatchService watchService;

    ProactiveScheduler(WatchService watchService) {
        this.watchService = watchService;
    }

    /**
     * 定时兜底扫描。不在这里判 {@code enabled}：判断集中在 {@link WatchService#scan()},
     * 这样「关掉之后不扫描」这条规则只有一个地方需要维护。
     */
    @Scheduled(fixedDelayString = "${mall.proactive.scan-delay-ms:300000}",
            initialDelayString = "${mall.proactive.initial-delay-ms:30000}")
    void scheduledScan() {
        scanQuietly("定时扫描");
    }

    /**
     * 商品变更后立刻扫一次。必须等事务提交（AFTER_COMMIT）：在事务内被唤醒时，
     * 扫描走的是另一个连接，读到的还是提交前的旧值——于是出现「改了价却提醒降价」。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onProductChanged(ProductChangedEvent event) {
        scanQuietly("商品变更(" + event.change() + " #" + event.productId() + ")");
    }

    private void scanQuietly(String source) {
        try {
            WatchService.ScanResult result = watchService.scan();
            if (result.priceAlerts() > 0 || result.stockAlerts() > 0) {
                log.info("主动提醒[{}]：本轮处理 {} 条，降价 {} 条、到货 {} 条，还有 {} 条排队",
                        source, result.scanned(), result.priceAlerts(), result.stockAlerts(),
                        result.remaining());
            } else if (result.remaining() > 0) {
                // 一段时间里持续出现这条告警，说明扫描窗口跟不上候选的增长速度：
                // 要么调大 max-alerts-per-scan，要么把扫描间隔调密。
                // 有了它，「有一批用户一直没被扫到」这件事不再是沉默的。
                log.warn("主动提醒[{}]：本轮未产生提醒，但仍有 {} 条关注在排队等待扫描",
                        source, result.remaining());
            }
        } catch (Exception e) {
            // 扫描失败只影响「这次没提醒」，绝不能影响调用它的那条请求（比如店员改价）。
            log.warn("主动提醒扫描失败[{}]：{}", source, e.toString());
        }
    }
}
