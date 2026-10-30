package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;

import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderCheckout;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.support.SqlCounting;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 订单列表的 SQL 条数<b>不随订单数量增长</b>。
 *
 * <p>这条测试守的是 B1 那次改动的唯一目的。之前 {@code ordersOf} 是
 * {@code rows.stream().map(orders::view)}，每单再发 3 条（明细 / 子订单 / 每个子单的明细），
 * 11 单就是 33 条。改成批量装配后恒为 4 条：父单、明细、子订单、子订单明细。
 *
 * <p><b>为什么不测耗时</b>：耗时断言必然 flaky（同一段代码在 CI 上可能是 3ms 也可能是 30ms）。
 * 「11 单与 1 单的 SQL 条数相等」是个非真即假的命题，不随机器快慢变化，
 * 也不会因为将来数据库换了、索引加了而失效。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(SqlCounting.Config.class)
class OrderListSqlCountTest {

    /** 独立买家：不复用 student/classmate，免得和别的用例的订单混在一起数不准。 */
    private static final String BUYER = "sql-count-buyer";

    @Autowired OrderCheckout orderCheckout;
    @Autowired OrderInsights insights;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        // 反序删：order_item / order_sub / outbox_event 都挂着订单外键，
        // 先删父单会撞约束。outbox_event 是下单时写的领域事件，也按 aggregate_id 指向订单。
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_id IN (SELECT id FROM mall_order WHERE username = :u)")
                .param("u", BUYER).update();
        jdbc.sql("DELETE FROM order_item WHERE order_id IN (SELECT id FROM mall_order WHERE username = :u)")
                .param("u", BUYER).update();
        jdbc.sql("DELETE FROM order_sub WHERE order_id IN (SELECT id FROM mall_order WHERE username = :u)")
                .param("u", BUYER).update();
        jdbc.sql("DELETE FROM mall_order WHERE username = :u").param("u", BUYER).update();
    }

    @Test
    @DisplayName("50 单与 1 单发的 SQL 条数相同（批量装配，无 N+1）")
    void sqlCountDoesNotGrowWithOrderCount() {
        // 取库存最高的商品，并先把库存补到够用：别的用例会消耗库存，
        // 全量跑时「库存最高的那件」也可能只剩个位数，第 5 单就下不动了。
        // 只加不减，不会影响其它用例。
        long productId = jdbc.sql("SELECT id FROM product ORDER BY stock DESC, id DESC LIMIT 1")
                .query(Long.class).single();
        jdbc.sql("UPDATE product SET stock = 200 WHERE id = :id").param("id", productId).update();

        placeOrder(productId);
        int oneOrder = countQueries();

        // 49 次而不是 9 次：原实现的放大倍数是每单 3 条，50 单该是 151 条对 4 条。
        // 差距拉到这个量级，任何「顺手在循环里查一下」的改动都会立刻被这条测试拦下。
        for (int i = 0; i < 49; i++) placeOrder(productId);
        int fiftyOrders = countQueries();

        assertThat(fiftyOrders)
                .as("订单从 1 单增到 50 单，SQL 条数必须不变（每多一单多几条 = N+1 回来了）")
                .isEqualTo(oneOrder);
    }

    private int countQueries() {
        // 必须先重置再测：读请求自身也可能触发懒初始化，那部分不该算进来。
        SqlCounting.reset();
        List<?> orders = insights.ordersOf(BUYER);
        assertThat(orders).isNotEmpty();
        return SqlCounting.count();
    }

    private void placeOrder(long productId) {
        String key = "sqlcount-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        orderCheckout.create(BUYER, key,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(productId, 1))));
    }
}
