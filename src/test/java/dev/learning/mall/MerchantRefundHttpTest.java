package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 退款审批端点在 <b>HTTP 层</b>的空 body 契约。
 *
 * <p>要守的只有一句话：<b>「直接点批准 / 驳回」可以不发请求体</b>。
 * 前端按按钮时手上没有任何意见要写，强迫它多传一个 {@code {"note":null}}
 * 是把 API 的不方便转嫁给调用方。
 *
 * <p><b>这条契约必须写在 {@code @RequestBody(required = false)} 上</b>：
 * 光在方法体里写 {@code request == null ? null : request.note()} 没有用——
 * {@code @RequestBody} 默认 {@code required = true}，Spring 会在 controller 方法体
 * 执行<b>之前</b>就返回 400，那段 null 分支永远走不到。注释与实现就会是两回事，
 * 而只有注释那一半是错的那一半：看代码的人以为空 body 被容忍了，实际上它在
 * 第一道门就被挡掉。
 *
 * <h3>为什么断言落在「钱真的退了」上</h3>
 *
 * <p>只断言 200 是不够的：端点返回 200 但内部吞掉了放款，HTTP 层看不出任何区别。
 * 所以审批通过的那条还要往下查余额与退款状态，证明这是<b>真的一次完整审批</b>，
 * 而不是「返回一个好看的 JSON 就完事」。
 *
 * <p>身份走 {@link UserDetailsService} 解析（与 {@link MerchantRoleTest} 同一条纪律）：
 * 手造一个 {@code ROLE_MERCHANT} 的 principal 会绕过「SecurityConfig 里 merchant
 * 到底带了什么角色」这一环，URL 级规则就没被真正走到。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantRefundHttpTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 18, 14, 0, 0);

    private static final String MERCHANT = "merchant";

    /** 商品1（¥39）×1 → ¥39 + 运费 8 = ¥47.00。 */
    private static final long ORDER_CENTS = 4_700L;

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @Autowired UserDetailsService userDetails;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;

    private String buyer;

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
        if (buyer != null) {
            WalletProbe.purge(jdbc, buyer);
            buyer = null;
        }
    }

    // ——————————————————— 批准 ———————————————————

    @Test
    @DisplayName("批准不带请求体：200、真的放款、审批意见留空（required=false 才是契约的那一半）")
    void approveWithoutBodySucceeds() throws Exception {
        String u = buyer("approve");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-EMPTY-1");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "WRONG_ITEM", "发错款式");
        assertThat(queued.route()).as("用例前提：这笔在等商家").isEqualTo(RefundView.ROUTE_MERCHANT_APPROVAL);
        long before = WalletProbe.balanceCents(jdbc, u);

        // 刻意不设 contentType、不带 content：这就是「前端直接点按钮」的形状。
        mvc.perform(post("/api/refunds/" + queued.id() + "/approve").with(merchant()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(statusOf(queued.id())).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(decisionNoteOf(queued.id()))
                .as("没写意见就是没有意见：不许落成空字符串，那会把「未填写」与「填了空白」混为一谈")
                .isNull();
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("200 之外还要落一层：钱真的回到了买家钱包")
                .isEqualTo(before + ORDER_CENTS);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).containsExactly(ORDER_CENTS);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 驳回 ———————————————————

    @Test
    @DisplayName("驳回不带请求体：200、状态 REJECTED、钱没有动")
    void rejectWithoutBodySucceeds() throws Exception {
        String u = buyer("reject");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-EMPTY-2");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "DAMAGED", "有磕碰");
        long before = WalletProbe.balanceCents(jdbc, u);
        int stockBefore = stockOf();

        mvc.perform(post("/api/refunds/" + queued.id() + "/reject").with(merchant()).with(csrf()))
                .andExpect(status().isOk());

        assertThat(statusOf(queued.id())).isEqualTo(RefundView.STATUS_REJECTED);
        assertThat(decisionNoteOf(queued.id())).isNull();
        assertThat(WalletProbe.balanceCents(jdbc, u)).as("驳回不放款").isEqualTo(before);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
        assertThat(stockOf()).as("驳回不动货").isEqualTo(stockBefore);
    }

    // ——————————————————— 反向控制 ———————————————————

    /**
     * 正向对照：<b>带请求体的审批同样要能用</b>。
     *
     * <p>少了这一句，「空 body 现在不报错」也可能来自「审批端点被整个改成了忽略 note 的
     * 另一个实现」。这里证明 note 那条路还在。
     */
    @Test
    @DisplayName("反向控制：带请求体的审批照样能用，note 真的落库（空 body 的容忍不等于丢掉 note）")
    void approveWithBodyStillRecordsTheNote() throws Exception {
        String u = buyer("with-body");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-EMPTY-3");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "OTHER", "其他原因");

        mvc.perform(post("/api/refunds/" + queued.id() + "/approve")
                        .with(merchant()).with(csrf())
                        .contentType("application/json")
                        .content("{\"note\":\"核实后同意\"}"))
                .andExpect(status().isOk());

        assertThat(statusOf(queued.id())).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(decisionNoteOf(queued.id())).isEqualTo("核实后同意");
        assertThat(decidedByOf(queued.id())).isEqualTo(MERCHANT);
    }

    // ——————————————————— 工具 ———————————————————

    /** 商家的 principal 来自真实账号解析链路，而不是手造一个 ROLE_MERCHANT。 */
    private RequestPostProcessor merchant() {
        UserDetails merchant = userDetails.loadUserByUsername(MERCHANT);
        assertThat(merchant).as("merchant 账号必须存在，否则这条用例根本没走到 URL 级规则").isNotNull();
        return user(merchant);
    }

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        org.mockito.Mockito.when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        org.mockito.Mockito.when(clock.getZone()).thenReturn(zone);
        org.mockito.Mockito.when(clock.withZone(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private int stockOf() {
        return jdbc.sql("SELECT stock FROM product WHERE id = 1").query(Integer.class).single();
    }

    private String statusOf(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private String decisionNoteOf(String refundId) {
        return jdbc.sql("SELECT decision_note FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).optional().orElse(null);
    }

    private String decidedByOf(String refundId) {
        return jdbc.sql("SELECT decided_by FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private String buyer(String suffix) {
        buyer = "refund-http-" + suffix;
        return buyer;
    }
}
