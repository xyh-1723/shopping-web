package dev.learning.mall;

import dev.learning.mall.assistant.api.ConversationView;
import dev.learning.mall.assistant.harness.AgentLoop;
import dev.learning.mall.assistant.harness.TurnListener;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.learning.mall.order.internal.OrderExpiryService;
import dev.learning.mall.order.internal.OrderProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可观测四项指标的守护测试。
 *
 * <p>要守的不是「有没有打日志」，而是<b>这些数字能不能被聚合出来</b>。日志是一次性的、
 * 查不出趋势；轨迹表（{@code assistant_run_trace}）只能靠 SQL count 事后统计、没有时序；
 * 而超时关单的积压量以前<b>算出来就丢</b>。所以这里每一条断言都直接读
 * {@link MeterRegistry}——它是运营真正会去看的那一层。
 *
 * <p><b>为什么全部用「增量」而不是绝对值断言：</b>本类与其它 {@code @SpringBootTest}
 * 共享同一个 Spring 上下文，因而共享同一个 {@code MeterRegistry}。其它测试跑过的轮次
 * 已经往这些计数器里加过数，断言「DONE == 1」会随执行顺序飘。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityMetricsTest {

    /**
     * 首字之后刻意保留的后续工作时长。用它把「首字延迟」和「整轮耗时」拉开一个
     * <b>确定</b>的间隔——只断言 {@code firstToken <= turn} 是假绿：
     * 把首字 Timer 错误地记在整轮结束时，两个值依然满足 {@code <=}。
     */
    private static final long TAIL_WORK_MS = 400L;
    /** 断言阈值取 TAIL_WORK_MS 的一半：留足调度抖动，又远大于「记错位置」能产生的差值。 */
    private static final long TAIL_ASSERT_MS = TAIL_WORK_MS / 2;

    private static final String ALICE = "student";
    private static final String ORDER_USER = "metrics-observer";

    @Autowired AssistantService service;
    @Autowired AgentLoop agentLoop;
    @Autowired AssistantProperties properties;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired MeterRegistry registry;
    @Autowired OrderExpiryService expiry;
    @Autowired OrderProperties orderProperties;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private int originalMaxPerScan;
    private boolean originalAutoClose;
    private int originalMaxSteps;

    @BeforeEach
    void rememberDefaults() {
        originalMaxPerScan = orderProperties.getAutoCloseMaxPerScan();
        originalMaxSteps = properties.getHarness().getMaxSteps();
        originalAutoClose = orderProperties.isAutoCloseEnabled();
        // 积压量是全表统计（不带 username 条件），别人遗留的到期订单会让断言飘，
        // 所以这里先把订单相关的三张表清干净——与 OrderExpiryTest.setUp 同一套做法。
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    @AfterEach
    void cleanup() {
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        ReflectionTestUtils.setField(agentLoop, "streamingModel", Optional.empty());
        rateLimiter.reset(ALICE);
        properties.getHarness().setMaxSteps(originalMaxSteps);
        orderProperties.setAutoCloseEnabled(originalAutoClose);
        orderProperties.setAutoCloseMaxPerScan(originalMaxPerScan);
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("DELETE FROM assistant_run").update();   // 步骤由外键级联清除
        jdbc.sql("DELETE FROM assistant_audit").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    // ——————————————————— 1. 停止原因 ———————————————————

    @Test
    @DisplayName("停止原因进了计数器：正常结束与预算耗尽分别按 reason 累加")
    void stopReasonIsCounted() {
        double doneBefore = counterValue(AgentLoop.METRIC_STOP, "reason", AgentLoop.STOP_DONE);
        double maxStepsBefore = counterValue(AgentLoop.METRIC_STOP, "reason", AgentLoop.STOP_MAX_STEPS);

        // 正常结束的一轮。
        ChatModel ok = mock(ChatModel.class);
        when(ok.chat(any(ChatRequest.class))).thenReturn(answer("好的，这就帮你查。"));
        ConversationView conv = runOneTurn(ok, "在吗");
        assertThat(StopReasons.latest(jdbc, conv.id())).isEqualTo("DONE");

        // 预算耗尽的一轮：只给工具调用、不给最终作答，步数用完必然停在 MAX_STEPS。
        properties.getHarness().setMaxSteps(1);
        ChatModel stuck = mock(ChatModel.class);
        when(stuck.chat(any(ChatRequest.class))).thenReturn(toolTurn(
                call("r1", "searchProducts", "{\"keyword\":\"杯\",\"category\":null,\"maxPrice\":null}")));
        ConversationView conv2 = runOneTurn(stuck, "有杯子吗");
        assertThat(StopReasons.latest(jdbc, conv2.id())).isEqualTo("MAX_STEPS");

        assertThat(counterValue(AgentLoop.METRIC_STOP, "reason", AgentLoop.STOP_DONE) - doneBefore)
                .as("DONE 必须被记一次").isEqualTo(1.0);
        assertThat(counterValue(AgentLoop.METRIC_STOP, "reason", AgentLoop.STOP_MAX_STEPS) - maxStepsBefore)
                .as("MAX_STEPS 必须被记一次").isEqualTo(1.0);
    }

    // ——————————————————— 2. 工具成功率 ———————————————————

    @Test
    @DisplayName("工具失败记成 ERROR 而不是 OK：成功率才可能是真的")
    void toolFailureIsCounted() {
        double errorBefore = counterValue(AgentLoop.METRIC_TOOL_CALLS, "tool", "getProduct",
                "status", AgentLoop.STATUS_ERROR);
        double okBefore = counterValue(AgentLoop.METRIC_TOOL_CALLS, "tool", "getProduct",
                "status", AgentLoop.STATUS_OK);

        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                // 参数不是合法 JSON：这条调用一定会失败
                .thenReturn(toolTurn(call("r1", "getProduct", "not-a-json")))
                .thenReturn(answer("这个商品编号我没查到，换个编号我再看看。"));
        ConversationView conv = runOneTurn(mock, "看看商品 99 的详情");

        assertThat(StopReasons.latest(jdbc, conv.id())).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT status FROM assistant_run_step ORDER BY step_no")
                .query(String.class).list()).containsExactly("ERROR");

        assertThat(counterValue(AgentLoop.METRIC_TOOL_CALLS, "tool", "getProduct",
                "status", AgentLoop.STATUS_ERROR) - errorBefore)
                .as("失败必须记成 ERROR").isEqualTo(1.0);
        // 反向：失败绝不能被顺手记成 OK，否则失败率恒为 0，这个指标就白埋了。
        assertThat(counterValue(AgentLoop.METRIC_TOOL_CALLS, "tool", "getProduct",
                "status", AgentLoop.STATUS_OK) - okBefore)
                .as("失败的调用不能被记成 OK").isZero();
    }

    // ——————————————————— 3. 首字延迟 ———————————————————

    @Test
    @DisplayName("首字延迟记在收到第一个字的那一刻，而不是整轮结束时")
    void firstTokenLatencyIsSmallerThanWholeTurn() {
        long firstTokenCountBefore = timerCount(AgentLoop.METRIC_FIRST_TOKEN);
        double firstTokenMsBefore = timerTotalMs(AgentLoop.METRIC_FIRST_TOKEN);
        long turnCountBefore = timerCount(AgentLoop.METRIC_TURN_DURATION, "stop", AgentLoop.STOP_DONE);
        double turnMsBefore = timerTotalMs(AgentLoop.METRIC_TURN_DURATION, "stop", AgentLoop.STOP_DONE);

        String full = "好的，这就帮你查。";
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("好的，");
            // 首字之后还有一段确定的后续工作：这两个指标的差值必须把这个间隔体现出来。
            Thread.sleep(TAIL_WORK_MS);
            handler.onPartialResponse("这就帮你查。");
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from(full)).build());
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        enableWithMocks(mock(ChatModel.class), streaming);
        ConversationView conv = service.create(ALICE, null);
        service.sendDetailed(conv.id(), ALICE, "帮我查一下", new Tokens());

        assertThat(timerCount(AgentLoop.METRIC_FIRST_TOKEN) - firstTokenCountBefore)
                .as("一轮只能记一次首字").isEqualTo(1L);
        assertThat(timerCount(AgentLoop.METRIC_TURN_DURATION, "stop", AgentLoop.STOP_DONE) - turnCountBefore)
                .isEqualTo(1L);

        double firstToken = timerTotalMs(AgentLoop.METRIC_FIRST_TOKEN) - firstTokenMsBefore;
        double turn = timerTotalMs(AgentLoop.METRIC_TURN_DURATION, "stop", AgentLoop.STOP_DONE) - turnMsBefore;
        assertThat(turn - firstToken)
                .as("首字之后还有 %dms 的后续工作，整轮耗时必须比首字延迟长这么多；"
                        .formatted(TAIL_WORK_MS)
                        + "若首字 Timer 被记在整轮结束时，这个差值会趋近于 0")
                .isGreaterThan((double) TAIL_ASSERT_MS);
    }

    // ——————————————————— 4. 指标端点与安全 ———————————————————

    @Test
    @DisplayName("指标端点对外暴露 mall.* 指标，且未登录拿不到")
    void metricsEndpointExposesMallMeters() throws Exception {
        // 端点只暴露注册表里已有的 meter，所以先真的跑一轮把它造出来。
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class))).thenReturn(answer("好的。"));
        runOneTurn(mock, "在吗");

        String body = mvc.perform(get("/actuator/metrics/" + AgentLoop.METRIC_STOP)
                        .with(user("admin").roles("USER", "STAFF")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(AgentLoop.METRIC_STOP);

        // 运营数据不外露：/actuator/** 落在 anyRequest().authenticated() 里。
        mvc.perform(get("/actuator/metrics/" + AgentLoop.METRIC_STOP))
                .andExpect(status().isUnauthorized());
    }

    // ——————————————————— 5. 积压 Gauge ———————————————————

    @Test
    @DisplayName("积压 Gauge 跟着最近一次扫描走：扫不完是 1，扫完是 0")
    void backlogGaugeTracksLastScan() {
        orderProperties.setAutoCloseEnabled(true);
        orderProperties.setAutoCloseMaxPerScan(1);
        newExpiredOrder();
        newExpiredOrder();

        var first = expiry.closeExpired();
        assertThat(first.candidates()).isEqualTo(1);
        assertThat(first.backlog()).as("单轮上限之外还剩一笔，积压量必须是 1").isEqualTo(1);
        assertThat(backlogGauge()).as("Gauge 必须跟上最近一次扫描的积压量").isEqualTo(1.0);

        var second = expiry.closeExpired();
        assertThat(second.backlog()).isZero();
        assertThat(backlogGauge()).as("扫完就该归零，不能停留在上一轮的值").isZero();
    }

    // ——————————————————— 读指标的工具 ———————————————————

    /** 计数器的当前值；meter 还没被创建过时返回 0（而不是 null）。 */
    private double counterValue(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0d : counter.count();
    }

    private long timerCount(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0L : timer.count();
    }

    private double timerTotalMs(String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0d : timer.totalTime(TimeUnit.MILLISECONDS);
    }

    private double backlogGauge() {
        Gauge gauge = registry.find("mall.order.expiry.backlog").gauge();
        assertThat(gauge).as("积压 Gauge 必须在服务构造时就注册好").isNotNull();
        return gauge.value();
    }

    // ——————————————————— 造数据 / 驱动一轮 ———————————————————

    private void enableWithMocks(ChatModel sync, StreamingChatModel streaming) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.ofNullable(sync));
        ReflectionTestUtils.setField(agentLoop, "streamingModel", Optional.ofNullable(streaming));
    }

    private ConversationView runOneTurn(ChatModel mock, String message) {
        enableWithMocks(mock, null);
        ConversationView conv = service.create(ALICE, null);
        service.sendDetailed(conv.id(), ALICE, message);
        return conv;
    }

    /** 插入一笔已经过了支付截止时间的待支付订单（不带商品行，因此不牵动库存）。 */
    private void newExpiredOrder() {
        jdbc.sql("""
                        INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status,
                                               created_at,expires_at)
                        VALUES(:id,:user,:key,'hash',39.00,'PENDING',:created,:expires)""")
                .param("id", UUID.randomUUID().toString())
                .param("user", ORDER_USER)
                .param("key", UUID.randomUUID().toString())
                .param("created", LocalDateTime.now().minusDays(1))
                .param("expires", LocalDateTime.now().minusMinutes(30))
                .update();
    }

    /** 要字的监听器：只有它才会让执行循环走流式路径。 */
    private static final class Tokens implements TurnListener {
        @Override public boolean consumesTokens() { return true; }
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
}
