package dev.learning.mall.order.internal;

import dev.learning.mall.order.api.OrderView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcClient jdbc;
    OrderRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    Optional<OrderRow> byKey(String user, String key) {
        return jdbc.sql("SELECT * FROM mall_order WHERE username = :user AND idempotency_key = :key")
                .param("user", user).param("key", key).query(OrderRow.class).optional();
    }
    Optional<OrderRow> find(String id, String user, boolean lock) {
        return jdbc.sql("SELECT * FROM mall_order WHERE id = :id AND username = :user" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("user", user).query(OrderRow.class).optional();
    }
    /**
     * 取出一行并加行锁，用于关单。
     *
     * <p>这里<b>不</b>限定用户：超时关闭是系统行为，不属于任何一次用户请求。
     * 权限检查只发生在用户发起的路径上（{@link #find(String, String, boolean)}），
     * 不能反过来让「系统想关掉一笔超时订单」也要先知道是谁在操作。
     */
    Optional<OrderRow> lock(String id) {
        return jdbc.sql("SELECT * FROM mall_order WHERE id = :id FOR UPDATE")
                .param("id", id).query(OrderRow.class).optional();
    }
    List<OrderRow> list(String user) {
        return jdbc.sql("SELECT * FROM mall_order WHERE username = :user ORDER BY created_at DESC, id LIMIT 50")
                .param("user", user).query(OrderRow.class).list();
    }
    /**
     * 到期的待支付订单。
     *
     * <p>被处理掉的订单会离开这个结果集（状态不再是 PENDING），所以队列天然收敛：
     * 不需要游标，也不会出现「排在后面的永远扫不到」。
     */
    List<String> expiredPending(LocalDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM mall_order
                         WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= :now
                         ORDER BY expires_at, id
                         LIMIT :limit""")
                .param("now", now).param("limit", limit).query(String.class).list();
    }
    /** 还有多少笔待支付订单已经到期但尚未处理：积压量，用来判断扫描跟不跟得上。 */
    int expiredBacklog(LocalDateTime now) {
        return jdbc.sql("SELECT COUNT(*) FROM mall_order WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= :now")
                .param("now", now).query(Integer.class).single();
    }
    /**
     * 落一笔订单。
     *
     * <p>{@code total} 与 {@code payAmount} 在这里用<b>同一个值</b>写入，写后不可变——
     * 「历史列 = 应付」这条不变量因此不可能漂移，也就能被 SQL 断言直接检查。
     * 这是唯一一处写这两列的地方，别在别处补写。
     */
    public void insert(String id, String user, String key, String hash, BigDecimal subtotal, BigDecimal discount,
                BigDecimal shippingFee, BigDecimal payAmount, LocalDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,
                                               subtotal,discount,shipping_fee,pay_amount,status,expires_at)
                        VALUES(:id,:user,:key,:hash,:pay,:subtotal,:discount,:shippingFee,:pay,'PENDING',:expiresAt)""")
                .param("id", id).param("user", user).param("key", key).param("hash", hash)
                .param("pay", payAmount).param("subtotal", subtotal).param("discount", discount)
                .param("shippingFee", shippingFee).param("expiresAt", expiresAt).update();
    }
    void insertItem(String id, OrderView.Item item) {
        jdbc.sql("INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity) VALUES(:id,:pid,:name,:price,:qty)")
                .param("id", id).param("pid", item.productId()).param("name", item.productName())
                .param("price", item.unitPrice()).param("qty", item.quantity()).update();
    }
    List<OrderView.Item> items(String id) {
        return jdbc.sql("SELECT product_id,product_name,unit_price,quantity FROM order_item WHERE order_id=:id ORDER BY product_id")
                .param("id", id).query(OrderView.Item.class).list();
    }
    /** 返回影响行数：1 表示这次支付真的发生了。 */
    int markPaid(String id, String method, String receipt) {
        return jdbc.sql("UPDATE mall_order SET status='PAID',payment_method=:method,payment_receipt=:receipt WHERE id=:id AND status='PENDING'")
                .param("id", id).param("method", method).param("receipt", receipt).update();
    }
    /**
     * 关单。<b>带前置条件</b>是这里的关键：只有 {@code status='PENDING'} 的行会被更新。
     *
     * <p>返回 1 才代表「这次状态转换是我完成的」，调用方凭这一点决定是否归还库存；
     * 返回 0 说明别人已经处理过（刚支付 / 已取消 / 已被另一轮扫描关闭），
     * 调用方应当安静跳过，而不是报错或重复归还。幂等就来自这里。
     */
    int markClosed(String id, String reason, LocalDateTime closedAt) {
        return jdbc.sql("UPDATE mall_order SET status='CANCELLED',close_reason=:reason,closed_at=:closedAt WHERE id=:id AND status='PENDING'")
                .param("id", id).param("reason", reason).param("closedAt", closedAt).update();
    }
    OrderView view(OrderRow row) {
        return new OrderView(row.id(), row.status(), row.total(), row.subtotal(), row.discount(),
                row.shippingFee(), row.payAmount(), row.paymentMethod(), row.paymentReceipt(),
                row.createdAt(), items(row.id()), row.expiresAt(), row.closeReason(), row.closedAt());
    }
}
