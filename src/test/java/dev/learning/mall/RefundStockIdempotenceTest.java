package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderProperties;
import dev.learning.mall.order.internal.RefundService;
import dev.learning.mall.wallet.internal.WalletProperties;
import java.math.BigDecimal;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 退款后的库存幂等（退款方案 §5，对应反向验证表 T14 #3 / #4 / #5 / #6 / #11）。
 *
 * <p>本轮要防的事故只有一句：<b>重复归还会凭空造出货</b>。
 * 它可能从三条路来——同一笔退款被执行两次、并发双击「批准」、
 * 以及最容易漏掉的那条：<b>已收货的订单退款却照样把库存还回来</b>（货在买家手上，库存也回到了货架）。
 *
 * <p>因此这里每条用例都自带基线（先记 {@code MAX(id)} 与当前现货），
 * 并且断言一律落在<b>「这笔订单到底留下了几条 REFUND 流水」</b>，
 * 而不是「库存是不是看起来变对了」：库存对了但流水多了，就是下一次对账要发现的账。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundStockIdempotenceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 18, 10, 0, 0);

    /** 商品1（¥39）×1 = ¥47.00。 */
    private static final long PRODUCT_ID = 1L;

    private static final int START_STOCK = 30;

    private static final String MERCHANT = "merchant";

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired ProductCatalog catalog;
    @Autowired OrderProperties properties;
    @Autowired WalletProperties walletProperties;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean Clock clock;

    private String user;
    private String approver = MERCHANT;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        // 重置库存走裸 SQL，不走被测的 updateStock：一旦被测路径自己坏了，
        // 用同一个坏路径做前置会让所有用例一起以 setUp 报错的形式变红，真正的失败点被噪声淹没。
        jdbc.sql("UPDATE product SET stock = :s WHERE id = :pid")
                .param("s", START_STOCK).param("pid", PRODUCT_ID).update();
        at(BASE);
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("UPDATE product SET stock = :s WHERE id = :pid")
                .param("s", START_STOCK).param("pid", PRODUCT_ID).update();
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
        WalletProbe.purge(jdbc, approver);
    }

    // ——————————————————— #3 并发批准只放行一次 ———————————————————

    /**
     * 两个线程同时按「批准」：<b>一次成功、一次被一次性迁移挡下</b>。
     *
     * <p>断言的形状取的是「输的那一方返回什么」而不是只看副作用：
     * 真正会因为删掉守卫而变红的就是这个——少了 {@code markApproved} 的行数守卫，
     * 输方会安静拿到一个「已放款」的视图而不是 409，于是「谁来负责」这件事在调用方眼里消失了
     * （副作用仍然只有一次，那由第二段的 {@code markRefunded} 守着，是这里的第二层防线）。
     *
     * <p>三样副作用各数一次：放款、归还库存、钱包流水。
     */
    @Test
    @DisplayName("#3 并发批准只放行一次：一次 REFUNDED、一次 ALREADY_RESOLVED，放款与还货各只有一次")
    void concurrentApproveOnlyDisbursesOnce() throws Exception {
        String u = user("race-approve");
        long baseLedgerId = maxLedgerId(PRODUCT_ID);
        int stockBase = stockOf(PRODUCT_ID);

        for (int round = 0; round < 3; round++) {
            OrderView paid = payWithWallet(u);
            long walletRows = WalletProbe.ledgerRowCount(jdbc, u);
            RefundView queued = merchantRefund(paid, u);

            List<String> outcomes = parallel(2, i -> {
                try {
                    return refunds.approve(queued.id(), MERCHANT, "同意退款").status();
                } catch (BusinessException e) {
                    return e.code();   // 输的那一方必须知道自己输在「已被别人批准」
                }
            });

            assertThat(outcomes).as("第 %d 轮：一次成功、一次被条件 UPDATE 挡下", round)
                    .containsExactlyInAnyOrder(RefundView.STATUS_REFUNDED, "ALREADY_RESOLVED");

            // 库存：这笔订单只退还一次（多了就是凭空造货）
            assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "REFUND")).as("第 %d 轮：REFUND 流水", round)
                    .hasSize(round + 1);
            assertThat(stockOf(PRODUCT_ID)).as("第 %d 轮：现货应当回到初始", round)
                    .isEqualTo(stockBase);
            assertThat(chainMismatch(PRODUCT_ID, baseLedgerId)).as("第 %d 轮：流水首尾相接", round).isZero();
            assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, stockBase))
                    .as("第 %d 轮：对账恒等式", round).isZero();
            // 钱：只入账一次
            assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).as("第 %d 轮：REFUND 入账", round)
                    .hasSize(round + 1);
            assertThat(WalletProbe.ledgerRowCount(jdbc, u)).isEqualTo(walletRows + 1);
            assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
            assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();
            assertThat(statusOf(queued.id())).isEqualTo(RefundView.STATUS_REFUNDED);
            assertThat(stockReturnedFlag(queued.id())).isTrue();
        }
    }

    // ——————————————————— #4 已收货：只退钱、不还货 ———————————————————

    /**
     * 这是比「重复归还」更隐蔽的一条：<b>货在买家手上，库存却回到了货架</b>。
     * 这条用例证明「这种时候 stock_returned = 0 是正确答案」。
     */
    @Test
    @DisplayName("#4 已收货的订单退款只退钱、不还货：stock_returned=0、现货不变、无 REFUND 流水")
    void receivedOrderRefundDoesNotReturnStock() {
        String u = user("received");
        long baseLedgerId = maxLedgerId(PRODUCT_ID);
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-RECV-1");
        fulfillment.receive(SubOrders.only(paid), u);
        // 收货后现货是「初始 − 1」：这笔货已经不在货架上了。
        int stockAfterShip = stockOf(PRODUCT_ID);
        assertThat(stockAfterShip).isEqualTo(START_STOCK - 1);

        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "NO_REASON", "不喜欢");
        assertThat(queued.route()).as("已收货必须走商家审批").isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);

        RefundView refunded = refunds.approve(queued.id(), MERCHANT, "同意");

        assertThat(refunded.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(refunded.stockReturned())
                .as("货在买家手上：这笔退款正确行为是「钱走了、库存没回来」")
                .isFalse();
        assertThat(stockOf(PRODUCT_ID)).as("现货一步都不许动").isEqualTo(stockAfterShip);
        assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "REFUND")).isEmpty();
        // 钱还是真的退回去了：不是「没退款」，是「没退货」。
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).hasSize(1);
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(walletProperties.getInitialGrantCents());
        assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, START_STOCK)).isZero();
    }

    // ——————————————————— #5 归还必须归因到 REFUND ———————————————————

    /**
     * 之所以要有 {@code returnStock} 而不是继续用 {@code release}：
     * 关单归还（RELEASE）与退款归还（REFUND）是两件不同的事，
     * 共用同一个 reason 之后，「这笔订单到底还过货没有」这条幂等判据就写不出来。
     */
    @Test
    @DisplayName("#5 退款归还归因到 REFUND：未发货订单退款后 REFUND 一行、RELEASE 零行，现货回来且算得清")
    void refundReturnIsAttributedToRefund() {
        String u = user("attributed");
        long baseLedgerId = maxLedgerId(PRODUCT_ID);
        int stockBase = stockOf(PRODUCT_ID);
        OrderView paid = payWithWallet(u);
        assertThat(stockOf(PRODUCT_ID)).isEqualTo(stockBase - 1);

        RefundView refund = refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null);

        assertThat(refund.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(stockOf(PRODUCT_ID)).as("未发货退款：货要真的回到货架").isEqualTo(stockBase);
        assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "REFUND")).hasSize(1);
        assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "RELEASE"))
                .as("退款归还不能借用关单归还的原因码：借了就分不清「没成交」与「售后退货」")
                .isEmpty();
        // 流水带着订单号：事后追得出这批货是被哪笔退款还回来的。
        assertThat(jdbc.sql("SELECT order_id FROM stock_ledger WHERE product_id = :pid AND id > :base"
                        + " AND reason = 'REFUND'").param("pid", PRODUCT_ID).param("base", baseLedgerId)
                .query(String.class).single()).isEqualTo(paid.id());
        assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, stockBase)).isZero();
        assertThat(chainMismatch(PRODUCT_ID, baseLedgerId)).isZero();
    }

    // ——————————————————— #6 NOT EXISTS 守卫 ———————————————————

    /**
     * 最后一道闸门：<b>即便①②两道应用层闸门都被绕过</b>（应急脚本直接调 judging yolu），
     * 同一笔订单的同一件商品也不会被退还两次。
     *
     * <p>刻意走<b>生产入口</b> {@link ProductCatalog#returnStock} 两次，而不是在测试里
     * 复制一份带着 {@code NOT EXISTS} 的 INSERT——那样只能证明「测试里的 SQL」，守不住生产实现。
     */
    @Test
    @DisplayName("#6 数据层守卫：同一笔订单重复归还，第二次安静跳过（一行流水、一次现货变化）")
    void ledgerGuardIsIdempotent() {
        long baseLedgerId = maxLedgerId(PRODUCT_ID);
        int stockBase = stockOf(PRODUCT_ID);
        // 订单号列是 VARCHAR(36)，这里刻意用短 id（UUID 原文是 36 位，加前缀就超了）。
        String orderId = "guard-" + UUID.randomUUID().toString().substring(0, 8);

        boolean first = tx().execute(s -> {
            catalog.returnStock(PRODUCT_ID, 1, "tester", orderId);
            return true;
        });
        boolean second = tx().execute(s -> {
            catalog.returnStock(PRODUCT_ID, 1, "tester", orderId);
            return true;
        });
        assertThat(first).as("第一次调用返回值") .isTrue();
        assertThat(second).as("第二次调用同样正常返回：幂等是「安静跳过」，不是报错").isTrue();

        assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "REFUND"))
                .as("第二次调用必须被 NOT EXISTS 挡下：凭空造货从这里开始").hasSize(1);
        assertThat(stockOf(PRODUCT_ID)).as("现货也只加一次").isEqualTo(stockBase + 1);
        assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, stockBase)).isZero();

        // 换个订单号是另一件事，不受去重影响（去重的键是订单号 + 商品，不是商品本身）。
        tx().executeWithoutResult(s -> catalog.returnStock(PRODUCT_ID, 1, "tester", orderId + "-2"));
        assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "REFUND")).hasSize(2);
        assertThat(stockOf(PRODUCT_ID)).isEqualTo(stockBase + 2);
    }

    // ——————————————————— #11 并发归还的流水链 ———————————————————

    /**
     * 两笔不同订单并发退款、退的是同一件商品：相邻流水必须首尾相接。
     *
     * <p><b>诚实边界</b>：这条用例能看见的是「同一商品的行锁 serializable 了两个事务」，
     * 而 {@code RefundService} 里的订单行锁在今天的单商品场景下会被商品行锁兜住——
     * 删掉订单行锁本条<b>仍然绿</b>。它的真实职责（给「累计已退金额」的 SUM 一个一致快照）
     * 在当前「同订单只能有一笔在途」的约束下也没有可去观察的竞态。
     * 详见 {@code RefundService#disburse} 的注释与本报告的反例记录。
     */
    @Test
    @DisplayName("#11 两笔订单并发退款同一件商品：流水首尾相接、现货回到初始、对账成立")
    void concurrentReturnsKeepLedgerChainConsistent() throws Exception {
        String u = user("race-stock");
        long baseLedgerId = maxLedgerId(PRODUCT_ID);
        int stockBase = stockOf(PRODUCT_ID);
        List<RefundView> queued = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            queued.add(merchantRefund(payWithWallet(u), u));
        }

        List<String> outcomes = parallel(2, i -> {
            try {
                return refunds.approve(queued.get(i).id(), MERCHANT, "同意").status();
            } catch (BusinessException e) {
                return e.code();
            }
        });

        assertThat(outcomes).containsExactlyInAnyOrder(
                RefundView.STATUS_REFUNDED, RefundView.STATUS_REFUNDED);
        assertThat(chainMismatch(PRODUCT_ID, baseLedgerId))
                .as("相邻流水的 after 必须等于下一条的 before，中间一格都不能丢").isZero();
        assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, stockBase)).isZero();
        assertThat(stockOf(PRODUCT_ID)).as("两笔各退一件：现货回到初始").isEqualTo(stockBase);
        assertThat(ledgerRows(PRODUCT_ID, baseLedgerId, "REFUND")).hasSize(2);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).hasSize(2);
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 哨兵：对账 SQL 不是空的 ———————————————————

    /**
     * 少不得的哨兵：上面几条全靠这条对账 SQL 得出结论，而一条永远返回 0 的对账 SQL
     * 会让上面所有断言变成装饰品。这里手工造一笔「没有流水的库存变化」，它必须看得见。
     */
    @Test
    @DisplayName("哨兵：绕开流水直接改现货，对账 SQL 必须能看见（否则上面的断言都是空的）")
    void reconciliationDetectsPhantomStock() {
        long baseLedgerId = maxLedgerId(PRODUCT_ID);
        int stockBase = stockOf(PRODUCT_ID);
        assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, stockBase)).isZero();

        jdbc.sql("UPDATE product SET stock = stock + 2 WHERE id = :pid").param("pid", PRODUCT_ID).update();

        assertThat(reconciliationMismatch(PRODUCT_ID, baseLedgerId, stockBase))
                .as("凭空多出来的两件货必须被对账 SQL 抓住").isEqualTo(1);
    }

    // ——————————————————— 工具 ———————————————————

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        org.mockito.Mockito.when(clock.getZone()).thenReturn(zone);
        org.mockito.Mockito.when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
    }

    /**
     * 造一笔「必须经过商家审批」的待退单：<b>不改变是否发货</b>，
     * 只把金额阈值调低，于是仓库完整性（未发货 → 该还货）依然可以被断言。
     */
    private RefundView merchantRefund(OrderView paid, String username) {
        int previousWindow = properties.getRefund().getAutoRefundWindowHours();
        BigDecimal previousMax = properties.getRefund().getAutoRefundMaxAmount();
        properties.getRefund().setAutoRefundMaxAmount(new BigDecimal("1.00"));
        try {
            RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), username, null, null, null);
            assertThat(queued.route()).as("用例前提：这笔必须落到商家队列").isEqualTo(
                    RefundView.ROUTE_MERCHANT_APPROVAL);
            return queued;
        } finally {
            properties.getRefund().setAutoRefundWindowHours(previousWindow);
            properties.getRefund().setAutoRefundMaxAmount(previousMax);
        }
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(PRODUCT_ID, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    private int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM product WHERE id = :pid").param("pid", productId)
                .query(Integer.class).single();
    }

    private String statusOf(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private boolean stockReturnedFlag(String refundId) {
        Integer flag = jdbc.sql("SELECT stock_returned FROM refund WHERE id = :id").param("id", refundId)
                .query(Integer.class).single();
        return flag != null && flag == 1;
    }

    /** 某原因的库存流水 delta 列表（基线之后）。 */
    private List<Integer> ledgerRows(long productId, long baseId, String reason) {
        return jdbc.sql("SELECT delta FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " AND reason = :reason ORDER BY id")
                .param("pid", productId).param("baseId", baseId).param("reason", reason)
                .query(Integer.class).list();
    }

    /** 对账：现货是否等于「基线现货 + 基线之后所有 delta 之和」。返回 1 表示对不上。 */
    private int reconciliationMismatch(long productId, long baseId, int baseStock) {
        return jdbc.sql("SELECT COUNT(*) FROM product p"
                        + " WHERE p.id = :pid AND p.stock <> :base + COALESCE("
                        + "   (SELECT SUM(l.delta) FROM stock_ledger l"
                        + "     WHERE l.product_id = :pid AND l.id > :baseId), 0)")
                .param("pid", productId).param("base", baseStock).param("baseId", baseId)
                .query(Integer.class).single();
    }

    /** 相邻流水的断链数：每条的 stock_after 必须等于下一条的 stock_before。 */
    private int chainMismatch(long productId, long baseId) {
        return jdbc.sql("SELECT COUNT(*) FROM ("
                        + " SELECT stock_after, LEAD(stock_before)"
                        + "        OVER (PARTITION BY product_id ORDER BY id) AS next_before"
                        + "   FROM stock_ledger WHERE product_id = :pid AND id > :baseId) t"
                        + " WHERE t.next_before IS NOT NULL AND t.stock_after <> t.next_before")
                .param("pid", productId).param("baseId", baseId)
                .query(Integer.class).single();
    }

    private long maxLedgerId(long productId) {
        return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM stock_ledger WHERE product_id = :pid")
                .param("pid", productId).query(Long.class).single();
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

    private String user(String suffix) {
        user = "refund-stock-" + suffix;
        return user;
    }
}
