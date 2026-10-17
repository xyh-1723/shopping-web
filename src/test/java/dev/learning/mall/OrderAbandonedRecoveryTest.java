package dev.learning.mall;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.order.api.*;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderProperties;
import dev.learning.mall.order.internal.OrderTransactions;
import dev.learning.mall.proactive.internal.AbandonedOrderService;
import dev.learning.mall.proactive.internal.ProactiveProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 弃单挽回：订单超时关闭之后，把用户拉回来。
 *
 * <p>这个功能的价值不在「多一条提醒」，而在<b>区分了两种关闭</b>：
 * 主动取消是「我不想买了」，超时关闭是「可能是忘了」。前者不该被打扰，后者值得提醒一句。
 * 所以这里最重要的一条测试反而是那条<b>不该产生提醒</b>的——用户主动取消的订单必须安静。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderAbandonedRecoveryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 16, 12, 0, 0);

    @Autowired OrderFacade facade;
    @Autowired OrderTransactions orders;
    @Autowired OrderProperties orderProperties;
    @Autowired AbandonedOrderService recovery;
    @Autowired ProactiveProperties proactiveProperties;
    @Autowired ProductCatalog products;
    @Autowired JdbcClient jdbc;
    @MockitoBean Clock clock;

    @BeforeEach void setUp() {
        orderProperties.setPaymentTimeoutMinutes(15);
        proactiveProperties.setEnabled(true);
        for (String table : List.of("mall_alert", "notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock=10").update();
        at(BASE);
    }

    @AfterEach void restore() {
        proactiveProperties.setEnabled(false);
    }

    @Test
    @DisplayName("超时关闭会召回：告诉用户东西还在，而不是让订单悄无声息地消失")
    void timeoutCloseBringsTheUserBack() {
        var order = create();
        at(BASE.plusMinutes(15));
        orders.closeExpired(order.id(), BASE.plusMinutes(15));

        var alert = alertsOf("student");
        assertThat(alert).hasSize(1);
        assertThat(alert.getFirst().title()).contains("东西还在");
        assertThat(alert.getFirst().content())
                .contains("超时未支付")
                .contains("云朵马克杯")
                .contains("还有");
    }

    @Test
    @DisplayName("召回里带的推荐必须写明理由：没理由的推荐看起来像广告")
    void suggestionCarriesAReason() {
        // 先买一件，给推荐一点真实的种子（否则只能退到「最近热门」）。
        var seed = create();
        orders.pay(seed.id(), "student", "DEMO_CARD");

        var abandoned = create();
        at(BASE.plusMinutes(15));
        orders.closeExpired(abandoned.id(), BASE.plusMinutes(15));

        var alert = alertsOf("student");
        assertThat(alert).hasSize(1);
        assertThat(alert.getFirst().content()).contains("你可能还想要").contains("（").contains("）");
    }

    @Test
    @DisplayName("用户主动取消的订单不召回：人家是不想买了，再推荐就是打扰")
    void userCancellationIsNotFollowedUp() {
        var order = create();
        orders.cancel(order.id(), "student");

        assertThat(alertsOf("student")).isEmpty();
        // 同样的订单若被超时关闭就会召回——差别只在关闭原因，这正是区分两者的意义。
        var another = create();
        at(BASE.plusMinutes(15));
        orders.closeExpired(another.id(), BASE.plusMinutes(15));
        assertThat(alertsOf("student")).hasSize(1);
    }

    @Test
    @DisplayName("同一笔订单只召回一次：重复扫描不能变成重复打扰")
    void sameOrderIsRecoveredOnce() {
        var order = create();
        var event = new OrderAbandonedEvent(order.id(), "student", List.of(1L));

        assertThat(recovery.recover(event)).isTrue();
        assertThat(recovery.recover(event)).isFalse();
        assertThat(alertsOf("student")).hasSize(1);
    }

    @Test
    @DisplayName("两个线程同时召回同一笔订单：唯一索引兜底，仍然只有一条")
    void concurrentRecoveryStillProducesOneAlert() throws Exception {
        var order = create();
        var event = new OrderAbandonedEvent(order.id(), "student", List.of(1L));

        var outcomes = parallel(4, i -> recovery.recover(event));
        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(alertsOf("student")).hasSize(1);
    }

    @Test
    @DisplayName("主动服务关闭时不召回：开关是唯一的，不能只关掉一半")
    void switchOffStopsRecovery() {
        proactiveProperties.setEnabled(false);
        var order = create();
        at(BASE.plusMinutes(15));
        orders.closeExpired(order.id(), BASE.plusMinutes(15));

        assertThat(alertsOf("student")).isEmpty();
        // 关单本身照常发生：召回是关单的后续动作，不该反过来影响关单。
        assertThat(orders.get(order.id(), "student").closeReason()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("召回只在关单事务提交之后发生：否则读到的还是归还前的旧库存")
    void recoverySeesTheStockAfterItWasReturned() {
        jdbc.sql("UPDATE product SET stock=1 WHERE id=1").update();
        var order = create();          // 库存 1 → 0
        assertThat(products.get(1).stock()).isZero();

        at(BASE.plusMinutes(15));
        orders.closeExpired(order.id(), BASE.plusMinutes(15));

        // 若监听器在事务内被唤醒，这里会写成「暂时缺货」——用户被召回却发现买不了。
        assertThat(alertsOf("student").getFirst().content()).contains("还有 1 件");
    }

    // ——————————————————— 辅助 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(any())).thenReturn(clock);
    }

    private OrderView create() {
        return facade.create("student", UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private record Alert(String title, String content) {}

    private List<Alert> alertsOf(String username) {
        return jdbc.sql("SELECT title, content FROM mall_alert WHERE username=:u ORDER BY created_at")
                .param("u", username)
                .query((rs, row) -> new Alert(rs.getString("title"), rs.getString("content"))).list();
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
