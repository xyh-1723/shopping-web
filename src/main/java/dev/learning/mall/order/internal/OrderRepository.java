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
    Optional<OrderRow> lock(String id) {
        return jdbc.sql("SELECT * FROM mall_order WHERE id = :id FOR UPDATE")
                .param("id", id).query(OrderRow.class).optional();
    }

    /**
     * 取出一行并加行锁，用于关单。
     *
     * <p>这里<b>不</b>限定用户：超时关闭是系统行为，不属于任何一次用户请求。
     * 权限检查只发生在用户发起的路径上（{@link #find(String, String, boolean)}），
     * 不能反过来让「系统想关掉一笔超时订单」也要先知道是谁在操作。
     *
     * <p>比 {@link #lock(String)} 多了 {@code SKIP LOCKED}：拿不到锁就<b>立刻返回空</b>，
     * 而不是排队等。谁在锁着这行？多半是另一个实例正在关它（分片已经尽量避免这种情况，
     * 这是第二道保险），也可能是买家正在付款。两种情况下等下去都没有意义——
     * 等到了也只是发现状态已经变了。跳过之后这一笔下一轮还会被选中，不会丢。
     *
     * <p>返回空因此有两种含义（行不存在 / 行被别人锁着），调用方一律当作
     * 「这一轮不归我处理」，与「已经不是 PENDING」是同一种结果。
     */
    Optional<OrderRow> lockForClose(String id) {
        return jdbc.sql("SELECT * FROM mall_order WHERE id = :id FOR UPDATE SKIP LOCKED")
                .param("id", id).query(OrderRow.class).optional();
    }
    List<OrderRow> list(String user) {
        return jdbc.sql("SELECT * FROM mall_order WHERE username = :user ORDER BY created_at DESC, id LIMIT 50")
                .param("user", user).query(OrderRow.class).list();
    }
    /**
     * 到期的待支付订单，<b>只取本实例负责的那个分片</b>。
     *
     * <p>被处理掉的订单会离开这个结果集（状态不再是 PENDING），所以队列天然收敛：
     * 不需要游标，也不会出现「排在后面的永远扫不到」。
     *
     * <p>分片条件写进 WHERE 而不是捞回来再在 Java 里过滤：前者让数据库只扫自己的那一份，
     * 后者每一轮都把全表的到期订单捞出来再丢掉 (N-1)/N，实例越多浪费越大——
     * 而这正是分片本来要解决的问题。
     *
     * @param shardCount 分片总数；1 表示不分片（单实例部署，行为与分片前一致）
     * @param shardIndex 本实例负责的分片序号，取值 {@code [0, shardCount)}
     */
    List<String> expiredPending(LocalDateTime now, int limit, int shardCount, int shardIndex) {
        return jdbc.sql("""
                        SELECT id FROM mall_order
                         WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= :now
                           AND MOD(expiry_shard, :shardCount) = :shardIndex
                         ORDER BY expires_at, id
                         LIMIT :limit""")
                .param("now", now).param("limit", limit)
                .param("shardCount", shardCount).param("shardIndex", shardIndex)
                .query(String.class).list();
    }

    /**
     * 本实例负责的分片里，还有多少笔待支付订单已经到期但尚未处理：积压量。
     *
     * <p>与 {@link #expiredPending} 用<b>同一套分片条件</b>：积压量要回答的是
     * 「我这台机器跟不跟得上」，不是「全局还剩多少」。用全局数字会让一个什么都不干的实例
     * 报出别人的积压，排障时把人引到错误的机器上。
     */
    int expiredBacklog(LocalDateTime now, int shardCount, int shardIndex) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM mall_order
                         WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at <= :now
                           AND MOD(expiry_shard, :shardCount) = :shardIndex""")
                .param("now", now)
                .param("shardCount", shardCount).param("shardIndex", shardIndex)
                .query(Integer.class).single();
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
        // 分片键在这里由订单号算出，不交给调用方传：它是订单号的纯函数，
        // 多一个入口传值就多一处「两个实例算出不同结果」的机会，而那会直接导致
        // 某些订单没有任何实例负责扫描。
        int expiryShard = OrderShard.of(id);
        jdbc.sql("""
                        INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,
                                               subtotal,discount,shipping_fee,pay_amount,status,expires_at,expiry_shard)
                        VALUES(:id,:user,:key,:hash,:pay,:subtotal,:discount,:shippingFee,:pay,'PENDING',:expiresAt,:shard)""")
                .param("id", id).param("user", user).param("key", key).param("hash", hash)
                .param("pay", payAmount).param("subtotal", subtotal).param("discount", discount)
                .param("shippingFee", shippingFee).param("expiresAt", expiresAt)
                .param("shard", expiryShard).update();
    }
    /**
     * 落一条明细。<b>{@code merchant_id} 在这一刻从商品带下来</b>，不交给调用方传。
     *
     * <p>为什么要在这里拷贝一份而不是每次 JOIN product 去查：明细上的商家是
     * <b>下单那一刻</b>的归属，而商品以后可能改挂到别的店（换供应商、店铺转让）。
     * 现查会让历史订单的归属跟着漂移，于是「这笔退款当年是哪家店的锅」永远查不清。
     *
     * <p>为什么不放在 Java 里先查一次再传进来：多一次往返就多一处
     * 「两个值不一致」的机会，而这个值必须是同一条 INSERT 读到的那个快照。
     */
    void insertItem(String id, OrderView.Item item) {
        jdbc.sql("INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,merchant_id)"
                        + " VALUES(:id,:pid,:name,:price,:qty,"
                        + "        (SELECT merchant_id FROM product WHERE id = :pid))")
                .param("id", id).param("pid", item.productId()).param("name", item.productName())
                .param("price", item.unitPrice()).param("qty", item.quantity()).update();
    }
    List<OrderView.Item> items(String id) {
        return jdbc.sql("SELECT product_id,product_name,unit_price,quantity FROM order_item WHERE order_id=:id ORDER BY product_id")
                .param("id", id).query(OrderView.Item.class).list();
    }
    /**
     * 返回影响行数：1 表示这次支付真的发生了。
     *
     * <p>{@code paidAt} 由调用方传入而不是写 {@code CURRENT_TIMESTAMP}：与
     * {@link #markClosed(String, String, LocalDateTime)} 同一个形状——时间可注入，
     * 测试用可控 {@code Clock} 就能断言「退款窗口从哪个时刻起算」，不用等真实时间。
     *
     * <p>这一列是<b>被数据库逼着写的</b>：V15 的 {@code ck_order_paid_needs_moment}
     * 要求 {@code status='PAID'} 的行必须有 {@code paid_at}，漏写就是支付当场被打回。
     */
    int markPaid(String id, String method, String receipt, LocalDateTime paidAt) {
        return jdbc.sql("UPDATE mall_order SET status='PAID',payment_method=:method,payment_receipt=:receipt,"
                        + "paid_at=:paidAt WHERE id=:id AND status='PENDING'")
                .param("id", id).param("method", method).param("receipt", receipt)
                .param("paidAt", paidAt).update();
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

    // ——————————————————— 履约：四条（连同自动收货共五条）状态迁移 ———————————————————

    /**
     * 发货：把履约状态从 {@code NONE} 推到 {@code SHIPPED}（§2.6 第一式）。
     *
     * <p><b>前置条件写死在 WHERE 里</b>，与 {@link #markClosed} 同一套写法：
     * 影响 1 行 = 「这次状态转换是我完成的」，于是写 {@code shipped_at}、发
     * {@code ORDER_SHIPPED} 也是我的责任；影响 0 行 = 别人已经处理过，调用方安静跳过。
     * {@code status='PAID'} 这一半是「钱到账才配货」的落点——越过它的代价是
     * {@code ck_order_fulfillment_needs_paid} 把这次发货当场打回。
     *
     * <p>{@code autoReceiveAt} 由调用方算好传入（= 发货时刻 + 配置的自动收货天数），
     * 在发货这一刻落库：与 {@code expiresAt} 同一个道理——运营事后把 10 天改成 7 天，
     * 不该让已经在这批路上的订单一起跳变。
     */
    int markShipped(String id, String staff, String shippingNo, LocalDateTime shippedAt, LocalDateTime autoReceiveAt) {
        return jdbc.sql("UPDATE mall_order SET fulfillment_status='SHIPPED',shipped_at=:now,shipped_by=:staff,"
                        + "shipping_no=:no,auto_receive_at=:auto"
                        + " WHERE id=:id AND status='PAID' AND fulfillment_status='NONE'")
                .param("id", id).param("staff", staff).param("no", shippingNo)
                .param("now", shippedAt).param("auto", autoReceiveAt).update();
    }

    /**
     * 买家确认收货：{@code SHIPPED → RECEIVED}（§2.6 第二式）。
     *
     * <p>{@code username = :user} 是<b>归属校验</b>，不是可选装饰：没有它，任何人都能替别人
     * 点掉「确认收货」，而这一步直接决定退款窗口与售后起算点。去掉它以后，别人的订单
     * 依然能被这条 UPDATE 命中（影响 1 行），所以这条校验只能在 WHERE 里，不能挪到
     * 调用方的 if 里——挪到 if 里，调用方漏判一次就全线失守。
     */
    int markReceived(String id, String user, LocalDateTime receivedAt) {
        return jdbc.sql("UPDATE mall_order SET fulfillment_status='RECEIVED',received_at=:now"
                        + " WHERE id=:id AND username=:user AND fulfillment_status='SHIPPED'")
                .param("id", id).param("user", user).param("now", receivedAt).update();
    }

    /** 买家确认完成：{@code RECEIVED → COMPLETED}（§2.6 第三式），同样带归属校验。 */
    int markCompleted(String id, String user, LocalDateTime completedAt) {
        return jdbc.sql("UPDATE mall_order SET fulfillment_status='COMPLETED',completed_at=:now"
                        + " WHERE id=:id AND username=:user AND fulfillment_status='RECEIVED'")
                .param("id", id).param("user", user).param("now", completedAt).update();
    }

    /**
     * 自动收货第一步：{@code SHIPPED → RECEIVED}（§2.3 状态图要求、§2.6 语句模板漏掉的那一条）。
     *
     * <p>系统路径<b>不带</b> username，与 {@link #lock(String)} 同一个道理：自动收货不是
     * 任何一次用户请求，让它先知道「是谁在操作」是没有意义的。
     *
     * <p>{@code auto_receive_at <= :due} 在这里是<b>第二道</b>到期校验（第一道在
     * {@link #dueForAutoReceive}）：候选集挑出来之后到拿到行锁之间，买家可能已经自己收货、
     * 也可能有人改了调度时刻；规则不该依赖「候选集一定没挑错」。
     */
    int markAutoReceived(String id, LocalDateTime receivedAt, LocalDateTime due) {
        return jdbc.sql("UPDATE mall_order SET fulfillment_status='RECEIVED',received_at=:now"
                        + " WHERE id=:id AND fulfillment_status='SHIPPED' AND auto_receive_at<=:due")
                .param("id", id).param("now", receivedAt).param("due", due).update();
    }

    /**
     * 自动收货第二步：{@code RECEIVED → COMPLETED}（§2.6 第四式原样保留）。
     *
     * <p>它<b>不能</b>与 {@link #markAutoReceived} 合成一条 {@code SHIPPED → COMPLETED}：
     * 那样写出来 {@code received_at} 仍是 NULL，而 {@code COMPLETED} 要求三个时刻齐全，
     * {@code ck_order_fulfillment_fields} 会把整条语句打回——「必须两步」是数据库逼出来的，
     * 不是排版偏好。见 {@code FulfillmentFlowTest#skipReceivedCannotReachCompleted}。
     */
    int markAutoCompleted(String id, LocalDateTime completedAt, LocalDateTime due) {
        return jdbc.sql("UPDATE mall_order SET fulfillment_status='COMPLETED',completed_at=:now"
                        + " WHERE id=:id AND fulfillment_status='RECEIVED' AND auto_receive_at<=:due")
                .param("id", id).param("now", completedAt).param("due", due).update();
    }

    /**
     * 到期该被自动收货的订单：{@code SHIPPED}（等着自动确认收货）与 {@code RECEIVED}（等着自动完成）。
     *
     * <p>{@code auto_receive_at IS NOT NULL} 是刻意的：没有到期时刻的订单永远不该被自动推进，
     * 与 {@link #expiredPending} 里 {@code expires_at IS NOT NULL} 同一个道理——
     * 「没有截止时间」不等于「早于任何时刻」。
     */
    List<String> dueForAutoReceive(LocalDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM mall_order
                         WHERE fulfillment_status IN ('SHIPPED','RECEIVED')
                           AND auto_receive_at IS NOT NULL AND auto_receive_at <= :now
                         ORDER BY auto_receive_at, id
                         LIMIT :limit""")
                .param("now", now).param("limit", limit).query(String.class).list();
    }
    OrderView view(OrderRow row) {
        return new OrderView(row.id(), row.status(), row.total(), row.subtotal(), row.discount(),
                row.shippingFee(), row.payAmount(), row.paymentMethod(), row.paymentReceipt(),
                row.createdAt(), items(row.id()), row.expiresAt(), row.closeReason(), row.closedAt(),
                row.paidAt(), row.fulfillmentStatus(), row.shippedAt(), row.shippingNo(),
                row.receivedAt(), row.completedAt());
    }
}
