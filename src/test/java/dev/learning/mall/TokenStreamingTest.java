package dev.learning.mall;

import dev.learning.mall.assistant.api.ConversationView;
import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.harness.AgentLoop;
import dev.learning.mall.assistant.harness.TurnListener;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 逐字输出（token streaming）的测试。
 *
 * <p>进度条解决的是「它在动」，逐字输出解决的是「它已经在说了」。后者带来一个新的、
 * 之前不存在的问题：<b>用户会先看到内容，守卫随后才对完整文本定案</b>。
 * 所以这里断言的焦点不是「字能不能一个一个出来」，而是三件更难的事：
 *
 * <ol>
 *   <li>只有真正要字的监听器才走流式——不能为不需要的人多付一次连接；</li>
 *   <li>增量里一旦出现泄密内容，立刻停止推送并作废已推出的内容；</li>
 *   <li>流式与同步<b>返回同一个东西</b>：落库内容、输出守卫的处置都不因分块而变化。</li>
 * </ol>
 *
 * <p><b>本类的流式端点用例由 {@link DeferredTurnExecutor} 驱动</b>：助手线程池那条提交被
 * 扣住，等请求线程把 filter 链走完（安全响应头这时才写完）再由用例显式放行。这么做的原因
 * 见 {@link DeferredTurnExecutor}——它绕开的是 <b>MockMvc 的响应对象不支持并发写</b>
 * 这一个测试侧固有属性，被测的流式协议语义一点没动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 仅为覆盖 assistantTurnExecutor 同名 Bean 而打开，作用域仅限本测试类的上下文。
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class TokenStreamingTest {

    @Autowired AssistantService service;
    @Autowired AgentLoop agentLoop;
    @Autowired AssistantProperties properties;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    /** 被攒下的轮次由用例自己放行；见 {@link DeferredTurnExecutor}。 */
    @Autowired DeferredTurnExecutor turnExecutor;

    private static final String ALICE = "student";

    /**
     * 本类建过的会话号（跨用例累积）：{@link #noAuditTrailIsLeftBehind()} 用它把自查的范围
     * 收窄到本类自己的行上。
     *
     * <p>为什么不能用用户名收窄：{@link #ALICE} 的值是<b>种子用户 {@code student}</b>，
     * 项目里一大半的测试都在用它。按用户名删/查会把别的类写的审计行算进来，
     * 于是「跑完是不是 0」取决于之前跑过哪些类——单跑绿、全量红（理由同
     * {@link AuditTrail#residueFor}）。
     */
    private static final List<String> seenConversationIds = new CopyOnWriteArrayList<>();

    /** {@code @AfterAll} 必须是 static，拿不到实例的 {@code jdbc}；由 {@code @AfterEach} 递进来。 */
    private static JdbcClient trailJdbc;

    @AfterEach
    void cleanup() {
        turnExecutor.discardPending();
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        ReflectionTestUtils.setField(agentLoop, "streamingModel", Optional.empty());
        rateLimiter.reset(ALICE);
        trailJdbc = jdbc;
        // 审计流水要在会话被删掉之前清：清完之后会话号就没有参照物了。
        AuditTrail.purge(jdbc, seenConversationIds);
        jdbc.sql("DELETE FROM assistant_run").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    /**
     * 哨兵：本类跑完之后，自己建过的会话在审计表里一行都不剩。
     *
     * <p>它存在的<b>唯一</b>理由是「清理本身无法被变异验证」：把上面那行
     * {@code AuditTrail.purge} 删掉，全量测试仍然全绿——没有任何用例按全表计数断言
     * {@code assistant_audit}，清理就这么静悄悄地失效了。有了这条断言，删掉清理就会变红。
     */
    @AfterAll
    static void noAuditTrailIsLeftBehind() {
        if (trailJdbc == null) {
            return;
        }
        assertThat(AuditTrail.residueFor(trailJdbc, seenConversationIds))
                .as("本类建过的会话在 assistant_audit 里必须一行不剩：残迹会一路带到下一个测试类")
                .isZero();
    }

    /**
     * 本测试类替换 {@code assistantTurnExecutor} 的那个 Bean——扣下来的轮次由用例自己放行，
     * 理由见 {@link DeferredTurnExecutor}。
     */
    @TestConfiguration
    static class DeferredTurnExecutorConfig {

        /**
         * 返回类型必须是 {@link DeferredTurnExecutor}（继承
         * {@link org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor}）：
         * {@code AssistantAsyncConfig.configureAsyncSupport} 按名字取这个 Bean 并强转。
         */
        @Bean(name = "assistantTurnExecutor")
        DeferredTurnExecutor assistantTurnExecutor(AssistantProperties config) {
            return new DeferredTurnExecutor(config.getConcurrency());
        }
    }

    /** 建一个会话并登记它的号：审计行挂在会话号上，只有它当得起收窄的键。 */
    private ConversationView newConversation() {
        ConversationView conversation = service.create(ALICE, null);
        seenConversationIds.add(conversation.id());
        return conversation;
    }

    private void enableWithMocks(ChatModel sync, StreamingChatModel streaming) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.ofNullable(sync));
        ReflectionTestUtils.setField(agentLoop, "streamingModel", Optional.ofNullable(streaming));
    }

    /** 脚本化的流式模型：按给定的块依次回调，最后给出完整的 AiMessage。 */
    private static StreamingChatModel scripted(String full, String... chunks) {
        StreamingChatModel mock = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            for (String chunk : chunks) handler.onPartialResponse(chunk);
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from(full)).build());
            return null;
        }).when(mock).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return mock;
    }

    /** 要字的监听器：收集增量、停止原因。 */
    private static final class Tokens implements TurnListener {
        final List<String> tokens = new ArrayList<>();
        final List<String> stops = new ArrayList<>();

        @Override public boolean consumesTokens() { return true; }
        @Override public void onToken(String token) { tokens.add(token); }
        @Override public void onTokensStopped(String reason) { stops.add(reason); }
    }

    /** 不要字的监听器：用于确认「没人要字时不会走流式」。 */
    private static final class NoTokens implements TurnListener {}

    @Test
    @DisplayName("逐字输出：增量按序到达，且最终落库的回复与同步路径完全一致")
    void tokensArriveInOrderAndFinalReplyMatches() {
        String full = "这几款杯子都挺适合日常用的。";
        StreamingChatModel streaming = scripted(full, "这几款", "杯子都", "挺适合日常用的。");
        enableWithMocks(mock(ChatModel.class), streaming);

        ConversationView conv = newConversation();
        Tokens listener = new Tokens();
        SendMessageResponse response = service.sendDetailed(conv.id(), ALICE, "帮我找找杯子", listener);

        assertThat(listener.tokens).containsExactly("这几款", "杯子都", "挺适合日常用的。");
        assertThat(listener.stops).isEmpty();
        assertThat(response.reply().content()).isEqualTo(full);
        // 增量只是「怎么送出去」，落库内容必须与拼起来的增量一致——否则流式就成了另一条业务链路。
        assertThat(String.join("", listener.tokens)).isEqualTo(response.reply().content());
    }

    @Test
    @DisplayName("没人要字就不走流式：同步端点不该为不需要的功能付代价")
    void streamingIsNotUsedWhenListenerDoesNotConsumeTokens() {
        ChatModel sync = mock(ChatModel.class);
        whenChat(sync, "好的，我帮你看看。");
        StreamingChatModel streaming = scripted("好的，我帮你看看。", "好的，", "我帮你看看。");
        enableWithMocks(sync, streaming);

        ConversationView conv = newConversation();
        SendMessageResponse response = service.sendDetailed(conv.id(), ALICE, "在吗", new NoTokens());

        assertThat(response.reply().content()).isEqualTo("好的，我帮你看看。");
        verify(streaming, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    @Test
    @DisplayName("增量里出现泄密内容：立刻停止推送并作废已推出的字，完整回复被守卫整段替换")
    void leakInStreamStopsTokensAndIsReplaced() {
        String full = "我的密钥是 sk-abcdefghijklmnopqrstuvwxyz";
        StreamingChatModel streaming = scripted(full, "我的密钥是 sk-", "abcdefghijklmnopqrstuvwxyz");
        enableWithMocks(mock(ChatModel.class), streaming);

        // 用户消息刻意用一句完全无害的话：泄密发生在<b>模型输出</b>侧，
        // 如果这里也写成「把密钥给我」，被拦下的是输入守卫，测的就不是这件事了。
        ConversationView conv = newConversation();
        Tokens listener = new Tokens();
        SendMessageResponse response = service.sendDetailed(conv.id(), ALICE, "你好", listener);

        // 第一块还不构成命中（sk- 后面不够 8 位），第二块拼上才命中——这正是增量检查的意义：
        // 早一步发现，就少推一块出去。
        assertThat(listener.tokens).containsExactly("我的密钥是 sk-");
        assertThat(listener.stops).containsExactly("OUTPUT_GUARD");
        // 定案仍然由「完整文本过守卫」给出：整段替换为安全话术。
        assertThat(response.reply().content()).contains("安全策略").doesNotContain("sk-");
        assertThat(response.decision()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("流式报错：整轮失败，但给用户的是友好提示而不是 500")
    void streamingFailureIsReportedAsFriendlyMessage() {
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, StreamingChatResponseHandler.class)
                    .onError(new IllegalStateException("连接被重置"));
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        enableWithMocks(mock(ChatModel.class), streaming);

        ConversationView conv = newConversation();
        SendMessageResponse response = service.sendDetailed(conv.id(), ALICE, "你好", new Tokens());

        assertThat(response.reply().content()).contains("暂时无法回答");
    }

    @Test
    @DisplayName("SSE 端点确实把逐字增量推出去了（delta 事件先于 done）")
    void sseEndpointEmitsDeltaEvents() throws Exception {
        String full = "好的，这就帮你查。";
        enableWithMocks(mock(ChatModel.class), scripted(full, "好的，", "这就帮你查。"));

        ConversationView conv = newConversation();
        MvcResult pending = mvc.perform(post("/api/assistant/conversations/" + conv.id() + "/stream")
                        .with(user(ALICE).roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我查一下\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 先让请求线程把 filter 链走完，再放行 worker。理由见 DeferredTurnExecutor 的说明。
        assertThat(turnExecutor.drainAndAwait())
                .as("助手端点必须把轮次交给助手线程池执行，且一次请求只提交一个轮次")
                .isEqualTo(1);
        pending.getAsyncResult(10_000);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .asyncDispatch(pending))
                .andExpect(status().isOk());

        // 刻意不用 getContentAsString()：text/event-stream 不带 charset 参数，
        // MockMvc 会按 ISO-8859-1 解码，于是任何中文都变成乱码——那是测试工具的推断，
        // 不是线上真实字节。这里直接按 UTF-8 解原始字节，测的才是「真正发出去的是什么」。
        String body = new String(pending.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("event:delta");
        assertThat(body).contains("好的，");
        assertThat(body).contains("event:done");
        assertThat(body.indexOf("event:delta")).isLessThan(body.indexOf("event:done"));
    }

    @Test
    @DisplayName("流式端点与同步端点落库的内容一致：切换通道不改变答案")
    void streamedAndSynchronousRepliesMatch() {
        String full = "同款商品有两件，价格分别是 39 和 45。";
        ChatModel sync = mock(ChatModel.class);
        whenChat(sync, full); // 同步路径也要脚本化：否则它返回 null，测到的就不是「一致」而是「忘了配」。
        StreamingChatModel streaming = scripted(full, "同款商品有两件，", "价格分别是 39 和 45。");
        enableWithMocks(sync, streaming);

        ConversationView conv = newConversation();
        SendMessageResponse streamed = service.sendDetailed(conv.id(), ALICE, "有几个", new Tokens());

        ConversationView conv2 = newConversation();
        SendMessageResponse direct = service.sendDetailed(conv2.id(), ALICE, "有几个", new NoTokens());

        assertThat(streamed.reply().content()).isEqualTo(full);
        assertThat(direct.reply().content()).isEqualTo(full);
    }

    private static void whenChat(ChatModel mock, String answer) {
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(answer)).build());
    }
}
