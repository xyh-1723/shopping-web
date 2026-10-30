package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderTransactions;
import dev.learning.mall.order.internal.RefundService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D2 商城业务面指标的守护测试：下单 / 支付 / 退款申请 / 退款完结 / 售罄数。
 *
 * <p>要守的不是「代码里写了 counter.increment()」，而是<b>这些数字能不能被运营读出来、
 * 口径对不对</b>。所以每条断言都走真实入口（{@link OrderFacade#create} /
 * {@link OrderPayments#pay} / {@link RefundService#apply}），再读 {@link MeterRegistry} 验证。
 *
 * <p><b>全部用增量断言</b>（与 {@link ObservabilityMetricsTest} 同一套纪律）：
 * 本类与其它 {@code @SpringBootTest} 共享同一个 Spring 上下文与注册表，
 * 别的测试跑过的订单已经往计数器里加过数，断言绝对值会随执行顺序飘。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessMetricsTest {

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired MeterRegistry registry;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private String user;

    @BeforeEach
    void setUp() {
        // 清出干净的舞台：别的测试类留下的订单会让「读总数」型的断言飘。
        // order_sub 必须一起清：V25 刻意没给它到 mall_order 的外键（省掉清理顺序约束），
        // 只删父单会留下一批无主子订单，污染后续按店统计的用例。
        jdbc.sql("DELETE FROM refund").update();
        for (String table : List.of("notification", "outbox_event", "order_item", "order_sub", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
        // 售罄用例把 stock 改成了 0，还原它，免得影响后面跑的测试类。
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
    }

    // ——————————————————— 下单 / 支付 ———————————————————

    @Test
    @DisplayName("下单计 mall.order.placed、支付计 mall.order.paid；重复支付不重复计")
    void orderPlacedAndPaidAreCounted() {
        double placedBefore = counterValue(OrderTransactions.METRIC_ORDER_PLACED);
        double paidBefore = counterValue(OrderTransactions.METRIC_ORDER_PAID);
        String u = buyer("metrics");

        OrderView order = facade.create(u, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        assertThat(counterValue(OrderTransactions.METRIC_ORDER_PLACED) - placedBefore)
                .as("落单成功必须计一次").isEqualTo(1.0);
        assertThat(counterValue(OrderTransactions.METRIC_ORDER_PAID) - paidBefore)
                .as("还没付款，支付计数不能动").isZero();

        payments.pay(order.id(), u, "DEMO_WALLET");
        assertThat(counterValue(OrderTransactions.METRIC_ORDER_PAID) - paidBefore)
                .as("支付成功必须计一次").isEqualTo(1.0);

        // 幂等分支：同方式重复支付直接返回已支付视图，不能再记一次。
        payments.pay(order.id(), u, "DEMO_WALLET");
        assertThat(counterValue(OrderTransactions.METRIC_ORDER_PAID) - paidBefore)
                .as("重复支付走幂等分支，计数若再涨，「成交单量」就虚了").isEqualTo(1.0);
    }

    @Test
    @DisplayName("/actuator/metrics/mall.order.placed 登录后可查询")
    void orderPlacedMetricIsQueryable() throws Exception {
        String u = buyer("endpoint");
        facade.create(u, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));

        String body = mvc.perform(get("/actuator/metrics/" + OrderTransactions.METRIC_ORDER_PLACED)
                        .with(user("admin").roles("USER", "STAFF")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(OrderTransactions.METRIC_ORDER_PLACED);
    }

    // ——————————————————— 退款：申请分桶 / 完结分动作 ———————————————————

    @Test
    @DisplayName("无理由退款：requested{route=AUTO} 与 resolved{action=APPROVE} 各 +1")
    void autoRefundIsBucketedByRoute() {
        double requestedBefore = counterValue(RefundService.METRIC_REFUND_REQUESTED, "route", RefundView.ROUTE_AUTO);
        double approvedBefore = counterValue(RefundService.METRIC_REFUND_RESOLVED, "action", "APPROVE");
        String u = buyer("auto");
        OrderView paid = payWithWallet(u);

        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null);
        assertThat(refund.route()).isEqualTo(RefundView.ROUTE_AUTO);

        assertThat(counterValue(RefundService.METRIC_REFUND_REQUESTED, "route", RefundView.ROUTE_AUTO) - requestedBefore)
                .as("AUTO 申请必须进 AUTO 桶").isEqualTo(1.0);
        assertThat(counterValue(RefundService.METRIC_REFUND_RESOLVED, "action", "APPROVE") - approvedBefore)
                .as("AUTO 当场放款，也算一次 APPROVE 完结").isEqualTo(1.0);
    }

    @Test
    @DisplayName("商家驳回：requested{route=MERCHANT_APPROVAL} 与 resolved{action=REJECT} 各 +1")
    void merchantRejectionIsCounted() {
        double requestedBefore = counterValue(RefundService.METRIC_REFUND_REQUESTED,
                "route", RefundView.ROUTE_MERCHANT_APPROVAL);
        double rejectedBefore = counterValue(RefundService.METRIC_REFUND_RESOLVED, "action", "REJECT");
        String u = buyer("reject");
        OrderView paid = payWithWallet(u);
        // 已发货 → 硬规则把它挡在 AUTO 之外，进商家审批队列。
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-METRICS-1");
        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "NO_REASON", null);
        assertThat(refund.route()).isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);

        assertThat(counterValue(RefundService.METRIC_REFUND_REQUESTED,
                "route", RefundView.ROUTE_MERCHANT_APPROVAL) - requestedBefore)
                .as("等商家判的申请必须进 MERCHANT_APPROVAL 桶").isEqualTo(1.0);

        String owner = merchantOwnerOf(SubOrders.only(paid));
        refunds.reject(refund.id(), owner, "已发货，不支持无理由退");
        assertThat(counterValue(RefundService.METRIC_REFUND_RESOLVED, "action", "REJECT") - rejectedBefore)
                .as("驳回是一次完结，必须按 action=REJECT 计数").isEqualTo(1.0);
    }

    // ——————————————————— 售罄 Gauge ———————————————————

    @Test
    @DisplayName("售罄 Gauge 实时反映 stock=0 的商品种数（成交导致售罄也能体现）")
    void soldOutGaugeTracksStock() {
        double before = soldOutGauge();

        jdbc.sql("UPDATE product SET stock = 0 WHERE id = 1").update();
        assertThat(soldOutGauge())
                .as("Gauge 读数必须实时：下单把库存买空走的是 reserve，不发商品变更事件——"
                        + "靠事件刷新的实现会在这里漏掉这一类售罄")
                .isEqualTo(before + 1);
    }

    // ——————————————————— 工具 ———————————————————

    private double counterValue(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0d : counter.count();
    }

    private double soldOutGauge() {
        Gauge gauge = registry.find("mall.catalog.sold_out").gauge();
        assertThat(gauge).as("售罄 Gauge 必须在 ProductService 构造时就注册好").isNotNull();
        return gauge.value();
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    /** 子订单归属商家的账号：驳回只能由「这家店的主人」执行。 */
    private String merchantOwnerOf(String subId) {
        return jdbc.sql("SELECT m.owner_username FROM merchant m"
                        + " JOIN order_sub s ON s.merchant_id = m.id WHERE s.id = :sub")
                .param("sub", subId).query(String.class).single();
    }

    /** 起个不撞名的名字：本类还要用 SecurityMockMvcRequestPostProcessors.user(...)。 */
    private String buyer(String suffix) {
        user = "biz-metrics-" + suffix;
        return user;
    }
}
