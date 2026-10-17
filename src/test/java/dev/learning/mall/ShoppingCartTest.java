package dev.learning.mall;

import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.internal.ToolRegistry;
import dev.learning.mall.assistant.security.AssistantActors;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.common.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 服务端购物袋：金额规则、库存约束、用户隔离、接口鉴权。
 *
 * <p>购物袋从 localStorage 搬到服务端之后，它就不再只是前端的一个 UI 状态，
 * 而是「助手」和「页面」共用的一个业务实体。共用意味着两件事必须被测试钉住：
 * 金额只有一处算法（页面和助手报出的数必须一致），以及袋子严格属于本人。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShoppingCartTest {

    @Autowired CartService cart;
    @Autowired ToolRegistry toolRegistry;
    @Autowired AssistantActors actors;
    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private static final String ALICE = "student";
    private static final String BOB = "classmate";

    /**
     * 把库存重置回种子值。
     *
     * <p>同一个内存库被所有测试类共用，下单类测试会把库存扣掉——依赖「库存还剩多少」的用例
     * 因此会随执行顺序飘。用例之间不该有这种隐式耦合：进场先复位，出场再复位，
     * 让这个类对别人既无依赖也无影响。
     */
    private static final String RESET_STOCK = "UPDATE product SET stock = CASE id "
            + "WHEN 1 THEN 30 WHEN 2 THEN 40 WHEN 3 THEN 15 WHEN 4 THEN 25 WHEN 5 THEN 10 WHEN 6 THEN 20 END "
            + "WHERE id BETWEEN 1 AND 6";

    @BeforeEach
    void resetFixtures() {
        jdbc.sql(RESET_STOCK).update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql(RESET_STOCK).update();
        properties.setEnabled(false);
        properties.setProvider("deepseek");
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        rateLimiter.reset(ALICE);
        jdbc.sql("DELETE FROM mall_cart_item").update();
        jdbc.sql("DELETE FROM assistant_message").update();
        jdbc.sql("DELETE FROM assistant_conversation").update();
    }

    // ————— 金额规则 —————

    @Test
    @DisplayName("试算：满 99 减 10 且包邮，免运费")
    void quoteAppliesDiscountAndFreeShipping() {
        // 云朵马克杯 39 元 × 3 = 117：达到满 99 减 10，折后 107 仍包邮。
        CartView view = cart.add(ALICE, 1, 3);
        assertThat(view.subtotal()).isEqualByComparingTo("117.00");
        assertThat(view.discount()).isEqualByComparingTo("10.00");
        assertThat(view.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(view.total()).isEqualByComparingTo("107.00");
        assertThat(String.join(" | ", view.notes())).contains("满 99 减 10").contains("已包邮");
    }

    @Test
    @DisplayName("试算：未达门槛时加运费，并给出包邮差额与凑单提示")
    void quoteChargesShippingBelowThreshold() {
        // 39 + 29 = 68：无满减，运费 8，应付 76。
        cart.add(ALICE, 1, 1);
        CartView view = cart.add(ALICE, 2, 1);
        assertThat(view.subtotal()).isEqualByComparingTo("68.00");
        assertThat(view.discount()).isEqualByComparingTo("0.00");
        assertThat(view.shippingFee()).isEqualByComparingTo("8.00");
        assertThat(view.total()).isEqualByComparingTo("76.00");
        String notes = String.join(" | ", view.notes());
        assertThat(notes).contains("还差 31 元包邮").contains("再买 31 元可减 10");
    }

    @Test
    @DisplayName("试算：满减取最高一档，不叠加")
    void quoteTakesHighestTierOnly() {
        // 机械键盘 199 元 × 1：同时满足 99 与 199 两档，只减 25。
        CartView view = cart.add(ALICE, 5, 1);
        assertThat(view.discount()).isEqualByComparingTo("25.00");
        assertThat(view.total()).isEqualByComparingTo("174.00");
    }

    @Test
    @DisplayName("试算：金额恒为两位小数，不受浮点误差影响")
    void quoteScalesMoney() {
        CartView view = cart.add(ALICE, 2, 3);
        assertThat(view.total().scale()).isEqualTo(2);
        assertThat(view.total()).isEqualByComparingTo(new BigDecimal("95.00"));
    }

    // ————— 库存与边界 —————

    @Test
    @DisplayName("加购：累计数量不能超过库存")
    void addRejectsBeyondStock() {
        cart.add(ALICE, 3, 10); // 桌面小夜灯库存 15
        BusinessException error = catchThrowableOfType(
                () -> cart.add(ALICE, 3, 6), BusinessException.class);
        assertThat(error.code()).isEqualTo("OUT_OF_STOCK");
        // 被拒的加购不能留下任何痕迹
        assertThat(cart.view(ALICE).itemCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("改数量：超过库存被拒，改为 0 视为移除")
    void setQuantityValidatesAndRemoves() {
        cart.add(ALICE, 1, 1);
        assertThat(catchThrowableOfType(() -> cart.setQuantity(ALICE, 1, 99), BusinessException.class))
                .isNotNull();
        assertThat(cart.setQuantity(ALICE, 1, 0).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("加购：单件上限 99，商品不存在时报错")
    void addValidatesQuantityAndProduct() {
        assertThat(catchThrowableOfType(() -> cart.add(ALICE, 1, 100), BusinessException.class).code())
                .isEqualTo("QUANTITY_TOO_LARGE");
        assertThat(catchThrowableOfType(() -> cart.add(ALICE, 999999, 1), BusinessException.class))
                .isNotNull();
    }

    // ————— 用户隔离 —————

    @Test
    @DisplayName("隔离：A 的购物袋 B 看不到，也改不动")
    void cartIsScopedToOwner() {
        cart.add(ALICE, 1, 2);
        assertThat(cart.view(BOB).isEmpty()).isTrue();
        // B 删自己袋子里的同一个商品编号，不能影响 A
        cart.remove(BOB, 1);
        assertThat(cart.view(ALICE).itemCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("清空：只清自己的袋子")
    void clearIsScopedToOwner() {
        cart.add(ALICE, 1, 1);
        cart.add(BOB, 2, 1);
        cart.clear(ALICE);
        assertThat(cart.view(ALICE).isEmpty()).isTrue();
        assertThat(cart.view(BOB).itemCount()).isEqualTo(1);
    }

    // ————— 接口层 —————

    @Test
    @DisplayName("接口：加购、改量、删除、清空全链路，返回的金额与 Service 一致")
    void cartApiRoundTrip() throws Exception {
        mvc.perform(post("/api/cart/items").with(user(ALICE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.total").value(107.00));

        mvc.perform(get("/api/cart").with(user(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("云朵马克杯"));

        mvc.perform(put("/api/cart/items/1").with(user(ALICE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(47.00)); // 39 + 8 运费

        mvc.perform(delete("/api/cart/items/1").with(user(ALICE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        assertThat(cart.add(ALICE, 2, 1).itemCount()).isEqualTo(1);
        mvc.perform(delete("/api/cart").with(user(ALICE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("接口：未登录被拒；库存不足返回 409 且不改动购物袋")
    void cartApiRejectsAnonymousAndOutOfStock() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart/items").with(user(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isForbidden()); // 缺 CSRF

        mvc.perform(post("/api/cart/items").with(user(ALICE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":5,\"quantity\":11}"))
                .andExpect(status().isConflict());
        assertThat(cart.view(ALICE).isEmpty()).isTrue();
    }

    // ————— 与助手工具链的接线 —————

    @Test
    @DisplayName("工具：顾客可见购物袋与记忆工具，店员工具不可见；购物袋写操作被标记为效果型")
    void toolBundleWiring() {
        ToolRegistry.Bundle customer = toolRegistry.forActor(actors.resolve(ALICE));
        assertThat(customer.routes()).containsKeys(
                "addToCart", "setCartQuantity", "removeFromCart", "clearCart",
                "viewCart", "compareProducts", "requestClarification",
                "rememberPreference", "recallPreferences", "forgetPreference");
        assertThat(customer.routes()).doesNotContainKey("customerOrders");

        ToolRegistry.Bundle staff = toolRegistry.forActor(actors.resolve("admin"));
        assertThat(staff.routes()).containsKey("customerOrders");

        // 写购物袋必须留在调用线程执行：并行执行会丢掉本轮上下文（见 Effectful 说明）。
        assertThat(customer.effectful())
                .contains("addToCart", "setCartQuantity", "removeFromCart", "clearCart")
                .doesNotContain("viewCart", "compareProducts", "rememberPreference");
    }

    @Test
    @DisplayName("端到端：模型调用加购工具后，服务端购物袋里真的有东西，金额与页面一致")
    void modelCanAddToCart() {
        ChatModel mock = mock(ChatModel.class);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("a1").name("addToCart")
                .arguments("{\"productId\":1,\"quantity\":3}").build();
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(request))).build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("已经放进购物袋了，三件云朵马克杯一共 107 元。")).build());
        properties.setEnabled(true);
        properties.setProvider("zhipu");
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));

        var conv = service.create(ALICE, null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), ALICE, "帮我加三件云朵马克杯");

        assertThat(resp.reply().content()).contains("107");
        // 助手加进去的东西，用户在购物袋页面（同一个 CartService）能看到。
        CartView view = cart.view(ALICE);
        assertThat(view.itemCount()).isEqualTo(3);
        assertThat(view.total()).isEqualByComparingTo("107.00");
    }
}
