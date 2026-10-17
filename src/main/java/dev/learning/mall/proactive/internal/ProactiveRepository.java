package dev.learning.mall.proactive.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 关注与提醒的持久化。
 *
 * <p>所有查询都带 username：关注列表和提醒都是「我的数据」，串味的后果和购物袋一样严重
 * （把别人关注的商品降价提醒发给我，是最难解释的一类故障）。
 */
@Repository
class ProactiveRepository {

    /** 一条关注，已带上商品的实时价格与库存（扫描需要，页面展示也需要）。 */
    record WatchRow(String username, long productId, String productName, String illustration,
                    String category, BigDecimal baselinePrice, boolean inStockAtWatch,
                    BigDecimal price, int stock) {}

    /** 一条提醒（提醒中心里展示）。 */
    record AlertRow(String id, String type, String title, String content, Long productId,
                    String createdAt, boolean read) {}

    private final JdbcClient jdbc;

    ProactiveRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ——————————————————— 关注 ———————————————————

    List<WatchRow> watchesOf(String username) {
        return jdbc.sql("""
                        SELECT w.username, w.product_id, p.name AS product_name, p.illustration,
                               p.category, w.baseline_price, w.in_stock_at_watch, p.price, p.stock
                        FROM mall_product_watch w JOIN product p ON p.id = w.product_id
                        WHERE w.username = :user
                        ORDER BY w.created_at DESC""")
                .param("user", username).query(ProactiveRepository::mapWatch).list();
    }

    Optional<WatchRow> watchOf(String username, long productId) {
        return jdbc.sql("""
                        SELECT w.username, w.product_id, p.name AS product_name, p.illustration,
                               p.category, w.baseline_price, w.in_stock_at_watch, p.price, p.stock
                        FROM mall_product_watch w JOIN product p ON p.id = w.product_id
                        WHERE w.username = :user AND w.product_id = :pid""")
                .param("user", username).param("pid", productId)
                .query(ProactiveRepository::mapWatch).optional();
    }

    /**
     * 待扫描的关注：<b>只捞真正有话要说的人</b>。
     *
     * <p>两个过滤条件（降价、补货）下推到 SQL，而不是把所有关注都拉到 JVM 里再判断——
     * 扫描是最频繁的操作（每几分钟一次），扫描成本必须由「满足条件的有多少」决定，
     * 而不是由「总共有多少人点了关注」决定。
     *
     * <p><b>排序即公平</b>：按「最久没被扫过」升序。旧实现是按 created_at 取前 N 条，
     * 于是一旦关注总数超过 N，排在最末尾的那批永远进不了窗口——它们降价了也没人被告知，
     * 而且日志里只有一句「扫描了 N 条」，看起来一切正常。
     * 有了 {@code last_scanned_at} 这个游标，N 不再是「能服务多少人」的上限，
     * 而退化成它本来的含义：一次处理多少条。
     */
    List<WatchRow> watchesToScan(int limit) {
        return jdbc.sql("""
                        SELECT w.username, w.product_id, p.name AS product_name, p.illustration,
                               p.category, w.baseline_price, w.in_stock_at_watch, p.price, p.stock
                        FROM mall_product_watch w JOIN product p ON p.id = w.product_id
                        WHERE p.price < w.baseline_price
                           OR (w.in_stock_at_watch = FALSE AND p.stock > 0)
                        ORDER BY COALESCE(w.last_scanned_at, :epoch) ASC, w.username, w.product_id
                        LIMIT :limit""")
                .param("epoch", java.time.LocalDateTime.of(1970, 1, 1, 0, 0))
                .param("limit", limit)
                .query(ProactiveRepository::mapWatch).list();
    }

