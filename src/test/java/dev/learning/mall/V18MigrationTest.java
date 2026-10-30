package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.order.internal.RefundService;
import java.lang.reflect.Field;
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
 * V18 迁移的结构性验收：{@code refund.reason_code} 的取值约束。
 *
 * <p>与 {@link V17MigrationTest} 同一条分工线：<b>本文件只验「库」，不验「代码」</b>。
 * 裸 JDBC 能证明的只有「约束在线且真的在拦」，它证明不了 {@code RefundService} 把
 * 原因码写对了——那一半归 {@code RefundRoutingTest} 用真实入口验。
 *
 * <p>本文件守的四件事：
 * <ul>
 *   <li>约束<b>存在且显式命名</b>（没有 {@code CONSTRAINT_x} 这类自动名）：
 *       自动名是建表顺序的函数，跨环境会失配；命名之后它还能出现在异常消息里被断言。</li>
 *   <li><b>真实写入探针</b>：四个合法码各写一行都成功、非法码被拒。
 *       只查 information_schema 的话，约束漏建 / 建了但值集合写窄，断言照样全绿。</li>
 *   <li><b>两侧逐字一致</b>：取值集合从 {@code RefundService.REASON_CODES} 反射读出来，
 *       逐个喂给数据库。Java 侧加了新值而本约束没同步扩展时，这条会红——
 *       那正是「新值在 Java 里合法、在库里被拒，线上报 500 而不是业务异常」的那一刻。</li>
 *   <li>大小写敏感：{@code damaged}（小写）必须被拒。Java 侧
 *       {@code normalizeReasonCode} 会先 {@code toUpperCase}，走到库里的一定是大写；
 *       裸写入的小写值若能落库，同一笔退款在库里就有两种写法，按原因码做的统计会分家。</li>
 * </ul>
 *
 * <p><b>诚实边界</b>：H2 判定 CHECK 为 UNKNOWN（NULL）时视为通过——这是 SQL 语义，
 * 不是漏洞。本列在 V17 里是 {@code NOT NULL}，NULL 根本进不来，所以不受影响；
 * 这条不在本文件里断言，免得把 NOT NULL 的功劳记到 CHECK 头上。
 */
@SpringBootTest
@ActiveProfiles("test")
class V18MigrationTest {

    /** 探针前缀：清理与「别人家的行」不要互相踩到。 */
    private static final String PROBE_ORDER_PREFIX = "v18-order-";

