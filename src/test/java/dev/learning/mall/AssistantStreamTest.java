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

        /** 返回类型即 {@link DeferredTurnExecutor}，说明见该类的注释。 */
        @Bean(name = "assistantTurnExecutor")
        DeferredTurnExecutor assistantTurnExecutor(AssistantProperties config) {
            return new DeferredTurnExecutor(config.getConcurrency());
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
