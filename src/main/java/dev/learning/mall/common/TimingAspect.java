package dev.learning.mall.common;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TimingAspect {
    private static final Logger log = LoggerFactory.getLogger(TimingAspect.class);
    @Around("@annotation(observed)")
    public Object time(ProceedingJoinPoint point, Observed observed) throws Throwable {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object result = point.proceed();
            success = true;
            return result;
        } finally {
            // 不记录密码、请求正文等敏感数据；无论成功失败都记录耗时。
            log.info("operation={} success={} elapsedMs={} thread={}", observed.value(), success,
                    (System.nanoTime() - start) / 1_000_000, Thread.currentThread().getName());
        }
    }
}
