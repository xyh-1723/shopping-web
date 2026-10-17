package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderTransactions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * 库存变动流水（P1-1）：<b>每一笔库存变动都要留痕，且留痕必须与现货对得上账</b>。
 *
 * <p>要守的不是「有没有一张流水表」，而是这条恒等式：
 *
 * <pre>
 *   某商品的现货  ==  迁移建账的基线  +  之后所有流水 delta 之和
 * </pre>
 *
 * <p>它一旦不成立，库存告警就无法归因（「货到了」说不清是关单退回还是店员补的），
 * 而且<b>没有任何一处会报错</b>——这正是没有流水时的现状。所以这里用对账 SQL 直接查，
 * 而不是去数「流水有几条」。
 *
 * <p>三条纪律，各有一条测试守着：
 * <ul>
 *   <li><b>写入口收在商品模块</b>：改 {@code product.stock} 的只有
 *       {@code reserve} / {@code release} / {@code updateStock} 三个方法。
 *       早期方案漏掉了补货，于是流水比现货少一大块——{@code restockIsRecordedAndReconciles} 守它。</li>
 *   <li><b>算术由数据库保证</b>：{@code stock_after = stock_before + delta} 写在 CHECK 里，
 *       不靠调用方算对——{@code ledgerArithmeticIsEnforcedByDatabase} 守它。</li>
 *   <li><b>流水要能归因</b>：带着订单号，才知道这批货是哪笔订单占走 / 退回的
 *       ——{@code releaseCarriesTheOrderId} 守它。</li>
 * </ul>
 *
 * <p>所有断言都<b>自带基线</b>（先记 {@code MAX(id)} 与当前现货，只统计基线之后的 delta）：
 * 别的测试类会直接 {@code UPDATE product SET stock=10} 重置库存，那类改动不走流水，
 * 库本来就是「脏」的。假设库干净而写出的断言，会在换一个执行顺序后随机变红。
 */
@SpringBootTest
@ActiveProfiles("test")
class StockLedgerTest {

    /** 用一个不被其它用例断言具体库存值的商品，避免互相干扰。 */
    private static final long PID = 6;
    private static final int START_STOCK = 20;

    @Autowired ProductCatalog catalog;
    @Autowired OrderFacade facade;
    @Autowired OrderTransactions orders;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    /**
     * 重置库存一律走裸 SQL，<b>不走被测的 {@code updateStock}</b>。
     *
     * <p>原因：一旦 updateStock 自己坏了（比如流水写错被 CHECK 打回），走它做前置/清理
     * 会让 5 条用例全部以 {@code setUp/tearDown} 报错的形式一起红，真正的失败点被噪声淹没，
     * 排查时只看到「全挂了」而看不出是哪一条断言没守住。
     *
     * <p>各条用例都自带基线（先记 {@code MAX(id)} 与当前现货），所以不写流水也无所谓。
     */
    @BeforeEach
    void setUp() {
        resetStock();
    }

    @AfterEach
    void tearDown() {
        resetStock();
    }

    private void resetStock() {
        jdbc.sql("UPDATE product SET stock = :s WHERE id = :pid")
                .param("s", START_STOCK).param("pid", PID).update();
    }

    @Test
    @DisplayName("店员补货必须入账，且「现货 == 基线 + Σ后续 delta」对得上")
    void restockIsRecordedAndReconciles() {
        long baseId = maxLedgerId(PID);
        int baseStock = stockOf(PID);

        catalog.updateStock(PID, baseStock + 7, "staff-admin");

        assertThat(stockOf(PID)).isEqualTo(baseStock + 7);
        assertThat(reconciliationMismatch(PID, baseId, baseStock))
                .as("补货是最容易漏掉流水的一条路径：漏了它，对账恒等式就凭空少一块").isZero();

        // 而且确实留下了一条 RESTOCK 流水，前值与后值都记得清清楚楚。
        assertThat(ledgerRows(PID, baseId, "RESTOCK")).hasSize(1);
        var row = jdbc.sql("SELECT stock_before, stock_after, delta, actor, order_id FROM stock_ledger"
                        + " WHERE product_id = :pid AND id > :baseId AND reason = 'RESTOCK'")
                .param("pid", PID).param("baseId", baseId)
                .query((rs, n) -> new int[] {rs.getInt("stock_before"), rs.getInt("stock_after"), rs.getInt("delta")})
                .single();
        assertThat(row[0]).isEqualTo(baseStock);
        assertThat(row[1]).isEqualTo(baseStock + 7);
        assertThat(row[2]).isEqualTo(7);
        assertThat(jdbc.sql("SELECT actor FROM stock_ledger WHERE product_id = :pid AND id > :baseId")
                .param("pid", PID).param("baseId", baseId).query(String.class).single()).isEqualTo("staff-admin");
    }

