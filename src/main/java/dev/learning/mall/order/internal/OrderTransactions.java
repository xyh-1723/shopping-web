package dev.learning.mall.order.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.*;
import dev.learning.mall.messaging.api.DomainEventPublisher;
import dev.learning.mall.order.api.*;
import dev.learning.mall.payment.api.*;
import dev.learning.mall.pricing.api.Pricer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderTransactions implements OrderCancellation {

    /** 关闭原因：用户主动取消。 */
    static final String REASON_USER = "USER";
    /** 关闭原因：超时未支付，由系统关闭。 */
    static final String REASON_TIMEOUT = "TIMEOUT";

    private final OrderRepository orders;
    private final ProductCatalog catalog;
    private final DomainEventPublisher events;
    private final PaymentRouter payments;
    private final OrderProperties properties;
    private final Clock clock;
    /** 唯一的金额计算入口：订单与购物袋共用，保证同一个用户不会看到两个不同的金额。 */
    private final Pricer pricer;
    /** Spring 的应用事件：用于「我这边发生了什么、谁爱听谁听」的进程内广播（区别于订单发件箱）。 */
    private final ApplicationEventPublisher appEvents;
    public OrderTransactions(OrderRepository orders, ProductCatalog catalog, DomainEventPublisher events,
            PaymentRouter payments, OrderProperties properties, Clock clock, Pricer pricer,
            ApplicationEventPublisher appEvents) {
        this.orders = orders; this.catalog = catalog; this.events = events; this.payments = payments;
        this.properties = properties; this.clock = clock; this.pricer = pricer;
        this.appEvents = appEvents;
    }

    @Transactional
    @Observed("create-order-transaction")
    public OrderView create(String user, String key, String hash, List<CreateOrderRequest.Line> lines) {
        List<OrderView.Item> items = lines.stream().map(line -> {
            var product = catalog.get(line.productId());
            return new OrderView.Item(product.id(), product.name(), product.price(), line.quantity());
        }).toList();
        // 金额一律问唯一的定价器：满减与运费在这一步就被算进去，
        // 于是订单上的数字和用户刚才在购物袋里、在对话中看到的完全一致。
        // 这里曾经是裸求和，把满减与运费全丢了——同一个用户 3 秒内看到两个金额。
        // 详见 docs/对标淘宝差距分析.md 的 G1 与 docs/优化计划.md。
        Pricer.Quote quote = pricer.quote(items.stream()
                .map(i -> new Pricer.Line(i.productId(), i.unitPrice(), i.quantity())).toList());
        String id = UUID.randomUUID().toString();
        // 截止时间在下单这一刻算好并落库：之后即使运营改了支付时限，这笔订单承诺给用户的
        // 那个时刻也不会跳变（用户看到的倒计时必须和下单时说的一致）。
        LocalDateTime expiresAt = LocalDateTime.now(clock).plusMinutes(properties.getPaymentTimeoutMinutes());
        // 唯一约束先占位；同 key 的并发请求会在此等待并最终冲突，尚未扣库存。
        orders.insert(id, user, key, hash, quote.subtotal(), quote.discount(), quote.shippingFee(),
                quote.payable(), expiresAt);
        // lines 已按商品编号排序，多个订单以相同顺序加库存行锁，降低死锁风险。
        for (OrderView.Item item : items) {
            catalog.reserve(item.productId(), item.quantity());
            orders.insertItem(id, item);
        }
        // 订单、库存、事件在同一数据库事务中提交或回滚。
        events.publish(id, user, "ORDER_CREATED", "订单已创建，应付 ¥" + quote.payable() + "，等待模拟支付。");
        // 同步应用事件：清袋的订阅方在本事务内执行，与落单同生共死。
        // 放在发件箱事件之后——后者是「异步通知」，这里是「同事务副作用」，不要混用。
        appEvents.publishEvent(new OrderPlacedEvent(id, user));
        return get(id, user);
    }

    @Transactional
    @Observed("pay-order")
    public OrderView pay(String id, String user, String method) {
        PaymentGateway gateway = payments.get(method);
        OrderRow row = owned(id, user, true);
        if (row.status().equals("PAID")) {
            if (!method.equals(row.paymentMethod()))
                throw BusinessException.conflict("PAYMENT_MISMATCH", "订单已使用另一种方式支付");
            return orders.view(row);
        }
        if (!row.status().equals("PENDING")) throw notPayable(row);
        // 仅本地无副作用的模拟策略。真实支付必须移出长事务，并使用支付单/回调幂等。
        String receipt = gateway.pay(id, row.total());
        // 带条件的更新：真被支付成功的只有一次。理论上行锁已经串行化了这里，
        // 但「谁改成了 PAID 谁负责」这条规则不该依赖调用方记得加锁。
        if (orders.markPaid(id, method, receipt) != 1) throw notPayable(orders.lock(id).orElse(row));
        events.publish(id, user, "ORDER_PAID", "模拟支付成功，订单 " + id.substring(0, 8) + " 已完成。");
        return get(id, user);
    }

    @Transactional
    @Observed("cancel-order")
    @Override
    public OrderView cancel(String id, String user) {
        OrderRow row = owned(id, user, true);
        if (row.status().equals("CANCELLED")) return orders.view(row);
        if (!row.status().equals("PENDING")) throw BusinessException.conflict("INVALID_STATE", "已支付订单不能取消");
        close(row, REASON_USER);
        return get(id, user);
    }

    /**
     * 关闭一笔到期的待支付订单（系统路径）。
     *
     * <p>与用户取消走<b>同一份关单实现</b>，差异只有关闭原因。拆成两份的那天开始，
     * 就会出现「主动取消归还了库存、超时关闭忘了归还」这类分叉，而且它不会立刻暴露。
     *
     * <p>每条订单一个独立事务：一笔关不掉（比如商品行被人为改坏）不该拖累同一轮的其它订单。
     *
     * @return 是否由本次调用关闭；{@code false} 表示它已经不是待支付状态（刚支付 / 已关闭）。
     */
    @Transactional
    @Observed("close-expired-order")
    public boolean closeExpired(String id, LocalDateTime now) {
        OrderRow row = orders.lock(id).orElse(null);
        if (row == null || !row.status().equals("PENDING")) return false;
        // 二次确认截止时间：从取出候选到拿到行锁之间，可能已经有人付了款或改了订单。
        // 到期与否由服务端判定，调用方传进来的时间只用来界定「这一轮扫到哪」。
        if (row.expiresAt() == null || row.expiresAt().isAfter(now)) return false;
        return close(row, REASON_TIMEOUT);
    }

    /**
     * 关单的唯一实现。
     *
     * @return 是否由本次调用完成状态转换。
     */
    private boolean close(OrderRow row, String reason) {
        // 先做带条件的状态更新，拿到「转换权」之后才归还库存：
        // 更新成功 = 这笔订单的待支付状态是我终结的，那么归还库存也是我的责任；
        // 更新影响 0 行 = 别人已经处理过，此刻绝不能再还一次库存（重复归还会凭空造出货）。
        if (orders.markClosed(row.id(), reason, LocalDateTime.now(clock)) != 1) return false;
        List<OrderView.Item> lines = orders.items(row.id());
        for (OrderView.Item item : lines) catalog.release(item.productId(), item.quantity());
        boolean timeout = REASON_TIMEOUT.equals(reason);
        if (timeout) {
            // 超时关闭是「弃单挽回」的入口：用户多半是忘了或卡在支付上，值得再提醒一句。
            // 主动取消不发——人家是不想买了，再推荐就是打扰。
            // 订单模块只负责广播事实，召回不召回由主动服务决定——它不认识订阅方。
            appEvents.publishEvent(new OrderAbandonedEvent(row.id(), row.username(),
                    lines.stream().map(OrderView.Item::productId).toList()));
        }
        events.publish(row.id(), row.username(), timeout ? "ORDER_CLOSED_TIMEOUT" : "ORDER_CANCELLED",
                timeout ? "订单超时未支付，已自动关闭，预占库存已归还。" : "订单已取消，预占库存已经归还。");
        return true;
    }

    @Transactional(readOnly = true)
    public OrderView get(String id, String user) { return orders.view(owned(id, user, false)); }
    @Transactional(readOnly = true)
    public List<OrderView> list(String user) { return orders.list(user).stream().map(orders::view).toList(); }
    private OrderRow owned(String id, String user, boolean lock) {
        // 直接在查询中限定用户，避免越权读取或操作他人的订单。
        return orders.find(id, user, lock).orElseThrow(() -> BusinessException.notFound("订单不存在"));
    }

    /**
     * 已关闭订单的支付失败文案。
     *
     * <p>竞态下的另一半结果同样要有明确说法：用户在最后一秒点支付、定时任务同时关单，
     * 输的那一方必须知道自己输在「超时」而不是「未知错误」——否则用户只能反复重试。
     */
    private static BusinessException notPayable(OrderRow row) {
        if (REASON_TIMEOUT.equals(row.closeReason()))
            return BusinessException.conflict("ORDER_EXPIRED", "订单超时未支付，已自动关闭，库存已归还，请重新下单");
        return BusinessException.conflict("INVALID_STATE", "已取消的订单不能支付");
    }
}
