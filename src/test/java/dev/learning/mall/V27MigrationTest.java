package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderCheckout;
import dev.learning.mall.order.api.OrderView;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * V27 迁移的结构性验收：<b>只验库，不验算法</b>。
 *
 * <p>算法是否正确由 {@code CouponPricingTest} 与 {@code CouponIntegrationTest} 证明；
 * 这里要证明的是另一件事——<b>数据库自己会拒绝不守恒的账</b>。
 * 只有约束在线，「代码写错」才会在落库那一刻变成异常，而不是变成一张
 * 商家收入为负、却谁也看不出来的子订单。
 *
 * <p>三条探针分别对应三个最容易写错的地方：
 * <ul>
 *   <li>平台收入为负（补贴超过佣金）；</li>
 *   <li>券分摊不守恒（券减免 ≠ 商家承担 + 平台补贴）；</li>
 *   <li>有券减免却没有券号（减了钱但查不到是哪张券）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class V27MigrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired OrderCheckout orders;

    @Test
    @DisplayName("新表与新列都在：coupon / user_coupon / 订单结算六列")
    void schemaIsPresent() {
        assertThat(tableExists("COUPON")).isTrue();
        assertThat(tableExists("USER_COUPON")).isTrue();
        assertThat(columnExists("MALL_ORDER", "COUPON_DISCOUNT")).isTrue();
        assertThat(columnExists("MALL_ORDER", "COUPON_ID")).isTrue();
        assertThat(columnExists("MALL_ORDER", "COUPON_CODE")).isTrue();
        for (String column : List.of("COUPON_DISCOUNT", "MERCHANT_DISCOUNT", "PLATFORM_SUBSIDY",
                "PLATFORM_FEE", "MERCHANT_INCOME", "PLATFORM_INCOME")) {
            assertThat(columnExists("ORDER_SUB", column)).as(column).isTrue();
        }
    }

    @Test
    @DisplayName("约束是显式命名的：审计与排障能按名字定位，而不是「某条 CHECK 失败了」")
    void constraintsAreNamed() {
        for (String name : List.of("ck_coupon_owner", "ck_coupon_type", "ck_coupon_value",
                "ck_coupon_share", "ck_coupon_quantity", "ck_coupon_budget", "ck_coupon_window",
                "ck_user_coupon_status", "ck_user_coupon_lifecycle",
                "ck_order_coupon_le_discount", "ck_order_coupon_fields",
                "ck_sub_coupon_split", "ck_sub_coupon_le_discount",
                "ck_sub_platform_income", "ck_sub_merchant_income")) {
            assertThat(constraintExists(name)).as(name).isTrue();
        }
    }

    @Test
    @DisplayName("平台收入不能为负：数据库拒绝「补贴超过佣金」的行")
    void platformIncomeCannotGoNegative() {
        OrderView order = placeOrder();

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE order_sub SET platform_income = -0.01
                         WHERE order_id = :id""")
                .param("id", order.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_SUB_PLATFORM_INCOME");
    }

    @Test
    @DisplayName("券分摊必须守恒：券减免 ≠ 商家承担 + 平台补贴 时写不进去")
    void couponSplitMustBalance() {
        // 用一笔有满减的订单：这样「券减免 ≤ 满减」先被满足，
        // 失败原因才会唯一地指向 CK_SUB_COUPON_SPLIT，而不是另一条约束。
        OrderView order = placeDiscountedOrder();

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE order_sub
                           SET coupon_discount = 0.01, merchant_discount = 0, platform_subsidy = 0
                         WHERE order_id = :id""")
                .param("id", order.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_SUB_COUPON_SPLIT");
    }

    @Test
    @DisplayName("有减免必须有券号：不能出现「减了钱但查不到是哪张券」")
    void couponDiscountRequiresCouponIdentity() {
        OrderView order = placeDiscountedOrder();

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE mall_order SET coupon_discount = 0.01
                         WHERE id = :id""")
                .param("id", order.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("CK_ORDER_COUPON_FIELDS");
    }

    @Test
    @DisplayName("存量与新增子单都满足收入恒等式：回填没有留下对不上的账")
    void incomeIdentityHoldsForEveryRow() {
        Integer broken = jdbc.sql("""
                        SELECT COUNT(*) FROM order_sub
                         WHERE merchant_income <> pay_amount + platform_subsidy - platform_fee
                            OR platform_income <> platform_fee - platform_subsidy
                            OR coupon_discount <> merchant_discount + platform_subsidy""")
                .query(Integer.class).single();
        assertThat(broken).isZero();
    }

    @Test
    @DisplayName("迁移重放到空库：V27 能跟在 V26 之后跑通，新表与新列在那里也存在")
    void migrationReplaysOnAFreshDatabase() throws SQLException {
        String url = TestH2.inMemory("v27-replay-" + UUID.randomUUID());
        SimpleDriverDataSource replay = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try {
            Flyway.configure().dataSource(replay).locations("classpath:db/migration")
                    .target("27").load().migrate();
            try (Connection c = replay.getConnection()) {
                assertThat(hasTable(c, "COUPON")).isTrue();
                assertThat(hasTable(c, "USER_COUPON")).isTrue();
                assertThat(hasColumn(c, "ORDER_SUB", "MERCHANT_INCOME")).isTrue();
            }
        } finally {
            try (Connection c = replay.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }

    // ——————————————————— 工具 ———————————————————

    private OrderView placeOrder() {
        return orders.create("student", "v27-" + UUID.randomUUID(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
    }

    /** 下一笔小计 ≥ 100 元的订单，确保满减档位生效（券减免上限才有意义）。 */
    private OrderView placeDiscountedOrder() {
        List<Long> ids = jdbc.sql("SELECT id FROM product WHERE stock >= 3 ORDER BY stock DESC LIMIT 30")
                .query(Long.class).list();
        for (Long id : ids) {
            var product = jdbc.sql("SELECT price, stock FROM product WHERE id = :id")
                    .param("id", id).query().singleRow();
            java.math.BigDecimal price = (java.math.BigDecimal) product.get("PRICE");
            int stock = ((Number) product.get("STOCK")).intValue();
            int quantity = price.signum() <= 0 ? 1
                    : (int) Math.ceil(100.0 / price.doubleValue());
            if (quantity <= stock && quantity <= 99) {
                return orders.create("student", "v27-" + UUID.randomUUID(),
                        new CreateOrderRequest(List.of(new CreateOrderRequest.Line(id, quantity))));
            }
        }
        throw new IllegalStateException("没有库存足够的商品来构造满减订单");
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.sql("""
                        SELECT COUNT(*) FROM information_schema.tables
                         WHERE table_schema = 'PUBLIC' AND UPPER(table_name) = :t""")
                .param("t", table).query(Integer.class).single();
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.sql("""
                        SELECT COUNT(*) FROM information_schema.columns
                         WHERE table_schema = 'PUBLIC'
                           AND UPPER(table_name) = :t AND UPPER(column_name) = :c""")
                .param("t", table).param("c", column).query(Integer.class).single();
        return n != null && n > 0;
    }

    private boolean constraintExists(String name) {
        Integer n = jdbc.sql("""
                        SELECT COUNT(*) FROM information_schema.table_constraints
                         WHERE table_schema = 'PUBLIC' AND LOWER(constraint_name) = :n""")
                .param("n", name).query(Integer.class).single();
        return n != null && n > 0;
    }

    private static boolean hasTable(Connection c, String table) throws SQLException {
        try (var ps = c.prepareStatement("""
                        SELECT COUNT(*) FROM information_schema.tables
                         WHERE table_schema = 'PUBLIC' AND UPPER(table_name) = ?""")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (var ps = c.prepareStatement("""
                        SELECT COUNT(*) FROM information_schema.columns
                         WHERE table_schema = 'PUBLIC'
                           AND UPPER(table_name) = ? AND UPPER(column_name) = ?""")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
