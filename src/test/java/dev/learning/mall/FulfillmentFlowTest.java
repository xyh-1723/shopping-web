package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import dev.learning.mall.order.internal.OrderProperties;
import dev.learning.mall.order.internal.OrderTransactions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 履约闭环（T12）：发货 → 收货 → 完成，以及到期自动收货。
 *
 * <p>本文件守的是 <b>§9「T11 / T12 履约」表的 #1a / #2a / #3 / #4 / #5 / #7 与末尾的哨兵</b>，
 * 每一条都走<b>真实 Java 入口</b>（真实支付入口 / 真实 {@link FulfillmentService} / 真实扫描器），
 * 不用裸 SQL 代替。理由写在 §9 的分层修订里：裸 JDBC 只能证明数据库约束在线，
 * 证明不了 Java writer 把字段写对——直接 UPDATE 根本不会执行 {@code markPaid}、
 * {@code FulfillmentService.ship} 里那段写 {@code shipped_at} 的代码，注回 bug 之后
 * 目标断言一次都不会被执行（本项目已经四次遇到「写完就绿但什么也没守住」）。
 * 约束层由 {@link V15MigrationTest} 的裸 SQL 守着，两层不重复、也不互相顶替。
 *
 * <p>时间是这里唯一的难点：靠 {@code sleep} 等 10 天不现实，所以注入可控 {@link Clock}，
 * 直接断言「没到期一步都不走、到期那天推进到底」这条规则本身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FulfillmentFlowTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 17, 9, 0, 0);
    /** 自动收货天数：与测试里显式设置的值保持一致，断言里不再到处写 10。 */
    private static final int AUTO_RECEIVE_DAYS = 10;

    @Autowired OrderFacade facade;
    /** 真实支付入口：#1a 必须从它进入，否则不会执行 markPaid。 */
    @Autowired OrderTransactions orders;
    @Autowired FulfillmentService fulfillment;
    @Autowired OrderProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;

    @BeforeEach
    void setUp() {
        properties.setAutoReceiveEnabled(true);
        properties.setAutoReceiveDays(AUTO_RECEIVE_DAYS);
        properties.setAutoReceiveMaxPerScan(200);
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order"))
            jdbc.sql("DELETE FROM " + table).update();
        jdbc.sql("UPDATE product SET stock=10").update();
        at(BASE);
    }

    @AfterEach
    void restore() {
        properties.setAutoReceiveEnabled(false);
    }

    // ——————————————————— #1a 支付必须写支付时刻 ———————————————————

    @Test
    @DisplayName("支付必须写支付时刻：从真实 Java 支付入口进入，markPaid 漏写 paid_at 会被 CK_ORDER_PAID_NEEDS_MOMENT 打回")
    void paymentWithoutPaidMomentIsRejectedByDatabase() {
        var order = create("student");

        // 走真实支付入口：markPaid 是被 ck_order_paid_needs_moment 逼着写 paid_at 的，
        // 漏写就是支付事务当场被打回（DataIntegrityViolationException，根因含约束名）。
        OrderView paid = orders.pay(order.id(), "student", "DEMO_CARD");

        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paidAt()).isEqualTo(BASE);   // 时刻由 Clock 注入，不是 CURRENT_TIMESTAMP
        assertThat(statusOf(order.id())).isEqualTo("PAID");
        assertThat(paidAtOf(order.id())).isEqualTo(BASE);
    }

    // ——————————————————— #2a 发货必须写发货时刻 ———————————————————

    @Test
    @DisplayName("发货必须写发货时刻：真实 FulfillmentService.ship 只改状态不写 shipped_at，会被 CK_ORDER_FULFILLMENT_FIELDS 打回")
    void shippingWithoutMomentIsRejectedByDatabase() {
        var order = paidOrder("student");

        OrderView shipped = fulfillment.ship(order.id(), "admin", "SF1234567890");

        assertThat(shipped.fulfillmentStatus()).isEqualTo("SHIPPED");
        assertThat(shipped.shippedAt()).isEqualTo(BASE);
        assertThat(shipped.shippingNo()).isEqualTo("SF1234567890");
        // 自动收货时刻在发货这一刻算好落库：之后改配置不该让这批在途订单一起跳变。
        assertThat(autoReceiveAtOf(order.id())).isEqualTo(BASE.plusDays(AUTO_RECEIVE_DAYS));
        // 交易态不动：履约推进时 status 始终是 PAID（§2.3）。
        assertThat(shipped.status()).isEqualTo("PAID");
        assertThat(countEvent("ORDER_SHIPPED", order.id())).isEqualTo(1);
    }

    // ——————————————————— #3 并发发货只准成功一次 ———————————————————

    @Test
    @DisplayName("两个店员同时点发货：只有一次成功，shipped_at 只被写一次、ORDER_SHIPPED 只有一条")
    void concurrentShipSetsOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            var order = paidOrder("student");

            List<String> outcomes = parallel(2, i -> {
                try {
                    return fulfillment.ship(order.id(), "admin", "SF-CONC-" + i).fulfillmentStatus();
                } catch (BusinessException e) {
                    return e.code();   // 输的那一方必须知道自己输在「已被别人发过货」
                }
            });

            assertThat(outcomes).as("第 %d 轮：一次成功、一次被条件 UPDATE 挡下", round)
                    .containsExactlyInAnyOrder("SHIPPED", "INVALID_STATE");
            assertThat(countEvent("ORDER_SHIPPED", order.id()))
                    .as("第 %d 轮：事件只能有一条（影响行数=1 才发）", round).isEqualTo(1);
            assertThat(shippedAtOf(order.id())).as("第 %d 轮：发货时刻只被写过一次", round).isEqualTo(BASE);
            assertThat(fulfillmentStatusOf(order.id())).isEqualTo("SHIPPED");
        }
    }

    // ——————————————————— #4 未支付不许发货 ———————————————————

    @Test
    @DisplayName("未支付的订单发不了货：从 Java 发货入口处理 PENDING 订单，越过 status='PAID' 会被 CK_ORDER_FULFILLMENT_NEEDS_PAID 打回")
    void unpaidOrderCannotShip() {
        var order = create("student");   // 待支付

        assertThatThrownBy(() -> fulfillment.ship(order.id(), "admin", "SF-UNPAID"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE");

        assertThat(statusOf(order.id())).isEqualTo("PENDING");
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("NONE");
        assertThat(shippedAtOf(order.id())).isNull();
        assertThat(countEvent("ORDER_SHIPPED", order.id())).isZero();
    }

    // ——————————————————— #5 别人的订单不能被收货 ———————————————————

    @Test
    @DisplayName("甲确认收货乙的订单：受影响 0 行并返回 404（归属校验写在 UPDATE 的 WHERE 里）")
    void othersOrderCannotReceive() {
        var order = shippedOrder("classmate");

        assertThatThrownBy(() -> fulfillment.receive(order.id(), "student"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).status().value()).isEqualTo(404));

        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("SHIPPED");
        assertThat(receivedAtOf(order.id())).isNull();
        assertThat(countEvent("ORDER_RECEIVED", order.id())).isZero();

        // 本人的同一笔订单就能收货：证明上面的红来自归属，而不是「收货这个功能坏了」。
        assertThat(fulfillment.receive(order.id(), "classmate").fulfillmentStatus()).isEqualTo("RECEIVED");
    }

    // ——————————————————— #7 自动收货看得到期时刻 ———————————————————

    @Test
    @DisplayName("自动收货只认到期时刻：没到期的在途订单一步都不走，到期的才推进到底")
    void autoReceiveRespectsDueTime() {
        var order = shippedOrder("student");   // 发货于 BASE，auto_receive_at = BASE + 10 天

        // 未到期：整轮扫描连候选都挑不出来
        at(BASE.plusDays(3));
        var early = fulfillment.autoReceiveScan();
        assertThat(early.candidates()).isZero();
        assertThat(early.received()).isZero();
        assertThat(early.completed()).isZero();
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("SHIPPED");

        // 买家在到期前自己点了收货 → RECEIVED；自动收货时刻仍是 BASE+10 天，还没到
        at(BASE.plusDays(4));
        assertThat(fulfillment.receive(order.id(), "student").fulfillmentStatus()).isEqualTo("RECEIVED");

        // 还没到期就扫：候选集空
        at(BASE.plusDays(5));
        var notYet = fulfillment.autoReceiveScan();
        assertThat(notYet.candidates()).isZero();
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("RECEIVED");

        // 并且绕开候选集、直接推进这一笔也必须被到期条件挡住（第二道校验在 UPDATE 里）
        var step = fulfillment.autoAdvance(order.id(), BASE.plusDays(5));
        assertThat(step.received()).isZero();
        assertThat(step.completed()).isZero();
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("RECEIVED");
        assertThat(completedAtOf(order.id())).isNull();
        assertThat(countEvent("ORDER_COMPLETED", order.id())).isZero();

        // 到期当天：真的推进，且时刻由 Clock 注入
        at(BASE.plusDays(AUTO_RECEIVE_DAYS));
        var due = fulfillment.autoReceiveScan();
        assertThat(due.completed()).isEqualTo(1);
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("COMPLETED");
        assertThat(completedAtOf(order.id())).isEqualTo(BASE.plusDays(AUTO_RECEIVE_DAYS));
    }

    // ——————————————————— 哨兵：跳过 RECEIVED 直接完成 ———————————————————

    @Test
    @DisplayName("哨兵：跳过 RECEIVED 直接完成——合成一条语句会被 CK_ORDER_FULFILLMENT_FIELDS 拒，所以自动收货必须两步")
    void skipReceivedCannotReachCompleted() {
        var order = shippedOrder("student");

        // Java 层：SHIPPED 状态上买家点「完成」必须无效——RECEIVED 之前不存在「完成」这一步
        assertThatThrownBy(() -> fulfillment.complete(order.id(), "student"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE");
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("SHIPPED");

        // 到期后跑真实扫描器：它必须真的经过 RECEIVED（received_at 有值）再落到 COMPLETED。
        // 若有人把两步合成一条 SHIPPED → COMPLETED 的 UPDATE，那条语句会被约束打回，
        // 扫描把它记成 failed，这笔订单停在 SHIPPED——下面这两条断言就会变红。
        at(BASE.plusDays(AUTO_RECEIVE_DAYS));
        var result = fulfillment.autoReceiveScan();
        assertThat(result.received()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("COMPLETED");
        assertThat(receivedAtOf(order.id())).isEqualTo(BASE.plusDays(AUTO_RECEIVE_DAYS));
        assertThat(completedAtOf(order.id())).isEqualTo(BASE.plusDays(AUTO_RECEIVE_DAYS));
        // 两步 = 两条事实 = 两条事件：合成一步的话这里只会有一条。
        assertThat(countEvent("ORDER_RECEIVED", order.id())).isEqualTo(1);
        assertThat(countEvent("ORDER_COMPLETED", order.id())).isEqualTo(1);

        // 库层：把两步合成一条（SHIPPED 直接写 COMPLETED）会被约束打回——这是「必须两步」的根因。
        var other = shippedOrder("student");
        assertThatThrownBy(() -> jdbc.sql("UPDATE mall_order SET fulfillment_status='COMPLETED',completed_at=:t"
                        + " WHERE id=:id AND fulfillment_status='SHIPPED'")
                .param("id", other.id()).param("t", BASE.plusDays(AUTO_RECEIVE_DAYS)).update())
                .hasMessageContaining("CK_ORDER_FULFILLMENT_FIELDS");
        assertThat(fulfillmentStatusOf(other.id())).isEqualTo("SHIPPED");
    }

    // ——————————————————— 端到端：三段都走一遍 ———————————————————

    @Test
    @DisplayName("发货→收货→完成：三段各自只生效一次，交易态始终是 PAID，重复点无效")
    void shipReceiveCompleteWalksTheWholeChain() {
        var order = paidOrder("student");

        assertThat(fulfillment.ship(order.id(), "admin", "SF-001").fulfillmentStatus()).isEqualTo("SHIPPED");
        // 已发货之后再发一次：0 行，不覆盖运单号与发货时刻
        assertThatThrownBy(() -> fulfillment.ship(order.id(), "admin", "SF-002"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE");
        assertThat(shippingNoOf(order.id())).isEqualTo("SF-001");

        at(BASE.plusHours(1));
        assertThat(fulfillment.receive(order.id(), "student").fulfillmentStatus()).isEqualTo("RECEIVED");
        assertThatThrownBy(() -> fulfillment.receive(order.id(), "student"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE");

        at(BASE.plusHours(2));
        assertThat(fulfillment.complete(order.id(), "student").fulfillmentStatus()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> fulfillment.complete(order.id(), "student"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE");

        assertThat(statusOf(order.id())).isEqualTo("PAID");        // 履约推进不动交易态
        assertThat(shippedAtOf(order.id())).isEqualTo(BASE);
        assertThat(receivedAtOf(order.id())).isEqualTo(BASE.plusHours(1));
        assertThat(completedAtOf(order.id())).isEqualTo(BASE.plusHours(2));
        assertThat(countEvent("ORDER_SHIPPED", order.id())).isEqualTo(1);
        assertThat(countEvent("ORDER_RECEIVED", order.id())).isEqualTo(1);
        assertThat(countEvent("ORDER_COMPLETED", order.id())).isEqualTo(1);
    }

    // ——————————————————— 端点 ———————————————————

    @Test
    @DisplayName("端点：发货（店员）/ 收货、完成（买家本人）；他人的订单收货返回 404")
    void endpointsExposeFulfillmentActions() throws Exception {
        var order = paidOrder("classmate");

        mvc.perform(post("/api/orders/" + order.id() + "/ship")
                        .with(user("admin").roles("USER", "STAFF")).with(csrf())
                        .contentType("application/json").content("{\"shippingNo\":\"SF-EP-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.shippingNo").value("SF-EP-001"));

        // 别人的订单：404，且不泄露它存在
        mvc.perform(post("/api/orders/" + order.id() + "/receive")
                        .with(user("student").roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(fulfillmentStatusOf(order.id())).isEqualTo("SHIPPED");

        mvc.perform(post("/api/orders/" + order.id() + "/receive")
                        .with(user("classmate").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillmentStatus").value("RECEIVED"));
        mvc.perform(post("/api/orders/" + order.id() + "/complete")
                        .with(user("classmate").roles("USER")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fulfillmentStatus").value("COMPLETED"));
    }

    // ——————————————————— 辅助 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(any())).thenReturn(clock);
    }

    private OrderView create(String user) {
        return facade.create(user, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private OrderView paidOrder(String user) {
        OrderView order = create(user);
        return orders.pay(order.id(), user, "DEMO_CARD");
    }

    private OrderView shippedOrder(String user) {
        OrderView order = paidOrder(user);
        return fulfillment.ship(order.id(), "admin", "SF" + order.id().substring(0, 8));
    }

    private String statusOf(String id) {
        return jdbc.sql("SELECT status FROM mall_order WHERE id=:id").param("id", id).query(String.class).single();
    }

    private String fulfillmentStatusOf(String id) {
        return jdbc.sql("SELECT fulfillment_status FROM mall_order WHERE id=:id")
                .param("id", id).query(String.class).single();
    }

    private String shippingNoOf(String id) {
        return jdbc.sql("SELECT shipping_no FROM mall_order WHERE id=:id")
                .param("id", id).query(String.class).single();
    }

    private LocalDateTime paidAtOf(String id) {
        return moment(id, "paid_at");
    }

    private LocalDateTime shippedAtOf(String id) {
        return moment(id, "shipped_at");
    }

    private LocalDateTime receivedAtOf(String id) {
        return moment(id, "received_at");
    }

    private LocalDateTime completedAtOf(String id) {
        return moment(id, "completed_at");
    }

    private LocalDateTime autoReceiveAtOf(String id) {
        return moment(id, "auto_receive_at");
    }

    /**
     * 取一个履约时刻，没写过就是 {@code null}。
     *
     * <p>刻意用 {@code optional()} 而不是 {@code single()}：{@code single()} 对「查到了、
     * 但值是 NULL」会抛 {@code TypeMismatchDataAccessException}，那正好会把
     * 「这个时刻还没被写」和「这一列查不到」混成一件事——前者是这里要断言的正常状态。
     */
    private LocalDateTime moment(String id, String column) {
        return jdbc.sql("SELECT " + column + " FROM mall_order WHERE id=:id")
                .param("id", id).query(LocalDateTime.class).optional().orElse(null);
    }

    /** 按订单点名计数：outbox_event 的表名是 aggregate_id，且用例之间会共用这张表。 */
    private int countEvent(String type, String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE event_type=:t AND aggregate_id=:id")
                .param("t", type).param("id", orderId).query(Integer.class).single();
    }

    /** 同时起步，让两个线程真的撞在一起（而不是一前一后跑完）。 */
    private static <T> List<T> parallel(int n, IntFunction<T> work) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        var gate = new CountDownLatch(1);
        try {
            var futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
                gate.await();
                return work.apply(i);
            })).toList();
            gate.countDown();
            var out = new ArrayList<T>();
            for (var f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
