package dev.learning.mall;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.learning.mall.assistant.api.*;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.common.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 购物助手集成测试。模型调用用 Mock ChatModel 验证工具编排与 toolData 落库，
 * 不依赖真实智谱 API；未启用降级路径直接走真实 Service。
 */
@SpringBootTest
@ActiveProfiles("test")
class AssistantIntegrationTest {
    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired JdbcClient jdbc;

    @AfterEach void resetAssistant() {
        // 恢复默认未启用状态，避免影响其它测试。
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    private ChatResponse toolRequest(String tool, String args) {
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("r1").name(tool).arguments(args).build();
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(req))).build();
    }
    private ChatResponse textReply(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
    private void enableWithMock(ChatModel mock) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));
    }

    @Test void disabledReturnsDemoHintAndPersistsMessages() {
        ConversationView conv = service.create("student", null);
        ChatMessageView reply = service.send(conv.id(), "student", "你好");
        assertThat(reply.content()).contains("尚未启用");
        // 用户消息与演示回复都应落库。
        ConversationDetail detail = service.get(conv.id(), "student");
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.messages().get(0).role()).isEqualTo("USER");
        assertThat(detail.messages().get(1).role()).isEqualTo("ASSISTANT");
    }

    @Test void firstMessageRenamesConversationFromContent() {
        ConversationView conv = service.create("student", null);
        service.send(conv.id(), "student", "帮我挑一个 200 元以内的生日礼物");
        ConversationView renamed = service.list("student").getFirst();
        assertThat(renamed.title()).startsWith("帮我挑一个 200 元以内的生日");
    }

    @Test void enabledPathCallsToolAndPersistsProductCards() {
        ChatModel mock = mock(ChatModel.class);
        // 第一轮：模型请求调用 searchProducts；第二轮：返回文本回复。
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(toolRequest("recommendByBudget", "{\"scenario\":\"送礼\",\"budget\":100}"))
                .thenReturn(textReply("为你推荐云朵马克杯，适合送礼。"));
        enableWithMock(mock);

        ConversationView conv = service.create("student", null);
        ChatMessageView reply = service.send(conv.id(), "student", "200 元以内送朋友的礼物");

        assertThat(reply.content()).contains("云朵马克杯");
        assertThat(reply.toolData()).isNotNull().contains("\"id\"");
        // 模型被调用两次（工具请求 + 最终回复）。
        verify(mock, times(2)).chat(any(ChatRequest.class));
        // toolData 已落库。
        ConversationDetail detail = service.get(conv.id(), "student");
        assertThat(detail.messages().getLast().toolData()).isNotNull();
    }

    @Test void modelFailureReturnsFriendlyMessageInsteadOf500() {
        ChatModel mock = mock(ChatModel.class);
        when(mock.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("网络超时"));
        enableWithMock(mock);

        ConversationView conv = service.create("student", null);
        ChatMessageView reply = service.send(conv.id(), "student", "你好");
        assertThat(reply.content()).contains("暂时无法回答");
        // 失败时用户消息仍已落库，助手消息为兜底提示。
        assertThat(service.get(conv.id(), "student").messages()).hasSize(2);
    }

    @Test void conversationsAreIsolatedPerUser() {
        ConversationView studentConv = service.create("student", null);
        // classmate 看不到 student 的会话。
        assertThat(service.list("classmate")).isEmpty();
        assertThatThrownBy(() -> service.get(studentConv.id(), "classmate"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send(studentConv.id(), "classmate", "你好"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(studentConv.id(), "classmate"))
                .isInstanceOf(BusinessException.class);
    }
}
