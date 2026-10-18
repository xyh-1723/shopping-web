package dev.learning.mall;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.messaging.internal.MessageProcessor;
import dev.learning.mall.order.api.*;
import dev.learning.mall.order.internal.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单超时自动关闭。
 *
 * <p>要守的不是「有没有一个定时任务」，而是这条因果链：
 * <b>下单 → 库存被预占 → 用户不付款就走 → 库存必须自己回来</b>。
 * 中间任何一环断了，用户看到的是「明明没人买，商品却显示卖完了」。
 *
 * <p>时间是这里唯一的难点：靠 {@code sleep} 等 15 分钟不现实，靠改库里的 expires_at
 * 又变成在测 SQL 而不是测规则。所以把时间做成可注入的 {@link Clock}，
 * 直接断言「到期前一秒不关、过了就关」这条规则本身。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderExpiryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 16, 12, 0, 0);

    @Autowired OrderFacade facade;
    @Autowired OrderTransactions orders;
    @Autowired OrderExpiryService expiry;
    @Autowired OrderProperties properties;
    @Autowired ProductCatalog products;
    @Autowired JdbcClient jdbc;
    @Autowired MessageProcessor processor;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean Clock clock;
    /** 用来制造「归还库存失败」，验证它与状态变更是不是同生共死。 */
    @MockitoSpyBean ProductCatalog catalog;

    /**
     * 本类建过的订单号：{@link #noDeliveryTrailIsLeftBehind()} 用它把留痕自查的范围
     * 收窄到本类自己的行上（为什么要收窄见 {@link DeliveryTrail#residueFor}）。
     */
    private static final List<String> seenOrderIds = new CopyOnWriteArrayList<>();

    /**
     * {@code @AfterAll} 必须是 static，因此拿不到实例的 {@code jdbc}；
     * 这里由 {@code @BeforeEach} 递一个进来，仅供 {@link #noDeliveryTrailIsLeftBehind()} 使用。
     */
    private static JdbcClient trailJdbc;

    @BeforeEach void setUp() {
        properties.setAutoCloseEnabled(true);
        properties.setPaymentTimeoutMinutes(15);
        properties.setAutoCloseMaxPerScan(200);
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) jdbc.sql("DELETE FROM " + table).update();
        jdbc.sql("UPDATE product SET stock=10").update();
        trailJdbc = jdbc;
        at(BASE);
    }

    @AfterEach void restore() {
        properties.setAutoCloseEnabled(false);
        reset(catalog);
        // 留痕表（notification_delivery）的清理：T15 之后每消费一个事件都会多写一行，
        // 本类的「超时关闭会通知到用户」那条用例就在消费事件。不清理的话残迹会留给下一个测试类。
        // 全表删而不是按订单号删的理由见 DeliveryTrail#purge。
        DeliveryTrail.purge(jdbc);
    }

    /**
     * 哨兵：本类跑完之后，自己建过的订单在留痕表里一行都不剩。
     *
     * <p>它存在的<b>唯一</b>理由是「清理这件事本身无法被变异验证」：
     * 把上面 {@code restore} 里的那行 {@code purge} 删掉，全量测试仍然全绿——
     * 没有任何用例按全表计数断言留痕，清理就这么静悄悄地失效了。
     * 有了这条断言，删掉清理就会变红。
     *
     * <p>范围按「本类建过的订单号」收窄，因此与其他测试类的执行顺序无关，
     * 不会引入「单跑绿、全量红」那种脆弱性（理由见 {@link DeliveryTrail#residueFor}）。
     */
    @AfterAll static void noDeliveryTrailIsLeftBehind() {
        if (trailJdbc == null) {
            return; // 上下文没起来：没有留痕可查，也没有什么要自查的
        }
        assertThat(DeliveryTrail.residueFor(trailJdbc, seenOrderIds))
                .as("本类建过的订单在 notification_delivery 里必须一行不剩："
                        + "留痕表的 order_id 没有外键，残迹会一路带到下一个测试类")
                .isZero();
    }

    // ——————————————————— 下单时的承诺 ———————————————————

    @Test
    @DisplayName("截止时间在下单那一刻落库，之后改配置也不会让已承诺的时间跳变")
    void expiresAtIsPromisedAtCreationAndNeverShifts() {
        var order = create();
        assertThat(order.expiresAt()).isEqualTo(BASE.plusMinutes(15));

        // 运营把支付时限放宽到 30 分钟：新订单用新时限，存量订单仍是当初说好的那个时刻。
        properties.setPaymentTimeoutMinutes(30);
        assertThat(orders.get(order.id(), "student").expiresAt()).isEqualTo(BASE.plusMinutes(15));
        assertThat(create().expiresAt()).isEqualTo(BASE.plusMinutes(30));
    }

    // ——————————————————— 到期与否 ———————————————————

    @Test
    @DisplayName("还没到期：一秒也不提前关")
    void pendingOrderIsNotClosedBeforeDeadline() {
        var order = create();
        at(BASE.plusMinutes(15).minusSeconds(1));
        assertThat(expiry.closeExpired().closed()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("PENDING");
        assertThat(products.get(1).stock()).isEqualTo(9);
    }

    @Test
    @DisplayName("到期即关闭：状态改为已取消、原因记为超时、库存回到商品")
    void expiredOrderIsClosedAndStockReturns() {
        var order = create();
        assertThat(products.get(1).stock()).isEqualTo(9);

        at(BASE.plusMinutes(15));
        var result = expiry.closeExpired();
        assertThat(result.closed()).isEqualTo(1);

        var closed = orders.get(order.id(), "student");
        assertThat(closed.status()).isEqualTo("CANCELLED");
        assertThat(closed.closeReason()).isEqualTo("TIMEOUT");
        assertThat(closed.closedAt()).isNotNull();
        assertThat(products.get(1).stock()).isEqualTo(10);
        // 不写「最新一条事件是 ORDER_CLOSED_TIMEOUT」，见 eventTypes() 的说明。
        assertThat(eventTypes()).containsExactlyInAnyOrder("ORDER_CREATED", "ORDER_CLOSED_TIMEOUT");
    }

    @Test
    @DisplayName("重复扫描只关一次：库存不会被归还两次（那等于凭空造货）")
    void closingTwiceReturnsStockOnlyOnce() {
        var order = create();
        at(BASE.plusMinutes(15));
        expiry.closeExpired();
        var second = expiry.closeExpired();

        assertThat(second.candidates()).isZero();  // 已关闭的订单离开了候选集
        assertThat(products.get(1).stock()).isEqualTo(10);
        assertThat(count("outbox_event", "ORDER_CLOSED_TIMEOUT")).isEqualTo(1);
        assertThat(statusOf(order.id())).isEqualTo("CANCELLED");
    }

    // ——————————————————— 两种关闭必须分得开 ———————————————————

    @Test
    @DisplayName("主动取消与超时关闭在数据上分得开：运营口径与后续动作都依赖这一点")
    void userCancellationAndTimeoutAreDistinguishable() {
        var cancelled = create();
        orders.cancel(cancelled.id(), "student");

        var timedOut = create();
        at(BASE.plusMinutes(15));
        expiry.closeExpired();

        assertThat(reasonOf(cancelled.id())).isEqualTo("USER");
        assertThat(reasonOf(timedOut.id())).isEqualTo("TIMEOUT");
        // 两者都是 CANCELLED，但业务上是两件事：一个是用户不想买了，一个是没付款。
        assertThat(statusOf(cancelled.id())).isEqualTo(statusOf(timedOut.id()));
        assertThat(products.get(1).stock()).isEqualTo(10);  // 两笔各占 1 件，都已归还
    }

    @Test
    @DisplayName("已支付的订单永不被超时关单命中：它的库存是卖出去了，不是被占着")
    void paidOrderIsNeverClosedByTimeout() {
        var order = create();
        orders.pay(order.id(), "student", "DEMO_CARD");

        at(BASE.plusMinutes(60));
        assertThat(expiry.closeExpired().closed()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("PAID");
        assertThat(products.get(1).stock()).isEqualTo(9);
    }

    // ——————————————————— 竞态 ———————————————————

    /**
     * 这两条测的是<b>关单动作自己</b>，而不是扫描。
     *
     * <p>「已支付订单不会进入候选集」是扫描给的第一道防线，但防线不该只靠调用方：
     * 关单动作必须自己再确认一次状态。否则将来谁把扫描改成「批量更新」，
     * 或者谁绕过扫描直接调用关单，卖出去的货就会被当成预占库存还回去。
     */
    @Test
    @DisplayName("就算调用方坚持说它到期了，关单动作也不会碰已支付的订单")
    void closingNeverTouchesAPaidOrderEvenIfCallerInsists() {
        var order = create();
        orders.pay(order.id(), "student", "DEMO_CARD");

        at(BASE.plusMinutes(60));
        assertThat(orders.closeExpired(order.id(), BASE.plusMinutes(60))).isFalse();
        assertThat(statusOf(order.id())).isEqualTo("PAID");
        assertThat(products.get(1).stock()).isEqualTo(9);  // 卖出去的货不能被还回来
    }

    @Test
    @DisplayName("已经关掉的订单再被关一次：返回没关，且库存不会多出来")
    void closingAnAlreadyClosedOrderDoesNothing() {
        var order = create();
        orders.cancel(order.id(), "student");

        at(BASE.plusMinutes(60));
        assertThat(orders.closeExpired(order.id(), BASE.plusMinutes(60))).isFalse();
        assertThat(reasonOf(order.id())).isEqualTo("USER");  // 原因不被覆盖成 TIMEOUT
        assertThat(products.get(1).stock()).isEqualTo(10);
    }

    @Test
    @DisplayName("超时关掉之后再支付：用户得到的是「订单已超时，请重新下单」，不是未知错误")
    void payingAfterTimeoutExplainsWhatHappened() {
        var order = create();
        at(BASE.plusMinutes(15));
        expiry.closeExpired();

        assertThatThrownBy(() -> orders.pay(order.id(), "student", "DEMO_CARD"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_EXPIRED")
                .hasMessageContaining("超时");
    }

    @Test
    @DisplayName("支付与关单撞在一起：结局只有一种，且库存与状态永远自洽")
    void raceBetweenPaymentAndClosingHasOneOutcome() throws Exception {
        for (int round = 0; round < 20; round++) {
            // 每轮把库存复位：这一轮验证的是竞态，不是库存会不会被抢光。
            jdbc.sql("UPDATE product SET stock=10").update();
            at(BASE.plusMinutes(round));
            var order = create();
            at(BASE.plusMinutes(round).plusMinutes(15));

            parallel(2, i -> {
                try {
                    return i == 0 ? orders.pay(order.id(), "student", "DEMO_CARD").status()
                            : String.valueOf(expiry.closeExpired().closed());
                } catch (BusinessException e) {
                    // 输的那一方必须知道自己输在超时，而不是一个笼统的「状态不对」。
                    assertThat(e.code()).isEqualTo("ORDER_EXPIRED");
                    return "EXPIRED";
                }
            });

            String status = statusOf(order.id());
            int stock = products.get(1).stock();
            if ("PAID".equals(status)) {
                assertThat(stock).as("第 %d 轮：支付成功则库存保持扣减", round).isEqualTo(9);
            } else {
                assertThat(status).as("第 %d 轮：被关掉则状态为 CANCELLED", round).isEqualTo("CANCELLED");
                assertThat(reasonOf(order.id())).isEqualTo("TIMEOUT");
                assertThat(stock).as("第 %d 轮：关单则库存必须回来，不能既关了单又丢库存", round).isEqualTo(10);
            }
        }
    }

    // ——————————————————— 原子性 ———————————————————

    @Test
    @DisplayName("归还库存失败时状态不能先变：不会出现「关了单却没还库存」")
    void stockReturnIsAtomicWithStatusChange() {
        var order = create();
        // {@code release} 标了 propagation = MANDATORY（它不允许脱离订单事务被单独调用），
        // 所以连「把它改成抛异常」这一步也得先待在事务里：否则事务代理会先把它挡下，
        // 桩只设了一半——下一次调用这个 spy 就会静默返回 null，比报错更难查。
        new TransactionTemplate(txManager).executeWithoutResult(status ->
                doThrow(new IllegalStateException("库存服务不可用")).when(catalog).release(anyLong(), anyInt(), any(), any()));

        at(BASE.plusMinutes(15));
        var result = expiry.closeExpired();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(statusOf(order.id())).isEqualTo("PENDING");   // 状态没变
        assertThat(products.get(1).stock()).isEqualTo(9);        // 库存也没动：二者同生共死

        // 故障恢复后下一轮能正常关掉，不会卡死在这一笔上。
        reset(catalog);
        assertThat(expiry.closeExpired().closed()).isEqualTo(1);
        assertThat(products.get(1).stock()).isEqualTo(10);
    }

    // ——————————————————— 运维与边界 ———————————————————

    @Test
    @DisplayName("开关关闭时什么都不做（排障时才有理由回到旧行为）")
    void switchOffStopsClosing() {
        var order = create();
        properties.setAutoCloseEnabled(false);
        at(BASE.plusMinutes(15));

        assertThat(expiry.closeExpired().candidates()).isZero();
        assertThat(statusOf(order.id())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("单轮有上限，超出部分如实报告积压，而不是假装扫完了")
    void backlogIsReportedWhenLimitIsReached() {
        properties.setAutoCloseMaxPerScan(1);
        create();
        create();
        at(BASE.plusMinutes(15));

        var first = expiry.closeExpired();
        assertThat(first.candidates()).isEqualTo(1);
        assertThat(first.closed()).isEqualTo(1);
        assertThat(first.backlog()).isEqualTo(1);   // 还剩一笔没处理，日志会告警

        var second = expiry.closeExpired();
        assertThat(second.closed()).isEqualTo(1);
        assertThat(second.backlog()).isZero();
        assertThat(products.get(1).stock()).isEqualTo(10);
    }

    @Test
    @DisplayName("没有截止时间的订单不会被当成「已到期」：NULL 不等于早于任何时刻")
    void orderWithoutDeadlineIsNeverClosedByMistake() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status,created_at) VALUES(:id,'student',:key,'hash',39.00,'PENDING',:created)")
                .param("id", id).param("key", UUID.randomUUID().toString()).param("created", BASE.minusDays(30)).update();

        at(BASE.plusMinutes(15));
        assertThat(expiry.closeExpired().candidates()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("V10 的存量回填语句在本库上真的能跑（DATEADD 是 H2 方言，写错就是迁移崩）")
    void legacyPendingOrdersAreBackfilledByTheMigrationStatement() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,status,created_at) VALUES(:id,'student',:key,'hash',39.00,'PENDING',:created)")
                .param("id", id).param("key", UUID.randomUUID().toString()).param("created", BASE.minusDays(1)).update();

        // 与 V10__order_expiry.sql 中的回填语句逐字一致；改一处就要改另一处。
        int filled = jdbc.sql("UPDATE mall_order SET expires_at = DATEADD('MINUTE', 15, created_at) WHERE status = 'PENDING' AND expires_at IS NULL").update();
        assertThat(filled).isEqualTo(1);

        LocalDateTime expiresAt = jdbc.sql("SELECT expires_at FROM mall_order WHERE id=:id").param("id", id).query(LocalDateTime.class).single();
        assertThat(expiresAt).isEqualTo(BASE.minusDays(1).plusMinutes(15));
        // 回填后的存量订单同样能被关掉——否则它们会永远停在待支付。
        assertThat(expiry.closeExpired().closed()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    // ——————————————————— 用户能感知 ———————————————————

    @Test
    @DisplayName("超时关闭会通知到用户：关单不能只是数据库里悄悄改一行")
    void timeoutCloseNotifiesTheUser() {
        var order = create();
        at(BASE.plusMinutes(15));
        expiry.closeExpired();

        String eventId = jdbc.sql("SELECT id FROM outbox_event WHERE event_type='ORDER_CLOSED_TIMEOUT'").query(String.class).single();
        // next_attempt_at 由数据库按 CURRENT_TIMESTAMP 生成，而实测 H2 这个值有 49/50 的概率
        // 比 JVM 时钟快出微秒级；MessageProcessor 的「没到重试时间就不投」判断会因此偶发地
        // 整条跳过，通知数变 0——这就是这条用例原来时红时绿的原因。
        // 这里把重试时间显式拨到过去：要不要投不该取决于两次取时的先后。
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = :t WHERE id = :id")
                .param("t", LocalDateTime.now().minusMinutes(1)).param("id", eventId).update();
        processor.process(eventId);

        assertThat(count("notification")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content FROM notification").query(String.class).single()).contains("超时");
        assertThat(orders.get(order.id(), "student").closeReason()).isEqualTo("TIMEOUT");
    }

    // ——————————————————— 辅助 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(any())).thenReturn(clock);
    }

    private OrderView create() {
        OrderView order = facade.create("student", UUID.randomUUID().toString(), new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        seenOrderIds.add(order.id());
        return order;
    }

    private String statusOf(String id) {
        return jdbc.sql("SELECT status FROM mall_order WHERE id=:id").param("id", id).query(String.class).single();
    }

    private String reasonOf(String id) {
        return jdbc.sql("SELECT close_reason FROM mall_order WHERE id=:id").param("id", id).query(String.class).single();
    }

    /**
     * 关单发出的事件类型，按名字点名而不是「取最新一条」。
     *
     * <p>原来写成 {@code ORDER BY created_at DESC} 取第一条，是这条用例时红时绿的根源：
     * {@code outbox_event.created_at} 由数据库按 {@code CURRENT_TIMESTAMP} 生成（插入时不带该列），
     * 而 <b>H2 会缓存这个值</b>——实测连续 8 次取样返回的是同一个时间戳（仅 1 个不同值）。
     * 于是「下单」和「关单」两条事件的时间戳完全并列，{@code ORDER BY created_at DESC}
     * 返回谁并不确定，跟执行时序有关。
     *
     * <p>加二级排序键也救不了：主键 {@code id} 是 {@code UUID.randomUUID()} 生成的 UUID，
     * 不是单调递增的，{@code ORDER BY created_at DESC, id DESC} 在并列时仍然随机。
     * 这张表也没有任何单调递增的列可用。所以改为按事件类型直接点名计数。
     */
    private List<String> eventTypes() {
        return jdbc.sql("SELECT event_type FROM outbox_event ORDER BY event_type").query(String.class).list();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private int count(String table, String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE event_type=:t").param("t", eventType).query(Integer.class).single();
    }

    private static <T> List<T> parallel(int n, IntFunction<T> work) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        try {
            var futures = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> work.apply(i))).toList();
            var out = new ArrayList<T>();
            for (var f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
