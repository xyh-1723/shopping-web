package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
import dev.learning.mall.payment.api.PaymentRouter;
import dev.learning.mall.payment.api.RefundRequest;
import dev.learning.mall.pricing.api.MoneyUnits;
import dev.learning.mall.wallet.internal.WalletProperties;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 退款的放款（T20）：<b>钱必须真的回到同一个钱包里</b>。
 *
 * <p>这一组存在的理由是一次明确的失败：上一轮把 {@code PaymentGateway.refund} 留成了
 * 「不报错、不失败，只在对账时才发现钱根本没退」的假接口，
 * 而且 {@code RefundRequest} 没有携带放款人身份，流水的 actor 列落成 {@code unknown}。
 * 于是本轮的断言全部落在<b>钱</b>与<b>放款责任人</b>上：
 * <ul>
 *   <li>余额：付之前 / 付之后 / 退之后三个快照必须精确相等（可能相差的不是误差，是 bug）；</li>
 *   <li>流水：REFUND 一行为正、带订单号、与相邻流水首尾相接；</li>
 *   <li>actor：无理由退款是 {@code SYSTEM}，商家批准是那位商家的账号，
 *       <b>既不是买家本人也不是 unknown</b>——前者会把「商家退钱给买家」读成
 *       「买家自己给自己退了一笔钱」，后者等于永久丢掉这个答案。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundWalletTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 18, 11, 0, 0);

    private static final String MERCHANT = "merchant";

    /** 商品1（¥39）×1 → 小计 39 + 运费 8 = ¥47.00。 */
    private static final long ORDER_CENTS = 4_700L;

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired PaymentRouter router;
    @Autowired WalletProperties walletProperties;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean Clock clock;

    private String user;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
        at(BASE);
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
    }

    // ——————————————————— 无理由退款：钱真的回来 ———————————————————

    @Test
    @DisplayName("无理由退款：余额精确回到初始额度，REFUND 一行为正、带订单号、账对数")
    void autoRefundCreditsBuyerWallet() {
        String u = user("auto");
        OrderView paid = payWithWallet(u);
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grant() - ORDER_CENTS);

        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "NO_REASON", null);

        assertThat(refund.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(refund.route()).isEqualTo(RefundView.ROUTE_AUTO);
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("退款是 mock 凭证时余额纹丝不动：这条断言是「钱真的回来了」的唯一证据")
                .isEqualTo(grant());
        List<Long> deltas = WalletProbe.deltas(jdbc, u, "REFUND");
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0)).as("退款必须是入账：符号写反会看起来又扣了一笔").isEqualTo(ORDER_CENTS);
        assertThat(jdbc.sql("SELECT order_id FROM wallet_ledger WHERE username = :u AND reason = 'REFUND'")
                .param("u", u).query(String.class).single()).isEqualTo(paid.id());
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();
        // 退的分值严格等于订单应付的分值：不允许在退款这条路上出现第二套金额口径。
        assertThat(deltas.get(0)).isEqualTo(MoneyUnits.toCents(paid.payAmount()));
    }

    // ——————————————————— actor 必须是放款责任人 ———————————————————

    @Test
    @DisplayName("无理由退款的放款人是 SYSTEM：不是买家本人，也不是 unknown")
    void autoRefundActorIsSystemNotTheBuyer() {
        String u = user("actor-auto");
        OrderView paid = payWithWallet(u);

        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null);
        assertThat(refund.decidedBy()).isEqualTo("SYSTEM");

        assertThat(refundActorOf(u))
                .as("这条线上记成买家本人，等于把「系统同意你退款」读成「你自己退给自己」；"
                        + "记成 unknown 则是永久丢掉这个答案")
                .isEqualTo("SYSTEM");
        assertThat(refundActorOf(u)).isNotEqualTo(u);
    }

    @Test
    @DisplayName("商家批准的放款人是那位商家：钱包流水 actor 记的是他的账号")
    void merchantApprovalActorIsTheApprover() {
        String u = user("actor-merchant");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-ACTOR-1");

        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "WRONG_ITEM", "发错款式");
        assertThat(queued.route()).as("已发货必须等商家").isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);

        RefundView refunded = refunds.approve(queued.id(), MERCHANT, "核实后同意");

        assertThat(refunded.decidedBy()).isEqualTo(MERCHANT);
        assertThat(refundActorOf(u)).as("放款责任人必须是按下「批准」的那个人").isEqualTo(MERCHANT);
        // 钱还是真的回到了买家钱包（这条线货不回来，钱必须回来）。
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grant());
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    @Test
    @DisplayName("不是商家不能批准：直接注入 Service 也过不去（URL 级之外的第二道）")
    void approvingWithoutMerchantRoleIsForbidden() {
        String u = user("actor-forbidden");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-ACTOR-2");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null);

        for (String intruder : List.of(u, "admin", "ghost-account")) {
            BusinessException denied = catchThrowableOfType(
                    () -> refunds.approve(queued.id(), intruder, "我来批"), BusinessException.class);
            assertThat(denied).as("%s 不该有审批权", intruder).isNotNull();
            assertThat(denied.code()).isEqualTo("REFUND_APPROVAL_FORBIDDEN");
            assertThat(denied.status().value()).isEqualTo(403);
        }
        assertThat(statusOf(queued.id())).isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
    }

    // ——————————————————— 银行卡订单：放款必须是响亮的失败 ———————————————————

    /**
     * 这笔钱压根没进过钱包，退款到这里<b>没有可以是真的地方</b>。
     *
     * <p>返回一张模拟凭证会让调用方以为钱已退回，唯一的真相（余额）纹丝不动——
     * 那正是本轮要消灭的假接口。所以这里断言的是「整条退款链一起退回去」：
     * 没有 refund 行、没有流水、现货也没被归还。
     */
    @Test
    @DisplayName("银行卡支付的订单退款：REFUND_UNSUPPORTED，整笔事务回滚（没有半吊子的退款记录）")
    void cardOrderRefundCannotBeFaked() {
        String u = user("card");
        OrderView order = create(u);
        OrderView paid = payments.pay(order.id(), u, "DEMO_CARD");
        assertThat(paid.paymentMethod()).isEqualTo("DEMO_CARD");
        int stockBefore = stockOf();

        BusinessException rejected = catchThrowableOfType(
                () -> refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null), BusinessException.class);

        assertThat(rejected).as("没有资金落点的退款必须报错，不能静默成功").isNotNull();
        assertThat(rejected.code()).isEqualTo("REFUND_UNSUPPORTED");
        assertThat(rejected.status().value()).isEqualTo(400);
        assertThat(WalletProbe.walletRowCount(jdbc, u))
                .as("银行卡路径不该给买家悄悄开一个钱包账户").isZero();
        assertThat(WalletProbe.ledgerRowCount(jdbc, u)).isZero();
        assertThat(countRefunds(paid.id()))
                .as("放款失败必须连带把退款单一起回滚：留下一条 REFUNDED 就是下一次对账的坑").isZero();
        assertThat(stockOf()).as("钱没退出去，货也不该回来").isEqualTo(stockBefore);
    }

    // ——————————————————— 同一订单不许放款两次 ———————————————————

    /**
     * 应用层之外的一半：绕开退款状态机直接调 gateway 放款第二次，
     * 由 {@code (order_id, reason)} 唯一索引拦下并整笔回滚。
     */
    @Test
    @DisplayName("绕开状态机直接二次放款：被 (order_id, reason) 唯一索引拒绝，余额只增加一次")
    void sameOrderCannotBeRefundedTwiceBelowTheService() {
        String u = user("twice");
        OrderView paid = payWithWallet(u);
        long balance = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> router.get("DEMO_WALLET")
                .refund(new RefundRequest(paid.id(), u, "SIM-WALLET-" + paid.id(),
                        paid.payAmount(), "SYSTEM")));
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(balance + ORDER_CENTS);

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> router.get("DEMO_WALLET")
                .refund(new RefundRequest(paid.id(), u, "SIM-WALLET-" + paid.id(),
                        paid.payAmount(), "SYSTEM"))))
                .as("同一笔订单在同一件事上只能入账一次")
                .hasMessageContaining("UQ_WALLET_LEDGER_ORDER_REASON");

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(balance + ORDER_CENTS);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 工具 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        org.mockito.Mockito.when(clock.getZone()).thenReturn(zone);
        org.mockito.Mockito.when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
    }

    private OrderView create(String username) {
        return facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    private OrderView payWithWallet(String username) {
        OrderView order = create(username);
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private long grant() {
        return walletProperties.getInitialGrantCents();
    }

    private int stockOf() {
        return jdbc.sql("SELECT stock FROM product WHERE id = 1").query(Integer.class).single();
    }

    private int countRefunds(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id = :o")
                .param("o", orderId).query(Integer.class).single();
    }

    private String statusOf(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private String refundActorOf(String username) {
        return jdbc.sql("SELECT actor FROM wallet_ledger WHERE username = :u AND reason = 'REFUND'")
                .param("u", username).query(String.class).single();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private String user(String suffix) {
        user = "refund-wallet-" + suffix;
        return user;
    }
}
