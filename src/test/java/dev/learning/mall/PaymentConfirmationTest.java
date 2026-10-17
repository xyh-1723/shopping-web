package dev.learning.mall;

import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.api.PendingActionResult;
import dev.learning.mall.assistant.api.PendingActionView;
import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.harness.TurnScope;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.learning.mall.assistant.security.AssistantSecurityProperties;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Human-in-the-loop 支付确认的集成测试。模型用 Mock 替代，因此可以精确断言
 * 「模型发起的只是待确认记录，真正的支付只在人类确认后发生」这条核心安全性质。
 *
 * <p>覆盖：发起零副作用、本人确认完成支付、取消保持待支付、越权确认 404、
 * 一次性防重放、过期拒绝、接口层 CSRF/归属校验、非法输入的工具级拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentConfirmationTest {

    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired AssistantSecurityProperties security;
    @Autowired PendingActionService pendingActions;
    @Autowired OrderFacade orderFacade;
    @Autowired OrderInsights insights;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @AfterEach
    void cleanup() {
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        security.setPaymentConfirmTtlMinutes(10);
        rateLimiter.reset("student");
        rateLimiter.reset("classmate");
        jdbc.sql("DELETE FROM assistant_audit").update();
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    /** 造一笔真实待支付订单（幂等键随机，避免用例间冲突）。 */
    private OrderView newPendingOrder(String username) {
        String key = "hitl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return orderFacade.create(username, key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private OrderView orderOf(String username, String orderId) {
        return insights.ordersOf(username).stream().filter(o -> o.id().equals(orderId))
                .findFirst().orElseThrow();
    }

    private void enableWithMock(ChatModel mock) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));
    }

    private PendingActionView newPendingPayment(String username, String conversationId, String orderId) {
        // 工具调用必须在轮次上下文内发生：会话归属由服务端注入，工具才拿得到。
        try (TurnScope.Scope ignored = TurnScope.begin(conversationId, username)) {
            return pendingActions.requestOrderPayment(username, orderId, "DEMO_CARD");
        }
    }

    private long auditCount(String ruleId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE rule_id = :rule")
                .param("rule", ruleId).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("HITL：模型调用支付工具只产生待确认记录，订单不会被支付")
    void toolOnlyCreatesPendingAction() {
        OrderView order = newPendingOrder("student");
        ChatModel mock = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("r1").name("requestPayment")
                .arguments("{\"orderId\":\"" + order.id() + "\",\"method\":\"DEMO_CARD\"}")
                .build();
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("已为您发起支付确认，请在页面点击「确认支付」。")).build());
        enableWithMock(mock);

        var conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "帮我支付订单 " + order.id());

        assertThat(resp.pendingAction()).isNotNull();
        assertThat(resp.pendingAction().orderId()).isEqualTo(order.id());
        assertThat(resp.pendingAction().amount()).isEqualByComparingTo(order.total());
        assertThat(resp.pendingAction().status()).isEqualTo("PENDING");
        // 核心安全性质：模型发起 ≠ 支付发生
        assertThat(orderOf("student", order.id()).status()).isEqualTo("PENDING");
        assertThat(auditCount("HITL_PAY_REQUEST")).isEqualTo(1);
    }

    @Test
    @DisplayName("HITL：本人确认后订单完成支付，结果写回会话")
    void confirmByOwnerExecutesPayment() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());

        PendingActionResult result = service.resolvePendingAction(pending.id(), "student", true);

        assertThat(result.action().status()).isEqualTo("CONFIRMED");
        assertThat(result.message()).isNotNull();
        assertThat(result.message().content()).contains("支付完成");
        OrderView paid = orderOf("student", order.id());
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paymentMethod()).isEqualTo("DEMO_CARD");
        // 结果消息确实出现在会话里
        assertThat(service.get(conv.id(), "student").messages())
                .anyMatch(m -> m.content().contains("支付完成"));
        assertThat(auditCount("HITL_PAY_CONFIRM")).isEqualTo(1);
    }

    @Test
    @DisplayName("HITL：本人取消后订单保持待支付，可再次发起")
    void cancelKeepsOrderPending() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());

        PendingActionResult result = service.resolvePendingAction(pending.id(), "student", false);

        assertThat(result.action().status()).isEqualTo("CANCELLED");
        assertThat(result.message().content()).contains("取消");
        assertThat(orderOf("student", order.id()).status()).isEqualTo("PENDING");
        assertThat(auditCount("HITL_PAY_CANCEL")).isEqualTo(1);
    }

    @Test
    @DisplayName("HITL：他人确认你的支付请求——404 且订单不被支付，审计留痕")
    void foreignConfirmIsRejected() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());

        assertThatThrownBy(() -> service.resolvePendingAction(pending.id(), "classmate", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).status().value()).isEqualTo(404));
        assertThat(orderOf("student", order.id()).status()).isEqualTo("PENDING");
        assertThat(auditCount("HITL_FOREIGN")).isEqualTo(1);
    }

    @Test
    @DisplayName("HITL：一次性——重复确认/取消被拒，防止双击与重放")
    void resolveIsOneShot() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());

        service.resolvePendingAction(pending.id(), "student", true);

        assertThatThrownBy(() -> service.resolvePendingAction(pending.id(), "student", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).status().value()).isEqualTo(409);
                    assertThat(((BusinessException) e).code()).isEqualTo("ALREADY_RESOLVED");
                });
        assertThatThrownBy(() -> service.resolvePendingAction(pending.id(), "student", false))
                .isInstanceOf(BusinessException.class);
        // 两次重放尝试（确认一次、取消一次）各留一条审计
        assertThat(auditCount("HITL_REPLAY")).isEqualTo(2);
    }

    @Test
    @DisplayName("HITL：过期的支付确认被拒绝并标记 EXPIRED")
    void expiredActionIsRejected() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());
        jdbc.sql("UPDATE assistant_pending_action SET expires_at = :t WHERE id = :id")
                .param("t", LocalDateTime.now().minusMinutes(1)).param("id", pending.id()).update();

        assertThatThrownBy(() -> service.resolvePendingAction(pending.id(), "student", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo("PAYMENT_EXPIRED"));
        assertThat(orderOf("student", order.id()).status()).isEqualTo("PENDING");
        assertThat(auditCount("HITL_PAY_EXPIRED")).isEqualTo(1);
    }

    @Test
    @DisplayName("HITL：接口层——确认需登录 + CSRF，他人确认返回 404")
    void confirmEndpointSecurity() throws Exception {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());

        // 未登录
        mvc.perform(post("/api/assistant/pending-actions/" + pending.id() + "/confirm").with(csrf()))
                .andExpect(status().isUnauthorized());
        // 他人（带合法登录与 CSRF）：404，不暴露该动作的存在性
        mvc.perform(post("/api/assistant/pending-actions/" + pending.id() + "/confirm")
                        .with(user("classmate").roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
        // 本人：200，订单完成支付
        mvc.perform(post("/api/assistant/pending-actions/" + pending.id() + "/confirm")
                        .with(user("student").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.message.content").value(org.hamcrest.Matchers.containsString("支付完成")));
        assertThat(orderOf("student", order.id()).status()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("HITL：工具级输入校验——不存在/非待支付订单与非法支付方式都被拒")
    void toolLevelValidation() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView pending = newPendingPayment("student", conv.id(), order.id());
        service.resolvePendingAction(pending.id(), "student", true); // 订单变为已支付

        assertThatThrownBy(() -> pendingActions.requestOrderPayment("student", order.id(), "DEMO_CARD"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo("INVALID_STATE"));
        assertThatThrownBy(() -> pendingActions.requestOrderPayment("student", "no-such-order", "DEMO_CARD"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).status().value()).isEqualTo(404));
        assertThatThrownBy(() -> pendingActions.requestOrderPayment("classmate", order.id(), "DEMO_CARD"))
                .isInstanceOf(BusinessException.class); // 别人的订单，对 classmate 而言不存在
        OrderView another = newPendingOrder("student");
        assertThatThrownBy(() -> pendingActions.requestOrderPayment("student", another.id(), "BITCOIN"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo("INVALID_PAYMENT"));
    }

    @Test
    @DisplayName("HITL：同一订单重复发起会复用未过期的待确认请求，不刷卡片")
    void duplicateRequestReusesPending() {
        OrderView order = newPendingOrder("student");
        var conv = service.create("student", null);
        PendingActionView first = newPendingPayment("student", conv.id(), order.id());
        PendingActionView second = newPendingPayment("student", conv.id(), order.id());
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(service.listPendingActions(conv.id(), "student")).hasSize(1);
    }
}
