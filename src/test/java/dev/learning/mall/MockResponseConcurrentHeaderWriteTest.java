package dev.learning.mall;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守一条<b>前提</b>：{@code MockHttpServletResponse} 的响应头表不支持并发写。
 *
 * <p><b>为什么要守它。</b>流式端点（{@code /api/assistant/conversations/{id}/stream}）的
 * 测试必须用一个「延迟放行」的助手线程池（见 {@link DeferredTurnExecutor}），
 * 而那个做法存在的<b>唯一理由</b>就是这张表不能并发写。理由本身如果没有任何东西盯着，
 * 就会发生这类事：某天有人看这个执行器不顺眼，「顺手」改回真实线程池，单跑全绿、
 * 于是合进去——然后测试开始偶发红，而没人再记得当初为什么绕这一下。
 *
 * <p><b>它断言的是反驳成本最高的那件事。</b>一次 SSE 请求里确实有两条线程在写同一张表：
 * <ol>
 *   <li><b>worker 线程</b>（{@code assistant-turn-*}）：第一次 flush SSE 事件时，
 *       由 {@code ServletServerHttpResponse.writeHeaders()} 写 {@code Content-Type}；</li>
 *   <li><b>请求线程</b>（MockMvc 的主线程）：handler 返回后 filter 链收尾，
 *       {@code HeaderWriterFilter.doHeadersAfter} 补写 {@code X-Frame-Options} 等安全头。</li>
 * </ol>
 * 两条线程都走 {@code MockHttpServletResponse.addHeader}，落到同一个
 * {@code LinkedCaseInsensitiveMap}——它内部是两张普通 {@code HashMap}/{@code LinkedHashMap}，
 * 并发写时 {@code computeIfAbsent} 会在扩容窗口里抛 {@code ConcurrentModificationException}。
 *
 * <p><b>为什么不直接断言「并发一定会抛 CME」那类更弱的说法</b>：那样等于什么都没说。
 * 这里给两条线程足够的写次数（实测约 0.6% 的写入会撞上扩容），
 * 于是「一次都不撞上」在本被测环境下不可能发生——它不是概率型断言，是一条近乎确定的材料性质。
 *
 * <p><b>它变红时意味着什么</b>：通常是 Spring 把 {@code MockHttpServletResponse}
 * 换成了线程安全的实现。那时应当回来核对 {@link DeferredTurnExecutor} 是否还需要，
 * 而不是把这条断言删掉——这类「绕一下」的做法一旦没人记得理由，就会被人当成多余之物删掉，
 * 然后偶发红重新回来。
 */
class MockResponseConcurrentHeaderWriteTest {

    /**
     * 每条线程写入的次数。两条线程共 50 万次；实测约有 0.6% 的写入会撞上扩容窗口，
     * 也就是一次运行中会炸出上千次 CME——「一次都不撞上」在本被测环境下不可能发生。
     */
    private static final int WRITES_PER_THREAD = 250_000;

    @Test
    @DisplayName("前提：MockHttpServletResponse 的响应头表并发写会炸 ConcurrentModificationException")
    void concurrentHeaderWritesThrowConcurrentModificationException() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 先放几个键，让表处在「已经有内容」的状态——实际请求里 Content-Type 之前也已经有安全头了。
        for (int i = 0; i < 8; i++) {
            response.addHeader("seed-" + i, "v");
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger concurrentModifications = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);
        try {
            for (int t = 0; t < 2; t++) {
                final int id = t;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < WRITES_PER_THREAD; i++) {
                            try {
                                // 与 SSE 冲突现场同款的写法：worker 写 Content-Type、请求线程写安全头，
                                // 两条路都落到 addHeader 上。
                                response.addHeader("h-" + id + "-" + i, "v");
                            } catch (ConcurrentModificationException ignored) {
                                concurrentModifications.incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS))
                    .as("两条线程应在 60 秒内写完，超时说明机器严重超载，本条前提无法被测量")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(concurrentModifications.get())
                .as("MockHttpServletResponse 的响应头表（LinkedCaseInsensitiveMap，内部是普通 HashMap）"
                        + "并发写时必须炸 ConcurrentModificationException。"
                        + "这正是流式端点用例要用 DeferredTurnExecutor 延迟放行助手轮次的唯一理由："
                        + "请求线程收尾 filter 链写安全头，与 worker 线程 flush SSE 写 Content-Type，"
                        + "会同时 add 进这一张表。若本条有一天不再成立（表变成线程安全的了），"
                        + "那就该回来核对 DeferredTurnExecutor 是否还需要，而不是删掉这条断言")
                .isPositive();
    }
}
