package dev.learning.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.api.PendingActionView;
import dev.learning.mall.assistant.harness.TurnScope;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.QuoteSnapshot;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品改价保护测试。
 *
 * <p>覆盖助手确认、页面 HTTP 链路、快照归属，以及最关键的“比价必须早于任何写调用”。
 * 浏览器是否真的保存并提交 {@code quoteId} 不在 MockMvc 的执行范围内；在引入前端测试框架前，
 * 该层必须由真机反向冒烟守护，不能把 {@link #pagePathIsGuardedToo()} 当成浏览器层证据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PriceChangeGuardTest {

    private static final List<CreateOrderRequest.Line> CUP_LINES =
            List.of(new CreateOrderRequest.Line(1, 1));

    @Autowired QuoteSnapshot quotes;
    @Autowired PendingActionService pendingActions;
    @Autowired OrderFacade orders;
    @Autowired CartService cart;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    /** 调用探针：证明价格拒绝发生时，订单 INSERT 尚未被调用。 */
    @MockitoSpyBean OrderRepository orderRepository;
    /** 调用探针：证明价格拒绝发生时，库存预占尚未被调用。 */
    @MockitoSpyBean ProductCatalog catalog;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        clearInvocations(orderRepository, catalog);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
        clearInvocations(orderRepository, catalog);
    }

    @Test
    @DisplayName("助手发起确认后商品改价：本人确认得到 409 PRICE_CHANGED")
    void confirmAfterPriceChangeIsRejected() {
        PendingActionView pending;
        try (TurnScope.Scope ignored = TurnScope.begin(UUID.randomUUID().toString(), "student")) {
            pending = pendingActions.requestOrderCreate("student", CUP_LINES);
        }
        catalog.updatePrice(1, new BigDecimal("49.00"));

        assertThatThrownBy(() -> pendingActions.resolve(pending.id(), "student", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    BusinessException business = (BusinessException) error;
                    assertThat(business.status().value()).isEqualTo(409);
                    assertThat(business.code()).isEqualTo("PRICE_CHANGED");
                    assertThat(business.getMessage()).contains("发起时 ¥47.00", "当前应付 ¥57.00");
                });
        assertThat(count("mall_order")).isZero();
    }

    @Test
    @DisplayName("金额变化在任何写调用之前被拒绝：不 INSERT、不预占库存、不写 RESERVE 流水")
    void rejectedBeforeAnyWrite() {
        QuoteSnapshot.Snapshot snapshot = quotes.capture("student", CUP_LINES);
        catalog.updatePrice(1, new BigDecimal("49.00"));
        int stockBefore = stockOf(1);
        clearInvocations(orderRepository, catalog);

        assertThatThrownBy(() -> orders.create("student", key(),
                new CreateOrderRequest(CUP_LINES, snapshot.id())))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code())
                        .isEqualTo("PRICE_CHANGED"));

        // 主证据是 Java 调用探针：即使未来事务回滚仍让数据库看起来是零行，
        // 只要有人把校验挪到 insert 之后，这两个 never 断言就会立即变红。
        verify(orderRepository, never()).insert(anyString(), anyString(), anyString(), anyString(),
                any(BigDecimal.class), any(BigDecimal.class), any(BigDecimal.class),
                any(BigDecimal.class), any(LocalDateTime.class));
        verify(catalog, never()).reserve(anyLong(), anyInt(), anyString(), anyString());

        // 数据库断言只作补充，不拿事务回滚后的零行冒充“写前校验”证据。
        assertThat(count("mall_order")).isZero();
        assertThat(stockOf(1)).isEqualTo(stockBefore);
        assertThat(reserveLedgerCount()).isZero();
    }

    @Test
    @DisplayName("他人的快照不参与我的下单比价：商品改价后仍按当前价成功下单")
    void foreignQuoteIsNotApplied() {
        QuoteSnapshot.Snapshot foreign = quotes.capture("classmate", CUP_LINES);
        catalog.updatePrice(1, new BigDecimal("49.00"));

        OrderView order = orders.create("student", key(),
                new CreateOrderRequest(CUP_LINES, foreign.id()));

        assertThat(order.payAmount()).isEqualByComparingTo("57.00");
        assertThat(count("mall_order")).isEqualTo(1);
        assertThat(stockOf(1)).isEqualTo(49);
    }

    @Test
    @DisplayName("页面服务端链路携带 quoteId：HTTP 拍快照后改价，下单返回 PRICE_CHANGED")
    void pagePathIsGuardedToo() throws Exception {
        String quoteBody = "{\"items\":[{\"productId\":1,\"quantity\":1}]}";
        String response = mvc.perform(post("/api/quotes")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode quote = json.readTree(response);
        catalog.updatePrice(1, new BigDecimal("49.00"));

        var orderJson = json.createObjectNode();
        orderJson.set("items", quoteItems());
        orderJson.put("quoteId", quote.get("id").asText());
        String orderBody = orderJson.toString();
        mvc.perform(post("/api/orders")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"));

        assertThat(count("mall_order")).isZero();
    }

    @Test
    @DisplayName("快照、购物袋与落单共用同一定价：命中满减和包邮时差值仍为零")
    void snapshotAndOrderUseTheSamePricing() {
        cart.add("student", 5, 1); // 199 - 25，且包邮，应付 174
        List<CreateOrderRequest.Line> lines = cart.orderLines("student");
        var cartView = cart.view("student");
        QuoteSnapshot.Snapshot snapshot = quotes.capture("student", lines);

        OrderView order = orders.create("student", key(),
                new CreateOrderRequest(lines, snapshot.id()));

        assertThat(snapshot.quote().payable()).isEqualByComparingTo(cartView.total());
        assertThat(snapshot.quote().discount()).isEqualByComparingTo("25.00");
        assertThat(snapshot.quote().shippingFee()).isEqualByComparingTo("0.00");
        assertThat(snapshot.quote().payable().subtract(order.payAmount())).isZero();
    }

    private com.fasterxml.jackson.databind.node.ArrayNode quoteItems() {
        var items = json.createArrayNode();
        items.addObject().put("productId", 1).put("quantity", 1);
        return items;
    }

    private void cleanDatabase() {
        for (String table : List.of("assistant_audit", "assistant_pending_action", "price_quote",
                "notification", "outbox_event", "order_item", "mall_order", "mall_cart_item")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM stock_ledger WHERE reason = 'RESERVE' OR reason = 'RELEASE'").update();
        jdbc.sql("""
                        UPDATE product
                           SET stock = 50,
                               price = CASE id
                                   WHEN 1 THEN 39.00 WHEN 2 THEN 29.00 WHEN 3 THEN 89.00
                                   WHEN 4 THEN 49.00 WHEN 5 THEN 199.00 WHEN 6 THEN 59.00
                               END""").update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM product WHERE id = :id")
                .param("id", productId).query(Integer.class).single();
    }

    private int reserveLedgerCount() {
        return jdbc.sql("SELECT COUNT(*) FROM stock_ledger WHERE reason = 'RESERVE'")
                .query(Integer.class).single();
    }

    private static String key() {
        return "pcg-" + UUID.randomUUID().toString().replace("-", "");
    }
}
