package dev.learning.mall.order.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 超时订单的关闭器。
 *
 * <p>它不是「回退库存」的实现——那份实现早就存在（关单时归还预占库存）。
 * 这里补的是<b>触发源</b>：以前只有用户自己点取消才会归还，用户不付款就走的话，
 * 库存会一直被扣着。定时任务只是把「时间到了」这件事变成一次关单调用。
 *
 * <p><b>每条订单一个独立事务</b>（调用带 {@code @Transactional} 的
 * {@link OrderTransactions#closeExpired(String, LocalDateTime)}）：一笔关不掉不该拖累
 * 同一轮的其它订单，否则一个坏数据就能让整批订单的库存永远回不来。
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    private final OrderRepository orders;
    private final OrderTransactions transactions;
    private final OrderProperties properties;
    private final Clock clock;
    /**
     * 最近一次扫描结束时仍未处理的积压量。
     *
     * <p>为什么是「持有值」而不是每次现算：{@link #closeExpired()} 已经把这个数算出来了，
     * 再为了指标去查一次库，等于把「算出来就丢」改成「算出来还再算一遍」。
     * Gauge 只负责把已有的值暴露出去。
     */
    private final AtomicLong lastBacklog = new AtomicLong(0L);

    public OrderExpiryService(OrderRepository orders, OrderTransactions transactions,
            OrderProperties properties, Clock clock, MeterRegistry registry) {
        this.orders = orders; this.transactions = transactions;
        this.properties = properties; this.clock = clock;
        Gauge.builder("mall.order.expiry.backlog", lastBacklog, value -> (double) value.get())
                .description("最近一次超时关单扫描结束时，仍到期未处理的待支付订单数")
                .register(registry);
    }

    /**
     * 关闭一批到期待支付订单。
     *
     * <p>幂等：已经关掉或已支付的订单会被带条件的更新挡下，重复扫描不会重复归还库存。
     */
    public CloseResult closeExpired() {
        // 开关关闭时没有发生扫描，积压量是「没测」而不是「为零」——所以这里刻意不更新 Gauge，
        // 让它保留上一轮的值。写 0 会把「排障中、没人扫」伪装成「一切正常」。
        if (!properties.isAutoCloseEnabled()) return new CloseResult(0, 0, 0, 0);
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> ids = orders.expiredPending(now, properties.getAutoCloseMaxPerScan());
        int closed = 0;
        int failed = 0;
        for (String id : ids) {
            try {
                if (transactions.closeExpired(id, now)) closed++;
            } catch (Exception e) {
                // 单笔失败只影响这一笔；它下一轮还会被选中，不会静默消失。
                failed++;
                log.warn("超时关单失败，下一轮会重试：order={} 原因={}", id, e.toString());
            }
        }
        // 关完再看一次剩余量：持续不为零说明扫描跟不上到期的速度（要么调大单轮上限，要么调密间隔）。
        int backlog = ids.isEmpty() ? 0 : orders.expiredBacklog(now);
        // 积压量持续不为零是「扫描跟不上到期速度」的唯一信号，但它只活在返回值里就没人看得见。
        // 写进 Gauge 才能被问出来：现在是 0 还是 200，是两件完全不同的事。
        lastBacklog.set(backlog);
        return new CloseResult(ids.size(), closed, failed, backlog);
    }

    /** 一轮扫描的结果，供测试断言与运营观测。 */
    public record CloseResult(int candidates, int closed, int failed, int backlog) {}
}
