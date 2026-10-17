package dev.learning.mall.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 计时切面：把「这个操作花了多久」从业务方法里彻底挪出来。
 *
 * <p>它同时做两件事，且两件事都不可省：
 *
 * <ol>
 *   <li><b>打日志</b>：单机排障时人眼可读，且能顺带带上线程名（并发问题靠它定位）。</li>
 *   <li><b>记指标</b>（{@code mall.operation.duration}）：日志是一次性的、查不到时序的，
 *       「下单耗时是不是在变慢」这种问题只能靠聚合指标回答。所以同一个耗时在这里
 *       既进日志也进注册表——它们服务的不是同一个问题。</li>
 * </ol>
 */
@Aspect
@Component
public class TimingAspect {
    private static final Logger log = LoggerFactory.getLogger(TimingAspect.class);

    private final MeterRegistry registry;
    /**
     * Timer 按操作名缓存一次。{@code register()} 本身是幂等的（同 id 返回已有实例），
     * 但每次调用都要构造 Builder 并做一次注册表查找，而这是每次业务调用都会走的路径。
     */
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public TimingAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(observed)")
    public Object time(ProceedingJoinPoint point, Observed observed) throws Throwable {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object result = point.proceed();
            success = true;
            return result;
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // 不记录密码、请求正文等敏感数据；无论成功失败都记录耗时。
            log.info("operation={} success={} elapsedMs={} thread={}", observed.value(), success,
                    elapsedMs, Thread.currentThread().getName());
            timerFor(observed.value()).record(elapsedMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 取（或首次创建）该操作名对应的 Timer。 */
    private Timer timerFor(String operation) {
        return timers.computeIfAbsent(operation, op -> Timer.builder("mall.operation.duration")
                .tag("operation", op)
                .description("被 @Observed 标注的业务操作耗时")
                .register(registry));
    }
}