    /**
     * 还有多少条关注在等这一轮没处理完（积压量）。
     *
     * <p>它存在的意义是把「关注有没有被漏掉」从一句信仰变成一行可以看的数字。
     * 饥饿这类缺陷之所以可怕，是因为它没有任何症状——有了这个计数，积压一旦持续不为零，
     * 就等于有一批用户正被系统性地跳过。
     */
    int countPendingScans() {
        Integer n = jdbc.sql("""
                        SELECT COUNT(*) FROM mall_product_watch w JOIN product p ON p.id = w.product_id
                        WHERE p.price < w.baseline_price
                           OR (w.in_stock_at_watch = FALSE AND p.stock > 0)""")
                .query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /**
     * 推进扫描游标。<b>扫到就推进，哪怕这一条因为别的原因没有发提醒。</b>
     *
     * <p>若只有「真的发了提醒」才推进，处于冷却/无需提醒的行会一直排在队首反复占名额，
     * 队尾的人永远轮不到——饥饿换个形式又回来了。让每一行都往前走，整队才能转起来。
     */
    void markScanned(String username, long productId) {
        jdbc.sql("""
                        UPDATE mall_product_watch SET last_scanned_at = CURRENT_TIMESTAMP
                        WHERE username = :user AND product_id = :pid""")
                .param("user", username).param("pid", productId).update();
    }

    void upsert(String username, long productId, BigDecimal baselinePrice, boolean inStock) {
        int updated = jdbc.sql("""
                        UPDATE mall_product_watch SET baseline_price = :price, in_stock_at_watch = :stock
                        WHERE username = :user AND product_id = :pid""")
                .param("price", baselinePrice).param("stock", inStock)
                .param("user", username).param("pid", productId).update();
        if (updated > 0) return;
        jdbc.sql("""
                        INSERT INTO mall_product_watch(username, product_id, baseline_price, in_stock_at_watch)
                        VALUES(:user, :pid, :price, :stock)""")
                .param("user", username).param("pid", productId)
                .param("price", baselinePrice).param("stock", inStock).update();
    }

    void delete(String username, long productId) {
        jdbc.sql("DELETE FROM mall_product_watch WHERE username = :user AND product_id = :pid")
                .param("user", username).param("pid", productId).update();
    }

    int countWatches(String username) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM mall_product_watch WHERE username = :user")
                .param("user", username).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    /** 提醒后重新武装：把基线价格压到现价，下次再降才会再提醒。 */
    void rearmPrice(String username, long productId, BigDecimal price) {
        jdbc.sql("""
                        UPDATE mall_product_watch SET baseline_price = :price, last_notified_at = CURRENT_TIMESTAMP
                        WHERE username = :user AND product_id = :pid""")
                .param("price", price).param("user", username).param("pid", productId).update();
    }

    /** 到货提醒后记住「已经有货了」，避免同一次到货反复提醒。 */
    void markInStock(String username, long productId) {
        jdbc.sql("""
                        UPDATE mall_product_watch SET in_stock_at_watch = TRUE, last_notified_at = CURRENT_TIMESTAMP
                        WHERE username = :user AND product_id = :pid""")
                .param("user", username).param("pid", productId).update();
    }

    // ——————————————————— 提醒 ———————————————————

    void insertAlert(String username, Long productId, String type, String title, String content) {
        jdbc.sql("""
                        INSERT INTO mall_alert(id, username, product_id, alert_type, title, content)
                        VALUES(:id, :user, :pid, :type, :title, :content)""")
                .param("id", UUID.randomUUID().toString()).param("user", username)
                .param("pid", productId).param("type", type)
                .param("title", title).param("content", content).update();
    }

    /**
     * 带来源键的提醒：同一个来源只提醒一次。
     *
     * <p>幂等交给了 {@code (username, source_key)} 的唯一索引，而不是「先查有没有、再决定插不插」——
     * 查和插之间那一瞬间另一个事务可以插进来，于是两条提醒同时诞生。让数据库来拒绝，
     * 是唯一靠得住的顺序保证；DuplicateKeyException 在这里不是错误，而是「已经提醒过了」的正常答案。
     *
     * @return 是否真的写进了提醒；{@code false} 表示这个来源已经提醒过。
     */
    boolean insertAlertOnce(String username, Long productId, String type, String title, String content, String sourceKey) {
        try {
            jdbc.sql("""
                            INSERT INTO mall_alert(id, username, product_id, alert_type, title, content, source_key)
                            VALUES(:id, :user, :pid, :type, :title, :content, :key)""")
                    .param("id", UUID.randomUUID().toString()).param("user", username)
                    .param("pid", productId).param("type", type)
                    .param("title", title).param("content", content).param("key", sourceKey).update();
            return true;
        } catch (DuplicateKeyException alreadyReminded) {
            return false;
        }
    }

    List<AlertRow> alertsOf(String username, int limit) {
        return jdbc.sql("""
                        SELECT id, alert_type, title, content, product_id, created_at, read_at
                        FROM mall_alert WHERE username = :user
                        ORDER BY created_at DESC, id DESC LIMIT :limit""")
                .param("user", username).param("limit", limit)
                .query((rs, rowNum) -> new AlertRow(
                        rs.getString("id"), rs.getString("alert_type"), rs.getString("title"),
                        rs.getString("content"), (Long) rs.getObject("product_id"),
                        String.valueOf(rs.getTimestamp("created_at")),
                        rs.getTimestamp("read_at") != null)).list();
    }

    int unreadCount(String username) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM mall_alert WHERE username = :user AND read_at IS NULL")
                .param("user", username).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    int markRead(String username, String id) {
        return jdbc.sql("UPDATE mall_alert SET read_at = CURRENT_TIMESTAMP WHERE id = :id AND username = :user")
                .param("id", id).param("user", username).update();
    }

    int markAllRead(String username) {
        return jdbc.sql("UPDATE mall_alert SET read_at = CURRENT_TIMESTAMP WHERE username = :user AND read_at IS NULL")
                .param("user", username).update();
    }

    private static WatchRow mapWatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WatchRow(rs.getString("username"), rs.getLong("product_id"),
                rs.getString("product_name"), rs.getString("illustration"), rs.getString("category"),
                rs.getBigDecimal("baseline_price"), rs.getBoolean("in_stock_at_watch"),
                rs.getBigDecimal("price"), rs.getInt("stock"));
    }
}
