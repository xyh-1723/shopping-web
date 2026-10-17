package dev.learning.mall;

import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.api.PendingActionResult;
import dev.learning.mall.assistant.api.PendingActionView;
import dev.learning.mall.assistant.api.SendMessageResponse;
import dev.learning.mall.assistant.harness.TurnScope;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.internal.OrderTools;
import dev.learning.mall.assistant.security.AssistantRateLimiter;
import dev.learning.mall.assistant.security.AssistantSecurityProperties;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * 助手下单闭环的端到端测试：模型发起 → 人类确认 → 才真的落单。
 *
 * <p>它补的是这个项目长期缺的那一跳。在此之前助手能把商品加进购物袋、能发起支付确认，
 * 却<b>不能下单</b>——因为创建订单的唯一入口是页面接口，而支付工具只能对付已存在的订单。
 * 于是「导购闭环」的成交环节不在闭环里：用户说「帮我买下」，助手只能让他自己去点。
 *
 * <p>这里要守的核心不变量只有一条：<b>确认之前，数据库零新增行。</b>
 * 断言的是行数，不是日志、不是返回值——只有行数能证明闸门真的拦住了副作用。
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentOrderCheckoutTest {

    @Autowired AssistantService service;
    @Autowired AssistantProperties properties;
    @Autowired AssistantSecurityProperties security;
    @Autowired PendingActionService pendingActions;
    @Autowired OrderTools orderTools;
    @Autowired OrderFacade orderFacade;
    @Autowired CartService cart;
    @Autowired AssistantRateLimiter rateLimiter;
    @Autowired JdbcClient jdbc;

    /**
     * 前后都清一遍。
     *
     * <p>为什么 @BeforeEach 也要清：<b>库存基线不能靠「上一个测试跑完后」</b>。
     * 测试顺序不是契约，JUnit 也不保证按声明顺序跑——第一个跑的测试看到的是
     * 种子数据里的库存（30），其余看到的才是 50。断言写成绝对数字就会随机红。
     * 每个用例从同一个已知状态出发，顺序才不再影响结果。
     */
    @BeforeEach
    @AfterEach
    void cleanup() {
        properties.setEnabled(false);
        properties.getZhipu().setApiKey("");
        ReflectionTestUtils.setField(service, "chatModel", Optional.empty());
        security.setPaymentConfirmTtlMinutes(10);
        rateLimiter.reset("student");
        rateLimiter.reset("classmate");
        for (String table : List.of("assistant_audit", "assistant_pending_action", "assistant_message",
                "assistant_conversation", "notification", "outbox_event", "order_item", "mall_order",
                "mall_cart_item")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock = 50").update();
    }

    // ——————————————————— 闸门：确认之前零副作用 ———————————————————

    @Test
    @DisplayName("模型发起下单只产生待确认记录：mall_order 零新增行、库存不变")
    void unconfirmedCreatesNoOrder() {
        cart.add("student", 1, 2); // 小计 78 + 运费 8 = 应付 86
        int before = count("mall_order");
        int stockBefore = stockOf(1);

        PendingActionView pending = requestOrderCreate("student");

        assertThat(pending.kind()).isEqualTo(PendingActionService.KIND_ORDER_CREATE);
        assertThat(pending.amount()).isEqualByComparingTo("86.00");
        assertThat(pending.status()).isEqualTo("PENDING");
        // 核心不变量：闸门真的拦住了副作用。
        assertThat(count("mall_order")).isEqualTo(before);
        assertThat(stockOf(1)).isEqualTo(stockBefore);
        // 购物袋也还在——没确认就不该动它。
        assertThat(cart.view("student").itemCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("完整走一遍执行循环：对话里说「帮我下单」也能拿到确认卡片，且仍然零副作用")
    void loopProducesConfirmationCardWithoutSideEffects() {
        cart.add("student", 1, 2);
        int before = count("mall_order");

        ChatModel mock = mock(ChatModel.class);
        ToolExecutionRequest tool = ToolExecutionRequest.builder()
                .id("r1").name("createOrder").arguments("{}").build();
        when(mock.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(tool))).build())
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("已帮你发起下单确认，请点击卡片上的「确认下单」。")).build());
        enableWithMock(mock);

        var conv = service.create("student", null);
        SendMessageResponse resp = service.sendDetailed(conv.id(), "student", "帮我把袋子里的买了");

        assertThat(resp.pendingAction()).isNotNull();
        assertThat(resp.pendingAction().kind()).isEqualTo(PendingActionService.KIND_ORDER_CREATE);
        assertThat(resp.pendingAction().amount()).isEqualByComparingTo("86.00");
        assertThat(count("mall_order")).isEqualTo(before);
    }

    // ——————————————————— 确认之后：落单 + 扣库存 + 清袋 ———————————————————

    @Test
    @DisplayName("本人确认后：订单落库、库存按量扣减、购物袋清空")
    void confirmCreatesOrderAndClearsCart() {
        cart.add("student", 1, 2);
        int stockBefore = stockOf(1);
        PendingActionView pending = requestOrderCreate("student");

        PendingActionResult result = service.resolvePendingAction(pending.id(), "student", true);

        assertThat(result.action().status()).isEqualTo("CONFIRMED");
        assertThat(result.message().content()).contains("下单成功");
        assertThat(count("mall_order")).isEqualTo(1);
        assertThat(stockOf(1)).isEqualTo(stockBefore - 2);
        // 清袋与落单同一事务：袋子必须一起空掉，否则就是幽灵袋。
        assertThat(cart.view("student").isEmpty()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mall_cart_item WHERE username = 'student'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("确认后落单的金额就是购物袋里承诺的那个数")
    void confirmedOrderCarriesThePromisedAmount() {
        cart.add("student", 5, 1); // 199 − 25 = 174，包邮
        PendingActionView pending = requestOrderCreate("student");

        service.resolvePendingAction(pending.id(), "student", true);

        String id = jdbc.sql("SELECT id FROM mall_order WHERE username = 'student'")
                .query(String.class).single();
        // 直接读库，不读视图：袋子里的承诺与库里的钱必须是同一个数，
        // 中间隔一层映射就可能把「算过一遍」藏起来。
        assertThat(column(id, "pay_amount")).isEqualByComparingTo("174.00");
        assertThat(column(id, "subtotal")).isEqualByComparingTo("199.00");
        assertThat(column(id, "discount")).isEqualByComparingTo("25.00");
        assertThat(column(id, "shipping_fee")).isEqualByComparingTo("0.00");
        // 历史列与应付列同源写入，不允许漂移。
        assertThat(column(id, "total")).isEqualByComparingTo("174.00");
    }

    /**
     * 串行重复确认：被<b>预检</b>拦住。
     *
     * <p>这里守的是「第二个请求连事务都进不去」。反向验证时故意去掉原子迁移的
     * {@code status='PENDING'} 条件，这条<b>依然是绿的</b>——因为它根本走不到那一步。
     * 真正依赖原子迁移的是下面那条并发测试，别把这两个层次混为一谈。
     */
    @Test
    @DisplayName("串行重复确认同一张卡片：第二个请求被预检拒绝，只有一笔订单")
    void secondConfirmIsRejectedByPrecheck() {
        cart.add("student", 1, 1);
        PendingActionView pending = requestOrderCreate("student");

        service.resolvePendingAction(pending.id(), "student", true);
        assertThatThrownBy(() -> service.resolvePendingAction(pending.id(), "student", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo("ALREADY_RESOLVED"));

        assertThat(count("mall_order")).isEqualTo(1);
        assertThat(stockOf(1)).isEqualTo(49); // 50 − 1，只扣了一次
    }

    /**
     * 并发重复确认：被<b>一次性原子迁移</b>拦住。
     *
     * <p>这是唯一能证明 {@code UPDATE ... WHERE status='PENDING'} 有意义的测试。
     * 串行路径上，{@code resolve} 的状态预检会先挡一层，原子迁移形同虚设；
     * 只有两个请求<b>同时</b>通过预检、又同时去抢同一行时，才轮到它决定胜负——
     * 「谁把状态改掉了，谁才有权执行」，另一个拿到 0 行影响、回滚、安静失败。
     *
     * <p>跑多轮是为了把线程交错的概率堆上去：单轮时序不保证，断言却必须在
     * 每一次交错下都成立。失败方必须是 {@code ALREADY_RESOLVED} 这类业务异常，
     * 不能是 500——那意味着唯一键在替状态机兜底，兜底本身没错，
     * 但它说明「状态转换权」已经丢了。
     */
    @Test
    @DisplayName("并发确认同一张卡片：恰好一次成功，落单与扣库存各只发生一次")
    void concurrentConfirmProducesExactlyOneOrder() throws Exception {
        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            cleanup();
            cart.add("student", 1, 1);
            PendingActionView pending = requestOrderCreate("student");

            int threads = 2;
            CyclicBarrier gate = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(3, TimeUnit.SECONDS);
                    try {
                        service.resolvePendingAction(pending.id(), "student", true);
                        return new Outcome(true, null);
                    } catch (BusinessException e) {
                        return new Outcome(false, e.code());
                    } catch (RuntimeException e) {
                        return new Outcome(false, "NOT_BUSINESS:" + e.getClass().getSimpleName());
                    }
                }));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            long won = futures.stream().filter(f -> outcome(f).won).count();
            List<String> lost = futures.stream().map(AgentOrderCheckoutTest::outcome)
                    .filter(o -> !o.won).map(Outcome::code).toList();

            assertThat(won).as("第 %d 轮：恰好一个请求拿到状态转换权", round).isEqualTo(1);
            assertThat(lost).as("第 %d 轮：落败方必须是可预期的业务拒绝，不能是未捕获异常", round)
                    .containsExactly("ALREADY_RESOLVED");
            assertThat(count("mall_order")).as("第 %d 轮：订单只落一笔", round).isEqualTo(1);
            assertThat(stockOf(1)).as("第 %d 轮：库存只扣一次", round).isEqualTo(49);
        }
    }

    private record Outcome(boolean won, String code) {}

    private static Outcome outcome(Future<Outcome> future) {
        try {
            return future.get();
        } catch (Exception e) {
            return new Outcome(false, "NOT_BUSINESS:" + e.getClass().getSimpleName());
        }
    }

    // ——————————————————— 取消闸门 ———————————————————

    @Test
    @DisplayName("取消确认：确认前不取消，确认后订单关闭且库存归还")
    void cancelOrderGoesThroughTheSameGate() throws Exception {
        OrderView order = newPendingOrder("student");
        int stockBefore = stockOf(1);
        assertThat(stockBefore).isEqualTo(49);

        PendingActionView pending = requestOrderCancel("student", order.id());
        assertThat(pending.kind()).isEqualTo(PendingActionService.KIND_ORDER_CANCEL);
        // 发起 ≠ 取消
        assertThat(statusOf(order.id())).isEqualTo("PENDING");

        PendingActionResult result = service.resolvePendingAction(pending.id(), "student", true);
        assertThat(result.action().status()).isEqualTo("CONFIRMED");
        assertThat(statusOf(order.id())).isEqualTo("CANCELLED");
        assertThat(stockOf(1)).isEqualTo(50); // 库存已归还
    }

    // ——————————————————— 越权与边界 ———————————————————

    @Test
    @DisplayName("他人确认下单卡片：404，且不落单")
    void foreignUserCannotConfirm() {
        cart.add("student", 1, 1);
        PendingActionView pending = requestOrderCreate("student");

        assertThatThrownBy(() -> service.resolvePendingAction(pending.id(), "classmate", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo("NOT_FOUND"));

        assertThat(count("mall_order")).isZero();
        assertThat(securityAuditCount("HITL_FOREIGN")).isEqualTo(1);
    }

    @Test
    @DisplayName("购物袋为空时发起下单：工具返回可读错误，不会生成待确认卡片")
    void emptyCartIsRejected() throws Exception {
        var conv = service.create("student", null);
        String result;
        try (TurnScope.Scope ignored = TurnScope.begin(conv.id(), "student")) {
            result = orderTools.createOrder("student");
        }
        // 工具刻意<b>不</b>抛异常：异常冒泡出去会让整轮崩掉，模型看不到原因，
        // 只能给用户编一个。所以这里断言的是「错误 JSON 里有没有如实说明」。
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(result);
        assertThat(node.get("error").asText()).isEqualTo("CART_EMPTY");
        assertThat(node.get("message").asText()).contains("购物袋是空的");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM assistant_pending_action")
                .query(Integer.class).single()).isZero();
    }

    // ——————————————————— 辅助 ———————————————————

    private PendingActionView requestOrderCreate(String username) {
        // 工具必须在轮次上下文里执行：会话归属由服务端注入，卡片才能挂回本轮。
        // 这里用真实的会话 id，不用随机 UUID——确认之后助手要往会话里回写一条
        // 「下单成功」的消息，会话不存在时写不进去，而那正是用户看到的确认回执。
        var conv = service.create(username, null);
        try (TurnScope.Scope ignored = TurnScope.begin(conv.id(), username)) {
            return pendingActions.requestOrderCreate(username, cart.orderLines(username));
        }
    }

    private PendingActionView requestOrderCancel(String username, String orderId) {
        var conv = service.create(username, null);
        try (TurnScope.Scope ignored = TurnScope.begin(conv.id(), username)) {
            return pendingActions.requestOrderCancel(username, orderId);
        }
    }

    private OrderView newPendingOrder(String username) {
        return orderFacade.create(username, "aco-" + UUID.randomUUID().toString().replace("-", ""),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private void enableWithMock(ChatModel mock) {
        properties.setEnabled(true);
        properties.getZhipu().setApiKey("test-key");
        ReflectionTestUtils.setField(service, "chatModel", Optional.of(mock));
    }

    private int count(String table) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM product WHERE id = :id")
                .param("id", productId).query(Integer.class).single();
    }

    private BigDecimal column(String orderId, String column) {
        return jdbc.sql("SELECT " + column + " FROM mall_order WHERE id = :id")
                .param("id", orderId).query(BigDecimal.class).single();
    }

    private String statusOf(String orderId) {
        return jdbc.sql("SELECT status FROM mall_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
    }

    private long securityAuditCount(String ruleId) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE rule_id = :rule")
                .param("rule", ruleId).query(Integer.class).single();
        return n == null ? 0 : n;
    }
}
