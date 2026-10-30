package dev.learning.mall.cart.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 购物袋持久化。所有语句都带 username，一个用户永远读不到也改不到别人的袋子。 */
@Repository
class CartRepository {

    private final JdbcClient jdbc;

    CartRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 一行：商品 + 数量。价格与库存不在这里——它们属于商品表，实时读才准。 */
    record Row(long productId, int quantity) {}

    List<Row> rowsOf(String username) {
        return jdbc.sql("SELECT product_id, quantity FROM mall_cart_item WHERE username = :user ORDER BY updated_at DESC, product_id")
                .param("user", username).query(Row.class).list();
    }

    Optional<Integer> quantityOf(String username, long productId) {
        return jdbc.sql("SELECT quantity FROM mall_cart_item WHERE username = :user AND product_id = :pid")
                .param("user", username).param("pid", productId).query(Integer.class).optional();
    }

    /**
     * 新增或累加：用数据库原生的「插入或更新」让「第一次加购」与「再加一件」
     * 走同一条语句，避免竞态下插重。
     *
     * <p>这里刻意<b>不是</b> H2 的 {@code MERGE INTO ... KEY(...)}：那个语法只有 H2 认，
     * 生产库 MySQL 8 既没有 {@code MERGE} 也不认 {@code KEY} 子句，加购会直接 500。
     * {@code ON DUPLICATE KEY UPDATE} 是两边都支持的写法（H2 在 MODE=MySQL 下同样接受），
     * 语义完全一致：命中唯一键就覆盖数量，否则插入。
     */
    void upsert(String username, long productId, int quantity) {
        jdbc.sql("INSERT INTO mall_cart_item(username, product_id, quantity, updated_at) " +
                 "VALUES(:user, :pid, :qty, CURRENT_TIMESTAMP) " +
                 "ON DUPLICATE KEY UPDATE quantity = :qty, updated_at = CURRENT_TIMESTAMP")
                .param("user", username).param("pid", productId).param("qty", quantity).update();
    }

    int delete(String username, long productId) {
        return jdbc.sql("DELETE FROM mall_cart_item WHERE username = :user AND product_id = :pid")
                .param("user", username).param("pid", productId).update();
    }

    int clear(String username) {
        return jdbc.sql("DELETE FROM mall_cart_item WHERE username = :user")
                .param("user", username).update();
    }

    int countOf(String username) {
        return jdbc.sql("SELECT COUNT(*) FROM mall_cart_item WHERE username = :user")
                .param("user", username).query(Integer.class).single();
    }
}