    @Test
    @DisplayName("流水的算术由数据库把关：stock_after 写成 before 会被 CHECK 拒绝")
    void ledgerArithmeticIsEnforcedByDatabase() {
        // 「前 + 变化 = 后」如果只靠调用方算对，那么任何一处漏改都会静默产生一条假流水。
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor)"
                        + " VALUES (:pid, 5, 10, 10, 'RESTOCK', 'tester')").param("pid", PID).update())
                .hasMessageContaining("CK_LEDGER_ARITHMETIC");

        // delta = 0 的行没有信息量：「没发生」不该被记成「发生了一次没变化」。
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor)"
                        + " VALUES (:pid, 0, 10, 10, 'RESTOCK', 'tester')").param("pid", PID).update())
                .hasMessageContaining("CK_LEDGER_DELTA_NONZERO");

        // 原因只有三类，多出来的第四种会被拒。
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor)"
                        + " VALUES (:pid, 1, 10, 11, 'MAGIC', 'tester')").param("pid", PID).update())
                .hasMessageContaining("CK_LEDGER_REASON");
    }

    /**
     * 负数库存不该出现在流水里。
     *
     * <p>两行插入都<b>故意让算术成立</b>（{@code 前 + 变化 = 后}），只踩非负这一条：
     * 否则红的可能是 {@code ck_ledger_arithmetic}，就证明不了非负约束真的在拦。
     */
    @Test
    @DisplayName("流水不记录负数库存：stock_before / stock_after 为负会被 CHECK 拒绝")
    void ledgerRejectsNegativeStock() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor)"
                        + " VALUES (:pid, 5, -1, 4, 'RESTOCK', 'tester')").param("pid", PID).update())
                .hasMessageContaining("CK_LEDGER_STOCK_NONNEG");

        assertThatThrownBy(() -> jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor)"
                        + " VALUES (:pid, -15, 10, -5, 'RESERVE', 'tester')").param("pid", PID).update())
                .hasMessageContaining("CK_LEDGER_STOCK_NONNEG");
    }

    @Test
    @DisplayName("关单归还的流水带着订单号：事后能说清这批货是哪笔订单退回的")
    void releaseCarriesTheOrderId() {
        long baseId = maxLedgerId(PID);
        int baseStock = stockOf(PID);

        OrderView order = facade.create("tester", "ledger-release-key",
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(PID, 2))));
        orders.cancel(order.id(), "tester");

        assertThat(reconciliationMismatch(PID, baseId, baseStock)).isZero();
        assertThat(jdbc.sql("SELECT order_id FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " AND reason = 'RESERVE'").param("pid", PID).param("baseId", baseId)
                .query(String.class).single()).isEqualTo(order.id());
        assertThat(jdbc.sql("SELECT order_id FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " AND reason = 'RELEASE'").param("pid", PID).param("baseId", baseId)
                .query(String.class).single()).isEqualTo(order.id());
        // 归还的操作者是被关单的那笔订单的归属用户，不是「系统」。
        assertThat(jdbc.sql("SELECT actor FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " AND reason = 'RELEASE'").param("pid", PID).param("baseId", baseId)
                .query(String.class).single()).isEqualTo("tester");
        assertThat(stockOf(PID)).isEqualTo(baseStock);
    }

    /**
     * 哨兵自证：这条如果不红，说明对账 SQL 本身写错了，上面所有对账断言都没有意义。
     *
     * <p>做法是直接改库而不写流水（模拟「运维手改 / 数据导入」这类绕过服务的改动），
     * 对账 SQL 必须把它揪出来。它验证的是<b>尺子</b>，不是被测物。
     */
    @Test
    @DisplayName("哨兵：不写流水直接改库存，对账 SQL 必须报 1 条差异")
    void reconciliationQueryDetectsUnloggedChange() {
        long baseId = maxLedgerId(PID);
        int baseStock = stockOf(PID);
        assertThat(reconciliationMismatch(PID, baseId, baseStock)).isZero();

        jdbc.sql("UPDATE product SET stock = stock - 1 WHERE id = :pid").param("pid", PID).update();
        assertThat(reconciliationMismatch(PID, baseId, baseStock))
                .as("对账 SQL 必须能抓到「改了现货却没留痕」，否则它就是一把坏尺子").isEqualTo(1);

        jdbc.sql("UPDATE product SET stock = :s WHERE id = :pid").param("s", baseStock).param("pid", PID).update();
        assertThat(reconciliationMismatch(PID, baseId, baseStock)).isZero();
    }

    /**
     * 并发预占：流水的前值依然首尾相接。
     *
     * <p>为什么这条值得测：{@code stock_before} 是先读后写读到的值，
     * 如果读与写之间没有行锁，另一个事务可能插在中间，于是前值记到的是别人的中间态——
     * 库存结果没错，流水却是错的，而且 {@code ck_ledger_arithmetic} 会把它当成数据错误抛出来。
     *
     * <p>断言「相邻流水首尾相接」（窗口函数）而不是「前值各不相同」：
     * 后者在串行执行时也会通过，等于没测；前者要求每一条的 {@code stock_after}
     * 正好等于下一条的 {@code stock_before}，中间不允许丢格。
     */
    @Test
    @DisplayName("并发预占：流水首尾相接，stock_before 不会记到别人的中间态")
    void concurrentReservesKeepBeforeValueExact() throws Exception {
        int threads = 6;
        long baseId = maxLedgerId(PID);
        int baseStock = stockOf(PID);
        assertThat(baseStock).isGreaterThanOrEqualTo(threads);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("等起跑信号超时");
                    tx.executeWithoutResult(status ->
                            catalog.reserve(PID, 1, "tester-" + index, "order-" + index));
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }

        assertThat(stockOf(PID)).isEqualTo(baseStock - threads);
        assertThat(chainMismatch(PID, baseId)).as("相邻流水必须首尾相接，中间不允许丢格").isZero();
        // 整条链还得接在基线上：第一条的前值是起始库存，最后一条的后值是现在的现货。
        assertThat(firstBeforeAfterBase(PID, baseId)).isEqualTo(baseStock);
        assertThat(lastAfterAfterBase(PID, baseId)).isEqualTo(baseStock - threads);
    }

    // ——————————————————— 对账 SQL ———————————————————

    /** 对账：现货是否等于「基线 + 基线之后所有 delta 之和」。返回 1 表示对不上。 */
    private int reconciliationMismatch(long productId, long baseId, int baseStock) {
        return jdbc.sql("SELECT COUNT(*) FROM product p"
                        + " WHERE p.id = :pid AND p.stock <> :base + COALESCE("
                        + "   (SELECT SUM(l.delta) FROM stock_ledger l"
                        + "     WHERE l.product_id = :pid AND l.id > :baseId), 0)")
                .param("pid", productId).param("base", baseStock).param("baseId", baseId)
                .query(Integer.class).single();
    }

    /** 相邻流水的断链数：每一条的 stock_after 必须等于下一条的 stock_before。 */
    private int chainMismatch(long productId, long baseId) {
        return jdbc.sql("SELECT COUNT(*) FROM ("
                        + " SELECT stock_after, LEAD(stock_before) OVER (PARTITION BY product_id ORDER BY id) AS next_before"
                        + "   FROM stock_ledger WHERE product_id = :pid AND id > :baseId) t"
                        + " WHERE t.next_before IS NOT NULL AND t.stock_after <> t.next_before")
                .param("pid", productId).param("baseId", baseId)
                .query(Integer.class).single();
    }

    private int firstBeforeAfterBase(long productId, long baseId) {
        return jdbc.sql("SELECT stock_before FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " ORDER BY id LIMIT 1").param("pid", productId).param("baseId", baseId)
                .query(Integer.class).single();
    }

    private int lastAfterAfterBase(long productId, long baseId) {
        return jdbc.sql("SELECT stock_after FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " ORDER BY id DESC LIMIT 1").param("pid", productId).param("baseId", baseId)
                .query(Integer.class).single();
    }

    private List<Integer> ledgerRows(long productId, long baseId, String reason) {
        return jdbc.sql("SELECT delta FROM stock_ledger WHERE product_id = :pid AND id > :baseId"
                        + " AND reason = :reason ORDER BY id")
                .param("pid", productId).param("baseId", baseId).param("reason", reason)
                .query(Integer.class).list();
    }

    private long maxLedgerId(long productId) {
        return jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM stock_ledger WHERE product_id = :pid")
                .param("pid", productId).query(Long.class).single();
    }

    private int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM product WHERE id = :pid").param("pid", productId)
                .query(Integer.class).single();
    }
}