    private static final String PROBE_USER = "v18-probe";

    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM refund WHERE order_id LIKE 'v18-order-%'").update();
    }

    // ——————————————————— 结构：命名约束 ———————————————————

    @Test
    @DisplayName("ck_refund_reason_code 存在且显式命名：不是 H2 的 CONSTRAINT_x 自动名")
    void reasonCodeConstraintIsNamedAndPresent() {
        Set<String> refundChecks = constraintNames("REFUND");

        assertThat(refundChecks)
                .as("refund.reason_code 的取值域必须由数据库守着：Java 侧只覆盖走 RefundService 的人")
                .contains("CK_REFUND_REASON_CODE");
        // 这条断言守的是「写法」而不是「结果」：将来有人图省事改回内联 CHECK，
        // 约束依然生效、名字却变成自动名，只有这一句会拦住他。
        assertThat(refundChecks).noneMatch(name -> name.startsWith("CONSTRAINT_"));
        // V17 那批约束没被这次迁移碰掉（加一条不能等于换掉一批）。
        assertThat(refundChecks).contains("CK_REFUND_STATUS", "CK_REFUND_ROUTE",
                "CK_REFUND_AUTO_DECIDED_BY_SYSTEM");
    }

    // ——————————————————— 两侧逐字一致 ———————————————————

    /**
     * Java 侧枚举的每个取值，数据库都必须接得住。
     *
     * <p>取值集合<b>反射读取</b>而不是在测试里另抄一份 {@code List.of(...)}：
     * 抄一份的写法在「Java 加了新值、V18 没跟着扩展」时依然全绿——
     * 它测的是自己手里那张清单，不是两侧是否一致。
     */
    @Test
    @DisplayName("RefundService.REASON_CODES 里的每个值，数据库都接得住（Java 加值时约束必须同步扩展）")
    void everyJavaReasonCodeIsAcceptedByDatabase() {
        List<String> codes = reasonCodesFromRefundService();
        assertThat(codes).as("至少还是当初那四个值，否则这条用例自己失去意义").hasSizeGreaterThanOrEqualTo(4);

        for (String code : codes) {
            assertThat(insertRefund("r-v18-java-" + code, code))
                    .as("Java 侧认的原因码「%s」必须也能落库：约束漏改会让生产报 500", code)
                    .isEqualTo(1);
        }
    }

    /** 向后兼容哨兵：已知的四个值逐个写一遍都能过。 */
    @Test
    @DisplayName("四个已知原因码都能落库：NO_REASON / DAMAGED / WRONG_ITEM / OTHER")
    void knownReasonCodesAreAllAccepted() {
        for (String code : List.of("NO_REASON", "DAMAGED", "WRONG_ITEM", "OTHER")) {
            String id = "r-v18-known-" + code;
            assertThat(insertRefund(id, code)).as("原因码 %s 必须能落库", code).isEqualTo(1);
            assertThat(jdbc.sql("SELECT reason_code FROM refund WHERE id = :id")
                    .param("id", id).query(String.class).single()).isEqualTo(code);
        }
    }

    // ——————————————————— 真实写入探针：非法值被拒 ———————————————————

    @Test
    @DisplayName("域外的原因码被拒：报的正是 CK_REFUND_REASON_CODE，且库里一行都没留下")
    void illegalReasonCodeIsRejected() {
        for (String bogus : List.of("BOGUS", "no_reason", "NO_REASON_OR_SOMETHING", "")) {
            assertThatThrownBy(() -> insertRefund("r-v18-bad-" + Math.abs(bogus.hashCode()), bogus))
                    .as("原因码「%s」不在取值域内，数据库必须拒", bogus)
                    .hasMessageContaining("CK_REFUND_REASON_CODE");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id LIKE 'v18-order-%'")
                .query(Integer.class).single())
                .as("被拒的 INSERT 不该留下半吊子行")
                .isZero();
    }

    /**
     * 大小写敏感：小写必须被拒。
     *
     * <p>Java 侧 {@code normalizeReasonCode} 会先 {@code toUpperCase} 再校验，
     * 于是走到库里的一定是大写。裸写入的小写值若能落库，
     * 同一笔退款在库里就有两种写法，按原因码做的统计会分家。
     */
    @Test
    @DisplayName("小写的原因码被拒：口径只有大写一种（Java 侧先 toUpperCase 再落库）")
    void lowercaseReasonCodeIsRejected() {
        assertThat(insertRefund("r-v18-upper", "DAMAGED")).isEqualTo(1);

        assertThatThrownBy(() -> insertRefund("r-v18-lower", "damaged"))
                .hasMessageContaining("CK_REFUND_REASON_CODE");
        assertThatThrownBy(() -> insertRefund("r-v18-mixed", "Wrong_Item"))
                .hasMessageContaining("CK_REFUND_REASON_CODE");
    }

    // ——————————————————— 重放：全新库 ———————————————————

    /**
     * 把迁移重放到一个全新的内存库：V18 必须能跟在 V17 后面跑通，
     * 且不依赖任何本地数据（Flyway 默认禁止 out-of-order，编号也因此必须大于 17）。
     */
    @Test
    @DisplayName("迁移重放到空库：V18 跟在 V17 之后成立，且不灌任何退款数据")
    void migrationReplaysOnAFreshDatabase() throws SQLException {
        String url = TestH2.inMemory("v18-replay-" + UUID.randomUUID());
        SimpleDriverDataSource replay = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try {
            Flyway.configure().dataSource(replay).locations("classpath:db/migration", "classpath:db/dev-seed")
                    .target("18").load().migrate();

            try (Connection c = replay.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM refund")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("迁移不 seeding 任何退款数据").isZero();
                }
            }
            assertThat(constraintNamesOf(replay, "REFUND")).contains("CK_REFUND_REASON_CODE");
        } finally {
            try (Connection c = replay.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }

    // ——————————————————— 工具 ———————————————————

    /**
     * 插一行退款；订单号按 id 派生，免得同订单两笔在途撞上 uq_refund_inflight。
     *
     * <p>V25 起 {@code order_sub_id} 是 NOT NULL，且 {@code in_flight} 生成列装的是它——
     * 子订单号同样按 id 派生（该列无外键，合成值即可），碰撞口径与派生订单号完全一致。
     */
    private int insertRefund(String id, String reasonCode) {
        return jdbc.sql("INSERT INTO refund(id,order_id,order_sub_id,username,amount,reason_code,route,status,"
                        + "requested_by) "
                        + "VALUES(:id,:order,:sub,:user,47.00,:code,'AUTO','PENDING_APPROVAL',:user)")
                .param("id", id)
                .param("order", PROBE_ORDER_PREFIX + id)
                .param("sub", PROBE_ORDER_PREFIX + id + "-S1")
                .param("user", PROBE_USER)
                .param("code", reasonCode)
                .update();
    }

    /**
     * 从 {@code RefundService.REASON_CODES} 反射读出取值集合。
     *
     * <p>它刻意<b>不是</b>手抄一份：手抄那份在两侧漂移时依然全绿，而这条会红。
     * 反射是因为该字段是包级可见（{@code internal} 包内的实现细节不该为了测试而 public 出去）。
     */
    @SuppressWarnings("unchecked")
    private static List<String> reasonCodesFromRefundService() {
        try {
            Field field = RefundService.class.getDeclaredField("REASON_CODES");
            field.setAccessible(true);
            Object value = field.get(null);
            return List.copyOf((List<String>) value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("读不到 RefundService.REASON_CODES："
                    + "字段被改名或删除时这条守卫必须红，而不是悄悄跳过", e);
        }
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
}
