package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 已知限制（实测固定）：<b>一笔订单今天只能真正放款一次。</b>
 *
 * <p>这不是猜的，是跑出来的：第一笔部分退款（¥20）走 AUTO 成功放款，
 * 紧接着申请第二笔（¥10），被 {@code uq_wallet_ledger_order_reason} 拒绝，
 * 抛 {@link DuplicateKeyException}（500），整笔事务回滚。
 *
 * <p>两处设计在这里是<b>对不上的</b>：
 * <ul>
 *   <li>服务层 {@code RefundService.requireWithinRemaining} 按「累计已退 + 本次 <= 实付」写，
 *       {@code hasInFlight} 只挡「在途」、不挡「已放款后的第二笔」——它预期支持多笔；</li>
 *   <li>数据层 V16 的 {@code uq_wallet_ledger_order_reason(order_id, reason)} 钉死
 *       「同一订单 + REFUND 只允许一行」——它只允许一笔。</li>
 * </ul>
 * V16 的注释已经把这个耦合点写明了（"将来放开部分退款，这个索引必须换成以 refund.id 为粒度"），
 * 但 V17 落地退款时没有同步改它。今天的结果是：<b>放行一半、拒一半，且拒绝方式是 500。</b>
 *
 * <p><b>好消息是失败形态是干净的</b>：异常让整个事务回滚，不会留下
 * 「refund 表记了 REFUNDED 但钱没到」这种半吊子。这也是本组断言的重点——
 * 限制可以存在，但不能以破坏账目为代价。
 *
 * <p>将来放开多笔退款时，本类应当被替换（不是修改断言）为「第二笔成功入账、流水两条」。
 */
@SpringBootTest
@ActiveProfiles("test")
class PartialRefundLimitTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 23, 11, 0, 0);

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired RefundService refunds;
    @Autowired JdbcClient jdbc;
    @MockitoBean Clock clock;

    private final String user = "partial-refund-limit";

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
        WalletProbe.purge(jdbc, user);
        at(BASE);
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        WalletProbe.purge(jdbc, user);
    }

    @Test
    @DisplayName("第一笔部分退款成功后，第二笔被唯一索引拒绝，且整笔回滚（不留半吊子退款）")
    void secondPartialRefundIsRejectedAndRolledBack() {
        OrderView order = facade.create(user, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        OrderView paid = payments.pay(order.id(), user, "DEMO_WALLET");
        long afterPay = WalletProbe.balanceCents(jdbc, user);

        // ① 第一笔：部分退款 ¥20，AUTO 放款成功。
        RefundView first = refunds.apply(paid.id(), SubOrders.only(paid), user, new BigDecimal("20.00"), "NO_REASON", "先退一部分");
        assertThat(first.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(first.route()).isEqualTo(RefundView.ROUTE_AUTO);
        long afterFirst = WalletProbe.balanceCents(jdbc, user);
        assertThat(afterFirst).isEqualTo(afterPay + 2_000L);
        assertThat(WalletProbe.deltas(jdbc, user, "REFUND")).hasSize(1);

        // ② 第二笔：服务层放行（不在途、累计未超实付），被数据层唯一索引挡下。
        DuplicateKeyException rejected = catchThrowableOfType(
                () -> refunds.apply(paid.id(), SubOrders.only(paid), user, new BigDecimal("10.00"), "NO_REASON", "再退一部分"),
                DuplicateKeyException.class);
        assertThat(rejected).as("同一订单第二次放款必须被 uq_wallet_ledger_order_reason 拒")
                .isNotNull();
        assertThat(rejected.getMessage()).contains("UQ_WALLET_LEDGER_ORDER_REASON");

        // ③ 拒绝必须是干净的：整笔回滚，账目回到第一笔之后的状态。
        assertThat(countRefunds(paid.id()))
                .as("第二笔的退款行必须随之回滚：留下一条终态 refund 就是下一次对账的坑")
                .isEqualTo(1);
        assertThat(WalletProbe.balanceCents(jdbc, user))
                .as("钱没退成，余额就不该动").isEqualTo(afterFirst);
        assertThat(WalletProbe.deltas(jdbc, user, "REFUND"))
                .as("REFUND 流水仍只有第一笔那一条").hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, user)).isZero();
        assertThat(WalletProbe.chainMismatch(jdbc, user)).isZero();
    }

    private int countRefunds(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id = :o")
                .param("o", orderId).query(Integer.class).single();
    }

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        org.mockito.Mockito.when(clock.getZone()).thenReturn(zone);
        org.mockito.Mockito.when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
    }
}
