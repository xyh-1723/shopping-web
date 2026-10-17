package dev.learning.mall.assistant.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 每用户滑动窗口限流（对应 LLM10 无限消耗）。
 * 目的很直接：一次请求就是一次 token 账单，也是模型侧的一次负载；
 * 没有上限的入口既容易被刷成本，也容易被当拒绝服务入口。
 *
 * <p>单机内存实现，教学项目足够；分布式部署要换成 Redis 计数器。
 */
@Component
public class AssistantRateLimiter {

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** @return 允许则返回 null，否则返回建议的等待秒数。 */
    public Long tryAcquire(String username, int perMinute) {
        int limit = Math.max(1, perMinute);
        Instant now = Instant.now();
        Instant floor = now.minus(Duration.ofMinutes(1));
        Deque<Instant> window = hits.computeIfAbsent(username, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(floor)) window.pollFirst();
            if (window.size() >= limit) {
                Instant oldest = window.peekFirst();
                long wait = Math.max(1, Duration.between(now, oldest.plus(Duration.ofMinutes(1))).toSeconds() + 1);
                return wait;
            }
            window.addLast(now);
            return null;
        }
    }

    public int used(String username, int perMinute) {
        Deque<Instant> window = hits.get(username);
        if (window == null) return 0;
        Instant floor = Instant.now().minus(Duration.ofMinutes(1));
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(floor)) window.pollFirst();
            return window.size();
        }
    }

    public void reset(String username) {
        hits.remove(username);
    }
}
