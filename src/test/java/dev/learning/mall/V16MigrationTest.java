package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
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
 * V16 迁移的结构性验收：内置钱包（{@code wallet} + {@code wallet_ledger}）。
 *
 * <p>与 {@link V14MigrationTest} 同一条分工线：<b>这一轮只验「库」不验「代码」</b>。
 * 裸 JDBC 能证明的只有「约束在线」——它<b>证明不了</b> Java writer 把字段写对了。
 * 所以「PAYMENT 记成出账」这件事在<b>两个层</b>各有一条断言：
 * 本文件验数据库约束（裸 SQL），{@code WalletLedgerTest#paymentIsRecordedAsOutflow}
 * 验真实 {@code WalletService} 写出来的那一行。只留前者守不住「换个地方写」。
 *
 * <p>本文件守的四件事：
 * <ul>
 *   <li>约束<b>存在且显式命名</b>：名字会出现在异常消息里，也会被后续迁移与测试断言引用，
 *       自动名（{@code CONSTRAINT_x}）换个库实例就认不出来。</li>
 *   <li><b>方向约束</b>真的在拦：{@code ck_wallet_ledger_arithmetic} 是一条<b>无方向</b>的恒等式，
 *       符号写反它照样成立，所以 PAYMENT 必须负、REFUND 必须正各钉一条。</li>
 *   <li>唯一索引<b>不是空的</b>，且它的 NULL 语义与设计一致（{@code OPENING}/{@code TOPUP} 不受约束）。</li>
 *   <li>迁移<b>不 seeding</b> 初始额度：额度来自 {@code application.yml}，
 *       在 SQL 里写死金额就是第二个真相来源。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class V16MigrationTest {

    private static final String PROBE_USER = "v16-probe";

    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        WalletProbe.purge(jdbc, PROBE_USER);
    }

    @Test
    @DisplayName("钱包两张表的结构、索引与命名约束都在（没有 CONSTRAINT_x 这类自动名）")
    void walletConstraintsAreNamedAndPresent() {
        assertThat(columnsOf("WALLET")).contains("USERNAME", "BALANCE_CENTS");
        assertThat(columnsOf("WALLET_LEDGER")).contains("ID", "USERNAME", "DELTA_CENTS",
                "BALANCE_BEFORE_CENTS", "BALANCE_AFTER_CENTS", "REASON", "ACTOR", "ORDER_ID",
                "CREATED_AT");

        Set<String> walletChecks = constraintNames("WALLET");
        assertThat(walletChecks).contains("CK_WALLET_BALANCE_NONNEG");

        Set<String> ledgerChecks = constraintNames("WALLET_LEDGER");
        assertThat(ledgerChecks).contains("CK_WALLET_LEDGER_REASON",
                "CK_WALLET_LEDGER_DELTA_NONZERO",
                "CK_WALLET_LEDGER_BALANCE_NONNEG",
                "CK_WALLET_LEDGER_ARITHMETIC",
                "CK_WALLET_LEDGER_PAYMENT_IS_OUTFLOW",
                "CK_WALLET_LEDGER_REFUND_IS_INFLOW");

        // 这条断言守的是「写法」而不是「结果」：将来有人图省事改回内联 CHECK，
        // 约束依然生效、名字却变成自动名，只有这一句会拦住他。
        assertThat(walletChecks).noneMatch(name -> name.startsWith("CONSTRAINT_"));
        assertThat(ledgerChecks).noneMatch(name -> name.startsWith("CONSTRAINT_"));
    }

    @Test
    @DisplayName("PAYMENT 必须是出账：算术恒等式抓不到符号写反，只有方向约束抓得到")
    void paymentOutflowIsConstraintEnforced() {
        // 这一行的算术与非负约束全部成立（0 + 100 = 100），唯一能拒它的是方向约束。
        // 没有它，一个符号写反的支付会变成「充了值」，而对账恒等式照样成立。
        assertThatThrownBy(() -> insertLedger("order-payment-sign", "PAYMENT", 100, 0, 100))
                .hasMessageContaining("CK_WALLET_LEDGER_PAYMENT_IS_OUTFLOW");

        // 方向正确的同一条才能过：证明上面的红来自约束，而不是 SQL 写错。
        assertThat(insertLedger("order-payment-ok", "PAYMENT", -100, 1000, 900)).isEqualTo(1);
    }

    @Test
    @DisplayName("REFUND 必须是入账：与 PAYMENT 对称，同样只能靠方向约束拦")
    void refundInflowIsConstraintEnforced() {
        assertThatThrownBy(() -> insertLedger("order-refund-sign", "REFUND", -100, 100, 0))
                .hasMessageContaining("CK_WALLET_LEDGER_REFUND_IS_INFLOW");

        assertThat(insertLedger("order-refund-ok", "REFUND", 100, 900, 1000)).isEqualTo(1);
    }

    @Test
    @DisplayName("算术与非负由数据库把关：before + delta ≠ after / 负余额 / 零 delta 都会被拒")
    void ledgerArithmeticAndNonNegativityAreEnforced() {
        assertThatThrownBy(() -> insertLedger("order-bad-arith", "PAYMENT", -100, 1000, 950))
                .hasMessageContaining("CK_WALLET_LEDGER_ARITHMETIC");
        // delta = 0 的行没有信息量：「没发生」不该被记成「发生了一次没变化」。
        assertThatThrownBy(() -> insertLedger("order-zero-delta", "PAYMENT", 0, 1000, 1000))
                .hasMessageContaining("CK_WALLET_LEDGER_DELTA_NONZERO");
        assertThatThrownBy(() -> insertLedger("order-negative-after", "PAYMENT", -100, 50, -50))
                .hasMessageContaining("CK_WALLET_LEDGER_BALANCE_NONNEG");
        // 原因只有四类，多出来的第五种会被拒。
        assertThatThrownBy(() -> insertLedger("order-bad-reason", "MAGIC", 100, 0, 100))
                .hasMessageContaining("CK_WALLET_LEDGER_REASON");

        // 被拒之后一张表都没多出来：约束拒绝的是那条 INSERT，而不是「先插进去再报错」。
        assertThat(WalletProbe.ledgerRowCount(jdbc, PROBE_USER)).isZero();
    }

    @Test
    @DisplayName("余额不允许为负：绕过服务直接把余额改成 -1 会被拒")
    void walletBalanceCannotGoNegative() {
        jdbc.sql("INSERT INTO wallet(username, balance_cents) VALUES (:u, 100)")
                .param("u", PROBE_USER).update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE wallet SET balance_cents = -1 WHERE username = :u")
                        .param("u", PROBE_USER).update())
                .hasMessageContaining("CK_WALLET_BALANCE_NONNEG");

        // 状态没被改成一半：要么整条 UPDATE 生效，要么一行都不动。
        assertThat(WalletProbe.balanceCents(jdbc, PROBE_USER)).isEqualTo(100L);
    }

    /**
     * 哨兵自证：{@code uq_wallet_ledger_order_reason} 不是空约束，
     * 而且它的 NULL 语义与设计一致——{@code OPENING} / {@code TOPUP} 的
     * {@code order_id} 为 NULL，本来就该允许多次。
     */
    @Test
    @DisplayName("哨兵：同一 (order_id, reason) 只能有一行；order_id 为 NULL 的多行不受约束")
    void orderReasonIndexIsNotVacuous() {
        assertThat(insertLedger("order-dup", "PAYMENT", -100, 1000, 900)).isEqualTo(1);

        assertThatThrownBy(() -> insertLedger("order-dup", "PAYMENT", -100, 900, 800))
                .as("幂等的数据库那一半：同一笔订单在同一件事上只能扣一次")
                .hasMessageContaining("UQ_WALLET_LEDGER_ORDER_REASON");

        // 换个 reason 是另一件事，允许（支付与退款各一行）。
        assertThat(insertLedger("order-dup", "REFUND", 100, 900, 1000)).isEqualTo(1);

        // order_id 为 NULL 的多行不受约束：H2 的唯一索引允许多个 NULL，
        // 否则「充值多次」会被这条索引当成重复。
        assertThat(insertLedger(null, "TOPUP", 10000, 1000, 11000)).isEqualTo(1);
        assertThat(insertLedger(null, "TOPUP", 10000, 11000, 21000)).isEqualTo(1);
    }

    @Test
    @DisplayName("迁移不 seeding 初始额度：全新库重放到 V16 之后 wallet 表零行")
    void migrationSeedsNoOpeningBalance() throws SQLException {
        String url = TestH2.inMemory("v16-replay-" + UUID.randomUUID());
        SimpleDriverDataSource replay = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try {
            Flyway.configure().dataSource(replay).locations("classpath:db/migration")
                    .target("16").load().migrate();

            try (Connection c = replay.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM wallet")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1))
                            .as("初始额度来自 application.yml 的 mall.wallet.initial-grant-cents；"
                                    + "迁移读不到 yml，在 SQL 里写死金额就是第二个真相来源")
                            .isZero();
                }
            }
            // 迁移虽然不灌数据，表和约束必须都在。
            assertThat(constraintNamesOf(replay, "WALLET_LEDGER")).contains(
                    "CK_WALLET_LEDGER_ARITHMETIC", "CK_WALLET_LEDGER_PAYMENT_IS_OUTFLOW",
                    "CK_WALLET_LEDGER_REFUND_IS_INFLOW");
        } finally {
            // 独立内存库用完即弃：SHUTDOWN 会连同数据一起丢掉，不占着 JVM 的内存。
            try (Connection c = replay.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }

    // ——————————————————— 工具 ———————————————————

    private int insertLedger(String orderId, String reason, long delta, long before, long after) {
        return jdbc.sql("INSERT INTO wallet_ledger"
                        + "(username, delta_cents, balance_before_cents, balance_after_cents,"
                        + " reason, actor, order_id)"
                        + " VALUES (:u, :delta, :before, :after, :reason, 'v16-tester', :orderId)")
                .param("u", PROBE_USER)
                .param("delta", delta)
                .param("before", before)
                .param("after", after)
                .param("reason", reason)
                .param("orderId", orderId)
                .update();
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
