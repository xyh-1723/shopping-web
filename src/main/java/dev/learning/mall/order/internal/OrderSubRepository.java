package dev.learning.mall.order.internal;

import dev.learning.mall.order.api.OrderView;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code order_sub} 子订单表与 {@link OrderSubRow} 之间的唯一通道。
 *
 * <p>包可见，理由与 {@link RefundRepository} 相同：子订单的状态迁移与「父单有没有付钱」
 * 绑在一起，只有 {@link FulfillmentService} 知道这个顺序。暴露出去等于邀请调用方
 * 自己把 {@code fulfillment_status} 改成 {@code COMPLETED}。
 *
 * <h3>「父单必须已支付」这条规则现在写在哪</h3>
 *
 * <p>V15 时它是 {@code mall_order} 上的一条 {@code ck_order_fulfillment_needs_paid}。
 * 履约下移到子订单之后，这条规则<b>跨表</b>了（子订单不知道父单的 {@code status}），
 * 而 SQL 里没有跨表 CHECK。它因此搬进了每一条写语句的 WHERE：
 * <pre>
 *   AND EXISTS (SELECT 1 FROM mall_order o
 *                WHERE o.id = order_sub.order_id AND o.status = 'PAID')
 * </pre>
 * 判定权仍然在数据库——谁把这一半从 WHERE 里挪走，谁就能给没付钱的订单发货。
 * 代价是「钱到账才配货」不再出现在约束清单里，而是散落在几条 UPDATE 上；
 * 这是跨表条件的必然结果，不是偷懒（V25 文件头记了这条实测结论）。
 *
 * <h3>五条写语句全部带前置条件</h3>
 *
 * <p>与 {@link OrderRepository#markClosed} 同一套写法：影响 1 行 = 「这次状态转换是我完成的」，
 * 0 行 = 别人已经处理过。自动收货仍然是<b>两步</b>（先收货、再完成），
 * 合一条会让 {@code received_at} 留空而被 {@code ck_sub_fulfillment_fields} 打回。
 */
@Repository
class OrderSubRepository {

    /**
     * 店名是<b>查出来的展示值</b>，不是子订单自己的列——子订单只存 {@code merchant_id}。
     * 少了这一句 JOIN，前端只能显示「商家 #37」，而那是给人看的界面最不该出现的东西。
     */
    private static final String SELECT = "SELECT s.*, m.name AS merchant_name"
            + " FROM order_sub s JOIN merchant m ON m.id = s.merchant_id ";

    private final JdbcClient jdbc;

    OrderSubRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<OrderSubRow> ofOrder(String orderId) {
        return jdbc.sql(SELECT + "WHERE s.order_id = :o ORDER BY s.sub_seq")
                .param("o", orderId).query(OrderSubRow.class).list();
    }

    Optional<OrderSubRow> find(String subId) {
        return jdbc.sql(SELECT + "WHERE s.id = :id").param("id", subId).query(OrderSubRow.class).optional();
    }

    /**
     * 取一行并加行锁。
     *
     * <p>与 {@link OrderRepository#lock} 同一个道理：不加 {@code username} 限定，
     * 因为发货不是买家动作；而退款要先拿到子订单与父单两个行锁，
     * 「累计已退金额」的 SUM 才有串行点。
     */
    Optional<OrderSubRow> lock(String subId) {
        return jdbc.sql(SELECT + "WHERE s.id = :id FOR UPDATE")
                .param("id", subId).query(OrderSubRow.class).optional();
    }

    /**
     * 这家店是不是这个子订单的主人——<b>商家发货与退款审批的归属校验</b>都落在这里。
     *
     * <p>3a 之前它是「订单里有没有这家店的商品」（{@code order_item.merchant_id}），
     * 那时一笔跨店订单会被多家店同时判成「有我一份」。挂上子订单之后，
     * 一笔退款在结构上只可能属于一个子订单，「A 批了 B 的钱」这句话第一次无法被表达。
     */
    boolean belongsToMerchant(String subId, long merchantId) {
        Long n = jdbc.sql("SELECT COUNT(*) FROM order_sub WHERE id = :s AND merchant_id = :m")
                .param("s", subId).param("m", merchantId).query(Long.class).single();
        return n != null && n > 0;
    }

    /**
     * 落一个子订单。
     *
     * <p>{@code id} 由调用方给出（= 父单号 + {@code -S} + 序号），不在这里生成：
     * 它是 (order_id, sub_seq) 的纯函数，交给数据库自增就要多一趟「取回主键」的往返，
     * 而那一趟往返本身就是「两个值对不上」的机会。
     */
    void insert(String id, String orderId, int seq, long merchantId, BigDecimal subtotal,
                BigDecimal discount, BigDecimal shippingFee, BigDecimal payAmount,
                BigDecimal couponDiscount, BigDecimal merchantDiscount, BigDecimal platformSubsidy,
                BigDecimal platformFee, BigDecimal merchantIncome, BigDecimal platformIncome) {
        jdbc.sql("INSERT INTO order_sub(id,order_id,sub_seq,merchant_id,"
                        + "subtotal,discount,shipping_fee,pay_amount,"
                        + "coupon_discount,merchant_discount,platform_subsidy,"
                        + "platform_fee,merchant_income,platform_income)"
                        + " VALUES(:id,:order,:seq,:m,:subtotal,:discount,:shippingFee,:pay,"
                        + "        :couponDiscount,:merchantDiscount,:platformSubsidy,"
                        + "        :platformFee,:merchantIncome,:platformIncome)")
                .param("id", id).param("order", orderId).param("seq", seq).param("m", merchantId)
                .param("subtotal", subtotal).param("discount", discount)
                .param("shippingFee", shippingFee).param("pay", payAmount)
                .param("couponDiscount", couponDiscount).param("merchantDiscount", merchantDiscount)
                .param("platformSubsidy", platformSubsidy).param("platformFee", platformFee)
                .param("merchantIncome", merchantIncome).param("platformIncome", platformIncome)
                .update();
    }

    /** 这家店在这笔订单里的明细：退款还货时只还这一家店的货，不能顺手把别家的也还了。 */
    List<OrderView.Item> itemsOf(String orderId, long merchantId) {
        return jdbc.sql("SELECT product_id,product_name,unit_price,quantity FROM order_item"
                        + " WHERE order_id = :o AND merchant_id = :m ORDER BY product_id")
                .param("o", orderId).param("m", merchantId).query(OrderView.Item.class).list();
    }

    // ——————————————————— 批量读取（消除 N+1） ———————————————————

    /**
     * 一批父单的全部子订单行。
     *
     * <p>{@code ORDER BY s.order_id, s.sub_seq} 里的 {@code order_id} 不是给人看的排序，
     * 是为了让同一父单的行在结果里连续出现——{@code groupingBy} 之后每个桶内的
     * 顺序仍然等于 {@code sub_seq}，前端看到的「第几家店」不会因为批量读取而打乱。
     */
    List<OrderSubRow> ofOrders(List<String> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.sql(SELECT + "WHERE s.order_id IN (:ids) ORDER BY s.order_id, s.sub_seq")
                .param("ids", orderIds).query(OrderSubRow.class).list();
    }

    /**
     * 一批父单的全部明细行，带上{@code order_id} 与 {@code merchant_id} 供内存分桶。
     *
     * <p>为什么不复用 {@link #itemsOf(String, long)}：那个方法按「父单 + 店」一次取一家，
     * 批量场景下每单每店都要往返一次，正是要消掉的那种放大。
     */
    List<ScopedOrderItem> itemsOfOrders(List<String> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT order_id, merchant_id, product_id, product_name, unit_price, quantity"
                        + " FROM order_item WHERE order_id IN (:ids) ORDER BY order_id, product_id")
                .param("ids", orderIds).query(ScopedOrderItem.class).list();
    }

    /** 明细行 + 它属于哪个父单、哪家店。只用于批量装配，不对外暴露。 */
    record ScopedOrderItem(String orderId, long merchantId, long productId, String productName,
                           BigDecimal unitPrice, int quantity) {}

    // ——————————————————— 履约：四条（连同自动收货共五条）状态迁移 ———————————————————

    /**
     * 发货：{@code NONE → SHIPPED}。
     *
     * <p>前置条件里那一半 {@code EXISTS (… o.status = 'PAID')} 就是「钱到账才配货」；
     * 另一半 {@code fulfillment_status = 'NONE'} 保证不会重复发货。
     * {@code autoReceiveAt} 由调用方算好传入，在发货这一刻落库——运营事后改配置，
     * 不该让已经在路上的这批货一起跳变。
     */
    int markShipped(String subId, String staff, String shippingNo, LocalDateTime shippedAt,
                    LocalDateTime autoReceiveAt) {
        return jdbc.sql("UPDATE order_sub SET fulfillment_status='SHIPPED',shipped_at=:now,"
                        + "shipped_by=:staff,shipping_no=:no,auto_receive_at=:auto"
                        + " WHERE id=:id AND fulfillment_status='NONE'"
                        + " AND EXISTS (SELECT 1 FROM mall_order o"
                        + "              WHERE o.id = order_sub.order_id AND o.status='PAID')")
                .param("id", subId).param("staff", staff).param("no", shippingNo)
                .param("now", shippedAt).param("auto", autoReceiveAt).update();
    }

    /**
     * 买家确认收货：{@code SHIPPED → RECEIVED}。
     *
     * <p>归属校验在这一条 UPDATE 的 EXISTS 里（父单的 {@code username}）：
     * 没有它，任何人都能替别人点掉「确认收货」，而这一步直接决定退款窗口的起算点。
     * 挪到调用方的 if 里就失守一次——判定必须只发生在 SQL 上。
     */
    int markReceived(String subId, String user, LocalDateTime receivedAt) {
        return jdbc.sql("UPDATE order_sub SET fulfillment_status='RECEIVED',received_at=:now"
                        + " WHERE id=:id AND fulfillment_status='SHIPPED'"
                        + " AND EXISTS (SELECT 1 FROM mall_order o"
                        + "              WHERE o.id = order_sub.order_id AND o.username=:user)")
                .param("id", subId).param("user", user).param("now", receivedAt).update();
    }

    /** 买家确认完成：{@code RECEIVED → COMPLETED}，同样带归属校验。 */
    int markCompleted(String subId, String user, LocalDateTime completedAt) {
        return jdbc.sql("UPDATE order_sub SET fulfillment_status='COMPLETED',completed_at=:now"
                        + " WHERE id=:id AND fulfillment_status='RECEIVED'"
                        + " AND EXISTS (SELECT 1 FROM mall_order o"
                        + "              WHERE o.id = order_sub.order_id AND o.username=:user)")
                .param("id", subId).param("user", user).param("now", completedAt).update();
    }

    /**
     * 自动收货第一步：{@code SHIPPED → RECEIVED}。系统路径不带用户名。
     *
     * <p>{@code auto_receive_at <= :due} 是<b>第二道</b>到期校验：从挑出候选到拿到行锁之间，
     * 买家可能已经自己点了收货，规则不该依赖「候选集一定没挑错」。
     */
    int markAutoReceived(String subId, LocalDateTime receivedAt, LocalDateTime due) {
        return jdbc.sql("UPDATE order_sub SET fulfillment_status='RECEIVED',received_at=:now"
                        + " WHERE id=:id AND fulfillment_status='SHIPPED' AND auto_receive_at<=:due")
                .param("id", subId).param("now", receivedAt).param("due", due).update();
    }

    /**
     * 自动收货第二步：{@code RECEIVED → COMPLETED}。
     *
     * <p>它<b>不能</b>与 {@link #markAutoReceived} 合成一条 {@code SHIPPED → COMPLETED}：
     * 那样 {@code received_at} 仍是 NULL，而 {@code COMPLETED} 要求三个时刻齐全，
     * {@code ck_sub_fulfillment_fields} 会把整条语句打回——「必须两步」是数据库逼出来的。
     */
    int markAutoCompleted(String subId, LocalDateTime completedAt, LocalDateTime due) {
        return jdbc.sql("UPDATE order_sub SET fulfillment_status='COMPLETED',completed_at=:now"
                        + " WHERE id=:id AND fulfillment_status='RECEIVED' AND auto_receive_at<=:due")
                .param("id", subId).param("now", completedAt).param("due", due).update();
    }

    /**
     * 到期该被自动收货的子订单：{@code SHIPPED}（等着自动收货）与 {@code RECEIVED}（等着自动完成）。
     *
     * <p>与父单的 {@link OrderRepository#expiredPending} 不同，这里<b>没有分片</b>：
     * 在途包裹的量级远小于「待支付订单」，而且自动收货晚一个扫描间隔没有体验代价。
     * 真到了要分片那天，加 {@code expiry_shard} 那一套即可——但那属于阶段 9 的活，
     * 现在不做，也不假装做了。
     */
    List<String> dueForAutoReceive(LocalDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM order_sub
                         WHERE fulfillment_status IN ('SHIPPED','RECEIVED')
                           AND auto_receive_at IS NOT NULL AND auto_receive_at <= :now
                         ORDER BY auto_receive_at, id
                         LIMIT :limit""")
                .param("now", now).param("limit", limit).query(String.class).list();
    }

    // ——————————————————— 视图 ———————————————————

    /** 这笔订单的全部子订单视图（含各自那家店的明细）。 */
    List<OrderView.SubView> viewsOf(String orderId) {
        return ofOrder(orderId).stream()
                .map(sub -> view(sub, itemsOf(orderId, sub.merchantId())))
                .toList();
    }

    /**
     * 一批父单的子订单视图，{@code key = 父单号}。<b>固定 2 条 SQL，与订单数量无关。</b>
     *
     * <p>这是 {@link #viewsOf(String)} 的批量版本，结果必须与逐单调用完全一致——
     * 包括子订单顺序（{@code sub_seq}）与明细顺序（{@code product_id}），
     * 否则「列表页看到的」和「详情页看到的」会是两个不同的订单。
     */
    Map<String, List<OrderView.SubView>> viewsOfOrders(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        Map<String, List<OrderSubRow>> subRows = ofOrders(orderIds).stream()
                .collect(Collectors.groupingBy(OrderSubRow::orderId));
        Map<String, List<ScopedOrderItem>> itemRows = itemsOfOrders(orderIds).stream()
                .collect(Collectors.groupingBy(ScopedOrderItem::orderId));
        Map<String, List<OrderView.SubView>> result = new HashMap<>();
        subRows.forEach((orderId, subs) -> {
            List<ScopedOrderItem> all = itemRows.getOrDefault(orderId, List.of());
            result.put(orderId, subs.stream().map(sub -> {
                List<OrderView.Item> mine = all.stream()
                        .filter(item -> item.merchantId() == sub.merchantId())
                        .map(item -> new OrderView.Item(item.productId(), item.productName(),
                                item.unitPrice(), item.quantity()))
                        .toList();
                return view(sub, mine);
            }).toList());
        });
        return result;
    }

    OrderView.SubView view(OrderSubRow sub, List<OrderView.Item> items) {
        return new OrderView.SubView(sub.id(), sub.subSeq(), sub.merchantId(), sub.merchantName(),
                sub.subtotal(), sub.discount(), sub.shippingFee(), sub.payAmount(),
                sub.fulfillmentStatus(), sub.shippedAt(), sub.shippingNo(),
                sub.receivedAt(), sub.completedAt(), items);
    }
}
