package dev.learning.mall.order.internal;

import dev.learning.mall.order.api.OrderView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcClient jdbc;
    /** 子订单：父单的视图必须带上它们，所以这里直接持有——不经过 Service，避免循环依赖。 */
    private final OrderSubRepository subs;
    OrderRepository(JdbcClient jdbc, OrderSubRepository subs) { this.jdbc = jdbc; this.subs = subs; }
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
                BigDecimal shippingFee, BigDecimal payAmount, BigDecimal couponDiscount,
                String couponId, String couponCode, LocalDateTime expiresAt) {
        // 分片键在这里由订单号算出，不交给调用方传：它是订单号的纯函数，
        // 多一个入口传值就多一处「两个实例算出不同结果」的机会，而那会直接导致
        // 某些订单没有任何实例负责扫描。
        int expiryShard = OrderShard.of(id);
        jdbc.sql("""
                        INSERT INTO mall_order(id,username,idempotency_key,request_hash,total,
                                               subtotal,discount,shipping_fee,pay_amount,
                                               coupon_discount,coupon_id,coupon_code,
                                               status,expires_at,expiry_shard)
                        VALUES(:id,:user,:key,:hash,:pay,:subtotal,:discount,:shippingFee,:pay,
                               :couponDiscount,:couponId,:couponCode,
                               'PENDING',:expiresAt,:shard)""")
                .param("id", id).param("user", user).param("key", key).param("hash", hash)
                .param("pay", payAmount).param("subtotal", subtotal).param("discount", discount)
                .param("shippingFee", shippingFee).param("couponDiscount", couponDiscount)
                .param("couponId", couponId).param("couponCode", couponCode)
                .param("expiresAt", expiresAt)
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
     * 某一家店在这笔订单里的明细。
     *
     * <p>退款还货必须用它而不是 {@link #items(String)}：跨店订单退的是<b>那一家店</b>的钱，
     * 顺手把别家的货也还回去，等于替另一家店做了一次「已退款」——
     * 而那家店此刻可能正在给买家发货。
     */
    List<OrderView.Item> itemsOfMerchant(String id, long merchantId) {
        return jdbc.sql("SELECT product_id,product_name,unit_price,quantity FROM order_item"
                        + " WHERE order_id=:id AND merchant_id=:m ORDER BY product_id")
                .param("id", id).param("m", merchantId).query(OrderView.Item.class).list();
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

    // ——————————————————— 视图 ———————————————————

    /**
     * 父单行 → 对外视图（含全部子订单）。
     *
     * <p>子订单在这里拼进来而不是让每个调用方自己再查一次：
     * 「一笔订单是什么样子」只有一处定义，于是「前端看到的」与「助手看到的」
     * 不可能是两个不同的订单。
     *
     * <p><b>单个订单用它；列表请用 {@link #views(List)}。</b>这个方法每调一次至少 3 条 SQL
     * （明细 + 子订单 + 每个子订单的明细），套在循环里就是典型的 N+1。
     */
    OrderView view(OrderRow row) {
        return view(row, items(row.id()), subs.viewsOf(row.id()));
    }

    /**
     * 一批父单 → 对外视图。<b>固定 3 条 SQL，与订单数量无关。</b>
     *
     * <p>为什么必须批量：列表页一次要 20 单，逐单 {@link #view(OrderRow)} 就是
     * 1 + 20×(1 明细 + 1 子订单 + N 子单明细) ≈ 60 条 SQL，实测 p50 从 6ms 涨到 50ms。
     * 而订单列表不只一个入口——助手的 {@code CustomerTools}、{@code StaffTools}、
     * 三种待确认动作、推荐服务都走这条链，慢一次就是慢一圈。
     *
     * <p><b>结果与逐单调用完全一致</b>是这里唯一的不变量：批量只是把往返合并，
     * 不改变任何字段、也不改变顺序。
     */
    List<OrderView> views(List<OrderRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(OrderRow::id).toList();
        Map<String, List<OrderView.Item>> itemsByOrder = itemsOfOrders(ids);
        Map<String, List<OrderView.SubView>> subsByOrder = subs.viewsOfOrders(ids);
        return rows.stream()
                .map(row -> view(row,
                        itemsByOrder.getOrDefault(row.id(), List.of()),
                        subsByOrder.getOrDefault(row.id(), List.of())))
                .toList();
    }

    private OrderView view(OrderRow row, List<OrderView.Item> items, List<OrderView.SubView> subs) {
        return new OrderView(row.id(), row.status(), row.total(), row.subtotal(), row.discount(),
                row.shippingFee(), row.payAmount(), row.couponDiscount(), row.couponCode(),
                row.paymentMethod(), row.paymentReceipt(),
                row.createdAt(), items, subs, row.expiresAt(),
                row.closeReason(), row.closedAt(), row.paidAt());
    }

    /**
     * 一批父单的全部明细，按父单分桶。带上 {@code order_id} 才能分桶，
     * 所以先落到一个中间行 {@link ScopedOrderItem}，再翻译成对外的 {@link OrderView.Item}。
     */
    private Map<String, List<OrderView.Item>> itemsOfOrders(List<String> ids) {
        return jdbc.sql("SELECT order_id,product_id,product_name,unit_price,quantity FROM order_item"
                        + " WHERE order_id IN (:ids) ORDER BY order_id,product_id")
                .param("ids", ids).query(ScopedOrderItem.class).list().stream()
                .collect(Collectors.groupingBy(ScopedOrderItem::orderId,
                        Collectors.mapping(item -> new OrderView.Item(item.productId(),
                                item.productName(), item.unitPrice(), item.quantity()),
                                Collectors.toList())));
    }

    /** 明细行 + 它属于哪个父单。只用于批量装配。 */
    private record ScopedOrderItem(String orderId, long productId, String productName,
                                   BigDecimal unitPrice, int quantity) {}
}
