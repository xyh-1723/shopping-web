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
 *
 * <p><b>V25 之后</b>：履约状态机（列 + 两条 {@code ck_*_fulfillment_*} 约束）从
 * {@code mall_order} 搬到了 {@code order_sub}，本文件的探针跟着指到子订单；
 * 「钱到账才配货」不再是 CHECK（跨表条件没有 CHECK 可写），
 * 改由 {@link #unpaidOrderCannotEnterFulfillment} 以「影响 0 行」的形状守。
 * 历史重放用例（{@link #scriptAddsConstraintsAfterBackfill}）停在 V15 那一刻的库上，
 * 那里约束当然还在 {@code mall_order} ——它验的是迁移本身，不是现状。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class V15MigrationTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-10-01T10:00:00");
    private static final String REPLAY_ID = "v15-replay-order";

    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("履约七列与显式命名约束都在（V25 起在 ORDER_SUB 上）；父单只剩支付时刻那一套")
    void fulfillmentColumnsAndNamedConstraintsArePresent() {
        // V25 把履约整套从 mall_order 搬到了 order_sub（一车三家的货各发各的，
        // 父单上不存在能成立的值），本断言跟着列的新住址走。
        assertThat(columnsOf("ORDER_SUB")).contains("FULFILLMENT_STATUS", "SHIPPED_AT",
                "SHIPPED_BY", "SHIPPING_NO", "RECEIVED_AT", "COMPLETED_AT", "AUTO_RECEIVE_AT");
        assertThat(columnsOf("MALL_ORDER")).contains("PAID_AT")
                .as("履约字段在父单上必须已经下线，否则「父单只管钱、子单管货」分叉成两处真相")
                .doesNotContain("FULFILLMENT_STATUS", "SHIPPED_AT", "RECEIVED_AT", "COMPLETED_AT");

        Set<String> subNames = constraintNames("ORDER_SUB");
        assertThat(subNames).contains("CK_SUB_FULFILLMENT_STATUS", "CK_SUB_FULFILLMENT_FIELDS",
                "CK_SUB_AMOUNTS");
        // 「钱到账才配货」不再是 CHECK：它跨表（子单不知道父单的 status），SQL 没有跨表 CHECK，
        // V25 把它写进了每条履约 UPDATE 的 WHERE（EXISTS 父单 PAID）——
        // 那条规则由 unpaidOrderCannotEnterFulfillment 以「0 行」的形状守着。
        assertThat(subNames).noneMatch(name -> name.contains("NEEDS_PAID"));

        Set<String> names = constraintNames("MALL_ORDER");
        assertThat(names).contains("CK_ORDER_PAID_NEEDS_MOMENT")
                .doesNotContain("CK_ORDER_FULFILLMENT_STATUS", "CK_ORDER_FULFILLMENT_FIELDS",
                        "CK_ORDER_FULFILLMENT_NEEDS_PAID");

        // 这里刻意不像 V14 那样断言「整张表没有 CONSTRAINT_ 前缀」：V1 的 status 与 total
        // 是内联 CHECK，H2 给它们的正是自动名，且方案 §2.2 决定不去动它（动它就得重建表）。
        // 履约这套约束必须自己起名——上面 contains 就是这条保证。
        assertThat(subNames).noneMatch(name -> name.startsWith("CONSTRAINT_") && name.contains("FULFILL"));
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
    @DisplayName("发货必须有发货时刻：裸 JDBC 把子订单改成 SHIPPED 却不写 shipped_at，会被拒")
    void shippedOrderMustHaveMoment() {
        String id = insertOrder("v15-shipped-without-moment", "PENDING");
        String subId = insertSub(id);
        try {
            jdbc.sql("UPDATE mall_order SET status='PAID', paid_at=:t WHERE id=:id")
                    .param("id", id).param("t", CREATED_AT.plusMinutes(3)).update();

            assertThatThrownBy(() -> jdbc.sql("UPDATE order_sub SET fulfillment_status='SHIPPED',"
                            + " shipping_no='SF123456' WHERE id=:id")
                    .param("id", subId).update())
                    .hasMessageContaining("CK_SUB_FULFILLMENT_FIELDS");

            // 状态没被改成一半：要么整条 UPDATE 生效，要么一行都不动。
            assertThat(fulfillmentOf(subId)).isEqualTo("NONE");

            // 时刻写全就能过，同样是为了证明上面的红来自约束而不是 SQL。
            assertThat(jdbc.sql("UPDATE order_sub SET fulfillment_status='SHIPPED', shipped_at=:t,"
                            + " shipping_no='SF123456' WHERE id=:id")
                    .param("id", subId).param("t", CREATED_AT.plusHours(1)).update()).isEqualTo(1);
        } finally {
            delete(id);
        }
    }

    @Test
    @DisplayName("哨兵：fulfillment_status 的取值集合真的被 CHECK 拦着（不是空约束）")
    void fulfillmentStatusValueSetIsEnforced() {
        String id = insertOrder("v15-status-value-sentinel", "PAID");
        String subId = insertSub(id);
        try {
            // 'DELIVERED' 看着像合法业务词，但它不在四值集合里——这类值最容易被人顺手加进来。
            assertThatThrownBy(() -> jdbc.sql("UPDATE order_sub SET fulfillment_status='DELIVERED' WHERE id=:id")
                    .param("id", subId).update())
                    .hasMessageContaining("CK_SUB_FULFILLMENT_STATUS");

            // 四值集合里的每一个都必须能写进去，否则约束就是写窄了。
            for (String value : List.of("NONE", "SHIPPED", "RECEIVED", "COMPLETED")) {
                jdbc.sql("UPDATE order_sub SET fulfillment_status=:s, shipped_at=:shipped,"
                                + " received_at=:received, completed_at=:completed WHERE id=:id")
                        .param("id", subId)
                        .param("s", value)
                        .param("shipped", "NONE".equals(value) ? null : CREATED_AT.plusHours(1))
                        .param("received", "SHIPPED".equals(value) || "NONE".equals(value)
                                ? null : CREATED_AT.plusHours(2))
                        .param("completed", "COMPLETED".equals(value) ? CREATED_AT.plusHours(3) : null)
                        .update();
                assertThat(fulfillmentOf(subId)).isEqualTo(value);
            }
        } finally {
            delete(id);
        }
    }

    @Test
    @DisplayName("未支付的订单不能进入履约：V25 起这条不在 CHECK 里，而在写语句的 WHERE（EXISTS 父单 PAID）上")
    void unpaidOrderCannotEnterFulfillment() {
        String id = insertOrder("v15-unpaid-cannot-fulfill", "PENDING");
        String subId = insertSub(id);
        try {
            // 「钱到账才配货」如今是每条履约 UPDATE 的 WHERE 条件（跨表条件没有 CHECK 可写）。
            // 用与 OrderSubRepository.markShipped 同形的语句探它：父单没付钱 → 影响 0 行，
            // 这就是新规制下「拒绝」的形状（不再是约束异常）。
            int unpaid = jdbc.sql("UPDATE order_sub SET fulfillment_status='SHIPPED', shipped_at=:t"
                            + " WHERE id=:id AND fulfillment_status='NONE'"
                            + " AND EXISTS (SELECT 1 FROM mall_order o"
                            + "              WHERE o.id = order_sub.order_id AND o.status='PAID')")
                    .param("id", subId).param("t", CREATED_AT.plusHours(1)).update();
            assertThat(unpaid).as("父单未支付时，发货 UPDATE 必须一行都够不到").isZero();
            assertThat(fulfillmentOf(subId)).isEqualTo("NONE");

            // 父单付了钱，同一条语句就够得到：证明上面的 0 行来自「未支付」而不是 SQL 写错。
            jdbc.sql("UPDATE mall_order SET status='PAID', paid_at=:t WHERE id=:id")
                    .param("id", id).param("t", CREATED_AT.plusMinutes(3)).update();
            assertThat(jdbc.sql("UPDATE order_sub SET fulfillment_status='SHIPPED', shipped_at=:t"
                            + " WHERE id=:id AND fulfillment_status='NONE'"
                            + " AND EXISTS (SELECT 1 FROM mall_order o"
                            + "              WHERE o.id = order_sub.order_id AND o.status='PAID')")
                    .param("id", subId).param("t", CREATED_AT.plusHours(1)).update()).isEqualTo(1);
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
            Flyway.configure().dataSource(replay).locations("classpath:db/migration", "classpath:db/dev-seed")
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
            Flyway.configure().dataSource(replay).locations("classpath:db/migration", "classpath:db/dev-seed")
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

    /** 履约状态的新住址在子订单上（V25）。 */
    private String fulfillmentOf(String subId) {
        return jdbc.sql("SELECT fulfillment_status FROM order_sub WHERE id=:id")
                .param("id", subId).query(String.class).single();
    }

    /**
     * 给探针订单落一个子订单（履约探针的操作对象）。
     *
     * <p>{@code order_sub} 刻意没有指向 {@code mall_order} 的外键（V25），所以可以直接插；
     * 它有指向 {@code merchant} 的外键，商家号从库里现查，不写死。
     */
    private String insertSub(String orderId) {
        Long merchant = jdbc.sql("SELECT MIN(id) FROM merchant").query(Long.class).single();
        assertThat(merchant).as("前提：库里有种子商家，order_sub 的外键才有落点").isNotNull();
        String subId = orderId + "-S1";
        // V27 给 order_sub 加了结算约束 merchant_income = pay_amount + platform_subsidy - platform_fee，
        // 所以这里显式写上 merchant_income：无券、无补贴的订单，商家拿到的就是实付。
        jdbc.sql("INSERT INTO order_sub(id, order_id, sub_seq, merchant_id,"
                        + " subtotal, discount, shipping_fee, pay_amount, merchant_income)"
                        + " VALUES (:id, :o, 1, :m, 320.00, 0, 0, 320.00, 320.00)")
                .param("id", subId).param("o", orderId).param("m", merchant).update();
        return subId;
    }

    private void delete(String id) {
        // order_sub 没有指向 mall_order 的外键，删订单带不走它，要自己清。
        jdbc.sql("DELETE FROM order_sub WHERE order_id=:id").param("id", id).update();
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
