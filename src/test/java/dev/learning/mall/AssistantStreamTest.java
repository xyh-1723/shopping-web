package dev.learning.mall;

import dev.learning.mall.assistant.api.ConversationView;
import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.harness.TurnListener;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流式输出（SSE）与工具进度的测试。
 *
 * <p>这里断言的核心不是「SSE 能不能跑通」，而是两件更容易出问题的事：
 * <ol>
 *   <li>进度是<b>旁路</b>——它不能改变任何安全判定，也不能因为监听器出错就让回答失败；</li>
 *   <li>失败也要<b>走流</b>——一旦返回了 SseEmitter，HTTP 状态码就已经发出去了，
 *       之后再出任何问题都只能从事件里表达。</li>
 * </ol>
 *
 * <p><b>本类的流式轮次由一个「延迟放行」的执行器驱动，原因见 {@link DeferredTurnExecutor} 的说明。</b>
 * 那是为了绕开 MockMvc 响应对象不可并发写的固有竞争，被测的协议语义一点没动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 仅为覆盖下面的 assistantTurnExecutor 同名 Bean 而打开，作用域仅限本测试类的上下文。
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class AssistantStreamTest {

    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    /** 被攒下的轮次由测试自己放行；见 {@link DeferredTurnExecutor}。 */
    @Autowired DeferredTurnExecutor turnExecutor;

    private static final String ALICE = "student";

    @AfterEach
    void cleanup() {
        turnExecutor.discardPending();
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        rateLimiter.reset(ALICE);
        jdbc.sql("DELETE FROM assistant_run").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    private void enableWithMock(ChatModel mock) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));
    }

    private static ChatResponse toolTurn(ToolExecutionRequest request) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    /**
     * 本测试类替换 {@code assistantTurnExecutor} 的那个 Bean——这是消除偶发红的关键，
     * 理由见 {@link DeferredTurnExecutor}。
     */
    @TestConfiguration
    static class DeferredTurnExecutorConfig {

        /**
         * 返回类型必须是 {@link ThreadPoolTaskExecutor} 的子类：
         * {@code AssistantAsyncConfig.configureAsyncSupport} 按名字取这个 Bean 并强转成该类型。
         */
        @Bean(name = "assistantTurnExecutor")
        DeferredTurnExecutor assistantTurnExecutor(AssistantProperties config) {
            // 容量沿用生产配置：下面只是推迟提交时机，不改变「跑在助手专用线程池上」这件事。
            AssistantProperties.Concurrency c = config.getConcurrency();
            return new DeferredTurnExecutor(c.getCorePoolSize(), c.getMaxPoolSize(),
                    c.getQueueCapacity());
        }
    }

    /**
     * 一个「先攒着、等测试放行」的助手轮次执行器。
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
     * 于是 {@code HashMap.computeIfAbsent} 会以约 <b>1/5</b> 的概率抛
     * {@code ConcurrentModificationException}。
     *
     * <p>这个竞争有两种外观，一度让人误判成两个不同的 flake：
     * <ul>
     *   <li>CME 落在<b>请求线程</b> → 直接从 {@code mvc.perform(...)} 抛出去，用例 15ms 就红；</li>
     *   <li>CME 落在<b>worker 线程</b> → 被 {@code TurnStreamSink.emit} 的 {@code catch} 吞掉，
     *       并置上「已关闭」标记，于是 {@code close()} 不再调用 {@code emitter.complete()}，
     *       异步结果永远不来，表现为 {@code getAsyncResult(10_000)} <b>10 秒超时</b>。</li>
     * </ul>
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
     *   <li>轮次依旧跑在<b>真实线程池</b>上，「不占用 Tomcat 工作线程」这条生产属性仍然被覆盖；</li>
     *   <li>{@code drainAndAwait()} 等的是「已结束」这个<b>条件</b>，而不是赌一个够长的时长。</li>
     * </ul>
     */
    static final class DeferredTurnExecutor extends ThreadPoolTaskExecutor {

        private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

        DeferredTurnExecutor(int corePoolSize, int maxPoolSize, int queueCapacity) {
            setCorePoolSize(Math.max(1, corePoolSize));
            setMaxPoolSize(Math.max(1, maxPoolSize));
            setQueueCapacity(Math.max(0, queueCapacity));
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
         */
        int drainAndAwait() throws InterruptedException {
            List<Runnable> tasks = List.copyOf(pending);
            pending.clear();
            CountDownLatch finished = new CountDownLatch(tasks.size());
            for (Runnable task : tasks) {
                super.execute(() -> {
                    try {
                        task.run();
                    }
                    finally {
                        finished.countDown();
                    }
                });
            }
            // 必须等 worker 彻底收工：下一步 asyncDispatch 会重跑 filter 链、再次写响应头，
            // 早一步放行就会和 worker 撞在同一张非线程安全的表上。
            if (!finished.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("助手轮次未在 10 秒内结束");
            }
            return tasks.size();
        }

        /** 丢弃尚未放行的轮次，避免上一个用例的残留漏进下一个用例。 */
        void discardPending() {
            pending.clear();
        }
    }

    /** 记录收到的进度，供断言「哪些进度被推出来了」。 */
    private static final class Recorder implements TurnListener {
        final List<String> stages = new ArrayList<>();
        final List<String> toolStarts = new ArrayList<>();
        final List<String> toolEnds = new ArrayList<>();

        @Override public void onStage(String stage) { stages.add(stage); }
        @Override public void onToolStart(String toolName, String args) { toolStarts.add(toolName); }
        @Override public void onToolEnd(String toolName, String status, long latencyMs) {
            toolEnds.add(toolName + ":" + status);
        }
    }

    @Test
    @DisplayName("进度：模型思考与每个工具的 开始/结束 都被推出来，且不影响回答本身")
    void listenerSeesThinkingAndToolProgress() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(ToolExecutionRequest.builder().id("r1").name("searchProducts")
                        .arguments("{\"keyword\":\"杯\",\"category\":null,\"maxPrice\":null}").build()))
                .thenReturn(answer("这几款杯子都挺适合日常用的。"));

        enableWithMock(mock);
        ConversationView conv = service.create(ALICE, null);
        Recorder recorder = new Recorder();
        SendMessageResponse response = service.sendDetailed(conv.id(), ALICE, "帮我找找杯子", recorder);

        assertThat(response.reply().content()).contains("杯子");
        assertThat(recorder.stages).isNotEmpty().contains("thinking");
        assertThat(recorder.toolStarts).containsExactly("searchProducts");
        assertThat(recorder.toolEnds).containsExactly("searchProducts:OK");
    }

    @Test
    @DisplayName("进度是旁路：监听器抛异常，回答照常返回（断开的浏览器不该让对话失败）")
    void listenerFailureDoesNotBreakTheTurn() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(ToolExecutionRequest.builder().id("r1").name("myOrders")
                        .arguments("{}").build()))
                .thenReturn(answer("你现在没有待处理的订单。"));

        enableWithMock(mock);
        ConversationView conv = service.create(ALICE, null);
        TurnListener hostile = new TurnListener() {
            @Override public void onStage(String stage) { throw new IllegalStateException("连接断了"); }
            @Override public void onToolStart(String toolName, String args) { throw new IllegalStateException("连接断了"); }
            @Override public void onToolEnd(String toolName, String status, long latencyMs) {
                throw new IllegalStateException("连接断了");
            }
        };

        SendMessageResponse response = service.sendDetailed(conv.id(), ALICE, "我的订单呢", hostile);

        assertThat(response.reply().content()).contains("订单");
    }

    @Test
    @DisplayName("SSE 端点：事件流以 open 开始、以 done 结束，且带事件名")
    void streamEndpointEmitsOpenAndDone() throws Exception {
        // 模型未启用时回复是系统固定文案，结果确定，适合断言协议本身。
        ConversationView conv = service.create(ALICE, null);

        MvcResult pending = mvc.perform(post("/api/assistant/conversations/" + conv.id() + "/stream")
                        .with(user(ALICE).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 先让请求线程把 filter 链走完，再放行 worker。理由见 DeferredTurnExecutor 的说明。
        assertThat(turnExecutor.drainAndAwait()).isEqualTo(1);
        pending.getAsyncResult(10_000);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(pending))
                .andExpect(status().isOk());

        String body = pending.getResponse().getContentAsString();
        assertThat(body).contains("event:open");
        assertThat(body).contains("event:done");
        assertThat(body).contains("conversationId");
        // 助手未启用是「正常回答」而不是错误：不该走 error 事件。
        assertThat(body).doesNotContain("event:error");
    }

    @Test
    @DisplayName("SSE 端点：会话不存在时用 error 事件表达失败，而不是断流")
    void streamEndpointReportsFailureAsEvent() throws Exception {
        MvcResult pending = mvc.perform(post("/api/assistant/conversations/does-not-exist/stream")
                        .with(user(ALICE).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 先让请求线程把 filter 链走完，再放行 worker。理由见 DeferredTurnExecutor 的说明。
        assertThat(turnExecutor.drainAndAwait()).isEqualTo(1);
        pending.getAsyncResult(10_000);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(pending))
                .andExpect(status().isOk());

        String body = pending.getResponse().getContentAsString();
        assertThat(body).contains("event:error");
        assertThat(body).contains("NOT_FOUND");
        assertThat(body).doesNotContain("event:done");
    }

    @Test
    @DisplayName("SSE 端点：未登录 401、缺 CSRF 403 —— 安全防线对流式端点同样生效")
    void streamEndpointKeepsSecurity() throws Exception {
        ConversationView conv = service.create(ALICE, null);
        mvc.perform(post("/api/assistant/conversations/" + conv.id() + "/stream")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/conversations/" + conv.id() + "/stream")
                        .with(user(ALICE).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("同步端点仍然可用：流式是新增通道，不是替换（老客户端不受影响）")
    void synchronousEndpointStillWorks() throws Exception {
        ConversationView conv = service.create(ALICE, null);
        mvc.perform(post("/api/assistant/conversations/" + conv.id() + "/messages")
                        .with(user(ALICE).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk());
    }
}
