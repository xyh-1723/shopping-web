package dev.learning.mall;

import dev.learning.mall.assistant.api.ConversationView;
import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.internal.ToolRegistry;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 执行循环（harness 核心）的集成测试。模型用 Mock 脚本化，因此可以精确断言
 * 「循环在每种异常情况下各做了什么决定」——这类行为用真实模型根本没法稳定验证。
 *
 * <p>覆盖：一轮内多工具并行、工具失败转结构化错误后模型自纠、超长结果裁剪、
 * 重复调用短路与停滞停止、预算耗尽给诚实兜底、效果型工具保会话上下文、轨迹落库与接口隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentLoopTest {

    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired ToolRegistry registry;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired OrderFacade orderFacade;

    private int originalMaxSteps;
    private int originalMaxToolResultChars;
    private boolean originalTraceEnabled;

    @BeforeEach
    void rememberDefaults() {
        originalMaxSteps = properties.getHarness().getMaxSteps();
        originalMaxToolResultChars = properties.getHarness().getMaxToolResultChars();
        originalTraceEnabled = properties.getHarness().isTraceEnabled();
    }

    @AfterEach
    void cleanup() {
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        properties.getHarness().setMaxSteps(originalMaxSteps);
        properties.getHarness().setMaxToolResultChars(originalMaxToolResultChars);
        properties.getHarness().setTraceEnabled(originalTraceEnabled);
        rateLimiter.reset("student");
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("DELETE FROM assistant_run").update();   // 步骤由外键级联清除
        jdbc.sql("DELETE FROM assistant_audit").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    private void enableWithMock(ChatModel mock) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));
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

    /**
     * 取指定会话最近一次 run 的停止原因。
     *
     * <p>必须传 conversation_id：H2 会缓存 {@code CURRENT_TIMESTAMP} 让多条 run 的
     * {@code created_at} 并列，并列时又靠随机 UUID 的 {@code id} 兜底排序，
     * 于是「全表取最近一条」随时会取到<b>别的会话</b>的 run。
     * 详见 {@link StopReasons}，守护测试见 {@link StopReasonSessionScopingTest}。
     */
    private String stopReason(String conversationId) {
        return StopReasons.latest(jdbc, conversationId);
    }

    private int stepCount() {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM assistant_run_step").query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private List<String> stepStatuses() {
        return jdbc.sql("SELECT status FROM assistant_run_step ORDER BY step_no").query(String.class).list();
    }

    private List<String> stepToolNames() {
        return jdbc.sql("SELECT tool_name FROM assistant_run_step ORDER BY step_no").query(String.class).list();
    }

    private OrderView newPendingOrder(String username) {
        String key = "loop-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return orderFacade.create(username, key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    // ——————————————————— 多工具 / 并行 ———————————————————

    @Test
    @DisplayName("多工具：一轮里的两个互不依赖查询都会执行，并按原顺序回填结果")
    void independentToolsInOneRoundAllExecute() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(
                        call("r1", "searchProducts", "{\"keyword\":\"杯\",\"category\":null,\"maxPrice\":null}"),
                        call("r2", "myOrders", "{}")))
                .thenReturn(answer("这是杯子商品，另外你目前有两笔订单。"));

        ConversationView conv = runOneTurn(mock, "帮我找找杯子，顺便看看我的订单");

        assertThat(conv).isNotNull();
        assertThat(stopReason(conv.id())).isEqualTo("DONE");
        assertThat(stepToolNames()).containsExactlyInAnyOrder("searchProducts", "myOrders");
        assertThat(stepStatuses()).containsOnly("OK");
        // 两个工具的结果都回到了模型上下文里，模型才可能同时回答两件事。
        assertThat(lastRequest(mock).messages())
                .filteredOn(m -> m instanceof ToolExecutionResultMessage)
                .hasSize(2);
    }

    @Test
    @DisplayName("效果型工具留在调用线程：与只读工具同轮时，支付确认卡片不会丢")
    void effectfulToolKeepsTurnContextWhenMixedWithReadTools() {
        OrderView order = newPendingOrder("student");
        ChatModel mock = mock(ChatModel.class);
        // 关键场景：只读查询与写操作在**同一轮**发出。写操作被并行化的话，
        // PendingActionService 的 ThreadLocal 会话上下文会静默丢失，卡片就不显示了。
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(
                        call("r1", "myOrders", "{}"),
                        call("r2", "requestPayment",
                                "{\"orderId\":\"" + order.id() + "\",\"method\":\"DEMO_CARD\"}")))
                .thenReturn(answer("已发起支付确认，请在页面点击「确认支付」。"));

        ChatModel enabled = mock;
        enableWithMock(enabled);
        ConversationView conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "支付我的订单");

        assertThat(resp.pendingAction()).as("效果型工具必须保留会话上下文，否则卡片静默消失").isNotNull();
        assertThat(resp.pendingAction().orderId()).isEqualTo(order.id());
        assertThat(stepToolNames()).contains("requestPayment");
    }

    @Test
    @DisplayName("工具分层：写操作被标记为效果型，只读工具不被标记")
    void effectfulClassification() {
        ToolRegistry.Bundle userBundle = registry.forActor(new AssistantActor("student", Set.of("ROLE_USER")));
        assertThat(userBundle.isEffectful("requestPayment")).isTrue();
        assertThat(userBundle.isEffectful("searchProducts")).isFalse();
        assertThat(userBundle.isEffectful("myOrders")).isFalse();
    }

    // ——————————————————— 工具失败 → 结构化错误 ———————————————————

    @Test
    @DisplayName("工具失败不炸整轮：异常转成结构化错误交给模型自纠，最终仍给出回答")
    void toolFailureBecomesStructuredErrorAndModelRecovers() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                // 参数无法绑定：这条调用一定会抛异常
                .thenReturn(toolTurn(call("r1", "getProduct", "not-a-json")))
                .thenReturn(answer("这个商品编号我没查到，换个编号我再看看。"));

        enableWithMock(mock);
        ConversationView conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "看看商品 99 的详情");

        assertThat(resp.reply().content()).contains("换个编号");
        assertThat(stopReason(conv.id())).isEqualTo("DONE");
        assertThat(stepStatuses()).containsExactly("ERROR");
        // 模型在第二轮看到的是结构化错误（含可行动的提示），而不是一个 500。
        List<ChatMessage> second = lastRequest(mock).messages();
        String toolResult = second.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .reduce((a, b) -> b).orElse("");
        assertThat(toolResult).contains("error").contains("hint");
    }

    // ——————————————————— 上下文预算：裁剪 ———————————————————

    @Test
    @DisplayName("工具结果裁剪只作用于给模型的副本，商品卡片仍用完整结果")
    void oversizedToolResultIsTruncatedForModelOnly() {
        properties.getHarness().setMaxToolResultChars(60);
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(call("r1", "searchProducts",
                        "{\"keyword\":null,\"category\":\"生活\",\"maxPrice\":null}")))
                .thenReturn(answer("找到了这些商品。"));

        enableWithMock(mock);
        ConversationView conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "有哪些生活用品");

        String toolResult = lastRequest(mock).messages().stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).text())
                .reduce((a, b) -> b).orElse("");
        assertThat(toolResult).contains("已截断");
        assertThat(toolResult.length()).isLessThan(300);
        // 裁剪不伤卡片：toolData 来自未截断的原始结果。
        assertThat(resp.reply().toolData()).as("商品卡片必须仍能渲染").isNotNull();
        assertThat(jdbc.sql("SELECT truncated FROM assistant_run_step ORDER BY step_no LIMIT 1")
                .query(Boolean.class).single()).isTrue();
    }

    // ——————————————————— 停滞检测 ———————————————————

    @Test
    @DisplayName("停滞检测：重复调用不再执行，先提示；再犯则主动停止并写审计")
    void repeatedIdenticalCallShortCircuitsThenStops() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(call("r1", "myOrders", "{}")))
                .thenReturn(toolTurn(call("r2", "myOrders", "{}")))
                .thenReturn(toolTurn(call("r3", "myOrders", "{}")));

        enableWithMock(mock);
        ConversationView conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "我的订单呢");

        assertThat(stopReason(conv.id())).isEqualTo("STALLED");
        // 第一轮真正执行，第二轮短路成 DUPLICATE，第三轮直接停下（不再产生步）。
        assertThat(stepStatuses()).containsExactly("OK", "DUPLICATE");
        assertThat(stepCount()).isEqualTo(2);
        // 兜底文案来自 harness（可信文本），要如实说明而不是假装答完了。
        assertThat(resp.reply().content()).contains("卡在同一个查询");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE rule_id = 'AGENT_STALLED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    // ——————————————————— 预算耗尽 ———————————————————

    @Test
    @DisplayName("步数预算耗尽：停止循环、给诚实兜底答复、轨迹记为 MAX_STEPS")
    void budgetExhaustionReturnsHonestFallback() {
        properties.getHarness().setMaxSteps(1);
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolTurn(call("r1", "searchProducts",
                        "{\"keyword\":\"杯\",\"category\":null,\"maxPrice\":null}")));

        enableWithMock(mock);
        ConversationView conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "有杯子吗");

        assertThat(stopReason(conv.id())).isEqualTo("MAX_STEPS");
        assertThat(resp.reply().content()).contains("拆开问我");
        // 预算用尽就该停：模型只被调用了一轮，不再继续烧额度。
        verify(mock, times(1)).chat(any(ChatRequest.class));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE rule_id = 'AGENT_MAX_STEPS'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("轨迹可关：关闭后不再落库，回答照常")
    void traceCanBeDisabled() {
        properties.getHarness().setTraceEnabled(false);
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class))).thenReturn(answer("你好，有什么可以帮你？"));

        ConversationView conv = runOneTurn(mock, "你好");

        assertThat(conv).isNotNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_run").query(Integer.class).single()).isZero();
    }

    // ——————————————————— 轨迹接口的角色隔离 ———————————————————

    @Test
    @DisplayName("运行轨迹接口：顾客 403、店员 200")
    void runTraceEndpointIsStaffOnly() throws Exception {
        mvc.perform(get("/api/assistant/runs").with(user("student").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/assistant/runs").with(user("admin").roles("USER", "STAFF")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/assistant/runs/x/steps").with(user("student").roles("USER")))
                .andExpect(status().isForbidden());
    }

    // ——————————————————— 工具方法 ———————————————————

    private ConversationView runOneTurn(ChatModel mock, String message) {
        enableWithMock(mock);
        ConversationView conv = service.create("student", null);
        service.sendDetailed(conv.id(), "student", message);
        return conv;
    }

    /** 取第 N 次（最后一次）发给模型的请求，用来断言「模型实际看到了什么」。 */
    private ChatRequest lastRequest(ChatModel mock) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(mock, org.mockito.Mockito.atLeastOnce()).chat(captor.capture());
        List<ChatRequest> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }
}
