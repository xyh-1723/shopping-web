package dev.learning.mall.order.internal;

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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * <p><b>为什么这个文件放在 {@code order.internal} 包下</b>：它要碰两样东西——
 * {@link OrderRepository} 的写调用（注入约束异常）与 {@link FulfillmentService#translate}
 * （喂真实 H2 异常）。把测试搬进同一个包，就<b>不必为了可见性把内部方法 public 出去</b>：
 * 「为了 Mockito 能打到就放宽可见性」等于把模块内部通路变成对外端口，
 * 这个代价由 {@link #fulfillmentWriteMethodsStayInternal} 反向守住。
 *
 * <p>为什么单独开一个文件：{@code FulfillmentFlowTest} 的 9 条守的是「Java 入口 + 数据库约束」
 * 这一层，它断言的是 {@code BusinessException} 与库里的列；而「用户/调用方从 HTTP 上到底
 * 拿到什么」它只在最后一个用例里顺带看了一眼 200/404。这里专门守<b>状态码的形状</b>：
 * 履约路径上的任何可预期业务拒绝都必须是 4xx，<b>绝不可以是 500</b>。
 *
 * <p>500 的代价不只是难看：调用方（含外部系统集成侧）会把 500 归类成「服务端出错、可重试」，
 * 于是「订单没付钱，不能发货」这种确定性拒绝会被当成抖动反复重试，而真正的原因在日志里
 * 又是一条没有业务语义的堆栈——线上等于静默。
 *
 * <h3>四类断言的分工</h3>
 * <ul>
 *   <li>{@link #shippingUnpaidOrderIsRejectedWith4xx}：HEAD 上未支付订单走 0 行分支，
 *       本来就应该是 409；它守的是「谁把 {@code status='PAID'} 从 {@code markShipped} 的
 *       WHERE 里挪走」这一类回归。</li>
 *   <li>{@link #constraintViolationOnShipIsTranslatedTo4xx}：用 spy 让写调用抛
 *       {@code DataIntegrityViolationException}，证明<b>翻译层真的存在</b>——没有它，这条
 *       用例会立刻变成 500。这是对翻译层本身的变异验证，不依赖有人去改 SQL。</li>
 *   <li>{@link #realH2ConstraintNamesAreToldApart}：<b>无 spy 实证</b>。上面那些异常文本是
 *       手写的，无法证明「三个约束名在<b>现实里</b>能被分别识别」（这正是变异 C 暴露的风险）。
 *       这一条真跑三条会被 H2 拒绝的 UPDATE，把数据库自己抛的异常原文喂给翻译层，
 *       断言三个业务码互不相同。</li>
 *   <li>{@link #fulfillmentWriteMethodsStayInternal}：可见性反向守卫。</li>
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
     *
     * <p>注意 {@link #realH2ConstraintNamesAreToldApart} 与
     * {@link #fulfillmentWriteMethodsStayInternal} 两条<b>不</b>使用它：它们要的是真实数据库行为。
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

    // ——————————————————— 无 spy 实证：真实 H2 的三条约束名 ———————————————————

    /**
     * <b>这一条不注入任何假异常</b>：三条 UPDATE 都是真跑的，异常是 H2 自己抛的，
     * 消息原文也不是手写的。它证明的是「三个约束名在现实里能被分别识别」——
     * 前面那些用 {@link #constraintViolation(String)} 手搓文本的用例证明不了这一点，
     * 因为文本是人给的，写错了也不会红。
     *
     * <p>三条 UPDATE 各自只踩一条约束（这一点由 {@code artifacts/t12-probe-h2-constraint-text.log}
     * 里的探针实测确认，不是推理）：
     * <ol>
     *   <li>已发货 + 已支付的订单改成非 {@code PAID} → {@code ck_order_fulfillment_needs_paid}
     *       （{@code paid_at} 同步清成 NULL，才不会顺带踩 {@code ck_order_paid_needs_moment}）；</li>
     *   <li>已支付 + {@code NONE} 的订单直接跳 {@code RECEIVED}（没给 {@code shipped_at}）
     *       → {@code ck_order_fulfillment_fields}；</li>
     *   <li>履约状态写成取值域外的值 → {@code ck_order_fulfillment_status}。</li>
     * </ol>
     *
     * <p><b>诚实声明</b>：这三条 UPDATE 是测试自己拼的，<b>不是</b>业务代码会发出的语句。
     * 业务路径上这三条约束<b>构造不可达</b>（理由见 {@link FulfillmentService} 类注释
     * 「诚实边界」：每条 UPDATE 的 WHERE 已经保证了对应的 CHECK）。所以这一条证明的是
     * 「一旦有约束异常逃逸出来，三个名字能被分开」，<b>不</b>证明「业务路径上真会逃逸」。
     */
    @Test
    @DisplayName("无 spy 实证：真实 H2 抛出的三条约束异常被翻译成三个不同的业务码")
    void realH2ConstraintNamesAreToldApart() {
        // ① ck_order_fulfillment_needs_paid
        OrderView shipped = paidOrder("student");
        assertThat(fulfillment.ship(shipped.id(), "admin", "SF-REAL-1").fulfillmentStatus()).isEqualTo("SHIPPED");
        DataIntegrityViolationException needsPaid = realViolation(
                "UPDATE mall_order SET status='CANCELLED', paid_at=NULL WHERE id=:id", shipped.id());
        assertThat(rawText(needsPaid))
                .as("H2 的真实报错形如 Check constraint violation: \"约束名: \"，匹配规则必须贴着这个形态")
                .contains("CK_ORDER_FULFILLMENT_NEEDS_PAID:")
                .as("NEEDS_PAID 的原文里不该出现 STATUS 的名字：出现即说明前缀碰撞会发生")
                .doesNotContain("CK_ORDER_FULFILLMENT_STATUS:");
        BusinessException translatedNeedsPaid = translateReal(needsPaid);

        // ② ck_order_fulfillment_fields
        OrderView paid = paidOrder("student");
        DataIntegrityViolationException fields = realViolation(
                "UPDATE mall_order SET fulfillment_status='RECEIVED' WHERE id=:id", paid.id());
        assertThat(rawText(fields)).contains("CK_ORDER_FULFILLMENT_FIELDS:");
        BusinessException translatedFields = translateReal(fields);

        // ③ ck_order_fulfillment_status
        OrderView another = paidOrder("student");
        DataIntegrityViolationException status = realViolation(
                "UPDATE mall_order SET fulfillment_status='BOGUS' WHERE id=:id", another.id());
        assertThat(rawText(status)).contains("CK_ORDER_FULFILLMENT_STATUS:");
        BusinessException translatedStatus = translateReal(status);

        assertThat(List.of(translatedNeedsPaid.code(), translatedFields.code(), translatedStatus.code()))
                .as("三条约束必须给出三个互不相同的业务码，否则翻译层只是在给所有约束贴同一个标签")
                .doesNotHaveDuplicates()
                .containsExactly("FULFILLMENT_NEEDS_PAID", "FULFILLMENT_MOMENT_MISSING",
                        "FULFILLMENT_STATUS_INVALID");
        for (BusinessException e : List.of(translatedNeedsPaid, translatedFields, translatedStatus)) {
            assertThat(e.status().value()).as("约束异常翻译出来的是业务冲突（409），不是服务器故障")
                    .isEqualTo(409);
        }

        // 三条 UPDATE 都被拒绝了，不该留下任何半吊子状态。
        assertThat(fulfillmentOf(paid.id())).isEqualTo("NONE");
        assertThat(fulfillmentOf(another.id())).isEqualTo("NONE");
        assertThat(fulfillmentOf(shipped.id())).isEqualTo("SHIPPED");
    }

    /**
     * 可见性的反向守卫：仓储的履约写调用<b>不许为了测试方便而 public 出去</b>。
     *
     * <p>本文件之所以在 {@code order.internal} 包下，就是为了不必放宽它们；
     * 这条断言把「不许放宽」写成可执行的事实——谁为了 Mockito 能打到而加 {@code public}，
     * 这里立刻红。
     */
    @Test
    @DisplayName("仓储的履约写调用保持包级可见：不许为了测试方便改成 public")
    void fulfillmentWriteMethodsStayInternal() {
        List<Method> writeMethods = List.of(OrderRepository.class.getDeclaredMethods()).stream()
                .filter(m -> m.getName().startsWith("mark")).toList();
        assertThat(writeMethods).as("至少要扫到那五条履约写调用，否则这条守卫自己失效了")
                .hasSizeGreaterThanOrEqualTo(5);
        for (Method m : writeMethods) {
            assertThat(Modifier.isPublic(m.getModifiers()))
                    .as("OrderRepository.%s 是模块内部方法，放宽成 public 等于把内部通路当对外端口"
                            .formatted(m.getName()))
                    .isFalse();
        }
        assertThat(Modifier.isPublic(OrderRepository.class.getModifiers()))
                .as("类本身要 public：Spring 要实例化它（这条守卫只针对方法）").isTrue();
    }

    /** 造一个 H2 形状的约束异常：约束名只出现在最内层消息里（与真实堆栈一致）。 */
    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException(
                "StatementCallback; SQL [UPDATE mall_order SET fulfillment_status=...]; "
                        + "Check constraint violation: \"" + constraintName + ": (...)\"");
    }

    /** 真跑一条会被数据库拒绝的 UPDATE，把数据库<b>自己</b>抛的异常原样带回来（不手写消息）。 */
    private DataIntegrityViolationException realViolation(String sql, String id) {
        try {
            jdbc.sql(sql).param("id", id).update();
            throw new AssertionError("这条 UPDATE 本该被 CHECK 约束拒绝，却执行成功了：" + sql);
        } catch (DataIntegrityViolationException e) {
            return e;
        }
    }

    /** 把真实异常喂给翻译层，并要求它认得出来（认不出来就会原样冒成 500）。 */
    private static BusinessException translateReal(DataIntegrityViolationException e) {
        BusinessException business = FulfillmentService.translate(e);
        assertThat(business).as("真实 H2 抛出的约束异常必须被翻译层认出来，否则会原样冒成 500").isNotNull();
        return business;
    }

    /** 整条异常链上的原文，用来断言 H2 真实形态（与翻译层内部的大写化无关）。 */
    private static String rawText(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) text.append(message);
        }
        return text.toString();
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
