package dev.learning.mall.order.internal;

import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderView;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 经营数据只读实现。只做 SELECT 聚合，不暴露写能力（最小权限的工具只读原则）。 */
@Service
public class OrderInsightsService implements OrderInsights {

    private final JdbcClient jdbc;
    private final OrderRepository orders;

    public OrderInsightsService(JdbcClient jdbc, OrderRepository orders) {
        this.jdbc = jdbc;
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderStats stats() {
        long total = count("SELECT COUNT(*) FROM mall_order");
        long pending = count("SELECT COUNT(*) FROM mall_order WHERE status = 'PENDING'");
        long paid = count("SELECT COUNT(*) FROM mall_order WHERE status = 'PAID'");
        long cancelled = count("SELECT COUNT(*) FROM mall_order WHERE status = 'CANCELLED'");
        // 营收取 pay_amount：V12 之后 total 恒等于应付，但明细列才是语义的正主。
        // 用 sum(total) 与 sum(pay_amount) 结果相同，前者只是历史列的兼容读法。
        BigDecimal paidAmount = jdbc.sql("SELECT COALESCE(SUM(pay_amount), 0) FROM mall_order WHERE status = 'PAID'")
                .query(BigDecimal.class).single();
        List<TopProduct> top = jdbc.sql(
                        "SELECT i.product_id AS productId, i.product_name AS productName, SUM(i.quantity) AS quantity "
                                + "FROM order_item i JOIN mall_order o ON o.id = i.order_id "
                                + "WHERE o.status = 'PAID' GROUP BY i.product_id, i.product_name "
                                + "ORDER BY quantity DESC LIMIT 3")
                .query(TopProduct.class).list();
        return new OrderStats(total, pending, paid, cancelled,
                paidAmount == null ? BigDecimal.ZERO : paidAmount, top);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> ordersOf(String username) {
        return orders.list(username).stream().map(orders::view).toList();
    }

    private long count(String sql) {
        Long n = jdbc.sql(sql).query(Long.class).single();
        return n == null ? 0 : n;
    }
}
