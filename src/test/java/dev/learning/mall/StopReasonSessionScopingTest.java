package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.learning.mall.assistant.api.ConversationView;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 守护 {@code stopReason()} 这类「取最近一次 run」的查询必须按会话过滤。
 *
 * <p>完整理由见 {@link StopReasons}（H2 缓存 CURRENT_TIMESTAMP 导致时间戳并列 +
 * 随机 UUID 兜底排序）。本类负责把那个场景<b>固定成必然而非概率</b>并断言后果：
 * 时间戳并列且兜底键指向别人时，未过滤会取错、按会话过滤会取对。
 *
 * <p><b>本测试是能真正拦住回归的</b>：它刻意断言 {@link StopReasons#latest} 本身，
 * 而不是自己内联一份 SQL。已实测——把 {@code latest} 的 {@code conversation_id}
 * 条件删掉，本类两个用例<b>全部</b>报红；内联副本则拦不住，别改回去。
 *
 * <p><b>本类的 fixture 必须自己清空</b>：用例会直接往 {@code assistant_run} 插行，
 * 而其他用例可能留下残留行。残留行一旦混进来，「有几行」「取到哪一行」就不再由本类决定，
 * 断言会随执行顺序飘。所以 {@code @BeforeEach} 里也要清，不只 {@code @AfterEach}。
 */
@SpringBootTest
@ActiveProfiles("test")
class StopReasonSessionScopingTest {

    @Autowired JdbcClient jdbc;
    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;

    private int originalMaxSteps;

    @BeforeEach
    void rememberDefaultsAndIsolateFixture() {
        originalMaxSteps = properties.getHarness().getMaxSteps();
        // 用例直接往 assistant_run 插行，别处残留的行会污染「有几行 / 取到哪一行」，
        // 所以进来先清一遍，不能只靠 @AfterEach。
        jdbc.sql("DELETE FROM assistant_run").update();
    }

    @AfterEach
    void cleanup() {
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        properties.getHarness().setMaxSteps(originalMaxSteps);
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("DELETE FROM assistant_run").update();   // 步骤由外键级联清除
        jdbc.sql("DELETE FROM assistant_audit").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    private static ChatResponse answer(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static ChatResponse toolTurn(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private void insertRun(String id, String conversationId, String stopReason) {
        jdbc.sql("INSERT INTO assistant_run"
                        + " (id, conversation_id, username, model, stop_reason, steps, tool_calls, wall_ms)"
                        + " VALUES (:id, :c, 'probe', 'm', :s, 1, 0, 1)")
                .param("id", id).param("c", conversationId).param("s", stopReason).update();
    }

    /**
     * 反例：不打会话条件的写法（时间戳并列 + UUID 兜底键 → 取到哪一行是随机的）。
     * <b>刻意走 {@link StopReasons} 而不是内联 SQL</b>——本测试要守的是大家实际在用的那一份。
     */
    private String stopReasonUnfiltered() {
        return StopReasons.latestIgnoringConversation(jdbc);
    }

    /**
     * AgentLoopTest / AssistantConcurrencyTest 实际在用的那一份。
     * 一旦有人把它"简化"成去掉 conversation_id，本类的断言就会红。
     */
    private String stopReasonFor(String conversationId) {
        return StopReasons.latest(jdbc, conversationId);
    }

    @Test
    @DisplayName("时间戳并列时，未过滤的查询会取到别的会话的 stop_reason；按会话过滤则取对")
    void unfilteredQueryPicksAnotherConversationRunFilteredQueryPicksTheRightOne() {
        // 两条 run：时间戳完全相同（模拟 H2 缓存），stop_reason 不同。
        // id 刻意造成本条会话的 UUID 更大 —— 于是 `ORDER BY created_at DESC, id` 的
        // 兜底键会命中「别的会话」那一行，把未过滤版本的取错行为固定成必然而非概率事件。
        String mine = "ffffffff-ffff-ffff-ffff-ffffffffffff";   // 本条会话：字典序大
        String other = "00000000-0000-0000-0000-000000000001"; // 别的会话：字典序小
        insertRun(other, "conv-other", "MAX_STEPS");
        insertRun(mine, "conv-mine", "DONE");
        jdbc.sql("UPDATE assistant_run SET created_at = '2024-01-01 00:00:00'").update();

        // 未过滤：拿到的是「别的会话」的 MAX_STEPS —— 本条会话明明是 DONE。
        assertThat(stopReasonUnfiltered()).isEqualTo("MAX_STEPS");

        // 按会话过滤：各自拿到自己的，不受并列与 UUID 字典序影响。
        assertThat(stopReasonFor("conv-mine")).isEqualTo("DONE");
        assertThat(stopReasonFor("conv-other")).isEqualTo("MAX_STEPS");
    }

    /**
     * 端到端版本：不手工造行，而是真的在同一个用例里跑<b>两条会话</b>（各自的停止原因不同），
     * 让上面那个坑在生产代码路径上真实发生一次。
     *
     * <p>这是最容易被忽略的场景：并发用例或「同一会话连续两轮」都会让库里同时存在多条
     * 属于不同会话的 run。此时 {@code stopReason()} 若不按会话过滤，断言的就不是这条会话的结果。
     */
    @Test
    @DisplayName("端到端：同一用例跑两条会话后，未过滤取到别人的 stop_reason；按会话过滤各自取对")
    void twoConversationsInOneTestUnfilteredQueryReportsTheWrongStopReason() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class)))
                // 第 1 次：会话 A 正常作答 → DONE
                .thenReturn(answer("好的，已为你处理。"))
                // 第 2 次：会话 B 还想调工具，但步数预算只有 1 → MAX_STEPS
                .thenReturn(toolTurn(ToolExecutionRequest.builder().id("r1").name("myOrders")
                        .arguments("{}").build()));
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));

        ConversationView a = service.create("student", null);
        service.sendDetailed(a.id(), "student", "你好");

        properties.getHarness().setMaxSteps(1);
        ConversationView b = service.create("student", null);
        service.sendDetailed(b.id(), "student", "看看我的订单");

        // 两条 run 都在库里，且属于不同会话。
        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_run").query(Integer.class).single()).isEqualTo(2);

        // 把时间戳对齐，模拟 H2 缓存 CURRENT_TIMESTAMP 造成的并列。
        // 实测 8 条连续 INSERT 只产生 1~4 个不同值（见 StopReasons 类注释），并列是常态；
        // 这里显式对齐是为了让本用例的结果不依赖机器负载，而不是为了让坑成立。
        jdbc.sql("UPDATE assistant_run SET created_at = '2024-01-01 00:00:00'").update();

        // 按会话过滤：各自拿到自己真实的停止原因。
        assertThat(stopReasonFor(a.id())).isEqualTo("DONE");
        assertThat(stopReasonFor(b.id())).isEqualTo("MAX_STEPS");

        // 未过滤：只能拿到其中一条。它若等于 A 的，B 就被误报；等于 B 的，A 就被误报。
        // 两条会话真实结果不同，单值查询不可能同时对二者成立 —— 这就是必须按会话过滤的原因。
        String unfiltered = stopReasonUnfiltered();
        String otherTruth = "DONE".equals(unfiltered) ? stopReasonFor(b.id()) : stopReasonFor(a.id());
        assertThat(unfiltered)
                .as("未过滤的单值查询与另一条会话的真实停止原因不符，故必须先按会话收窄再取")
                .isNotEqualTo(otherTruth);
    }
}
