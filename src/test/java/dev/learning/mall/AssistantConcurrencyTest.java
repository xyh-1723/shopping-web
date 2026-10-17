package dev.learning.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.assistant.api.ConversationView;
import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.harness.AgentLoop;
import dev.learning.mall.assistant.harness.AssistantConcurrencyGate;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多用户并发下的隔离与异步行为测试。
 *
 * <p>这些用例回答的都是「<b>两个人同时说话时会发生什么</b>」这类问题——单线程测试永远发现不了：
 * <ul>
 *   <li>模型调用期间是否还持有数据库连接（慢请求会不会打空连接池、拖垮整个商城）；</li>
 *   <li>同一会话并发发消息会不会让对话历史错乱；</li>
 *   <li>一个人猛发、很多人猛发时，谁被牺牲、牺牲方式是否可预期；</li>
 *   <li>并发轮次之间会不会串味（A 的支付卡片挂到 B 的会话上）；</li>
 *   <li>并行预算被占满时，工具是「集体超时」还是「降级为顺序执行」。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantConcurrencyTest {

    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired AssistantConcurrencyGate gate;
    @Autowired AgentLoop agentLoop;
    @Autowired OrderFacade orderFacade;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RequestMappingHandlerAdapter handlerAdapter;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(6);
        resetConcurrency();
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        resetConcurrency();
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        for (String user : List.of("student", "classmate", "admin")) rateLimiter.reset(user);
        jdbc.sql("DELETE FROM assistant_audit").update();
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("DELETE FROM assistant_run").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    private void resetConcurrency() {
        properties.getConcurrency().setMaxInFlightGlobal(16);
        properties.getConcurrency().setMaxInFlightPerUser(3);
        properties.getConcurrency().setSerializePerConversation(true);
    }

    // ——————————————— 异步：事务不跨越模型调用 ———————————————

    @Test
    @DisplayName("模型调用期间没有活动事务：慢请求不再长期独占数据库连接")
    void modelCallRunsOutsideAnyTransaction() {
        ChatModel mock = mock(ChatModel.class);
        AtomicBoolean transactionActiveDuringModel = new AtomicBoolean(true);
        when(mock.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            // 断言点：模型调用发生在事务之外。若这里为 true，说明整个轮次又变回了一个长事务，
            // 十几个并发请求就能把连接池（Hikari 12）吃光，并连带拖死商城的其它接口。
            transactionActiveDuringModel.set(TransactionSynchronizationManager.isActualTransactionActive());
            return answer("好的。");
        });
        enableWithMock(mock);

        ConversationView conv = service.create("student", null);
        service.sendDetailed(conv.id(), "student", "你好");

        assertThat(transactionActiveDuringModel)
                .as("模型调用期间若仍持有事务，连接池会被慢请求打空")
                .isFalse();
    }

    // ——————————————— 会话串行 ———————————————

    @Test
    @DisplayName("同一会话串行：前一条还在思考时发第二条返回 409，且不落库")
    void sameConversationIsSerialized() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        enableWithMock(blockingModel(entered, release));
        ConversationView conv = service.create("student", null);

        Future<SendMessageResponse> first =
                pool.submit(() -> service.sendDetailed(conv.id(), "student", "第一条"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("第一条已进入模型调用").isTrue();

        assertThatThrownBy(() -> service.sendDetailed(conv.id(), "student", "第二条"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("CONVERSATION_BUSY"));
        assertThat(auditCount("CONVERSATION_BUSY")).isEqualTo(1);
        // 被闸门拒绝的请求不该留下任何痕迹：只有第一条的用户消息落了库。
        assertThat(messageCount(conv.id())).isEqualTo(1);

        release.countDown();
        assertThat(first.get(20, TimeUnit.SECONDS).reply().content()).isEqualTo("回答");
        assertThat(messageCount(conv.id())).isEqualTo(2);
    }

    // ——————————————— 每用户在途 ———————————————

    @Test
    @DisplayName("每用户在途上限：同一个人同时开多个会话也有天花板（429）")
    void perUserInFlightLimitIsEnforced() throws Exception {
        properties.getConcurrency().setMaxInFlightPerUser(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        enableWithMock(blockingModel(entered, release));

        ConversationView a = service.create("student", null);
        ConversationView b = service.create("student", null);
        ConversationView c = service.create("student", null);

        Future<SendMessageResponse> f1 = pool.submit(() -> service.sendDetailed(a.id(), "student", "一"));
        Future<SendMessageResponse> f2 = pool.submit(() -> service.sendDetailed(b.id(), "student", "二"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("两个会话都已进入模型调用").isTrue();

        assertThatThrownBy(() -> service.sendDetailed(c.id(), "student", "三"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("TOO_MANY_IN_FLIGHT"));
        assertThat(auditCount("CONCURRENCY_USER")).isEqualTo(1);

        release.countDown();
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        // 闸门必须完全释放：拒绝之后不能留下泄漏的额度，否则额度会被慢慢吃光。
        assertThat(gate.inFlightOf("student")).isZero();
    }

    // ——————————————— 全局在途 ———————————————

    @Test
    @DisplayName("全局在途上限：跨用户也有限额，超出返回 503 而不是让所有人一起超时")
    void globalInFlightLimitIsEnforced() throws Exception {
        properties.getConcurrency().setMaxInFlightGlobal(2);
        properties.getConcurrency().setMaxInFlightPerUser(10); // 排除用户维度干扰，只测全局
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        enableWithMock(blockingModel(entered, release));

        ConversationView s = service.create("student", null);
        ConversationView c = service.create("classmate", null);
        ConversationView a = service.create("admin", null);

        Future<SendMessageResponse> f1 = pool.submit(() -> service.sendDetailed(s.id(), "student", "一"));
        Future<SendMessageResponse> f2 = pool.submit(() -> service.sendDetailed(c.id(), "classmate", "二"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.sendDetailed(a.id(), "admin", "三"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("ASSISTANT_BUSY"));
        assertThat(auditCount("CONCURRENCY_GLOBAL")).isEqualTo(1);

        release.countDown();
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        assertThat(gate.stats().globalInFlight()).isZero();
    }

    // ——————————————— 并发轮次之间的隔离 ———————————————

    @Test
    @DisplayName("并发隔离：两个用户同时发起支付确认，卡片各自归属自己的订单")
    void concurrentTurnsKeepTheirOwnPendingActions() throws Exception {
        OrderView studentOrder = newPendingOrder("student");
        OrderView classmateOrder = newPendingOrder("classmate");

        // 模型按「本轮用户消息里出现的订单号」决定给谁发起支付——模拟真实模型的意图解析。
        // 用 ThreadLocal 计轮次：每个请求占一条线程，第二轮（看到工具结果后）直接给最终答复。
        ThreadLocal<Integer> round = ThreadLocal.withInitial(() -> 0);
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            int n = round.get();
            round.set(n + 1);
            if (n > 0) return answer("已发起支付确认，请在页面点击「确认支付」。");
            Thread.sleep(200); // 让两轮真正重叠，否则测不出并发下的串味
            String text = lastUserText(invocation.getArgument(0));
            String orderId = text.contains(studentOrder.id()) ? studentOrder.id() : classmateOrder.id();
            return toolTurn(call("r1", "requestPayment",
                    "{\"orderId\":\"" + orderId + "\",\"method\":\"DEMO_CARD\"}"));
        });
        enableWithMock(mock);

        ConversationView studentConv = service.create("student", null);
        ConversationView classmateConv = service.create("classmate", null);

        Future<SendMessageResponse> f1 = pool.submit(
                () -> service.sendDetailed(studentConv.id(), "student", "帮我支付 " + studentOrder.id()));
        Future<SendMessageResponse> f2 = pool.submit(
                () -> service.sendDetailed(classmateConv.id(), "classmate", "帮我支付 " + classmateOrder.id()));

        SendMessageResponse r1 = f1.get(30, TimeUnit.SECONDS);
        SendMessageResponse r2 = f2.get(30, TimeUnit.SECONDS);

        // 这是本测试的核心断言：并发下轮次上下文没有串味，卡片各归其主。
        assertThat(r1.pendingAction()).isNotNull();
        assertThat(r1.pendingAction().orderId()).isEqualTo(studentOrder.id());
        assertThat(r1.pendingAction().conversationId()).isEqualTo(studentConv.id());
        assertThat(r2.pendingAction()).isNotNull();
        assertThat(r2.pendingAction().orderId()).isEqualTo(classmateOrder.id());
        assertThat(r2.pendingAction().conversationId()).isEqualTo(classmateConv.id());
        // 消息也没有写错会话。
        assertThat(messageCount(studentConv.id())).isEqualTo(2);
        assertThat(messageCount(classmateConv.id())).isEqualTo(2);
    }

    // ——————————————— 并行预算耗尽 → 降级 ———————————————

    @Test
    @DisplayName("全局并行预算耗尽时降级为顺序执行：工具仍全部成功，不会被集体超时")
    void parallelBudgetExhaustionDegradesToSequential() {
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(agentLoop, "parallelPermits");
        int drained = permits.drainPermits(); // 许可数为 0：任何并行调用都拿不到额度
        try {
            ChatModel mock = mock(ChatModel.class);
            when(mock.chat(any(ChatRequest.class)))
                    .thenReturn(toolTurn(
                            call("r1", "searchProducts", "{\"keyword\":\"杯\",\"category\":null,\"maxPrice\":null}"),
                            call("r2", "myOrders", "{}")))
                    .thenReturn(answer("这是杯子，另外你有一笔订单。"));

            enableWithMock(mock);
            ConversationView conv = service.create("student", null);
            SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "找杯子并看看我的订单");

            assertThat(stepToolNames()).containsExactlyInAnyOrder("searchProducts", "myOrders");
            // 关键：降级为顺序执行后每条工具都跑完了，没有一条变成 TIMEOUT。
            // 若这里换成「排队等许可」，超时会让所有用户一起拿到失败。
            assertThat(stepStatuses()).containsOnly("OK");
            assertThat(stopReason(conv.id())).isEqualTo("DONE");
            assertThat(resp.reply().content()).contains("杯子");
        } finally {
            permits.release(drained);
        }
    }

    // ——————————————— 异步：MVC 层确实走了异步 ———————————————

    @Test
    @DisplayName("消息接口是异步的：进入异步派发而不是占着请求线程等模型")
    void sendEndpointStartsAsyncProcessing() throws Exception {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class))).thenReturn(answer("你好，有什么可以帮你？"));
        enableWithMock(mock);
        ConversationView conv = service.create("student", null);

        MvcResult pending = mvc.perform(post("/api/assistant/conversations/" + conv.id() + "/messages")
                        .with(user("student").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("message", "你好"))))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply.content").value("你好，有什么可以帮你？"));
    }

    @Test
    @DisplayName("异步由助手专用线程池承担：没有退化成每请求新建线程，也没误用消息模块的池")
    void asyncSupportUsesTheAssistantDedicatedExecutor() {
        // handlerAdapter 的 taskExecutor 是私有字段（Spring 只暴露 setter），用反射读它，
        // 目的是断言 configureAsyncSupport 真的生效了。
        AsyncTaskExecutor executor = (AsyncTaskExecutor) ReflectionTestUtils.getField(
                handlerAdapter, "taskExecutor");
        // 不显式配置的话，因为项目里有多个线程池 bean，Boot 不会创建 applicationTaskExecutor，
        // MVC 会悄悄退化成 SimpleAsyncTaskExecutor（每请求一条新线程）——并发一高直接雪崩。
        assertThat(executor)
                .as("MVC 异步必须绑定助手专用池，否则退化成每请求新建线程")
                .isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getThreadNamePrefix()).isEqualTo("assistant-turn-");
        assertThat(pool.getMaxPoolSize()).isEqualTo(properties.getConcurrency().getMaxPoolSize());
    }

    // ——————————————— 辅助 ———————————————

    private ChatModel blockingModel(CountDownLatch entered, CountDownLatch release) {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await(20, TimeUnit.SECONDS);
            return answer("回答");
        });
        return mock;
    }

    private void enableWithMock(ChatModel mock) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));
    }

    private OrderView newPendingOrder(String username) {
        String key = "conc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return orderFacade.create(username, key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private static ToolExecutionRequest call(String id, String name, String args) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(args).build();
    }

    private static ChatResponse toolTurn(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static String lastUserText(ChatRequest request) {
        return request.messages().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText())
                .reduce((a, b) -> b)
                .orElse("");
    }

    private long auditCount(String ruleId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE rule_id = :rule")
                .param("rule", ruleId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private int messageCount(String conversationId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM assistant_message WHERE conversation_id = :c")
                .param("c", conversationId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /**
     * 取指定会话最近一次 run 的停止原因。
     *
     * <p>必须传 conversation_id：H2 缓存 {@code CURRENT_TIMESTAMP} 让多条 run 的
     * {@code created_at} 并列，并列时又靠随机 UUID 的 {@code id} 兜底排序，
     * 于是「全表取最近一条」会取到<b>别人的</b> run。
     *
     * <p>本类是最容易踩到的地方：并发用例会同时产生多条属于<b>不同会话</b>的 run，
     * 它们时间戳并列，取到哪一行取决于 UUID 字典序。
     * 详见 {@link StopReasons}，守护测试见 {@link StopReasonSessionScopingTest}。
     */
    private String stopReason(String conversationId) {
        return StopReasons.latest(jdbc, conversationId);
    }

    private List<String> stepStatuses() {
        return jdbc.sql("SELECT status FROM assistant_run_step ORDER BY step_no").query(String.class).list();
    }

    private List<String> stepToolNames() {
        return jdbc.sql("SELECT tool_name FROM assistant_run_step ORDER BY step_no").query(String.class).list();
    }
}
