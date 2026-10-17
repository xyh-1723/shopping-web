package dev.learning.mall;

import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.proactive.api.AlertCenter;
import dev.learning.mall.proactive.api.RelatedProduct;
import dev.learning.mall.proactive.api.WatchView;
import dev.learning.mall.proactive.internal.AlertService;
import dev.learning.mall.proactive.internal.ProactiveProperties;
import dev.learning.mall.proactive.internal.RelatedRecommendationService;
import dev.learning.mall.proactive.internal.WatchService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主动服务的测试：关注、降价/补货提醒、关联推荐。
 *
 * <p>两条路径都要覆盖，因为它们负责的是不同的事：
 * <ul>
 *   <li><b>商品变更事件</b>——及时性（改价后当场提醒）；</li>
 *   <li><b>定时扫描</b>——完整性（绕过改价接口的变更也不能漏）。</li>
 * </ul>
 * 第三件事同样重要：<b>不要烦人</b>。提醒后必须重新武装，同一次降价只能提醒一次。
 *
 * <p>测试用独立的用户名（不与其他用例共用 student/classmate），避免关注与提醒串味到别的断言里。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProactiveServiceTest {

    @Autowired WatchService watchService;
    @Autowired AlertService alertService;
    @Autowired RelatedRecommendationService recommendations;
    @Autowired ProactiveProperties proactiveProperties;
    @Autowired ProductCatalog catalog;
    @Autowired CartService cart;
    @Autowired OrderFacade orderFacade;
    @Autowired OrderPayments payments;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    private static final String ALICE = "watch-alice";
    private static final String BOB = "watch-bob";

    @BeforeEach
    void setUp() {
        proactiveProperties.setEnabled(true);
        proactiveProperties.setMaxAlertsPerScan(500);
        resetCatalog();
        cleanState();
    }

    @AfterEach
    void cleanup() {
        proactiveProperties.setEnabled(false);
        cleanState();
        resetCatalog();
    }

    /** 种子商品的原始价格与库存——用例会改它们，跑完必须还原，否则会飘到别的测试类。 */
    private void resetCatalog() {
        jdbc.sql("UPDATE product SET price = 39.00, stock = 30 WHERE id = 1").update();
        jdbc.sql("UPDATE product SET price = 29.00, stock = 40 WHERE id = 2").update();
        jdbc.sql("UPDATE product SET price = 89.00, stock = 15 WHERE id = 3").update();
        jdbc.sql("UPDATE product SET price = 49.00, stock = 25 WHERE id = 4").update();
        jdbc.sql("UPDATE product SET price = 199.00, stock = 10 WHERE id = 5").update();
        jdbc.sql("UPDATE product SET price = 59.00, stock = 20 WHERE id = 6").update();
    }

    private void cleanState() {
        jdbc.sql("DELETE FROM mall_alert WHERE username IN (:a, :b)")
                .param("a", ALICE).param("b", BOB).update();
        jdbc.sql("DELETE FROM mall_product_watch WHERE username IN (:a, :b)")
                .param("a", ALICE).param("b", BOB).update();
        jdbc.sql("DELETE FROM mall_cart_item WHERE username IN (:a, :b)")
                .param("a", ALICE).param("b", BOB).update();
        // 订单要连 outbox / notification 一起清（外键自下而上）。
        jdbc.sql("DELETE FROM notification WHERE username IN (:a, :b)").param("a", ALICE).param("b", BOB).update();
        jdbc.sql("DELETE FROM outbox_event WHERE username IN (:a, :b)").param("a", ALICE).param("b", BOB).update();
        jdbc.sql("DELETE FROM order_item WHERE order_id IN (SELECT id FROM mall_order WHERE username IN (:a, :b))")
                .param("a", ALICE).param("b", BOB).update();
        jdbc.sql("DELETE FROM mall_order WHERE username IN (:a, :b)").param("a", ALICE).param("b", BOB).update();
    }

    private void setPriceDirectly(long productId, String price) {
        // 绕过改价接口直接改库：模拟「运维手改 / 数据导入」这类不会发事件的变更，
        // 用来单独验证定时扫描这条兜底路径。
        jdbc.sql("UPDATE product SET price = :p WHERE id = :id")
                .param("p", new BigDecimal(price)).param("id", productId).update();
    }

    private void setStockDirectly(long productId, int stock) {
        jdbc.sql("UPDATE product SET stock = :s WHERE id = :id")
                .param("s", stock).param("id", productId).update();
    }

    private OrderView paidOrder(String username, long... productIds) {
        List<CreateOrderRequest.Line> lines = java.util.Arrays.stream(productIds)
                .mapToObj(id -> new CreateOrderRequest.Line(id, 1)).toList();
        String key = "proactive-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        OrderView order = orderFacade.create(username, key, new CreateOrderRequest(lines));
        return payments.pay(order.id(), username, "DEMO_CARD");
    }

    private List<AlertCenter.Alert> alertsOf(String username) {
        return alertService.center(username).alerts();
    }

    // ——————————————————— 关注 ———————————————————

    @Test
    @DisplayName("关注：以关注时刻的价格与库存为基线，取消后不再提醒")
    void watchRecordsBaseline() {
        WatchView view = watchService.watch(ALICE, 1);
        assertThat(view.baselinePrice()).isEqualByComparingTo("39.00");
        assertThat(view.price()).isEqualByComparingTo("39.00");
        assertThat(view.inStock()).isTrue();
        assertThat(watchService.watchesOf(ALICE)).hasSize(1);

        watchService.unwatch(ALICE, 1);
        assertThat(watchService.watchesOf(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("关注：重复关注同一件商品是幂等的（不会出现两条关注）")
    void watchIsIdempotent() {
        watchService.watch(ALICE, 1);
        watchService.watch(ALICE, 1);
        assertThat(watchService.watchesOf(ALICE)).hasSize(1);
    }

    // ——————————————————— 降价提醒 ———————————————————

    @Test
    @DisplayName("降价：走改价接口时由商品变更事件当场触发提醒，无需等定时扫描")
    void priceDropIsTriggeredByChangeEvent() {
        watchService.watch(ALICE, 1);

        // 关键：这里**不调用** watchService.scan()。改价事务提交后事件监听器应当自己扫一遍。
        catalog.updatePrice(1, new BigDecimal("33.00"));

        List<AlertCenter.Alert> alerts = alertsOf(ALICE);
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).type()).isEqualTo("PRICE_DROP");
        assertThat(alerts.get(0).content()).contains("39").contains("33");
        assertThat(alertService.unreadCount(ALICE)).isEqualTo(1);
    }

    @Test
    @DisplayName("降价：绕过改价接口的变更由定时扫描兜底，且提醒一次后重新武装（不重复提醒）")
    void scanCatchesDirectChangesAndRearms() {
        watchService.watch(ALICE, 1);

        setPriceDirectly(1, "30.00");
        WatchService.ScanResult first = watchService.scan();
        assertThat(first.priceAlerts()).isEqualTo(1);
        assertThat(alertsOf(ALICE)).hasSize(1);

        // 价格没再降：基线已被重置为 30，所以第二次扫描什么都不该发。这是「不烦人」的核心。
        WatchService.ScanResult second = watchService.scan();
        assertThat(second.priceAlerts()).isZero();
        assertThat(alertsOf(ALICE)).hasSize(1);
        assertThat(watchService.watchesOf(ALICE).get(0).baselinePrice()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("降价：再次下跌会再提醒一次（关注行同时记录了「已提醒到什么程度」）")
    void furtherDropAlertsAgain() {
        watchService.watch(ALICE, 1);
        setPriceDirectly(1, "30.00");
        watchService.scan();
        setPriceDirectly(1, "25.00");
        watchService.scan();

        assertThat(alertsOf(ALICE)).hasSize(2);
        assertThat(alertService.unreadCount(ALICE)).isEqualTo(2);
    }

    @Test
    @DisplayName("降价：涨价不提醒（提醒只对用户有利的方向触发）")
    void priceIncreaseDoesNotAlert() {
        watchService.watch(ALICE, 1);
        setPriceDirectly(1, "49.00");
        assertThat(watchService.scan().priceAlerts()).isZero();
        assertThat(alertsOf(ALICE)).isEmpty();
    }

    // ——————————————————— 补货提醒 ———————————————————

    @Test
    @DisplayName("补货：售罄时关注，补货后提醒一次；同一次补货不重复提醒")
    void restockAlertsOnce() {
        setStockDirectly(1, 0);
        watchService.watch(ALICE, 1);
        assertThat(watchService.watchesOf(ALICE).get(0).inStock()).isFalse();

        setStockDirectly(1, 5);
        assertThat(watchService.scan().stockAlerts()).isEqualTo(1);
        AlertCenter.Alert alert = alertsOf(ALICE).get(0);
        assertThat(alert.type()).isEqualTo("BACK_IN_STOCK");
        assertThat(alert.content()).contains("5");

        assertThat(watchService.scan().stockAlerts()).isZero();
        assertThat(alertsOf(ALICE)).hasSize(1);
    }

    // ——————————————————— 扫描的完整性（公平轮转） ———————————————————

    @Test
    @DisplayName("扫描：候选数超过单轮上限时，多轮轮转后全部被照顾到，没有人被永久跳过")
    void scanRotatesThroughBacklog() {
        // 把单轮上限压到 1：三条降价候选塞不进一轮，旧实现（按创建时间取前 N 条）
        // 会永远只看到第一条，另外两条降价用户一个字都收不到。
        proactiveProperties.setMaxAlertsPerScan(1);
        watchService.watch(ALICE, 1);
        watchService.watch(ALICE, 2);
        watchService.watch(ALICE, 3);
        try {
            setPriceDirectly(1, "30.00");
            setPriceDirectly(2, "25.00");
            setPriceDirectly(3, "70.00");

            WatchService.ScanResult first = watchService.scan();
            assertThat(first.scanned()).isEqualTo(1);
            assertThat(first.priceAlerts()).isEqualTo(1);
            assertThat(first.remaining()).as("还有两条在排队，且这个数字必须能被看到")
                    .isEqualTo(2);

            assertThat(watchService.scan().remaining()).isEqualTo(1);
            assertThat(watchService.scan().remaining()).isZero();

            assertThat(alertsOf(ALICE)).hasSize(3);
        } finally {
            proactiveProperties.setMaxAlertsPerScan(500);
        }
    }

    @Test
    @DisplayName("扫描：多人关注同一件商品时不会有人被饿死")
    void scanIsFairAcrossUsers() {
        proactiveProperties.setMaxAlertsPerScan(1);
        watchService.watch(ALICE, 1);
        watchService.watch(BOB, 1);
        try {
            setPriceDirectly(1, "30.00");

            assertThat(watchService.scan().scanned()).isEqualTo(1);
            assertThat(watchService.scan().scanned()).isEqualTo(1);

            assertThat(alertsOf(ALICE)).hasSize(1);
            assertThat(alertsOf(BOB)).hasSize(1);
        } finally {
            proactiveProperties.setMaxAlertsPerScan(500);
        }
    }

    @Test
    @DisplayName("扫描：没降价也没补货的关注不占名额——它们不该让真正有变化的人排队")
    void scanSkipsUnchangedWatches() {
        watchService.watch(ALICE, 1);
        watchService.watch(ALICE, 2);

        WatchService.ScanResult result = watchService.scan();

        assertThat(result.scanned()).isZero();
        assertThat(result.remaining()).isZero();
        assertThat(alertsOf(ALICE)).isEmpty();
    }

    // ——————————————————— 隔离 ———————————————————

    @Test
    @DisplayName("隔离：提醒只发给关注者本人，别人的提醒读不了也改不了")
    void alertsAreScopedToOwner() {
        watchService.watch(ALICE, 1);
        setPriceDirectly(1, "30.00");
        watchService.scan();

        assertThat(alertsOf(ALICE)).hasSize(1);
        assertThat(alertsOf(BOB)).isEmpty();
        assertThat(alertService.unreadCount(BOB)).isZero();

        String aliceAlertId = alertsOf(ALICE).get(0).id();
        assertThat(alertService.markRead(BOB, aliceAlertId)).as("不能标记别人的提醒").isFalse();
        assertThat(alertService.unreadCount(ALICE)).isEqualTo(1);
        assertThat(alertService.markRead(ALICE, aliceAlertId)).isTrue();
        assertThat(alertService.unreadCount(ALICE)).isZero();
    }

    @Test
    @DisplayName("隔离：关闭主动服务后扫描不产生任何提醒")
    void disabledScanDoesNothing() {
        watchService.watch(ALICE, 1);
        proactiveProperties.setEnabled(false);
        setPriceDirectly(1, "30.00");
        assertThat(watchService.scan().scanned()).isZero();
        assertThat(alertsOf(ALICE)).isEmpty();
        proactiveProperties.setEnabled(true);
    }

    // ——————————————————— 关联推荐 ———————————————————

    @Test
    @DisplayName("关联推荐：有过购买记录时优先用「共同购买」，并给出理由")
    void recommendsByCoPurchase() {
        paidOrder(ALICE, 1, 5);
        paidOrder(BOB, 1, 3);

        List<RelatedProduct> related = recommendations.recommend(ALICE, 3);

        assertThat(related).extracting(RelatedProduct::id).contains(3L);
        assertThat(related).extracting(RelatedProduct::id).doesNotContain(1L, 5L);
        RelatedProduct pick = related.stream().filter(p -> p.id() == 3L).findFirst().orElseThrow();
        assertThat(pick.reason()).contains("一起加");
    }

    @Test
    @DisplayName("关联推荐：没有共现数据时退回到同类目，且不会推荐已经在袋里的商品")
    void recommendsByCategoryFromCart() {
        cart.add(ALICE, 2, 1);

        List<RelatedProduct> related = recommendations.recommend(ALICE, 3);

        assertThat(related).isNotEmpty();
        assertThat(related).extracting(RelatedProduct::id).doesNotContain(2L);
        assertThat(related.get(0).reason()).contains("品类");
        // 文具类目下除了 2 还有 6（专注计时器）。
        assertThat(related).extracting(RelatedProduct::id).contains(6L);
    }

    @Test
    @DisplayName("关联推荐：订单是待支付时不算购买（避免把「加过没付」当成喜好）")
    void unpaidOrdersAreNotHistory() {
        String key = "proactive-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        orderFacade.create(ALICE, key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));

        List<RelatedProduct> related = recommendations.recommend(ALICE, 3);

        // 没有已支付历史也没有购物袋：退回热销兜底，理由不该是「一起加」或「品类相同」。
        assertThat(related).isNotEmpty();
        assertThat(related.get(0).reason()).contains("最近");
    }

    @Test
    @DisplayName("关联推荐：完全没有历史的用户也有结果（不给空白页）")
    void newUserStillGetsSomething() {
        List<RelatedProduct> related = recommendations.recommend("nobody-has-this-name", 3);
        assertThat(related).isNotEmpty();
        assertThat(related).allSatisfy(p -> assertThat(p.stock()).isPositive());
    }

    // ——————————————————— 接口与权限 ———————————————————

    @Test
    @DisplayName("接口：关注/提醒只对本人开放，未登录 401")
    void watchEndpointsRequireLogin() throws Exception {
        mvc.perform(get("/api/watches")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/watches").with(user(ALICE).roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(post("/api/watches").with(user(ALICE).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    @DisplayName("权限：改价/改库存只有店员能做（顾客 403、店员 200）")
    void priceAndStockEndpointsAreStaffOnly() throws Exception {
        String priceBody = "{\"price\":36.00}";
        mvc.perform(post("/api/products/1/price").with(user(ALICE).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/products/1/price").with(user("admin").roles("USER", "STAFF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(36.00));

        mvc.perform(post("/api/products/1/stock").with(user(ALICE).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":12}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/products/1/stock").with(user("admin").roles("USER", "STAFF")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stock\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(12));
    }
}
