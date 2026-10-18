package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
import dev.learning.mall.payment.internal.DemoWalletPayment;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 退款归属校验的<b>第二层</b>（反向验证表 R8）。
 *
 * <p>「订单是不是你的」这条规则在项目里判了两次：
 * <ol>
 *   <li><b>外层</b>：{@code PendingActionService.ordersOf(username)} / Controller 的登录身份。
 *       越权调用通常在那一层就被拦下，根本到不了 Service。</li>
 *   <li><b>内层</b>：{@code RefundService.apply} 里的
 *       {@code if (!order.username().equals(username)) throw ...}。</li>
 * </ol>
 * 本文件只测<b>第二层</b>，而且是<b>直击</b>：由 A 下单并支付，然后直接调
 * {@code refunds.apply(A 的订单号, "B", ...)}——绕开 HITL、绕开 Controller、
 * 连 HTTP 都不发。「绕过 HTTP 直接调 Service」这条路不该因为没有 HTTP 就失守。
 *
 * <h3>为什么这个测法才有意义</h3>
 *
 * <p>删掉内层的那一行校验，既有测试<b>一条都不会红</b>：
 * {@code RefundRoutingTest#cannotRequestRefundForOthersOrder} 走的是外层（HITL 卡片），
 * 它在内层被删掉之后照样被外层拦下。这就是「零覆盖」的形状——
 * 守卫存在但没有哪条断言会因为它消失而改变颜色。本文件要把它变红。
 *
 * <h3>为什么 404 而不是 403</h3>
 *
 * <p>「存在但不是你的」与「不存在」必须给同一个答复。给 403 等于告诉调用方
 * 「这个订单号是真的，只是不是你的」——于是订单号本身成了可以被枚举的东西。
 *
 * <h3>断言为什么落在「零退款行」上</h3>
 *
 * <p>光断言抛了异常不够：内层被删掉时，这次越权调用会真的<b>走完</b>整条退款链
 * （AUTO 路由 → 放款 → 钱回到 A 的钱包）。事务回滚能撤掉库里的行，
 * 撤不掉已经发出去的 gateway 调用——所以这里同时数「放款被调用了几次」。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundOwnershipTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 18, 13, 0, 0);

    /** 订单金额的「分」：商品1（¥39）×1 + 运费 8 = ¥47.00。 */
    private static final long ORDER_CENTS = 4_700L;

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired RefundService refunds;
    @Autowired JdbcClient jdbc;
    @MockitoBean Clock clock;
    /** 放款入口的 spy：用来数「被调用过几次」，而不是看库里有没有留下痕迹。 */
    @MockitoSpyBean DemoWalletPayment walletGateway;

    private String owner;
    private String intruder;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
        at(BASE);
        clearInvocations(walletGateway);
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        if (owner != null) WalletProbe.purge(jdbc, owner);
        if (intruder != null) WalletProbe.purge(jdbc, intruder);
        owner = null;
        intruder = null;
    }

    // ——————————————————— R8 第二层 ———————————————————

    @Test
    @DisplayName("R8 别人的订单号直接喂给 Service：404 NOT_FOUND，且一笔退款都没产生")
    void applyingForAnotherUsersOrderIsRejectedAtTheServiceLayer() {
        String a = userA("owner");
        String b = userB("intruder");
        OrderView paid = payWithWallet(a);
        long balanceBefore = WalletProbe.balanceCents(jdbc, a);

        // 直击 Service：没有 HTTP、没有 HITL 卡片，外层那道归属校验整个被绕过去了。
        BusinessException denied = catchThrowableOfType(
                () -> refunds.apply(paid.id(), b, null, "NO_REASON", "替别人退一下"),
                BusinessException.class);

        assertThat(denied).as("第二层归属校验必须自己站得住：外层被绕过时它不该跟着失效").isNotNull();
        assertThat(denied.code()).isEqualTo("NOT_FOUND");
        assertThat(denied.status().value())
                .as("「存在但不是你的」与「不存在」必须给同一个答复：403 会泄露订单号的存在性")
                .isEqualTo(404);
        assertThat(countRefunds(paid.id())).as("越权请求零副作用").isZero();
        assertThat(WalletProbe.balanceCents(jdbc, a)).as("钱不许动").isEqualTo(balanceBefore);
        assertThat(WalletProbe.deltas(jdbc, a, "REFUND")).isEmpty();
        verify(walletGateway, never()).refund(any());

        // 反向控制：同一个 Service、同一笔订单，本人调用一切正常。
        // 少了这一句，上面那条绿也可能来自「这个入口对所有人都报 404」的偷懒实现。
        RefundView own = refunds.apply(paid.id(), a, null, "NO_REASON", "不想要了");
        assertThat(own.route()).isEqualTo(RefundView.ROUTE_AUTO);
        assertThat(own.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(countRefunds(paid.id())).isEqualTo(1);
        assertThat(WalletProbe.deltas(jdbc, a, "REFUND")).containsExactly(ORDER_CENTS);
    }

    // ——————————————————— 工具 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private int countRefunds(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id = :o")
                .param("o", orderId).query(Integer.class).single();
    }

    private String userA(String suffix) {
        owner = "refund-own-a-" + suffix;
        return owner;
    }

    private String userB(String suffix) {
        intruder = "refund-own-b-" + suffix;
        return intruder;
    }
}
