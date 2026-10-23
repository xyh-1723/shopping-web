package dev.learning.mall;

import dev.learning.mall.assistant.internal.AssistantProperties;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 一个「先攒着、等测试放行」的助手轮次执行器（测试专用）。
 *
 * <p><b>它解决的是一次真实的并发写冲突，不是让测试等久一点。</b>
 * {@code MockHttpServletResponse} 的响应头表是 {@code LinkedCaseInsensitiveMap}
 * 包着一个<b>普通 {@code LinkedHashMap}，不是线程安全的</b>。SSE 端点把轮次提交到线程池后，
 * 就有两条线程同时改这张表：
 * <ol>
 *   <li><b>worker 线程</b>：flush 事件时走 {@code ServletServerHttpResponse.writeHeaders()}
 *       → {@code response.addHeader(...)}；</li>
 *   <li><b>请求线程</b>：handler 返回后 filter 链收尾，
 *       {@code HeaderWriterFilter.doHeadersAfter} 补写 {@code X-Frame-Options} 等安全头
 *       → {@code response.setHeader(...)}。</li>
 * </ol>
 * “worker 的第一次 flush”与“请求线程退出 filter 链”这两个窗口几乎重合，
 * 于是 {@code HashMap.computeIfAbsent} 会抛 {@code ConcurrentModificationException}。
 *
 * <p><b>这个竞争有两种外观，一度让人误判成两个不同的 flake</b>：
 * <ul>
 *   <li>CME 落在<b>请求线程</b> → 直接从 {@code mvc.perform(...)} 抛出去，用例毫秒级就红；</li>
 *   <li>CME 落在<b>worker 线程</b> → 被 {@code TurnStreamSink.emit} 的 {@code catch} 吞掉，
 *       并置上「已关闭」标记，于是 {@code close()} 不再调用 {@code emitter.complete()}，
 *       异步结果永远不来，表现为 {@code getAsyncResult(...)} <b>超时</b>。</li>
 * </ul>
 * 第二种外观尤其危险：它长得像「机器慢」，很容易被误判成超时不够而一味加大超时——
 * 但根因是并发写，加大超时只是把窗口挪大。
 *
 * <p><b>为什么归测试侧，而不是端点的缺陷</b>：真实容器里响应在首次 flush 时即提交，
 * 安全头由 {@code OnCommittedResponseWrapper} 在提交那一刻由<b>同一条 worker 线程</b>写出，
 * 不存在跨线程竞争。这里的冲突源于 MockMvc 的响应对象本就不支持并发写，
 * 因此不该为此去动主代码。
 *
 * <p><b>做法</b>：{@code execute} 先把轮次收下不执行，等请求线程把 filter 链走完
 * （安全响应头此时已写完），再由测试显式放行到真实线程池，并<b>等到轮次真的跑完</b>。
 * 于是：
 * <ul>
 *   <li>两条线程不再重叠 → 并发写头表的结构性前提消失，结果确定；</li>
 *   <li>轮次依旧跑在<b>真实线程池</b>上，「不占用 Tomcat 工作线程」这条生产属性仍然被覆盖
 *       （装配断言见 {@code AssistantConcurrencyTest}）；</li>
 *   <li>{@link #drainAndAwait()} 等的是「已结束」这个<b>条件</b>，而不是赌一个够长的时长。</li>
 * </ul>
 *
 * <p><b>为什么抽到共享类而不是每个测试类各留一份</b>：这个执行器连同上面这段理由
 * 一共上百行，复制第二份的下场必然是两份注释各写一半、修 bug 时只改一处。
 */
final class DeferredTurnExecutor extends ThreadPoolTaskExecutor {

    /**
     * 放行超时。一轮 SSE 在测试里最慢也就几秒，给到 10 秒已经宽到不可能被正常路径吃满；
     * 超过它说明轮次真的卡住了，那就该失败而不是继续等。
     */
    private static final long DRAIN_TIMEOUT_MS = 10_000L;

    private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

    DeferredTurnExecutor(AssistantProperties.Concurrency concurrency) {
        // 容量沿用生产配置：下面只是推迟提交时机，不改变「跑在助手专用线程池上」这件事。
        setCorePoolSize(Math.max(1, concurrency.getCorePoolSize()));
        setMaxPoolSize(Math.max(1, concurrency.getMaxPoolSize()));
        setQueueCapacity(Math.max(0, concurrency.getQueueCapacity()));
        setThreadNamePrefix("assistant-turn-");
    }

    @Override
    public void execute(Runnable task) {
        pending.add(task);
    }

    /**
     * 把攒下的轮次交给真实线程池，并阻塞到<b>全部跑完</b>。
     *
     * @return 实际放行的轮次数（调用方据此断言「确实只提交了一个轮次」）
     * @throws IllegalStateException 轮次未在 {@value #DRAIN_TIMEOUT_MS} 毫秒内结束
     */
    int drainAndAwait() throws InterruptedException {
        List<Runnable> tasks = List.copyOf(pending);
        pending.clear();
        CountDownLatch finished = new CountDownLatch(tasks.size());
        for (Runnable task : tasks) {
            super.execute(() -> {
                try {
                    task.run();
                } finally {
                    finished.countDown();
                }
            });
        }
        // 必须等 worker 彻底收工：下一步 asyncDispatch 会重跑 filter 链、再次写响应头，
        // 早一步放行就会和 worker 撞在同一张非线程安全的表上。
        if (!finished.await(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("助手轮次未在 " + DRAIN_TIMEOUT_MS + " 毫秒内结束");
        }
        return tasks.size();
    }

    /** 丢弃尚未放行的轮次，避免上一个用例的残留漏进下一个用例。 */
    void discardPending() {
        pending.clear();
    }

    /** 尚未放行的轮次数。供调用方断言「这一轮确实被交给了助手线程池」。 */
    int pendingCount() {
        return pending.size();
    }
}
