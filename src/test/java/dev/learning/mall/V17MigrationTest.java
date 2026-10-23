package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * V17 迁移的结构性验收：退款（{@code refund}）+ HITL 退款闸门字段 + 外发投递留痕。
 *
 * <p>与 {@link V16MigrationTest} 同一条分工线：<b>本文件只验「库」，不验「代码」</b>。
 * 裸 JDBC 能证明的只有「约束在线」——它<b>证明不了</b> Java writer 把字段写对了。
 * 所以「退款的两类落点、库存幂等、放款责任人」分别在 {@link RefundRoutingTest}、
 * {@link RefundStockIdempotenceTest} 与 {@link RefundWalletTest} 里用真实入口再验一次。
 *
 * <p>本文件守的四件事：
 * <ul>
 *   <li>约束<b>存在且显式命名</b>（没有 {@code CONSTRAINT_x} 这类自动名）：
 *       名字会出现在异常消息里，也会被后续迁移与测试断言引用。</li>
 *   <li><b>{@code uq_refund_inflight}</b> 不是空约束：同订单第二笔在途必须被拒，
 *       终态的多条历史必须被允许，且生成列在 UPDATE 时会重算（否则约束会退化成
 *       「这笔订单永远不能再退款」）。</li>
 *   <li><b>{@code ck_refund_auto_decided_by_system}</b> 允许 AUTO 建单那一刻的
 *       {@code decided_by IS NULL}——这条曾经被自己写错过一次。</li>
 *   <li>HITL 的 {@code ORDER_REFUND} 卡片能被插进去（旧约束会把新 kind 直接拒掉），
 *       同时既有三类 kind 仍然能写（向后兼容哨兵）。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class V17MigrationTest {

    private static final String PROBE_ORDER = "v17-order";
    private static final String PROBE_USER = "v17-probe";
    private static final String PROBE_CONVERSATION = "v17-conversation";

    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM refund WHERE order_id = :o").param("o", PROBE_ORDER).update();
        jdbc.sql("DELETE FROM refund WHERE username = :u").param("u", PROBE_USER).update();
        jdbc.sql("DELETE FROM assistant_pending_action WHERE username = :u").param("u", PROBE_USER).update();
        jdbc.sql("DELETE FROM notification_delivery WHERE username = :u").param("u", PROBE_USER).update();
    }

    // ——————————————————— 结构：命名约束 ———————————————————

    @Test
    @DisplayName("refund / notification_delivery 的结构、索引与命名约束都在（没有 CONSTRAINT_x 自动名）")
    void refundConstraintsAreNamedAndPresent() {
        assertThat(columnsOf("REFUND")).contains("ID", "ORDER_ID", "USERNAME", "AMOUNT",
                "REASON_CODE", "REASON_TEXT", "ROUTE", "STATUS", "REQUESTED_BY", "DECIDED_BY",
                "DECIDED_AT", "DECISION_NOTE", "STOCK_RETURNED", "IN_FLIGHT");
        assertThat(columnsOf("NOTIFICATION_DELIVERY")).contains("ID", "USERNAME", "CHANNEL",
                "PREFERRED_CHANNEL", "DEGRADED", "EVENT_TYPE", "ORDER_ID", "CONTENT");

        Set<String> refundChecks = constraintNames("REFUND");
        assertThat(refundChecks).contains("CK_REFUND_AMOUNT", "CK_REFUND_ROUTE",
                "CK_REFUND_STATUS", "CK_REFUND_STOCK_FLAG", "CK_REFUND_DECISION_FIELDS",
                "CK_REFUND_AUTO_DECIDED_BY_SYSTEM", "CK_REFUND_STOCK_ONLY_WHEN_REFUNDED");
        assertThat(constraintNames("NOTIFICATION_DELIVERY")).contains("CK_DELIVERY_CHANNEL",
                "CK_DELIVERY_PREFERRED");
        // 这条断言守的是「写法」而不是「结果」：将来有人图省事改回内联 CHECK，
        // 约束依然生效、名字却变成自动名，只有这一句会拦住他。
        assertThat(refundChecks).noneMatch(name -> name.startsWith("CONSTRAINT_"));

        // stock_ledger 认得 REFUND 了（V14 那条被换成了带 REFUND 的 v2）。
        assertThat(constraintNames("STOCK_LEDGER")).contains("CK_LEDGER_REASON_V2")
                .doesNotContain("CK_LEDGER_REASON");
        assertThat(indexNames("REFUND")).contains("UQ_REFUND_INFLIGHT", "IDX_REFUND_ORDER",
                "IDX_REFUND_QUEUE");
    }

    @Test
    @DisplayName("库存流水认得退款归还：REFUND 允许、第五种原因仍然被拒，旧原因不受影响")
    void ledgerReasonAcceptsRefundReturns() {
        // 旧值仍然能写：换约束不是改语义（向后兼容哨兵）。
        assertThat(insertLedger(PROBE_ORDER, "RELEASE", 1, 10, 11)).isEqualTo(1);
        assertThat(insertLedger(PROBE_ORDER, "REFUND", 1, 11, 12)).isEqualTo(1);
        assertThatThrownBy(() -> insertLedger(PROBE_ORDER, "MAGIC", 1, 12, 13))
                .hasMessageContaining("CK_LEDGER_REASON_V2");
    }

    // ——————————————————— 在途唯一：uq_refund_inflight ———————————————————

    @Test
    @DisplayName("#7 一笔订单只能有一笔在途退款：第二笔被 UQ_REFUND_INFLIGHT 拒（CASE 漏掉 APPROVED 就守不住）")
    void onlyOneActiveRefundPerOrder() {
        assertThat(insertRefund("r-v17-a", "PENDING_APPROVAL", "AUTO", null, null)).isEqualTo(1);

        assertThatThrownBy(() -> insertRefund("r-v17-b", "APPROVED", "MERCHANT_APPROVAL", "merchant", null))
                .as("同一订单的第二笔在途（APPROVED 也算在途）必须被拒")
                .hasMessageContaining("UQ_REFUND_INFLIGHT");

        // 终态不受约束：历史可以留多条（唯一索引允许多个 NULL）。
        assertThatThrownBy(() -> insertRefund("r-v17-c", "PENDING_APPROVAL", "AUTO", null, null))
                .hasMessageContaining("UQ_REFUND_INFLIGHT");
    }

    @Test
    @DisplayName("#12 终态可以共存：同订单三条终态各自成立（约束没写过头）")
    void historyRowsMayCoexist() {
        assertThat(insertRefund("r-v17-t1", "REJECTED", "MERCHANT_APPROVAL", "merchant", null)).isEqualTo(1);
        assertThat(insertRefund("r-v17-t2", "REJECTED", "MERCHANT_APPROVAL", "merchant", null)).isEqualTo(1);
        assertThat(insertRefund("r-v17-t3", "REFUNDED", "AUTO", "SYSTEM", 1)).isEqualTo(1);
        assertThat(insertRefund("r-v17-t4", "CANCELLED", "MERCHANT_APPROVAL", null, null)).isEqualTo(1);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id = :o")
                .param("o", PROBE_ORDER).query(Integer.class).single()).isEqualTo(4);
    }

    /**
     * 哨兵自证：{@code uq_refund_inflight} 不是空约束。
     *
     * <p>它是 #7 的前提——若这个索引根本没建成，「第二笔被拒」可能只是别的原因在生效。
     */
    @Test
    @DisplayName("哨兵：in_flight 与它的唯一索引都真实存在，且生成列不许手工赋值")
    void inFlightIndexIsNotVacuous() {
        // 索引与列都在（information_schema 各查一条）。
        assertThat(indexNames("REFUND")).contains("UQ_REFUND_INFLIGHT");
        assertThat(generatedColumnsOf("REFUND")).contains("IN_FLIGHT");

        // 生成列：INSERT 里写它会报 cannot be assigned。
        Throwable thrown = catchThrowableOfType(() -> jdbc.sql("INSERT INTO refund(id,order_id,username,"
                        + "amount,reason_code,route,status,requested_by,in_flight) "
                        + "VALUES('r-v17-gen',:o,:u,1,'NO_REASON','AUTO','PENDING_APPROVAL',:u,:o)")
                .param("o", PROBE_ORDER).param("u", PROBE_USER).update(), Throwable.class);
        // H2 把这条报错放在最内层的原因里（外层只有一句 bad SQL grammar），
        // 所以断言必须走整条异常链，而不是只看最外层那条消息。
        assertThat(messageChain(thrown)).contains("Generated column")
                .contains("cannot be assigned");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM refund WHERE id = 'r-v17-gen'")
                .query(Integer.class).single()).isZero();
    }

    /**
     * 生成列必须<b>在 UPDATE 时重算</b>：否则「终态让位」不成立，
     * 约束会从「防重复」退化成「这笔订单永远不能再退款」。
     */
    @Test
    @DisplayName("哨兵：在途 → 终态后 in_flight 变 NULL，同一订单可以再申请一笔")
    void inFlightRecomputesOnTerminal() {
        insertRefund("r-v17-recompute", "PENDING_APPROVAL", "AUTO", null, null);

        assertThat(inFlightOf("r-v17-recompute")).isEqualTo(PROBE_ORDER);

        // PENDING_APPROVAL → APPROVED：仍在途（CASE 里两个值都算）
        assertThat(jdbc.sql("UPDATE refund SET status='APPROVED',decided_by='SYSTEM',"
                        + "decided_at=CURRENT_TIMESTAMP WHERE id='r-v17-recompute'").update()).isEqualTo(1);
        assertThat(inFlightOf("r-v17-recompute")).isEqualTo(PROBE_ORDER);

        // APPROVED → REFUNDED：让位
        assertThat(jdbc.sql("UPDATE refund SET status='REFUNDED' WHERE id='r-v17-recompute'").update())
                .isEqualTo(1);
        assertThat(inFlightOf("r-v17-recompute")).isNull();

        // 于是同一订单可以再申请一笔（约束没退化成永久封禁）。
        assertThat(insertRefund("r-v17-again", "PENDING_APPROVAL", "MERCHANT_APPROVAL", null, null))
                .isEqualTo(1);
    }

    // ——————————————————— AUTO 的 decided_by ———————————————————

    /**
     * 这条曾经被自己写错过：约束写成 {@code route <> 'AUTO' OR decided_by = 'SYSTEM'} 时，
     * AUTO 连建单都会被拒（建单那一刻 {@code decided_by} 是 {@code NULL}，而
     * {@code NULL = 'SYSTEM'} 不是 TRUE）。
     */
    @Test
    @DisplayName("AUTO 允许「还没定」与「系统定的」，但永远不允许「某个人定的」")
    void autoDecidedByAllowsNullAndSystemOnly() {
        // 建单那一刻：decided_by 为空 —— 必须允许。
        assertThat(insertRefund("r-v17-auto-null", "PENDING_APPROVAL", "AUTO", null, null)).isEqualTo(1);
        // 系统定了：允许。
        assertThat(insertRefund("r-v17-auto-sys", "REFUNDED", "AUTO", "SYSTEM", null)).isEqualTo(1);
        jdbc.sql("DELETE FROM refund WHERE id = 'r-v17-auto-null'").update();
        jdbc.sql("DELETE FROM refund WHERE id = 'r-v17-auto-sys'").update();

        // 某个人定的：永远不合法（伪造审批仍然被挡）。
        assertThatThrownBy(() -> insertRefund("r-v17-auto-human", "REFUNDED", "AUTO", "merchant", null))
                .hasMessageContaining("CK_REFUND_AUTO_DECIDED_BY_SYSTEM");

        // 反向控制：非 AUTO 路由本来就该由人批。
        assertThat(insertRefund("r-v17-merchant", "REFUNDED", "MERCHANT_APPROVAL", "merchant", 1))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("审批字段要么全空（还在等）要么全满（已有决定），半吊子行不存在")
    void decisionFieldsAreAllOrNothing() {
        assertThatThrownBy(() -> insertRefund("r-v17-half", "APPROVED", "MERCHANT_APPROVAL", null, 0))
                .hasMessageContaining("CK_REFUND_DECISION_FIELDS");
        // 待审批就置 stock_returned = 1：还没批就先退货，不允许。
        assertThatThrownBy(() -> insertRefund("r-v17-early-stock", "PENDING_APPROVAL", "AUTO", null, 1))
                .hasMessageContaining("CK_REFUND_STOCK_ONLY_WHEN_REFUNDED");
    }

    // ——————————————————— HITL：ORDER_REFUND 卡片 ———————————————————

    /**
     * 只加两个列、不换 {@code ck_pending_kind_fields} 的话，
     * 这里的 INSERT 会被旧约束直接拒掉（V13 那条是穷举式 OR）。
     */
    @Test
    @DisplayName("#10 助手的退款确认卡片插得进去：ORDER_REFUND 带金额与原因码")
    void orderRefundCardIsInsertable() {
        assertThat(insertPendingAction("ORDER_REFUND", PROBE_ORDER, new java.math.BigDecimal("47.00"),
                "NO_REASON", "不想要了")).isEqualTo(1);

        // 缺原因码：约束要求它必须有（模型侧漏传时要被数据库拦住，而不是写一条残缺的行）。
        assertThatThrownBy(() -> insertPendingAction("ORDER_REFUND", PROBE_ORDER,
                new java.math.BigDecimal("47.00"), null, null))
                .hasMessageContaining("CK_PENDING_KIND_FIELDS_V2");

        // 向后兼容哨兵：既有的三类 kind 换约束后依然能写。
        assertThat(insertPendingAction("ORDER_CANCEL", PROBE_ORDER, new java.math.BigDecimal("47.00"),
                null, null)).isEqualTo(1);
        assertThat(insertPendingAction("ORDER_PAYMENT", PROBE_ORDER, new java.math.BigDecimal("47.00"),
                null, null)).isEqualTo(1);
    }

    // ——————————————————— 重放：全新库 ———————————————————

    /**
     * 把迁移重放到一个全新的内存库，确认 V17 在「从 V1 一路过来」的路径上不依赖任何本地数据。
     */
    @Test
    @DisplayName("迁移重放到空库：refund 与它的唯一索引、生成列都在，且不灌任何种子数据")
    void migrationReplaysOnAFreshDatabase() throws SQLException {
        String url = TestH2.inMemory("v17-replay-" + UUID.randomUUID());
        SimpleDriverDataSource replay = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try {
            Flyway.configure().dataSource(replay).locations("classpath:db/migration")
                    .target("17").load().migrate();

            try (Connection c = replay.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM refund")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("迁移不 seeding 任何退款数据").isZero();
                }
            }
            assertThat(constraintNamesOf(replay, "REFUND")).contains(
                    "CK_REFUND_DECISION_FIELDS", "CK_REFUND_AUTO_DECIDED_BY_SYSTEM",
                    "CK_REFUND_STOCK_ONLY_WHEN_REFUNDED");
            assertThat(generatedColumnsOf(replay, "REFUND")).contains("IN_FLIGHT");
        } finally {
            try (Connection c = replay.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }

    // ——————————————————— 工具 ———————————————————

    /**
     * 插一行退款。
     *
     * <p>{@code stockReturned} 传 {@code null} 表示「没还货」（落库为 0）：
     * 这是退款最常见的形状，让调用处保持沉默比逼它们处处写 0 更好读。
     */
    private int insertRefund(String id, String status, String route, String decidedBy, Integer stockReturned) {
        return jdbc.sql("INSERT INTO refund(id,order_id,username,amount,reason_code,route,status,"
                        + "requested_by,decided_by,decided_at,stock_returned) "
                        + "VALUES(:id,:order,:user,47.00,'NO_REASON',:route,:status,:user,:decided,"
                        + "CASE WHEN :decided IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,COALESCE(:stock,0))")
                .param("id", id)
                .param("order", PROBE_ORDER)
                .param("user", PROBE_USER)
                .param("route", route)
                .param("status", status)
                .param("decided", decidedBy)
                .param("stock", stockReturned)
                .update();
    }

    /** 待确认动作 id：主键列是 VARCHAR(36)，这里刻意短于 UUID（前缀 + 8 位十六进制）。 */
    private static String pendingActionId(String kind) {
        return "pa-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int insertPendingAction(String kind, String orderId, java.math.BigDecimal amount,
                                    String reasonCode, String reasonText) {
        return jdbc.sql("INSERT INTO assistant_pending_action(id,username,conversation_id,kind,order_id,"
                        + "amount,payment_method,refund_reason_code,refund_reason_text,status,expires_at) "
                        + "VALUES(:id,:user,:conv,:kind,:order,:amount,'DEMO_WALLET',:code,:text,"
                        + "'PENDING',TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP))")
                .param("id", pendingActionId(kind))
                .param("user", PROBE_USER)
                .param("conv", PROBE_CONVERSATION)
                .param("kind", kind)
                .param("order", orderId)
                .param("amount", amount)
                .param("code", reasonCode)
                .param("text", reasonText)
                .update();
    }

    private int insertLedger(String orderId, String reason, int delta, int before, int after) {
        return jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor, order_id)"
                        + " VALUES (1, :delta, :before, :after, :reason, 'v17-tester', :orderId)")
                .param("delta", delta)
                .param("before", before)
                .param("after", after)
                .param("reason", reason)
                .param("orderId", orderId)
                .update();
    }

    /** 把整条异常链上的消息拼成一段文本（H2 的约束名只出现在最内层）。 */
    private static String messageChain(Throwable thrown) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t.getMessage() != null) text.append(t.getMessage());
        }
        return text.toString();
    }

    private String inFlightOf(String refundId) {
        return jdbc.sql("SELECT in_flight FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).optional().orElse(null);
    }

    private Set<String> constraintNames(String table) {
        return new HashSet<>(jdbc.sql("SELECT constraint_name FROM information_schema.table_constraints"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t AND constraint_type = 'CHECK'")
                .param("t", table).query(String.class).list());
    }

    private Set<String> constraintNamesOf(SimpleDriverDataSource ds, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT constraint_name"
                     + " FROM information_schema.table_constraints"
                     + " WHERE table_schema = 'PUBLIC' AND table_name = ?"
                     + " AND constraint_type = 'CHECK'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    private Set<String> indexNames(String table) {
        return new HashSet<>(jdbc.sql("SELECT index_name FROM information_schema.indexes"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t")
                .param("t", table).query(String.class).list());
    }

    private Set<String> generatedColumnsOf(String table) {
        return new HashSet<>(jdbc.sql("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t"
                        + " AND is_generated = 'ALWAYS'")
                .param("t", table).query(String.class).list());
    }

    private Set<String> generatedColumnsOf(SimpleDriverDataSource ds, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT column_name FROM information_schema.columns"
                     + " WHERE table_schema = 'PUBLIC' AND table_name = ? AND is_generated = 'ALWAYS'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    private Set<String> columnsOf(String table) {
        return new HashSet<>(jdbc.sql("SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'PUBLIC' AND table_name = :t")
                .param("t", table).query(String.class).list());
    }
}
