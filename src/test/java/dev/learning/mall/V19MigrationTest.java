package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * V19 迁移的结构性验收：{@code notification_delivery.content} 的长度对齐到
 * {@code notification.content}。
 *
 * <p>与 {@link V17MigrationTest} / {@link V18MigrationTest} 同一条分工线：
 * <b>本文件只验「库」，不验「代码」</b>。「文案到 500 字符时不会出现半成功」那一半
 * 由 {@link NotificationChannelTest} 用真实 sink 入口验（那里才有「先写站内信、
 * 再写留痕」这两步的顺序）。
 *
 * <p>本文件守的三件事：
 * <ul>
 *   <li><b>断言「相等」而不是「都等于 500」</b>：写死 500 会在将来又一次单边改动时
 *       失效——两侧一起改成 600 会让它假红，而它要守的不变量是「留痕必须接得住
 *       站内信接得住的任何一条文案」，也就是两侧相等。
 *       但只断言相等也有一个洞：两侧一起被压到 50 也会绿。所以另加一条<b>下限</b>
 *       （≥ 500）：把长度压小是「改需求」，必须有人显式地来改这条断言。</li>
 *   <li><b>真实写入探针</b>：按 {@code notification} 那一侧的真实长度写满，
 *       必须能落进 {@code notification_delivery}。只查 information_schema 的话，
 *       「ALTER 没跑」或「跑了对着错的列」断言照样全绿。</li>
 *   <li><b>NOT NULL 没被顺手改没</b>：改列类型在部分数据库里会连带丢掉约束，
 *       V17 定下的 {@code content NOT NULL} 必须还在。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class V19MigrationTest {

    private static final String PROBE_USER = "v19-probe";
    private static final String PROBE_PREFIX = "v19-";

    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM notification_delivery WHERE username = :u").param("u", PROBE_USER).update();
    }

    // ——————————————————— 不变量：两侧相等 ———————————————————

    @Test
    @DisplayName("两张表的 content 长度相等：留痕必须接得住站内信接得住的任何一条文案")
    void contentLengthsAreAligned() {
        int notificationLength = lengthOf("NOTIFICATION", "CONTENT");
        int deliveryLength = lengthOf("NOTIFICATION_DELIVERY", "CONTENT");

        assertThat(deliveryLength)
                .as("notification=%s 但 notification_delivery=%s：长度不对称会让「站内信写成功、留痕写失败」"
                        + "成为可能——那是一种不红不报错的半成功", notificationLength, deliveryLength)
                .isEqualTo(notificationLength);
    }

    /**
     * 「相等」这一条单独看有个洞：两侧一起被压小它也绿。
     *
     * <p>所以这里再钉一个下限。它是<b>刻意写死</b>的：把文案上限往下调是「改需求」，
     * 得有人显式地来改这条断言，而不是让它在一次「顺手对齐」里悄悄发生。
     */
    @Test
    @DisplayName("下限哨兵：两侧长度都不小于 500（只断言相等的话，一起被压到 50 也会绿）")
    void lengthsAreNotSilentlyShrunk() {
        assertThat(lengthOf("NOTIFICATION", "CONTENT")).isGreaterThanOrEqualTo(500);
        assertThat(lengthOf("NOTIFICATION_DELIVERY", "CONTENT")).isGreaterThanOrEqualTo(500);
    }

    // ——————————————————— 真实写入探针 ———————————————————

    @Test
    @DisplayName("500 字符的文案能写进 notification_delivery：不是只把数字改对了")
    void fiveHundredCharContentIsWritable() {
        String content = "x".repeat(500);
        String id = PROBE_PREFIX + UUID.randomUUID().toString().substring(0, 8);

        assertThat(insertDelivery(id, content)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content FROM notification_delivery WHERE id = :id")
                .param("id", id).query(String.class).single()).hasSize(500);
    }

    /**
     * 探针按 <b>notification 那一侧的真实长度</b>生成，而不是写死 500。
     *
     * <p>写死 500 的写法在「两侧一起改成 600」时依然绿——它测的是自己手里那个数字，
     * 不是「留痕接得住站内信接得住的东西」这条不变量。
     */
    @Test
    @DisplayName("按 notification 那侧的真实长度写满：留痕必须同样接得住")
    void deliveryAcceptsWhateverNotificationAccepts() {
        int limit = lengthOf("NOTIFICATION", "CONTENT");
        String content = "y".repeat(limit);
        String id = PROBE_PREFIX + UUID.randomUUID().toString().substring(0, 8);

        assertThat(insertDelivery(id, content)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content FROM notification_delivery WHERE id = :id")
                .param("id", id).query(String.class).single()).hasSize(limit);
    }

    /**
     * 向后兼容哨兵：改列类型不能把 V17 定下的 NOT NULL 顺手改没。
     *
     * <p>{@code content} 为 NULL 的留痕行是「投递过了，但投了什么不知道」——
     * 留痕因此失去意义，而它还占着「投递过一次」这个计数。
     */
    @Test
    @DisplayName("NOT NULL 还在：content 为空的留痕行仍然被拒")
    void contentIsStillNotNull() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO notification_delivery(id,username,channel,"
                        + "preferred_channel,degraded,event_type,order_id,content) "
                        + "VALUES(:id,:u,'IN_APP','IN_APP',FALSE,'ORDER_SHIPPED',NULL,NULL)")
                .param("id", PROBE_PREFIX + UUID.randomUUID())
                .param("u", PROBE_USER)
                .update())
                .as("改列类型不能顺手把 NOT NULL 改没")
                .hasMessageContaining("NULL");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE username = :u")
                .param("u", PROBE_USER).query(Integer.class).single()).isZero();
    }

    // ——————————————————— 重放：全新库 ———————————————————

    /**
     * 把迁移重放到一个全新的内存库：V19 必须能跟在 V18 后面跑通，且不依赖任何本地数据。
     *
     * <p>这条同时是「编号必须 > 18」的哨兵：Flyway 默认禁止 out-of-order，
     * 编号写小了在这里就会红，而不是等到上线。
     */
    @Test
    @DisplayName("迁移重放到空库：V19 跟在 V18 之后成立，且两侧长度在那里也相等")
    void migrationReplaysOnAFreshDatabase() throws SQLException {
        String url = TestH2.inMemory("v19-replay-" + UUID.randomUUID());
        SimpleDriverDataSource replay = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try {
            Flyway.configure().dataSource(replay).locations("classpath:db/migration", "classpath:db/dev-seed")
                    .target("19").load().migrate();

            int notificationLength = lengthOn(replay, "NOTIFICATION", "CONTENT");
            int deliveryLength = lengthOn(replay, "NOTIFICATION_DELIVERY", "CONTENT");
            assertThat(deliveryLength).as("空库重放后两侧仍然相等").isEqualTo(notificationLength);
            assertThat(deliveryLength).isGreaterThanOrEqualTo(500);
        } finally {
            try (Connection c = replay.getConnection()) {
                c.createStatement().execute("SHUTDOWN");
            }
        }
    }

    // ——————————————————— 工具 ———————————————————

    private int insertDelivery(String id, String content) {
        return jdbc.sql("INSERT INTO notification_delivery(id,username,channel,preferred_channel,"
                        + "degraded,event_type,order_id,content) "
                        + "VALUES(:id,:u,'IN_APP','IN_APP',FALSE,'ORDER_SHIPPED','v19-order',:content)")
                .param("id", id)
                .param("u", PROBE_USER)
                .param("content", content)
                .update();
    }

    /** 读某一列声明的字符长度（CHARACTER_MAXIMUM_LENGTH）。 */
    private int lengthOf(String table, String column) {
        return jdbc.sql("SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'PUBLIC' AND table_name = :t AND column_name = :c")
                .param("t", table).param("c", column).query(Integer.class).single();
    }

    private int lengthOn(SimpleDriverDataSource ds, String table, String column) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT character_maximum_length"
                     + " FROM information_schema.columns"
                     + " WHERE table_schema = 'PUBLIC' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s 必须存在", table, column).isTrue();
                return rs.getInt(1);
            }
        }
    }
}
