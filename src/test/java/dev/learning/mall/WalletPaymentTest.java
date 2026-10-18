package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.api.PendingActionView;
import dev.learning.mall.assistant.harness.TurnScope;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.payment.api.PayRequest;
import dev.learning.mall.payment.api.PaymentGateway;
import dev.learning.mall.payment.api.PaymentRouter;
import dev.learning.mall.payment.api.RefundRequest;
import dev.learning.mall.pricing.api.MoneyUnits;
import dev.learning.mall.wallet.api.WalletAccounts;
import dev.learning.mall.wallet.internal.WalletProperties;
import dev.learning.mall.wallet.internal.WalletService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 支付通道改造（T18）：<b>钱必须真的动一下</b>。
 *
 * <p>在此之前 {@code DemoWalletPayment} 是一句 {@code return "SIM-WALLET-" + orderId}：
 * 不查余额、不扣款、不落任何账。于是「退款把钱退回买家钱包」这句话从头到尾没有对照物——
 * 钱从来没进来过，也就谈不上退回去。这组用例要钉死的是这条因果链：
 *
 * <pre>
 *   订单支付  →  钱包真的少一笔  →  PAYMENT 流水为负、带订单号、前后值衔接
 *   退款放款  →  钱包真的多一笔  →  REFUND 流水为正、对得上账
 *   余额不足  →  一次干净的 409  →  订单仍是待支付，钱包一分未动
 * </pre>
 *
 * <p><b>断言一律落在「钱有没有真的动」上，而不是落在凭证字符串上</b>：
 * 凭证是可以被 {@code return} 出来的，余额不行。
 *
 * <p><b>分层纪律</b>：每条都走真实入口（{@link OrderPayments} / {@link PaymentGateway} /
 * {@link WalletService}），不用裸 SQL 顶替被测行为——本项目已经在「自带 SQL」上栽过一次。
 * 数据库层的约束由 {@link V16MigrationTest} 单独守。
 *
 * <p><b>反向验证</b>：每条断言都做过变异验证（“注回 bug → 变红”），见提交信息与设计方案 §9.2。
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletPaymentTest {

    /**
     * 用例统一的订单金额：商品1（¥39）×1 → 小计 39 → 未满 99 无满减、不包邮 → 39 + 运费 8 = ¥47.00。
     *
     * <p>用具体数字而不是 {@code order.total()} 自证，是为了让「金额口径变了」这件事显形。
     */
    private static final long ORDER_CENTS = 4_700L;

    /** 放款责任人：无理由退款（AUTO 路由）的代表值。 */
    private static final String ACTOR_SYSTEM = "SYSTEM";

    /** 放款责任人：商家批准路径的代表值。 */
    private static final String ACTOR_APPROVER = "merchant";

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired OrderInsights insights;
    @Autowired PaymentRouter router;
    @Autowired PendingActionService pendingActions;
    @Autowired WalletService wallets;
    @Autowired WalletAccounts accounts;
    @Autowired WalletProperties walletProperties;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    private String user;

    /**
     * 本类建过的订单号（跨用例累积）：{@link #noOrderTrailIsLeftBehind()} 用它把自查
     * 的范围收窄到本类自己的行上（为什么要收窄见 {@link OrderTrail#residueFor}）。
     */
    private static final List<String> seenOrderIds = new CopyOnWriteArrayList<>();

    /** {@code @AfterAll} 必须是 static，拿不到实例的 {@code jdbc}；由 {@code @BeforeEach} 递进来。 */
    private static JdbcClient trailJdbc;

    @BeforeEach
    void resetData() {
        // 与 MallIntegrationTest / CheckoutAmountConsistencyTest 同一份约定：
        // H2 内存库在整个 JVM 内共享，不做清理的断言会在换一个执行顺序后随机变红。
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order",
                "mall_cart_item")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE product SET stock = 30").update();
        trailJdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        // 只清本用例那个用户名：别的测试的数据不该因为它而被连带删掉。
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            for (String table : List.of("assistant_audit", "assistant_pending_action")) {
                jdbc.sql("DELETE FROM " + table + " WHERE username = :u").param("u", user).update();
            }
            user = null;
        }
        // 订单及其附属行：@BeforeEach 里那串全表清只护得住「本类开始时是干净的」，
        // 护不住「本类跑完之后没给下一个类留东西」——最后一条用例的订单 + 事件会留到别的类里去。
        // 删除顺序按外键来（notification → outbox_event → order_item → mall_order），见 OrderTrail#purge。
        OrderTrail.purge(jdbc, seenOrderIds);
    }

    /**
     * 哨兵：本类跑完之后，自己建过的订单在订单表 / 明细表 / 事件表里一行都不剩。
     *
     * <p>它存在的<b>唯一</b>理由是「清理这件事本身无法被变异验证」：把上面那行
     * {@code OrderTrail.purge} 删掉，全量测试仍然全绿——没有任何用例按全表计数断言
     * 这三张表，清理就这么静悄悄地失效了。有了这条断言，删掉清理就会变红。
     *
     * <p>范围按本类自己建过的订单号收窄，因此与其他测试类的执行顺序无关。
     */
    @AfterAll
    static void noOrderTrailIsLeftBehind() {
        if (trailJdbc == null) {
            return;
        }
        assertThat(OrderTrail.residueFor(trailJdbc, seenOrderIds))
                .as("本类建过的订单在 mall_order / order_item / outbox_event 里必须一行不剩："
                        + "outbox_event.aggregate_id 有外键指向订单，残迹会一路带到下一个测试类")
                .isZero();
    }

    // ——————————————————— 支付：真的扣款 ———————————————————

    @Test
    @DisplayName("钱包支付：钱真的从买家钱包里扣掉，PAYMENT 流水为负且首尾相接")
    void walletPaymentDebitsBuyerWallet() {
        String u = user("pays");
        OrderView order = newPendingOrder(u);
        assertThat(order.total()).isEqualByComparingTo("47.00");
        // 迁移不 seeding：支付之前这个用户不该有钱包行，账户是在首次触达时才开的。
        assertThat(WalletProbe.walletRowCount(jdbc, u)).isZero();

        long grant = walletProperties.getInitialGrantCents();
        OrderView paid = payments.pay(order.id(), u, "DEMO_WALLET");

        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paymentMethod()).isEqualTo("DEMO_WALLET");
        assertThat(paid.paymentReceipt()).isEqualTo("SIM-WALLET-" + order.id());
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("支付一笔订单之后余额必须少这么多：凭证可以 return 出来，余额不行")
                .isEqualTo(grant - ORDER_CENTS);

        List<Long> deltas = WalletProbe.deltas(jdbc, u, "PAYMENT");
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0))
                .as("支付必须是出账：符号写反会让它看起来像充值，而对账恒等式与算术 CHECK 都发现不了")
                .isEqualTo(-ORDER_CENTS);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();

        // 流水要能归因：带着订单号与操作者，才知道这笔钱是被哪笔订单、被谁扣走的。
        assertThat(jdbc.sql("SELECT order_id FROM wallet_ledger WHERE username = :u"
                        + " AND reason = 'PAYMENT'").param("u", u).query(String.class).single())
                .isEqualTo(order.id());
        assertThat(jdbc.sql("SELECT actor FROM wallet_ledger WHERE username = :u"
                        + " AND reason = 'PAYMENT'").param("u", u).query(String.class).single())
                .isEqualTo(u);
    }

    /**
     * 方案 §9.2 #18：({@code row.total()} 与 {@code row.payAmount()}) 取错的探测器。
     *
     * <p>今天两列由 insert 同值写入恒等，所以这条现在绿；它的价值在于将来有人改坏其中一列时，
     * 这条会立刻红，而不是等到某次对账才发现差了 8 块钱运费。
     */
    @Test
    @DisplayName("扣款分值 == 订单应付的分值（不是裸算出来的第二套金额）")
    void debitedCentsEqualTheOrderPayable() {
        String u = user("amount");
        OrderView order = newPendingOrder(u);

        payments.pay(order.id(), u, "DEMO_WALLET");

        long debitCents = -WalletProbe.deltas(jdbc, u, "PAYMENT").get(0);
        assertThat(debitCents).isEqualTo(MoneyUnits.toCents(order.payAmount()));
        assertThat(debitCents).isEqualTo(MoneyUnits.toCents(order.total()));
        assertThat(debitCents).isEqualTo(ORDER_CENTS);
        // 既有事实不许漂移：两条口径恒等，上面的等式才有意义。
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE id = :id AND total <> pay_amount")
                .param("id", order.id()).query(Integer.class).single()).isZero();
    }

    /**
     * 「幅度校验」那一层：{@code MoneyUnits.toCents} 必须在碰到钱包<b>之前</b>炸。
     *
     * <p>退化成「静态守卫 {@code noRawCentConversionOutsideMoneyUnits}」不够——
     * 那条守的是「不许自己换算」，这条守的是「换算发生的位置必须早于任何写」。
     */
    @Test
    @DisplayName("精度失衡直接炸在任何写之前：19.999 元既不许四舍五入也不许先开户")
    void subCentAmountIsRejectedBeforeTouchingTheWallet() {
        String u = user("precision");

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> router.get("DEMO_WALLET")
                .pay(new PayRequest("order-precision", u, new BigDecimal("19.999")))))
                .as("超过「分」的精度必须抛异常，而不是抹平成一分钱")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("精度");

        assertThat(WalletProbe.walletRowCount(jdbc, u))
                .as("换算必须早于任何写：出错时不该已经开过户、更不该已经扣过钱").isZero();
        assertThat(WalletProbe.ledgerRowCount(jdbc, u)).isZero();
    }

    // ——————————————————— 余额不足：干净的 409 ———————————————————

    @Test
    @DisplayName("余额不足：一次干净的 409 INSUFFICIENT_FUNDS，订单保持待支付，钱包零变化")
    void insufficientFundsIsClean409AndLeavesNothingBehind() {
        String u = user("broke");
        drainWalletTo(u, 100L);
        long balanceBefore = WalletProbe.balanceCents(jdbc, u);
        long ledgerBefore = WalletProbe.ledgerRowCount(jdbc, u);
        OrderView order = newPendingOrder(u);

        BusinessException rejected = catchThrowableOfType(
                () -> payments.pay(order.id(), u, "DEMO_WALLET"), BusinessException.class);

        assertThat(rejected)
                .as("余额不足必须是一次干净的 409，而不是从数据库漏出来的约束异常（500）").isNotNull();
        assertThat(rejected.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected.status().value()).isEqualTo(409);
        assertThat(rejected.getMessage()).contains("余额不足");

        OrderView after = insights.ordersOf(u).stream()
                .filter(o -> o.id().equals(order.id())).findFirst().orElseThrow();
        assertThat(after.status()).as("扣款失败必须连带把订单留在待支付：扣了钱没落单是最难查的一种账")
                .isEqualTo("PENDING");
        assertThat(after.paymentReceipt()).isNull();
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(balanceBefore);
        assertThat(WalletProbe.ledgerRowCount(jdbc, u))
                .as("失败了就不许留痕").isEqualTo(ledgerBefore);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 幂等：只扣一次 ———————————————————

    /**
     * 应用层的那一半：订单状态机保证第二次 {@code pay} 不会再到钱包那里走一趟。
     */
    @Test
    @DisplayName("同一订单重复支付只扣一次：第二次返回同一张凭证，PAYMENT 流水仍只有一行")
    void payingAnOrderTwiceDebitsTheWalletOnce() {
        String u = user("twice");
        OrderView order = newPendingOrder(u);
        long grant = walletProperties.getInitialGrantCents();

        OrderView first = payments.pay(order.id(), u, "DEMO_WALLET");
        OrderView second = payments.pay(order.id(), u, "DEMO_WALLET");

        assertThat(second.status()).isEqualTo("PAID");
        assertThat(second.paymentReceipt()).isEqualTo(first.paymentReceipt());
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("重复支付不能把同一笔钱扣两次").isEqualTo(grant - ORDER_CENTS);
        assertThat(WalletProbe.deltas(jdbc, u, "PAYMENT"))
                .as("出账流水只能有一行").hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    /**
     * 数据库的那一半：绕过订单状态机直接二次收款，也要被
     * {@code (order_id, reason)} 唯一索引挡下。
     *
     * <p><b>刻意走生产 writer（{@link PaymentGateway#pay}）</b>而不是复制一份 INSERT：
     * 在测试里自带 SQL 只能证明「测试里的 SQL」，那是本项目已经踩过的错配。
     */
    @Test
    @DisplayName("数据层纵深防御：绕开订单状态机直接二次收款，被 (order_id, reason) 唯一索引拒")
    void sameOrderCannotBeDebitedTwiceBelowTheStatusMachine() {
        String u = user("twice-db");
        accounts.ensureAccount(u);
        String orderId = "order-outside-status-machine";
        BigDecimal amount = new BigDecimal("47.00");
        PaymentGateway wallet = router.get("DEMO_WALLET");
        long balanceBefore = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> wallet.pay(new PayRequest(orderId, u, amount)));
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(balanceBefore - ORDER_CENTS);

        assertThatThrownBy(() -> tx().executeWithoutResult(
                s -> wallet.pay(new PayRequest(orderId, u, amount))))
                .as("同一 (order_id, reason) 只能有一行")
                .hasMessageContaining("UQ_WALLET_LEDGER_ORDER_REASON");

        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("唯一索引挡下的第二次收款必须整笔回滚，余额只减少一次")
                .isEqualTo(balanceBefore - ORDER_CENTS);
        assertThat(WalletProbe.deltas(jdbc, u, "PAYMENT")).hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 银行卡：不碰钱包 ———————————————————

    /**
     * 方案 §6.4：{@code DemoCardPayment} 保留下来，代价是必须持续证明它无害。
     * 这条断言把「外部支付方式不进钱包」从一句注释升级成一个事实。
     */
    @Test
    @DisplayName("银行卡支付完全不碰钱包：走完一次真实支付，wallet 无该用户的行、流水零行")
    void cardPaymentWritesNoWalletRows() {
        String u = user("card");
        OrderView order = newPendingOrder(u);

        OrderView paid = payments.pay(order.id(), u, "DEMO_CARD");

        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paymentReceipt()).isEqualTo("SIM-CARD-" + order.id());
        assertThat(WalletProbe.walletRowCount(jdbc, u))
                .as("外部支付方式不该给买家悄悄开一个钱包账户").isZero();
        assertThat(WalletProbe.ledgerRowCount(jdbc, u))
                .as("外部支付方式不该在钱包流水里留任何痕迹").isZero();
    }

    /**
     * {@code DemoCardPayment.refund} 必须报错而不是返回一张模拟凭证。
     *
     * <p>钱压根没进过钱包，退款到这里<b>没有可以是真的地方</b>；静默返回凭证正是本项目
     * 一直在消灭的「假接口」：它不报错、不失败，只在对账时才被发现。
     */
    @Test
    @DisplayName("银行卡退款必须报错而不是返回凭证：REFUND_UNSUPPORTED（400），钱包零变化")
    void cardRefundIsRejectedNotSilentlyReceipted() {
        String u = user("card-refund");
        OrderView order = newPendingOrder(u);
        payments.pay(order.id(), u, "DEMO_CARD");
        assertThat(WalletProbe.walletRowCount(jdbc, u)).isZero();
        PaymentGateway card = router.get("DEMO_CARD");

        BusinessException rejected = catchThrowableOfType(
                () -> card.refund(new RefundRequest(order.id(), u, "SIM-CARD-" + order.id(),
                        order.total(), "merchant")),
                BusinessException.class);

        assertThat(rejected).as("没有资金落点的退款必须报错，不能静默返回一张凭证").isNotNull();
        assertThat(rejected.code()).isEqualTo("REFUND_UNSUPPORTED");
        assertThat(rejected.status().value()).isEqualTo(400);
        assertThat(rejected.getMessage()).contains("不支持退款");
        assertThat(WalletProbe.walletRowCount(jdbc, u)).isZero();
        assertThat(WalletProbe.ledgerRowCount(jdbc, u)).isZero();

        // 哨兵自证：钱包通道的放款<b>不</b>抛。少了这一句，上面的红也可能来自
        // 「所有 refund 都会抛」——那样它就是一条恒绿的空断言。
        assertThatCode(() -> tx().executeWithoutResult(s -> router.get("DEMO_WALLET")
                .refund(new RefundRequest(order.id(), u, "SIM-CARD-" + order.id(), order.total(),
                        ACTOR_SYSTEM))))
                .doesNotThrowAnyException();
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).hasSize(1);
    }

    // ——————————————————— 退款：真的入账 ———————————————————

    /**
     * 退款能力的资金入口：<b>钱只有真的进来过，退款才谈得上把钱退回去</b>。
     *
     * <p>断言刻意落在余额与流水符号上。只查订单状态会在「放款仍是 mock」时<b>全绿</b>——
     * 那是退款侧断言层错配的高危形态。
     */
    @Test
    @DisplayName("退款放款：钱真的回到买家钱包，REFUND 流水为正且对得上账")
    void walletRefundCreditsBuyerWallet() {
        String u = user("refund");
        OrderView order = newPendingOrder(u);
        long grant = walletProperties.getInitialGrantCents();
        String receipt = payments.pay(order.id(), u, "DEMO_WALLET").paymentReceipt();
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(grant - ORDER_CENTS);

        String refundReceipt = tx().execute(s -> router.get("DEMO_WALLET")
                .refund(new RefundRequest(order.id(), u, receipt, order.total(), ACTOR_APPROVER)));

        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("退款必须真的把钱放回同一个钱包：放款是 mock 时余额纹丝不动")
                .isEqualTo(grant);
        List<Long> deltas = WalletProbe.deltas(jdbc, u, "REFUND");
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0)).as("退款必须是入账：符号写反会让它看起来又扣了一笔").isEqualTo(ORDER_CENTS);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();
        assertThat(jdbc.sql("SELECT order_id FROM wallet_ledger WHERE username = :u"
                        + " AND reason = 'REFUND'").param("u", u).query(String.class).single())
                .isEqualTo(order.id());
        // 放款凭证要能反查当初那笔收款——这是 originalReceipt 存在的理由，也是它今天的消费者。
        assertThat(refundReceipt).isEqualTo("SIM-REFUND-" + order.id() + "/" + receipt);
        // actor 记录的是<b>放款责任人</b>：审批退款的商家（无理由退款则是 SYSTEM），
        // 从来不是收款的买家本人。记成买家 = 「买家自己给自己退了一笔钱」，
        // 记成 unknown = 「没人知道这笔钱是谁放出来的」，两种都是错的。
        assertThat(jdbc.sql("SELECT actor FROM wallet_ledger WHERE username = :u"
                        + " AND reason = 'REFUND'").param("u", u).query(String.class).single())
                .isEqualTo(ACTOR_APPROVER);
        // 缺 actor 直接炸：宁可在构造请求时就红，也不要等到事后对账发现这笔钱没有责任人。
        assertThatThrownBy(() -> new RefundRequest(order.id(), u, receipt, order.total(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("放款责任人");
    }

    // ——————————————————— 助手代付的默认值 ———————————————————

    /**
     * 方案 §6.5：默认支付方式会<b>架空</b>整个退款能力。
     * 助手代下的单若默认走银行卡，退款时钱没有真实落点，钱包的价值归零。
     *
     * <p>所以这条断言要做两层：卡片上写的确实是 {@code DEMO_WALLET}，
     * 并且拿这个默认值真的去付一次款，钱确实从钱包里出去了。
     * 只断言字符串的话，「改名成了钱包但走的还是银行卡」这种 bug 会从它底下溜过去。
     */
    @Test
    @DisplayName("助手代付的默认支付方式确实是钱包：method 为 DEMO_WALLET，且确认后真的从钱包扣款")
    void assistantDefaultPaymentMethodIsWallet() {
        String u = user("default");
        OrderView order = newPendingOrder(u);
        long grant = walletProperties.getInitialGrantCents();

        PendingActionView pending;
        try (TurnScope.Scope ignored = TurnScope.begin(UUID.randomUUID().toString(), u)) {
            pending = pendingActions.requestOrderPayment(u, order.id(), null);
        }
        assertThat(pending.method())
                .as("助手代付的缺省方式必须是钱包（§6.5）：默认走银行卡，退款就不再有落点")
                .isEqualTo("DEMO_WALLET");

        // 模型经常传空串而不是省略参数：那条路必须落到同一个缺省值。
        try (TurnScope.Scope ignored = TurnScope.begin(UUID.randomUUID().toString(), u)) {
            assertThat(pendingActions.requestOrderPayment(u, order.id(), "   ").method())
                    .isEqualTo("DEMO_WALLET");
        }

        OrderView paid = payments.pay(order.id(), u, pending.method());
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paymentMethod()).isEqualTo("DEMO_WALLET");
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("光改一个字符串不够：这个默认值必须真的走得通钱包扣款")
                .isEqualTo(grant - ORDER_CENTS);
        assertThat(WalletProbe.deltas(jdbc, u, "PAYMENT")).hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 辅助 ———————————————————

    /** 造一笔真实待支付订单（商品1 ×1，应付 ¥47.00）。 */
    private OrderView newPendingOrder(String username) {
        String key = "wp-" + UUID.randomUUID().toString().replace("-", "");
        OrderView order = facade.create(username, key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        seenOrderIds.add(order.id());
        return order;
    }

    /**
     * 用一笔<b>真实的扣款</b>把余额压到 {@code targetCents}，于是账仍然对得上。
     *
     * <p>刻意不直接 {@code UPDATE wallet}：那样会造出一笔没有流水的差额，
     * 后面所有对账断言都会被它污染。
     */
    private void drainWalletTo(String username, long targetCents) {
        accounts.ensureAccount(username);
        long grant = WalletProbe.balanceCents(jdbc, username);
        assertThat(grant).as("用例前提：初始额度大于目标余额").isGreaterThan(targetCents);
        tx().executeWithoutResult(s ->
                wallets.debit(username, grant - targetCents, "tester", "order-drain"));
        assertThat(WalletProbe.balanceCents(jdbc, username)).isEqualTo(targetCents);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** 取一个本用例专属的用户名（顺便登记给 {@link #cleanUp()}）。 */
    private String user(String suffix) {
        user = "wallet-pay-" + suffix;
        return user;
    }
}
