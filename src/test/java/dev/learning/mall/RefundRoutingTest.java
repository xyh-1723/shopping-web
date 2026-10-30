package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.harness.TurnScope;
import dev.learning.mall.catalog.internal.ProductService;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderProperties;
import dev.learning.mall.order.internal.RefundService;
import dev.learning.mall.payment.internal.DemoWalletPayment;
import dev.learning.mall.wallet.internal.WalletProperties;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 退款分流（退款方案 §4，对应反向验证表 T14 #1 / #2 / #8 / #9 / #13）。
 *
 * <p>要守的核心是这句话：<b>「未发货」是硬规则，时间与金额是配置</b>。
 * 三条判据里只有后两条允许被改；把它们写成常量、或从别处抄一个数，
 * 「货已经在买家手上还能一键退回货款」这条路就被悄悄打开了。
 *
 * <p><b>分层纪律</b>（本项目已四次栽在「写完就绿的测试其实什么也没守住」上）：
 * <ul>
 *   <li>每条断言都走<b>真实入口</b>（{@link OrderPayments#pay} / {@link RefundService#apply} /
 *       {@link PendingActionService#requestOrderRefund}），不用裸 SQL 顶替被测行为；</li>
 *   <li>#8 必须<b>数调用次数</b>而不是看「表零新增」：一旦金额校验被挪到放款之后，
 *       gateway 的调用已经发生且不可回滚，库里却什么都看不出来（方案 §9 的分层注记）；</li>
 *   <li>「阈值来自配置」用「改配置 → 结论翻转」证明，而不是断言配置对象里躺着某个值——
 *       后者在阈值被写死的情况下依然全绿。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundRoutingTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 18, 9, 0, 0);

    /** 商品1（¥39）×1 → ¥39 + 运费 8 = ¥47.00。用具体数字，让「金额口径变了」这件事显形。 */
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("47.00");

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired PendingActionService pendingActions;
    @Autowired OrderProperties properties;
    @Autowired WalletProperties walletProperties;
    @Autowired JdbcClient jdbc;
    /** 时间可注入：窗口「还没到期 / 刚到期」才能被直接断言，而不是靠 sleep。 */
    @MockitoBean Clock clock;
    /** 放款入口的 spy：用来数「被调用过几次」，而不是看库里有没有留下痕迹。 */
    @MockitoSpyBean DemoWalletPayment walletGateway;
    /** 归还库存的生产入口 spy：同上。 */
    @MockitoSpyBean ProductService catalog;

    private String user;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
        at(BASE);
        // spy 的调用计数是跨用例累积的：这里确认的是「本轮调用了多少次」。
        clearInvocations(walletGateway, catalog);
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("DELETE FROM assistant_pending_action").update();
        jdbc.sql("DELETE FROM assistant_audit").update();
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
    }

    // ——————————————————— #1 未发货是硬规则 ———————————————————

    @Test
    @DisplayName("#1 已发货的订单必须走商家确认：不分 AUTO、不自动放款、钱与货都不动")
    void shippedOrderRequiresMerchant() {
        String u = user("shipped");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-ROUTE-1");
        long stockBefore = stockOf(1);

        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "NO_REASON", "不想要了");

        assertThat(refund.route()).as("货已出库就不能「点击即退」：这条是硬规则，不受配置影响")
                .isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);
        assertThat(refund.status()).isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        assertThat(refund.decidedBy()).as("还没人批过它").isNull();
        assertThat(refund.stockReturned()).isFalse();

        // 没有被自动放款：钱包余额纹丝不动，REFUND 流水零行。
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grantCents() - 4_700L);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
        assertThat(stockOf(1)).isEqualTo(stockBefore);
        // 放款入口一次都没被碰到。
        verify(walletGateway, never()).refund(any());
        verify(catalog, never()).returnStock(anyLong(), anyInt(), any(), any());

        // 它真的落在了商家的待办事宜里（§4.3 路径 B 的落点）。
        // 用 admin（店员）看全站队列——商家账号只能看到自己那家店的单，
        // 而这条断言要证明的是「它进了队列」，不是「某个商家看得到它」。
        assertThat(refunds.pendingQueueFor("admin", 20).stream().map(RefundView::id).toList())
                .contains(refund.id());
    }

    // ——————————————————— #2 窗口与金额来自配置 ———————————————————

    /**
     * 「阈值是不是真的读了配置」必须用<b>改配置 → 结论翻转</b>来证明。
     *
     * <p>写成 {@code assertThat(properties.getRefund().getAutoRefundMaxAmount()).isEqualTo(200)}
     * 的那种断言在实现把阈值写死时依然全绿——它测的是配置对象本身，不是判定。
     */
    @Test
    @DisplayName("#2 时间与金额阈值来自配置：同一笔申请换个配置就在 AUTO / MERCHANT 之间翻转")
    void thresholdsComeFromProperties() {
        String u = user("threshold");

        // 基线：默认阈值（24 小时 / ¥200）下，¥47、刚支付的未发货订单是 AUTO。
        OrderView baseline = payWithWallet(u);
        assertThat(refunds.apply(baseline.id(), SubOrders.only(baseline), u, null, null, null).route())
                .as("用例前提：默认阈值下这笔走无理由退款")
                .isEqualTo(RefundView.ROUTE_AUTO);

        // ① 金额阈值改成 ¥1 ——同一件商品同一金额，必须改判需要商家确认。
        OrderView amountCase = payWithWallet(u);
        RefundView overCap;
        try (Thresholds ignored = thresholds(24, new BigDecimal("1.00"))) {
            overCap = refunds.apply(amountCase.id(), SubOrders.only(amountCase), u, null, null, null);
        }
        assertThat(overCap.route()).as("金额阈值必须是配出来的，不是写死在代码里的 200")
                .isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);
        assertThat(overCap.status()).isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        assertThat(overCap.amount()).isEqualByComparingTo(ORDER_AMOUNT);

        // ② 时间窗口：25 小时后申请，默认窗口（24h）应当已经过期。
        //    计的是 paid_at，不是 created_at。
        OrderView staleCase = payWithWallet(u);
        at(BASE.plusHours(25));
        assertThat(refunds.apply(staleCase.id(), SubOrders.only(staleCase), u, null, null, null).route())
                .as("超过 24 小时就不该还能自助退（按支付时刻计）")
                .isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);

        // ③ 反向控制：把窗口配成 48 小时后，同一笔「25 小时前支付」的订单重新变成 AUTO。
        //    少了这一句，上面那条绿也可能来自「只要时间过了就一律 MERCHANT」这种偷懒实现。
        //
        //    支付时刻必须<b>先拨回 BASE</b>：② 之后时钟停在 25 小时后，若在这里直接下单，
        //    paid_at 与申请时刻相差 0 小时，于是「窗口配成 48」这个动作<b>根本没被判定读到</b>
        //    ——把窗口写死成 24 的实现在这条用例下同样全绿（实测，见本次变异记录 R2b）。
        at(BASE);
        OrderView widened = payWithWallet(u);
        at(BASE.plusHours(25));
        RefundView wide;
        try (Thresholds ignored = thresholds(48, new BigDecimal("200.00"))) {
            wide = refunds.apply(widened.id(), SubOrders.only(widened), u, null, null, null);
        }
        assertThat(wide.route()).as("窗口是配出来的，把它放宽应当立刻生效")
                .isEqualTo(RefundView.ROUTE_AUTO);
        assertThat(wide.status()).isEqualTo(RefundView.STATUS_REFUNDED);
    }

    // ——————————————————— #13 AUTO 必须先建单再放行 ———————————————————

    /**
     * 这条的存在是因为一个<b>已经发生过一次</b>的 bug：约束写成
     * {@code route <> 'AUTO' OR decided_by = 'SYSTEM'} 时，AUTO 连建单都会被自己的约束拒掉
     * （建单那一刻 {@code decided_by} 还是 {@code NULL}，而 {@code NULL = 'SYSTEM'} 不是 TRUE）。
     *
     * <p>所以它必须从<b>真实的 AUTO 申请入口</b>走到终态：
     * 绕开建单直接落一条 REFUNDED，绿了也说明不了任何事。
     */
    @Test
    @DisplayName("#13 无理由退款：从申请入口走到 REFUNDED，decided_by 是 SYSTEM（建单时的 NULL 不被约束拒绝）")
    void autoRefundIsCreatedBeforeItIsDecided() {
        String u = user("auto");
        OrderView paid = payWithWallet(u);

        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null);

        assertThat(refund.route()).isEqualTo(RefundView.ROUTE_AUTO);
        assertThat(refund.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(refund.decidedBy())
                .as("AUTO 的审批人是 SYSTEM：不许把自助退款伪造成「某某批过」")
                .isEqualTo("SYSTEM");
        assertThat(refund.requestedBy()).isEqualTo(u);
        assertThat(refund.username()).isEqualTo(u);
        assertThat(refund.reasonCode()).isEqualTo("NO_REASON");
        assertThat(refund.stockReturned()).as("未发货 → 货确实回来了").isTrue();
        // 建单必须真的发生过一行（而不是「一步插成终态」的捷径）。
        assertThat(countRefunds(paid.id())).isEqualTo(1);
        assertThat(statusOf(refund.id())).isEqualTo(RefundView.STATUS_REFUNDED);
    }

    // ——————————————————— #8 超额必须在任何副作用之前被拒 ———————————————————

    /**
     * <b>这条断言必须落在「调用次数」上</b>：把金额校验挪到放款之后，实现依然报 409，
     * 事务回滚后 {@code refund} 表零新增、库存账也照样对得上——
     * 唯一会变红的是「放款 / 还货<b>被调用过</b>」，而那个调用一旦发生就无法撤销。
     */
    @Test
    @DisplayName("#8 退款金额不得超过实付：409、零新增，且放款与还货一次都没被调用")
    void refundCannotExceedPaid() {
        String u = user("exceed");
        OrderView paid = payWithWallet(u);
        long stockBefore = stockOf(1);

        BusinessException rejected = catchThrowableOfType(
                () -> refunds.apply(paid.id(), SubOrders.only(paid), u, new BigDecimal("100.00"), null, null),
                BusinessException.class);

        assertThat(rejected).as("超额退款必须是一次说得清的 409").isNotNull();
        assertThat(rejected.code()).isEqualTo("REFUND_EXCEEDS_PAID");
        assertThat(rejected.status().value()).isEqualTo(409);
        assertThat(countRefunds(paid.id())).isZero();
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grantCents() - 4_700L);
        assertThat(stockOf(1)).isEqualTo(stockBefore);

        verify(walletGateway, never()).refund(any());
        verify(catalog, never()).returnStock(anyLong(), anyInt(), any(), any());
    }

    // ——————————————————— #9 HITL 发起侧要校验订单归属 ———————————————————

    @Test
    @DisplayName("#9 助手代发起退款要校验订单归属：替别人申请返回 404，本人确认后才真的发起")
    void cannotRequestRefundForOthersOrder() {
        String u = user("hitl");
        OrderView paid = payWithWallet(u);
        String conversation = UUID.randomUUID().toString();

        try (TurnScope.Scope ignored = TurnScope.begin(conversation, "intruder")) {
            BusinessException denied = catchThrowableOfType(
                    () -> pendingActions.requestOrderRefund("intruder", paid.id(), SubOrders.only(paid), null, null, null),
                    BusinessException.class);
            assertThat(denied).isNotNull();
            assertThat(denied.code()).as("别人的订单：404，且不泄露它存在").isEqualTo("NOT_FOUND");
            assertThat(denied.status().value()).isEqualTo(404);
        }
        assertThat(countRefunds(paid.id())).as("越权请求零副作用").isZero();

        // 正向控制：本人发起 → 拿到待确认卡片（此时还没退钱）→ 本人确认 → 真的退了。
        var card = pendingActions.requestOrderRefund(u, paid.id(), SubOrders.only(paid), null, null, null);
        assertThat(card.kind()).isEqualTo(PendingActionService.KIND_ORDER_REFUND);
        assertThat(card.orderId()).isEqualTo(paid.id());
        assertThat(card.amount()).isEqualByComparingTo(ORDER_AMOUNT);
        assertThat(card.status()).isEqualTo("PENDING");
        assertThat(countRefunds(paid.id())).as("闸门之前零副作用").isZero();

        var resolution = pendingActions.resolve(card.id(), u, true);
        assertThat(resolution.order()).isNotNull();
        assertThat(statusOf(firstRefundId(paid.id())))
                .as("确认之后走的是页面那份实现（这里是无理由退款，直接到账）")
                .isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(countRefunds(paid.id())).isEqualTo(1);
    }

    // ——————————————————— 在途互斥 / 开关 ———————————————————

    @Test
    @DisplayName("同一笔订单的第二笔在途申请被拒：409 ALREADY_RESOLVED，而不是数据库约束炸出来的 500")
    void secondRefundWhileOneIsInFlightIsRejectedCleanly() {
        String u = user("inflight");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-ROUTE-2");

        refunds.apply(paid.id(), SubOrders.only(paid), u, null, "NO_REASON", null);
        BusinessException second = catchThrowableOfType(
                () -> refunds.apply(paid.id(), SubOrders.only(paid), u, null, "NO_REASON", null), BusinessException.class);

        assertThat(second).isNotNull();
        assertThat(second.code()).isEqualTo("ALREADY_RESOLVED");
        assertThat(countRefunds(paid.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("退款开关关掉后回到旧行为：REFUND_DISABLED，钱包与库存都不动")
    void refundIsDisabledWhenSwitchIsOff() {
        String u = user("disabled");
        OrderView paid = payWithWallet(u);
        properties.getRefund().setEnabled(false);
        try {
            BusinessException rejected = catchThrowableOfType(
                    () -> refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null), BusinessException.class);
            assertThat(rejected).isNotNull();
            assertThat(rejected.code()).isEqualTo("REFUND_DISABLED");
        } finally {
            properties.getRefund().setEnabled(true);
        }
        assertThat(countRefunds(paid.id())).isZero();
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grantCents() - 4_700L);
        verify(walletGateway, never()).refund(any());
    }

    // ——————————————————— 工具 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(any())).thenReturn(clock);
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private long grantCents() {
        return walletProperties.getInitialGrantCents();
    }

    private int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM product WHERE id = :pid").param("pid", productId)
                .query(Integer.class).single();
    }

    private String statusOf(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private int countRefunds(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id = :o")
                .param("o", orderId).query(Integer.class).single();
    }

    private String firstRefundId(String orderId) {
        return jdbc.sql("SELECT id FROM refund WHERE order_id = :o ORDER BY created_at, id LIMIT 1")
                .param("o", orderId).query(String.class).single();
    }

    private String user(String suffix) {
        user = "refund-route-" + suffix;
        return user;
    }

    /** 临时改写阈值、退出时还原：判定每次都重新读这个配置对象，所以改写立刻生效。 */
    private Thresholds thresholds(int windowHours, BigDecimal maxAmount) {
        return new Thresholds(windowHours, maxAmount);
    }

    /** 阈值作用域。 */
    private final class Thresholds implements AutoCloseable {
        private final int previousWindowHours;
        private final BigDecimal previousMaxAmount;

        private Thresholds(int windowHours, BigDecimal maxAmount) {
            this.previousWindowHours = properties.getRefund().getAutoRefundWindowHours();
            this.previousMaxAmount = properties.getRefund().getAutoRefundMaxAmount();
            properties.getRefund().setAutoRefundWindowHours(windowHours);
            properties.getRefund().setAutoRefundMaxAmount(maxAmount);
        }

        @Override
        public void close() {
            properties.getRefund().setAutoRefundWindowHours(previousWindowHours);
            properties.getRefund().setAutoRefundMaxAmount(previousMaxAmount);
        }
    }
}
