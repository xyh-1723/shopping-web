package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * V15 迁移的结构性验收：履约状态机（N1）落在库上的那部分。
 *
 * <p>与 {@link V14MigrationTest} 同一条分工线：<b>这一轮只验「库」不验「代码」</b>。
 * 裸 JDBC 能证明的只有「约束在线」——它<b>证明不了</b> Java writer 把字段写对了
 * （§9 的分层修订：#1a「markPaid 漏写 paid_at」、#2a「发货漏写 shipped_at」
 * 属于 Java writer → DB CHECK 联动层，要等 T12 的 {@code FulfillmentService} 真实入口
 * 才测得到，本轮不做）。
 *
 * <p>本文件守的三件事：
 * <ul>
 *   <li>四条约束<b>存在且显式命名</b>：名字会出现在异常消息里，也会被后续迁移与测试
 *       断言引用，自动名（{@code CONSTRAINT_x}）换个库实例就认不出来。</li>
 *   <li>约束<b>不是空约束</b>：光「有名字」不够，得证明非法行真的被拒（哨兵断言）。</li>
 *   <li>迁移里的<b>语句顺序</b>：加列 → 回填 → 加约束 → 加索引。顺序反了，
 *       存量 PAID 行会自己把迁移顶回去（§2.5）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class V15MigrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-10-01T10:00:00");
    private static final String REPLAY_ID = "v15-replay-order";

    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("履约八列与四条显式命名约束都在")
    void fulfillmentColumnsAndNamedConstraintsArePresent() {
        assertThat(columnsOf("MALL_ORDER")).contains("PAID_AT", "FULFILLMENT_STATUS", "SHIPPED_AT",
                "SHIPPED_BY", "SHIPPING_NO", "RECEIVED_AT", "COMPLETED_AT", "AUTO_RECEIVE_AT");

        Set<String> names = constraintNames("MALL_ORDER");
        assertThat(names).contains("CK_ORDER_FULFILLMENT_STATUS", "CK_ORDER_FULFILLMENT_FIELDS",
                "CK_ORDER_PAID_NEEDS_MOMENT", "CK_ORDER_FULFILLMENT_NEEDS_PAID");

        // 这里刻意不像 V14 那样断言「整张表没有 CONSTRAINT_ 前缀」：V1 的 status 与 total
        // 是内联 CHECK，H2 给它们的正是自动名，且方案 §2.2 决定不去动它（动它就得重建表）。
        // V15 新增的这四条必须自己起名——上面 contains 就是这条保证。
        assertThat(names).noneMatch(name -> name.startsWith("CONSTRAINT_") && name.contains("FULFILL"));
    }

    @Test
    @DisplayName("付过款必须有支付时刻：裸 JDBC 把订单改成 PAID 却不写 paid_at，会被拒")
    void paidOrderMustHavePaidMoment() {
        String id = insertOrder("v15-paid-without-moment", "PENDING");
        try {
            assertThatThrownBy(() -> jdbc.sql("UPDATE mall_order SET status='PAID',"
                            + " payment_method='DEMO_CARD', payment_receipt='receipt-1' WHERE id=:id")
                    .param("id", id).update())
                    .hasMessageContaining("CK_ORDER_PAID_NEEDS_MOMENT");

            // 被拒之后这一行必须原样留在 PENDING：约束拒绝的是这条 UPDATE，
            // 而不是「先改了状态再报错」——支付入口正是靠异常来判定这次支付没发生。
            assertThat(statusOf(id)).isEqualTo("PENDING");

            // 写了时刻的同一条 UPDATE 就能过：证明上面的红不是因为 SQL 写错。
            assertThat(jdbc.sql("UPDATE mall_order SET status='PAID',"
                            + " payment_method='DEMO_CARD', payment_receipt='receipt-1', paid_at=:t WHERE id=:id")
                    .param("id", id).param("t", CREATED_AT.plusMinutes(3)).update()).isEqualTo(1);
        } finally {
            delete(id);
        }
    }

    @Test
    @DisplayName("发货必须有发货时刻：裸 JDBC 把订单改成 SHIPPED 却不写 shipped_at，会被拒")
    void shippedOrderMustHaveMoment() {
        String id = insertOrder("v15-shipped-without-moment", "PENDING");
        try {
            jdbc.sql("UPDATE mall_order SET status='PAID', paid_at=:t WHERE id=:id")
                    .param("id", id).param("t", CREATED_AT.plusMinutes(3)).update();

            assertThatThrownBy(() -> jdbc.sql("UPDATE mall_order SET fulfillment_status='SHIPPED',"
                            + " shipping_no='SF123456' WHERE id=:id")
                    .param("id", id).update())
                    .hasMessageContaining("CK_ORDER_FULFILLMENT_FIELDS");

            // 状态没被改成一半：要么整条 UPDATE 生效，要么一行都不动。
            assertThat(jdbc.sql("SELECT fulfillment_status FROM mall_order WHERE id=:id")
                    .param("id", id).query(String.class).single()).isEqualTo("NONE");

            // 时刻写全就能过，同样是为了证明上面的红来自约束而不是 SQL。
            assertThat(jdbc.sql("UPDATE mall_order SET fulfillment_status='SHIPPED', shipped_at=:t,"
                            + " shipping_no='SF123456' WHERE id=:id")
                    .param("id", id).param("t", CREATED_AT.plusHours(1)).update()).isEqualTo(1);
        } finally {
            delete(id);
        }
    }

    @Test
    @DisplayName("哨兵：fulfillment_status 的取值集合真的被 CHECK 拦着（不是空约束）")
    void fulfillmentStatusValueSetIsEnforced() {
        String id = insertOrder("v15-status-value-sentinel", "PAID");
        try {
            // 'DELIVERED' 看着像合法业务词，但它不在四值集合里——这类值最容易被人顺手加进来。
            assertThatThrownBy(() -> jdbc.sql("UPDATE mall_order SET fulfillment_status='DELIVERED' WHERE id=:id")
                    .param("id", id).update())
                    .hasMessageContaining("CK_ORDER_FULFILLMENT_STATUS");

            // 四值集合里的每一个都必须能写进去，否则约束就是写窄了。
            for (String value : List.of("NONE", "SHIPPED", "RECEIVED", "COMPLETED")) {
                jdbc.sql("UPDATE mall_order SET fulfillment_status=:s, shipped_at=:shipped,"
                                + " received_at=:received, completed_at=:completed WHERE id=:id")
                        .param("id", id)
                        .param("s", value)
                        .param("shipped", "NONE".equals(value) ? null : CREATED_AT.plusHours(1))
                        .param("received", "SHIPPED".equals(value) || "NONE".equals(value)
                                ? null : CREATED_AT.plusHours(2))
                        .param("completed", "COMPLETED".equals(value) ? CREATED_AT.plusHours(3) : null)
                        .update();
                assertThat(jdbc.sql("SELECT fulfillment_status FROM mall_order WHERE id=:id")
                        .param("id", id).query(String.class).single()).isEqualTo(value);
            }
        } finally {
            delete(id);
        }
    }

    @Test
    @DisplayName("未支付的订单不能进入履约：fulfillment_status 想往前走，status 必须是 PAID")
    void unpaidOrderCannotEnterFulfillment() {
        String id = insertOrder("v15-unpaid-cannot-fulfill", "PENDING");
        try {
            // shipped_at 一并写上：这样唯一能拒绝它的只有「未支付」这条，异常归因才干净。
            assertThatThrownBy(() -> jdbc.sql("UPDATE mall_order SET fulfillment_status='SHIPPED',"
                            + " shipped_at=:t WHERE id=:id")
                    .param("id", id).param("t", CREATED_AT.plusHours(1)).update())
                    .hasMessageContaining("CK_ORDER_FULFILLMENT_NEEDS_PAID");

            assertThat(jdbc.sql("SELECT fulfillment_status FROM mall_order WHERE id=:id")
                    .param("id", id).query(String.class).single()).isEqualTo("NONE");
        } finally {
            delete(id);
        }
    }

    @Test
    @DisplayName("迁移内的语句顺序：先回填再加约束（空库灌一笔 PAID 后完整重放 V15 必须成功）")
    void scriptAddsConstraintsAfterBackfill() throws SQLException {
        String url = TestH2.inMemory("v15-replay-" + UUID.randomUUID());
        SimpleDriverDataSource replay = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try {
            // 第一步：把库停在 V14。那一刻 mall_order 还没有 paid_at 这一列，
            // 于是「status='PAID' 且没有支付时刻」正是迁移前的真实存量形态。
            Flyway.configure().dataSource(replay).locations("classpath:db/migration")
                    .target("14").load().migrate();
            try (Connection c = replay.getConnection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO mall_order"
                         + "(id, username, idempotency_key, request_hash, total, subtotal, discount,"
                         + " shipping_fee, pay_amount, status, created_at)"
                         + " VALUES (?, 'v15-tester', ?, 'hash', 320.00, 320.00, 0, 0, 320.00, 'PAID', ?)")) {
                ps.setString(1, REPLAY_ID);
                ps.setString(2, REPLAY_ID);
                ps.setTimestamp(3, Timestamp.valueOf(CREATED_AT));
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            // 第二步：只往前跑一格。若有人把回填挪到约束之后，上面这笔行会当场把迁移顶回去：
            // Check constraint violation: "CK_ORDER_PAID_NEEDS_MOMENT"
            Flyway.configure().dataSource(replay).locations("classpath:db/migration")
                    .target("15").load().migrate();

            // 迁移成功还不够——那笔存量行必须真的被回填了，否则约束只是恰好没被触发。
            assertThat(paidAtOf(replay, REPLAY_ID)).isEqualTo(CREATED_AT);
            // 且约束已经建起来：迁移完成后，「PAID 无时刻」这种行再也写不进去。
            assertThat(constraintNamesOf(replay)).contains("CK_ORDER_PAID_NEEDS_MOMENT",
                    "CK_ORDER_FULFILLMENT_STATUS", "CK_ORDER_FULFILLMENT_FIELDS",
                    "CK_ORDER_FULFILLMENT_NEEDS_PAID");
        } finally {
            // 独立内存库用完即弃：SHUTDOWN 会连同数据一起丢掉，不占着 JVM 的内存。
            try (Connection c = replay.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }

    private String insertOrder(String id, String status) {
        jdbc.sql("INSERT INTO mall_order(id, username, idempotency_key, request_hash, total,"
                        + " subtotal, discount, shipping_fee, pay_amount, status, created_at, paid_at)"
                        + " VALUES (:id, 'v15-tester', :key, 'hash', 320.00, 320.00, 0, 0, 320.00,"
                        + " :status, :created, :paid)")
                .param("id", id).param("key", id).param("status", status)
                .param("created", CREATED_AT)
                // PAID 行要先有支付时刻才进得去（约束在线），PENDING 行则必须没有。
                .param("paid", "PAID".equals(status) ? CREATED_AT.plusMinutes(3) : null)
                .update();
        return id;
    }

    private String statusOf(String id) {
        return jdbc.sql("SELECT status FROM mall_order WHERE id=:id").param("id", id).query(String.class).single();
    }

    private void delete(String id) {
        jdbc.sql("DELETE FROM mall_order WHERE id=:id").param("id", id).update();
    }

    private LocalDateTime paidAtOf(SimpleDriverDataSource ds, String id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT paid_at FROM mall_order WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getTimestamp("paid_at").toLocalDateTime();
            }
        }
    }

    private Set<String> constraintNamesOf(SimpleDriverDataSource ds) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT constraint_name"
                     + " FROM information_schema.table_constraints"
                     + " WHERE table_schema = 'PUBLIC' AND table_name = 'MALL_ORDER'"
                     + " AND constraint_type = 'CHECK'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    /** 查某张表上 CHECK 约束的名字（H2 里未加引号的标识符都是大写）。 */
    private Set<String> constraintNames(String table) {
        List<String> names = jdbc.sql("SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t AND constraint_type = 'CHECK'")
                .param("t", table).query(String.class).list();
        return new HashSet<>(names);
    }

    private Set<String> columnsOf(String table) {
        List<String> names = jdbc.sql("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t")
                .param("t", table).query(String.class).list();
        return new HashSet<>(names);
    }
}
