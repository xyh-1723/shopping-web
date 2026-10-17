package dev.learning.mall.assistant.harness;

import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.common.BusinessException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 助手并发闸门——多用户同时提问时的资源与顺序保护。
 *
 * <p><b>限流与并发的区别（都要有，缺一不可）</b>：{@code AssistantRateLimiter} 管的是
 * 「单位时间发了多少条」（频次），本类管的是「此刻有多少条正在被处理」（在途数）。
 * 只限频次挡不住「同一秒发出 20 条、20 条同时在途」——而每一条在途都占着一个执行线程、
 * 一次真实的大模型调用（= 真实花费）和一次数据库连接借还。
 *
 * <p>三道闸门按成本从低到高依次开合：
 *
 * <ol>
 *   <li><b>全局在途上限</b>：保护整个应用。超过说明系统已经打满，新请求应快速失败（503），
 *       而不是排队把队列堵死、让所有请求一起超时。</li>
 *   <li><b>每用户在途上限</b>：防止单个用户开多个会话把全局额度占满——这是最常见的
 *       「一个人拖垮所有人」路径。超过返回 429。</li>
 *   <li><b>会话串行</b>：同一会话同一时刻只允许一个轮次在执行，第二个直接 409。
 *       这不只是限流，更是<b>正确性要求</b>：同一会话并发两个轮次会各自读到同一份历史快照，
 *       然后各自追加消息，导致对话历史顺序错乱、模型上下文互相覆盖
 *       （典型表现是「模型答非所问，因为它没看到你刚发的那条」）。</li>
 * </ol>
 *
 * <p><b>关于锁表的取舍</b>：会话锁与用户计数都「只增不减」。安全地移除一个仍在被使用的条目
 * 需要引入第二把锁和引用计数，而收益只是几十字节——在并发路径上正确性优先于微小的内存节省。
 * 教学与单机场景下条数等于「活跃会话数/用户数」，量级很小；生产环境若要长期运行，
 * 应换成带过期策略的本地缓存（如 Caffeine）或分布式锁（如 Redis）。
 */
@Component
public class AssistantConcurrencyGate {

    /** 闸门当前状态，供测试断言与运维观测。 */
    public record Stats(int globalInFlight, int globalLimit, long busyRejections) {}

    private static final String RULE_GLOBAL = "CONCURRENCY_GLOBAL";
    private static final String RULE_USER = "CONCURRENCY_USER";
    private static final String RULE_CONVERSATION = "CONVERSATION_BUSY";

    private final AssistantProperties properties;
    private final AssistantAudit audit;

    private final AtomicInteger globalInFlight = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> userInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();
    private final AtomicLong busyRejections = new AtomicLong();

    public AssistantConcurrencyGate(AssistantProperties properties, AssistantAudit audit) {
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * 申请一个轮次的执行许可。三道闸门任一不通过就抛业务异常，调用方不必自己判空。
     * 返回的 {@link Lease} 必须在同一线程上 close（try-with-resources）。
     */
    public Lease enter(String username, String conversationId) {
        AssistantProperties.Concurrency config = properties.getConcurrency();

        // —— 闸门 1：全局在途 ——
        int limit = Math.max(1, config.getMaxInFlightGlobal());
        if (globalInFlight.incrementAndGet() > limit) {
            globalInFlight.decrementAndGet();
            busyRejections.incrementAndGet();
            reject(username, conversationId, RULE_GLOBAL,
                    "全局在途已达上限 " + limit);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_BUSY",
                    "助手当前请求较多，请稍后再试。");
        }

        // —— 闸门 2：每用户在途 ——
        AtomicInteger userCount = userInFlight.computeIfAbsent(username, k -> new AtomicInteger());
        int perUserLimit = Math.max(1, config.getMaxInFlightPerUser());
        if (userCount.incrementAndGet() > perUserLimit) {
            userCount.decrementAndGet();
            globalInFlight.decrementAndGet();
            busyRejections.incrementAndGet();
            reject(username, conversationId, RULE_USER,
                    "用户同时在途已达上限 " + perUserLimit);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_IN_FLIGHT",
                    "你同时进行的对话有点多，请等前面几条回答完再发。");
        }

        // —— 闸门 3：会话串行 ——
        ReentrantLock conversationLock = null;
        if (config.isSerializePerConversation() && conversationId != null) {
            ReentrantLock candidate = conversationLocks.computeIfAbsent(conversationId,
                    k -> new ReentrantLock());
            if (!candidate.tryLock()) {
                userCount.decrementAndGet();
                globalInFlight.decrementAndGet();
                busyRejections.incrementAndGet();
                reject(username, conversationId, RULE_CONVERSATION,
                        "同一会话已有轮次在执行");
                throw new BusinessException(HttpStatus.CONFLICT, "CONVERSATION_BUSY",
                        "这条消息还在处理中，请等它回答完再发下一条。");
            }
            conversationLock = candidate;
        }

        return new Lease(username, conversationId, conversationLock, userCount);
    }

    /** 当前状态快照。 */
    public Stats stats() {
        return new Stats(globalInFlight.get(),
                Math.max(1, properties.getConcurrency().getMaxInFlightGlobal()),
                busyRejections.get());
    }

    /** 某用户当前的 在途轮次数（测试与排障用）。 */
    public int inFlightOf(String username) {
        AtomicInteger counter = userInFlight.get(username);
        return counter == null ? 0 : counter.get();
    }

    private void reject(String username, String conversationId, String ruleId, String detail) {
        audit.record(username, conversationId, AssistantAudit.STAGE_RATE, "BLOCK",
                ruleId, "LLM10", "并发闸门拒绝：" + detail);
    }

    /**
     * 执行许可。{@code close()} 幂等，按「会话锁 → 用户计数 → 全局计数」逆序释放。
     *
     * <p>必须在<b>申请时的同一线程</b>关闭：会话锁是 {@link ReentrantLock}，
     * 跨线程释放会抛 IllegalMonitorStateException。所以这里的结构是
     * 「谁申请谁归还」，而不是把手柄传来传去。
     */
    public final class Lease implements AutoCloseable {

        private final String username;
        private final String conversationId;
        private final ReentrantLock conversationLock;
        private final AtomicInteger userCounter;
        private boolean closed;

        private Lease(String username, String conversationId, ReentrantLock conversationLock,
                      AtomicInteger userCounter) {
            this.username = username;
            this.conversationId = conversationId;
            this.conversationLock = conversationLock;
            this.userCounter = userCounter;
        }

        public String username() { return username; }

        public String conversationId() { return conversationId; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (conversationLock != null && conversationLock.isHeldByCurrentThread()) {
                conversationLock.unlock();
            }
            userCounter.decrementAndGet();
            globalInFlight.decrementAndGet();
        }
    }
}
