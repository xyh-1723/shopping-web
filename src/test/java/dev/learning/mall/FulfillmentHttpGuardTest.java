package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderRepository;
import dev.learning.mall.order.internal.OrderTransactions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 履约路径在 <b>HTTP 层</b> 的错误码守卫（T12 收尾）。
 *
 * <p>为什么单独开一个文件：{@link FulfillmentFlowTest} 的 9 条守的是「Java 入口 + 数据库约束」
 * 这一层，它断言的是 {@code BusinessException} 与库里的列；而「用户/调用方从 HTTP 上到底
 * 拿到什么」它只在最后一个用例里顺带看了一眼 200/404。这里专门守<b>状态码的形状</b>：
 * 履约路径上的任何可预期业务拒绝都必须是 4xx，<b>绝不可以是 500</b>。
 *
 * <p>500 的代价不只是难看：调用方（含外部系统集成侧）会把 500 归类成「服务端出错、可重试」，
 * 于是「订单没付钱，不能发货」这种确定性拒绝会被当成抖动反复重试，而真正的原因在日志里
 * 又是一条没有业务语义的堆栈——线上等于静默。
 *
 * <h3>两条断言的分工</h3>
 * <ul>
 *   <li>{@link #shippingUnpaidOrderIsRejectedWith4xx}：HEAD 上未支付订单走 0 行分支，
 *       本来就应该是 409；它守的是「谁把 {@code status='PAID'} 从 {@code markShipped} 的
 *       WHERE 里挪走」这一类回归。</li>
 *   <li>{@link #constraintViolationOnShipIsTranslatedTo4xx}：直接让写调用抛
 *       {@code DataIntegrityViolationException}，证明<b>翻译层真的存在</b>——没有它，这条
 *       用例会立刻变成 500。这是对翻译层本身的变异验证，不依赖有人去改 SQL。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FulfillmentHttpGuardTest {

    @Autowired OrderFacade facade;
    @Autowired OrderTransactions orders;
    @Autowired FulfillmentService fulfillment;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    /**
     * 用 spy 在「写调用」这一层注入约束异常：真实事务、真实入口、真实 HTTP，
     * 只有 UPDATE 的那一次调用被替换。比改 SQL 更能说明「翻译层守的是哪个边界」。
     */
    @MockitoSpyBean OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "price_quote", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock=10").update();
        reset(orderRepository);
    }

    @AfterEach
    void tearDown() {
        reset(orderRepository);
        for (String table : List.of("notification", "outbox_event", "order_item", "price_quote", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    // ——————————————————— 发货：未支付 ———————————————————

    @Test
    @DisplayName("发货未支付的订单：返回 409 INVALID_STATE，绝不是 500")
    void shippingUnpaidOrderIsRejectedWith4xx() throws Exception {
        OrderView pending = create("student");

        MvcResult result = mvc.perform(post("/api/orders/" + pending.id() + "/ship")
                        .with(user("admin").roles("USER", "STAFF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingNo\":\"SF-UNPAID-001\"}"))
                .andExpect(jsonPath("$.code").exists())
                .andReturn();

        // 这里刻意<b>不</b>断言具体的 code：未支付发货在 HEAD 上走「0 行 → INVALID_STATE」，
        // 但若有人把 status='PAID' 从 markShipped 的 WHERE 里挪走，写就真发出去了，
        // 届时应由 ck_order_fulfillment_* 把它打回、并被翻译层变成另一个 409 业务码。
        // 本条守的是「必须是 4xx 且业务可读」，不是「必须是某一个码」。
        int http = result.getResponse().getStatus();
        assertThat(http).as("可预期的业务拒绝必须是 4xx，500 等于把确定性拒绝伪装成服务器故障")
                .isBetween(400, 499);
        assertThat(http).isNotEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("INTERNAL_ERROR");

        // 拒绝不能夹带副作用：订单仍是待支付、未进入履约。
        assertThat(statusOf(pending.id())).isEqualTo("PENDING");
        assertThat(fulfillmentOf(pending.id())).isEqualTo("NONE");
        assertThat(countEvent("ORDER_SHIPPED", pending.id())).isZero();
    }

    @Test
    @DisplayName("收货未支付的订单：同样是 4xx，且订单不进入履约")
    void receivingUnpaidOrderIsRejectedWith4xx() throws Exception {
        OrderView pending = create("student");

        MvcResult result = mvc.perform(post("/api/orders/" + pending.id() + "/receive")
                        .with(user("student").roles("USER")).with(csrf()))
                .andReturn();

        int http = result.getResponse().getStatus();
        assertThat(http).as("收货路径也不能把可预期拒绝报成 500").isBetween(400, 499);
        assertThat(http).isNotEqualTo(500);
        assertThat(fulfillmentOf(pending.id())).isEqualTo("NONE");
    }

    // ——————————————————— 翻译层：约束异常不许冒泡成 500 ———————————————————

    @Test
    @DisplayName("写调用被履约约束打回：翻译层把它变成 409 FULFILLMENT_NEEDS_PAID，而不是 500")
    void constraintViolationOnShipIsTranslatedTo4xx() throws Exception {
        OrderView paid = paidOrder("student");   // 已支付：0 行分支不会先把它拦下
        doThrow(new DataIntegrityViolationException(
                "StatementCallback; SQL [UPDATE mall_order SET fulfillment_status='SHIPPED' ...]; "
                        + "Check constraint violation: \"CK_ORDER_FULFILLMENT_NEEDS_PAID: "
                        + "(FULFILLMENT_STATUS = 'NONE' OR STATUS = 'PAID')\""))
                .when(orderRepository).markShipped(anyString(), anyString(), anyString(),
                        any(LocalDateTime.class), any(LocalDateTime.class));

        MvcResult result = mvc.perform(post("/api/orders/" + paid.id() + "/ship")
                        .with(user("admin").roles("USER", "STAFF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingNo\":\"SF-VIOLATION\"}"))
                .andExpect(jsonPath("$.code").value("FULFILLMENT_NEEDS_PAID"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("约束异常必须被翻译成业务冲突").isEqualTo(409);
        assertThat(fulfillmentOf(paid.id())).isEqualTo("NONE");
    }

    @Test
    @DisplayName("不认识的约束名不翻译：未知的数据层错误仍然必须是 500，不能被藏进业务冲突")
    void unknownConstraintStillSurfacesAsServerError() throws Exception {
        OrderView paid = paidOrder("student");
        doThrow(new DataIntegrityViolationException(
                "Unique index or primary key violation: \"PUBLIC.PRIMARY_KEY_9 ON PUBLIC.MALL_ORDER(ID)\""))
                .when(orderRepository).markShipped(anyString(), anyString(), anyString(),
                        any(LocalDateTime.class), any(LocalDateTime.class));

        MvcResult result = mvc.perform(post("/api/orders/" + paid.id() + "/ship")
                        .with(user("admin").roles("USER", "STAFF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingNo\":\"SF-UNKNOWN\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("翻译层只认本项目自己起的履约约束名；其余一律原样抛出，免得把真 bug 藏起来")
                .isEqualTo(500);
    }

    /**
     * 另一个约束名同样要被翻译：{@code ck_order_fulfillment_status}（履约状态取值非法）。
     *
     * <p>为什么这条走 Java 入口而不是 HTTP：上面那条已经证明了「写调用抛约束异常 → HTTP 409」
     * 这条链路，这里只需要再证明<b>约束名识别不是写死一个</b>。走 Java 入口可以直接断言
     * {@code BusinessException} 的 code，不必再搭一次 HTTP。
     */
    @Test
    @DisplayName("履约状态取值非法：翻译成 FULFILLMENT_STATUS_INVALID（同一个翻译层认第二个约束名）")
    void outOfDomainFulfillmentStatusIsTranslated() {
        OrderView paid = paidOrder("student");
        doThrow(constraintViolation("CK_ORDER_FULFILLMENT_STATUS"))
                .when(orderRepository).markShipped(anyString(), anyString(), anyString(),
                        any(LocalDateTime.class), any(LocalDateTime.class));

        assertThatThrownBy(() -> fulfillment.ship(paid.id(), "admin", "SF-BAD-STATUS"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException business = (BusinessException) e;
                    assertThat(business.status().value()).isEqualTo(409);
                    assertThat(business.code()).isEqualTo("FULFILLMENT_STATUS_INVALID");
                });
        assertThat(fulfillmentOf(paid.id())).isEqualTo("NONE");
    }

    /**
     * 收货路径同样被翻译层包住。
     *
     * <p>{@code markReceived} 的 SQL 在 SHIPPED 行上写 RECEIVED，一旦这一行的
     * {@code shipped_at} 是空的（历史脏数据 / 有人在库里手改过），
     * {@code ck_order_fulfillment_fields} 会把它打回——那不是一个 500 该描述的事情。
     */
    @Test
    @DisplayName("收货路径的约束异常同样翻译成 409（FULFILLMENT_MOMENT_MISSING），不冒泡成 500")
    void constraintViolationOnReceiveIsTranslated() {
        OrderView paid = paidOrder("student");
        assertThat(fulfillment.ship(paid.id(), "admin", "SF-RECV").fulfillmentStatus()).isEqualTo("SHIPPED");
        doThrow(constraintViolation("CK_ORDER_FULFILLMENT_FIELDS"))
                .when(orderRepository).markReceived(anyString(), anyString(), any(LocalDateTime.class));

        assertThatThrownBy(() -> fulfillment.receive(paid.id(), "student"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException business = (BusinessException) e;
                    assertThat(business.status().value()).isEqualTo(409);
                    assertThat(business.code()).isEqualTo("FULFILLMENT_MOMENT_MISSING");
                });
    }

    /** 造一个 H2 形状的约束异常：约束名只出现在最内层消息里（与真实堆栈一致）。 */
    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException(
                "StatementCallback; SQL [UPDATE mall_order SET fulfillment_status=...]; "
                        + "Check constraint violation: \"" + constraintName + ": (...)\"");
    }

    // ——————————————————— 辅助 ———————————————————

    private OrderView create(String user) {
        return facade.create(user, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private OrderView paidOrder(String user) {
        return orders.pay(create(user).id(), user, "DEMO_CARD");
    }

    private String statusOf(String id) {
        return jdbc.sql("SELECT status FROM mall_order WHERE id=:id").param("id", id).query(String.class).single();
    }

    private String fulfillmentOf(String id) {
        return jdbc.sql("SELECT fulfillment_status FROM mall_order WHERE id=:id")
                .param("id", id).query(String.class).single();
    }

    private int countEvent(String type, String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE event_type=:t AND aggregate_id=:id")
                .param("t", type).param("id", orderId).query(Integer.class).single();
    }
}
