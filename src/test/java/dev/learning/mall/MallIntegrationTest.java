package dev.learning.mall;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.messaging.internal.*;
import dev.learning.mall.order.api.*;
import dev.learning.mall.order.internal.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MallIntegrationTest {
    @Autowired OrderFacade facade;
    @Autowired OrderTransactions orders;
    @Autowired ProductCatalog products;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired MessageProcessor processor;
    @Autowired MessageWorker worker;
    @MockitoSpyBean OutboxRepository outbox;
    @MockitoSpyBean NotificationSink sink;

    @BeforeEach void resetData() {
        reset(outbox, sink);
        // 不给测试方法加 @Transactional：每个并发线程必须观察真实的提交和回滚。
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) jdbc.sql("DELETE FROM " + table).update();
        jdbc.sql("UPDATE product SET stock=10").update();
    }
    CreateOrderRequest request(int quantity) { return new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, quantity))); }
    OrderView create() { return facade.create("student", UUID.randomUUID().toString(), request(1)); }
    int count(String table) { return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single(); }
    String eventId() { return jdbc.sql("SELECT id FROM outbox_event LIMIT 1").query(String.class).single(); }
    String eventStatus(String id) { return jdbc.sql("SELECT status FROM outbox_event WHERE id=:id").param("id", id).query(String.class).single(); }
    /**
     * 把事件的重试时间拨到过去，让「到点就该消费」不再取决于取时的先后。
     *
     * <p>基准用 <b>JVM 时钟</b>而不是数据库的 {@code CURRENT_TIMESTAMP}：
     * {@code MessageProcessor} 判断「到没到重试时间」用的是 {@code LocalDateTime.now()}，
     * 而实测 H2 的 CURRENT_TIMESTAMP 有 49/50 的概率比 JVM 时钟快，
     * 拿数据库时间做基准等于让两个时钟互相较劲——事件刚建好就去消费，会偶发地被整条跳过。
     *
     * <p>凡是「建完事件立刻消费」的地方都要先调它一次，否则用例会时红时绿。
     */
    void dueNow(String id) {
        jdbc.sql("UPDATE outbox_event SET next_attempt_at=:t WHERE id=:id")
                .param("t", LocalDateTime.now().minusSeconds(1)).param("id", id).update();
    }

    @Test void publicPagesAndProtectedEndpoints() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("小小商城")));
        mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(post("/api/orders").with(user("student")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void realFormLoginWorks() throws Exception {
        mvc.perform(formLogin().user("student").password("learn12345")).andExpect(authenticated().withUsername("student"));
    }
    @Test void invalidInputsReturn400WithoutMutation() throws Exception {
        for (String body : List.of("{\"items\":[]}", "{\"items\":[{\"productId\":1,\"quantity\":0}]}",
                "{\"items\":[null]}", "{\"items\":[{\"productId\":1,\"quantity\":100}]}")) {
            mvc.perform(post("/api/orders").with(user("student")).with(csrf()).header("Idempotency-Key","valid-key-123")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/orders").with(user("student")).with(csrf()).contentType("application/json")
                .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
        assertThat(count("mall_order")).isZero();
    }
    @Test void ownershipIsEnforcedForReadPayCancelAndMessages() throws Exception {
        var order = create();
        mvc.perform(get("/api/orders/"+order.id()).with(user("classmate"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/"+order.id()+"/cancel").with(user("classmate")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/"+order.id()+"/pay").with(user("classmate")).with(csrf())
                .contentType("application/json").content("{\"method\":\"DEMO_CARD\"}")).andExpect(status().isNotFound());
        String delivered = eventId(); dueNow(delivered); processor.process(delivered);
        mvc.perform(get("/api/orders").with(user("classmate"))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/messages").with(user("classmate")))
                .andExpect(jsonPath("$.events.length()").value(0)).andExpect(jsonPath("$.notifications.length()").value(0));
        assertThat(products.get(1).stock()).isEqualTo(9);
    }
    @Test void priceComesFromDatabaseAndSnapshotIsStored() {
        var order = facade.create("student", "pricing-request", request(2));
        // 商品1（¥39）×2 = 小计 78；未满 99 的包邮门槛 → 无满减、加运费 8 → 应付 86。
        //
        // 这里原本断言 total() == 78.00。那个数字正是「下单绕过唯一定价器、自己裸求和」
        // 的结果——也就是说，这条测试一直在替 bug 站岗：257 个测试全绿，
        // 是因为其中一条断言锁住了错误行为。改造后按正确语义断言明细与应付。
        // 想让「走定价器算出 86」与「total() == 78」同时成立，在数学上不可能。
        assertThat(order.subtotal()).isEqualByComparingTo("78.00");
        assertThat(order.discount()).isEqualByComparingTo("0.00");
        assertThat(order.shippingFee()).isEqualByComparingTo("8.00");
        assertThat(order.payAmount()).isEqualByComparingTo("86.00");
        assertThat(order.total()).isEqualByComparingTo("86.00");
        assertThat(order.items().getFirst().productName()).isEqualTo("云朵马克杯");
        assertThat(products.get(1).stock()).isEqualTo(8);
        assertThat(count("outbox_event")).isEqualTo(1);
    }
    @Test void replayReturnsOriginalButChangedPayloadConflicts() {
        var original = facade.create("student", "same-request-key", request(1));
        assertThat(facade.create("student", "same-request-key", request(1)).id()).isEqualTo(original.id());
        assertThatThrownBy(() -> facade.create("student", "same-request-key", request(2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不同");
        // key 的唯一范围包含用户：另一个账号可以使用相同 key。
        assertThat(facade.create("classmate", "same-request-key", request(1)).id()).isNotEqualTo(original.id());
        assertThat(products.get(1).stock()).isEqualTo(8);
    }
    @Test void itemOrderDoesNotChangeFingerprintAndDuplicatesAreRejected() {
        var a = new CreateOrderRequest.Line(1,1); var b = new CreateOrderRequest.Line(2,2);
        var first = facade.create("student","sorted-request",new CreateOrderRequest(List.of(a,b)));
        var second = facade.create("student","sorted-request",new CreateOrderRequest(List.of(b,a)));
        assertThat(second.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> facade.create("student","duplicate-items",new CreateOrderRequest(List.of(a,a))))
                .isInstanceOf(BusinessException.class);
    }
    @Test void insufficientSecondProductRollsBackEarlierDeductionAndOrder() {
        jdbc.sql("UPDATE product SET stock=0 WHERE id=2").update();
        var request = new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1,2), new CreateOrderRequest.Line(2,1)));
        assertThatThrownBy(() -> facade.create("student", "rollback-request", request)).isInstanceOf(BusinessException.class);
        assertThat(products.get(1).stock()).isEqualTo(10);
        assertThat(count("mall_order")).isZero(); assertThat(count("order_item")).isZero(); assertThat(count("outbox_event")).isZero();
    }
    @Test void outboxWriteFailureRollsBackBusinessTransaction() {
        doThrow(new IllegalStateException("模拟事件表失败")).when(outbox).insert(anyString(),anyString(),anyString(),anyString(),anyString());
        assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
        assertThat(products.get(1).stock()).isEqualTo(10);
        assertThat(count("mall_order")).isZero(); assertThat(count("order_item")).isZero();
    }
    @Test void concurrentDuplicateRequestsProduceOneOrder() throws Exception {
        var ids = parallel(16, i -> facade.create("student","parallel-same-key",request(1)).id());
        assertThat(new HashSet<>(ids)).hasSize(1); assertThat(count("mall_order")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1); assertThat(products.get(1).stock()).isEqualTo(9);
    }
    @Test void competingRequestsCannotOversell() throws Exception {
        jdbc.sql("UPDATE product SET stock=7 WHERE id=1").update();
        var results = parallel(24, i -> {
            try { facade.create("student","purchase-number-"+i,request(1)); return true; }
            catch (BusinessException e) { assertThat(e.code()).isEqualTo("OUT_OF_STOCK"); return false; }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(7);
        assertThat(products.get(1).stock()).isZero(); assertThat(count("mall_order")).isEqualTo(7);
        assertThat(count("outbox_event")).isEqualTo(7);
    }
    @Test void concurrentDifferentPayloadsUsingSameKeyConflict() throws Exception {
        var results = parallel(2, i -> {
            try { return facade.create("student","same-key-conflict",request(i+1)).items().getFirst().quantity(); }
            catch (BusinessException e) { assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"); return 0; }
        });
        assertThat(results.stream().filter(q -> q > 0).count()).isEqualTo(1);
        assertThat(products.get(1).stock()).isEqualTo(10-results.stream().mapToInt(Integer::intValue).sum());
    }
    @Test void concurrentPaymentsHaveOneReceiptAndOnePaidEvent() throws Exception {
        var order = create();
        var results = parallel(12, i -> orders.pay(order.id(),"student","DEMO_CARD").paymentReceipt());
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(count("outbox_event")).isEqualTo(2); assertThat(products.get(1).stock()).isEqualTo(9);
        // 只断言 isInstanceOf(BusinessException.class) 太宽：任何业务失败（余额不足、订单不存在、
        // 支付通道没注册）都能让它绿。断言必须回答「为什么失败」——
        // 已用 DEMO_CARD 付过的订单再拿 DEMO_WALLET 去付，失败原因只能是「换了一种支付方式」。
        assertThat(catchThrowableOfType(() -> orders.pay(order.id(),"student","DEMO_WALLET"),
                BusinessException.class).code())
                .as("已支付订单换支付方式：必须是 PAYMENT_MISMATCH，而不是「因为别的原因失败了」")
                .isEqualTo("PAYMENT_MISMATCH");
        assertThat(catchThrowableOfType(() -> orders.cancel(order.id(),"student"),
                BusinessException.class).code())
                .as("已支付订单不能取消：必须是 INVALID_STATE")
                .isEqualTo("INVALID_STATE");
    }
    @Test void concurrentCancellationsRestoreStockOnlyOnce() throws Exception {
        var order = create(); parallel(12, i -> orders.cancel(order.id(),"student"));
        assertThat(products.get(1).stock()).isEqualTo(10); assertThat(count("outbox_event")).isEqualTo(2);
        // 同上：已取消的订单去支付，失败原因必须是 INVALID_STATE（closeReason=USER），
        // 而不是超时关闭的 ORDER_EXPIRED——后者说明关单原因被写错了。
        assertThat(catchThrowableOfType(() -> orders.pay(order.id(),"student","DEMO_CARD"),
                BusinessException.class).code())
                .as("已取消订单不能支付：必须是 INVALID_STATE（主动取消，不是超时关闭）")
                .isEqualTo("INVALID_STATE");
    }
    @Test void paymentAndCancellationRaceHasOneWinner() throws Exception {
        var order = create();
        var results = parallel(2, i -> {
            try { return i == 0 ? orders.pay(order.id(),"student","DEMO_CARD").status() : orders.cancel(order.id(),"student").status(); }
            catch (BusinessException e) { assertThat(e.code()).isEqualTo("INVALID_STATE"); return "CONFLICT"; }
        });
        assertThat(results).contains("CONFLICT");
        String status = orders.get(order.id(),"student").status();
        assertThat(products.get(1).stock()).isEqualTo(status.equals("PAID") ? 9 : 10);
        assertThat(count("outbox_event")).isEqualTo(2);
    }
    @Test void duplicateConsumersProduceOneNotification() throws Exception {
        create(); String id = eventId(); dueNow(id); parallel(12, i -> { processor.process(id); return i; });
        assertThat(count("notification")).isEqualTo(1); assertThat(eventStatus(id)).isEqualTo("DELIVERED");
    }
    @Test void notificationAndAcknowledgementRollBackTogetherAndRetrySucceeds() {
        create(); String id = eventId();
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("写入通知后失败"); }).when(sink).deliver(any());
        dueNow(id); worker.handle(id);
        assertThat(count("notification")).isZero(); assertThat(eventStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT attempts FROM outbox_event WHERE id=:id").param("id",id).query(Integer.class).single()).isEqualTo(1);
        reset(sink); dueNow(id); worker.handle(id);
        assertThat(count("notification")).isEqualTo(1); assertThat(eventStatus(id)).isEqualTo("DELIVERED");
    }
    @Test void retryBackoffDeadLetterAndOwnerReplay() throws Exception {
        create(); String id = eventId();
        doThrow(new IllegalStateException("故障")).when(sink).deliver(any());
        dueNow(id); worker.handle(id); worker.handle(id); // 尚未到重试时间，不再次执行消费。
        verify(sink,times(1)).deliver(any());
        for (int i=1; i<5; i++) { dueNow(id); worker.handle(id); }
        assertThat(eventStatus(id)).isEqualTo("DEAD"); assertThat(count("notification")).isZero();
        worker.handle(id); verify(sink,times(5)).deliver(any());
        mvc.perform(post("/api/messages/"+id+"/retry").with(user("classmate")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/messages/"+id+"/retry").with(user("student")).with(csrf())).andExpect(status().isOk());
        // 重放把 next_attempt_at 重置成数据库的 CURRENT_TIMESTAMP（主代码行为），同样要先拨到过去。
        reset(sink); dueNow(id); worker.handle(id);
        assertThat(eventStatus(id)).isEqualTo("DELIVERED"); assertThat(count("notification")).isEqualTo(1);
    }
    @Test void inventoryWriteRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> products.reserve(1,1,"student",null)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    private <T> List<T> parallel(int count, IntFunction<T> action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(count)) {
            var ready = new CountDownLatch(count); var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i=0; i<count; i++) { final int index=i; futures.add(pool.submit(() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new TimeoutException(); return action.apply(index); })); }
            try { assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); } finally { start.countDown(); }
            List<T> results = new ArrayList<>(); for (Future<T> future : futures) results.add(future.get(30,TimeUnit.SECONDS));
            return results;
        }
    }
}
