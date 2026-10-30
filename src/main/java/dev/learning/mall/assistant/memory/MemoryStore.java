package dev.learning.mall.assistant.memory;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 长期记忆的持久化。
 *
 * <p>记忆分层的常规做法是四类（工作 / 情景 / 语义 / 程序性）。这里落地的是其中两类：
 * <ul>
 *   <li><b>语义记忆</b>：本表。跨会话、跨时间生效的用户偏好（预算、风格、常买给谁）；</li>
 *   <li><b>工作记忆</b>：见 {@code assistant_conversation_summary}，是当前会话的滚动摘要，
 *       会话结束就不再有意义。</li>
 * </ul>
 *
 * <p>每次读写都带 username：A 的偏好永远不会被读进 B 的上下文。
 * 「记忆串味」在电商场景里不只是隐私问题——给错人推荐错东西，直接就是客诉。
 */
@Repository
public class MemoryStore {

    private final JdbcClient jdbc;

    public MemoryStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * 一条生效中的记忆。过期数据不物理删除，靠查询条件过滤，保留可追溯性。
     *
     * <p>{@code source} 区分「用户明确说过的」（MODEL）与「系统自动抽的」（AUTO）——
     * 两者的可信度不同，写入时的覆盖规则也因此不同（见 {@code MemoryWriter}）。
     */
    public record Entry(String key, String value, String source, double confidence, String updatedAt) {}

    /**
     * 手写行映射而不靠「属性名 → 列名」自动推导：{@code memory_key} 推不出 {@code key}，
     * 自动映射会在这里静默踩空。列名映射这种事，显式写出来最省事也最不容易出错。
     */
    private static final org.springframework.jdbc.core.RowMapper<Entry> ENTRY_MAPPER =
            (rs, rowNum) -> new Entry(
                    rs.getString("memory_key"),
                    rs.getString("memory_value"),
                    rs.getString("source"),
                    rs.getDouble("confidence"),
                    String.valueOf(rs.getTimestamp("updated_at")));

    public List<Entry> active(String username) {
        return jdbc.sql("SELECT memory_key, memory_value, source, confidence, updated_at " +
                        "FROM assistant_user_memory " +
                        "WHERE username = :user AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) " +
                        // 次排序键 memory_key：一轮对话可能同时刷新多条记忆，
                        // 它们的 updated_at 会撞在同一毫秒上，没有它「这次记住了什么」的顺序不稳定。
                        "ORDER BY updated_at DESC, memory_key")
                .param("user", username).query(ENTRY_MAPPER).list();
    }

    /** 单条生效中的记忆；不存在或已过期时为空。写入前用它判断「这个键有没有人写过、谁写的」。 */
    public java.util.Optional<Entry> find(String username, String key) {
        return jdbc.sql("SELECT memory_key, memory_value, source, confidence, updated_at " +
                        "FROM assistant_user_memory " +
                        "WHERE username = :user AND memory_key = :key " +
                        "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)")
                .param("user", username).param("key", key).query(ENTRY_MAPPER).optional();
    }

    /**
     * 写入（同一 key 覆盖）。
     *
     * @param ttlDays 有效期（天）。偏好会变——「这次预算 200」不该在半年后还在生效，
     *                过期机制让记忆自然衰减，而不需要一个「清理任务」去擦屁股。
     */
    public void remember(String username, String key, String value, String source,
                         double confidence, long ttlDays) {
        // 先 UPDATE 再 INSERT，而不是 MERGE：MERGE 的「按哪组列匹配」依赖底层主键/唯一索引的
        // 具体实现，换个库就可能有细微差别；两条显式语句在哪都一样。同一 key 覆盖而非追加。
        //
        // 日期加减统一用 TIMESTAMPADD(DAY, n, ts)，不用 DATEADD()：
        // DATEADD 是 H2 专有函数，MySQL 上会报 FUNCTION does not exist；而 MySQL 的
        // DATE_ADD(ts, INTERVAL n DAY) 在 H2 上又不存在。同一个应用要在 H2（测试）和
        // MySQL（生产）上都跑，只能取交集——TIMESTAMPADD 是实测两边都成立的那个。
        int updated = jdbc.sql("UPDATE assistant_user_memory SET memory_value = :value, source = :source, " +
                        "confidence = :conf, updated_at = CURRENT_TIMESTAMP, " +
                        "expires_at = TIMESTAMPADD(DAY, :ttl, CURRENT_TIMESTAMP) " +
                        "WHERE username = :user AND memory_key = :key")
                .param("value", value).param("source", source).param("conf", confidence)
                .param("ttl", (int) ttlDays)
                .param("user", username).param("key", key)
                .update();
        if (updated > 0) return;
        jdbc.sql("INSERT INTO assistant_user_memory " +
                 "(id, username, memory_key, memory_value, source, confidence, expires_at) " +
                 "VALUES(:id, :user, :key, :value, :source, :conf, TIMESTAMPADD(DAY, :ttl, CURRENT_TIMESTAMP))")
                .param("id", UUID.randomUUID().toString())
                .param("user", username)
                .param("key", key)
                .param("value", value)
                .param("source", source)
                .param("conf", confidence)
                .param("ttl", (int) ttlDays)
                .update();
    }

    public void forget(String username, String key) {
        jdbc.sql("DELETE FROM assistant_user_memory WHERE username = :user AND memory_key = :key")
                .param("user", username).param("key", key).update();
    }

    public void forgetAll(String username) {
        jdbc.sql("DELETE FROM assistant_user_memory WHERE username = :user")
                .param("user", username).update();
    }

    /**
     * 淘汰最旧的一条。记忆不该无上限增长：一是上下文成本，二是「陈旧偏好」比「没有偏好」
     * 更有害——用户两年前说过一次喜欢红色，不该影响今天的推荐。
     */
    public void evictOldest(String username) {
        jdbc.sql("DELETE FROM assistant_user_memory WHERE id = (" +
                 "SELECT id FROM assistant_user_memory WHERE username = :user ORDER BY updated_at LIMIT 1)")
                .param("user", username).update();
    }

    public int count(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM assistant_user_memory WHERE username = :user")
                .param("user", username).query(Integer.class).single();
    }
}
