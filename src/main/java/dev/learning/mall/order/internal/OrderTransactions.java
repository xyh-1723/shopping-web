package dev.learning.mall.order.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.*;
import dev.learning.mall.coupon.api.CouponApplication;
import dev.learning.mall.coupon.api.CouponRedemptions;
import dev.learning.mall.coupon.api.CouponShop;
import dev.learning.mall.messaging.api.DomainEventPublisher;
import dev.learning.mall.order.api.*;
import dev.learning.mall.payment.api.*;
import dev.learning.mall.pricing.api.Pricer;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
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

    /** 指标：成功下单（D2）。落点在 Service 而不是 Controller——助手工具下单也走这里，指标才不会漏。 */
    public static final String METRIC_ORDER_PLACED = "mall.order.placed";
    /** 指标：支付成功（D2）。只有 markPaid 真正影响 1 行才计数，重复支付不重复计。 */
    public static final String METRIC_ORDER_PAID = "mall.order.paid";

    private final OrderRepository orders;
    private final OrderSubRepository subs;
    private final ProductCatalog catalog;
    private final DomainEventPublisher events;
    private final PaymentRouter payments;
    private final OrderProperties properties;
    private final Clock clock;
    /** 唯一的金额计算入口：订单与购物袋共用，保证同一个用户不会看到两个不同的金额。 */
    private final Pricer pricer;
    /** Spring 的应用事件：用于「我这边发生了什么、谁爱听谁听」的进程内广播（区别于订单发件箱）。 */
    private final ApplicationEventPublisher appEvents;
    /** 业务指标注册表（D2）：下单 / 支付两个 Counter 从这里出去。 */
    private final MeterRegistry registry;
    /** 优惠券的唯一入口：算券、占预算、锁券都在它的下单事务里完成。 */
    private final CouponRedemptions coupons;
    public OrderTransactions(OrderRepository orders, OrderSubRepository subs, ProductCatalog catalog,
            DomainEventPublisher events, PaymentRouter payments, OrderProperties properties, Clock clock,
            Pricer pricer, ApplicationEventPublisher appEvents, MeterRegistry registry,
            CouponRedemptions coupons) {
        this.orders = orders; this.subs = subs; this.catalog = catalog; this.events = events;
        this.payments = payments;
        this.properties = properties; this.clock = clock; this.pricer = pricer;
        this.appEvents = appEvents;
        this.registry = registry;
        this.coupons = coupons;
    }

    @Transactional
    @Observed("create-order-transaction")
    public OrderView create(String user, String key, String hash,
                            List<CreateOrderRequest.Line> lines, BigDecimal expectedPayable,
                            String userCouponId) {
        // 金额快照与落单共用 OrderPricing：两条路径都从商品表取实时价格，
        // 再交给唯一的 Pricer，不允许在订单里另写一套求和规则。
        Pricer.Quote baseQuote = OrderPricing.quoteOf(catalog, pricer, lines);
        // 比价必须发生在任何写调用之前。若先 insert 再抛异常，事务回滚虽然能清库，
        // 但已经越过了“价格变化时零写入”的控制流边界。
        // 快照是券前拍的，所以这里也比<b>券前</b>应付：券减免在服务端实时计算，
        // 不依赖快照，也就不会出现「快照里的券价被改过」这种可能。
        if (expectedPayable != null && baseQuote.payable().compareTo(expectedPayable) != 0) {
            throw BusinessException.conflict("PRICE_CHANGED",
                    "商品价格已变动：发起时 ¥" + expectedPayable + "，当前应付 ¥"
                            + baseQuote.payable() + "，请确认后重新提交");
        }
        List<OrderView.Item> items = lines.stream().map(line -> {
            var product = catalog.get(line.productId());
            return new OrderView.Item(product.id(), product.name(), product.price(), line.quantity());
        }).toList();
        String id = UUID.randomUUID().toString();
        // 截止时间在下单这一刻算好并落库：之后即使运营改了支付时限，这笔订单承诺给用户的
        // 那个时刻也不会跳变（用户看到的倒计时必须和下单时说的一致）。
        LocalDateTime expiresAt = LocalDateTime.now(clock).plusMinutes(properties.getPaymentTimeoutMinutes());

        List<SubOrderSplitter.Shop> shops = OrderPricing.shopsOf(catalog, pricer, lines);
        // 券前先拆一次，只为拿到「每家店分摊到的满减」——它是券门槛与券减免的基数。
        // 这次拆分不落库，运费在这里不参与券计算（券只作用于商品金额）。
        List<SubOrderSplitter.Share> promotionShares =
                SubOrderSplitter.split(shops, baseQuote.discount(), baseQuote.shippingFee());
        List<CouponShop> couponShops = promotionShares.stream()
                .map(share -> new CouponShop(share.merchantId(), share.subtotal(), share.discount()))
                .toList();
        // 算券、占预算、锁券在下单事务内完成：后续任何一步失败（库存、唯一键）都会
        // 连带把它们一起回滚，不会留下一张「锁着但订单没建成」的券。
        CouponApplication coupon = coupons.reserve(user, userCouponId, id, couponShops);
        // 券后重算：券也是折扣，包邮门槛必须按券后金额重新判断。
        Pricer.Quote quote = OrderPricing.quoteOf(catalog, pricer, lines, coupon.discount());

        // 唯一约束先占位；同 key 的并发请求会在此等待并最终冲突，尚未扣库存。
        orders.insert(id, user, key, hash, quote.subtotal(), quote.discount(), quote.shippingFee(),
                quote.payable(), coupon.discount(), coupon.couponId(), coupon.couponCode(), expiresAt);
        // lines 已按商品编号排序，多个订单以相同顺序加库存行锁，降低死锁风险。
        // 下单者用户名与订单号一起交给商品模块：它们会被写进库存流水，
        // 于是「这批货是被哪笔订单占走的」事后可查，不用反推。
        for (OrderView.Item item : items) {
            catalog.reserve(item.productId(), item.quantity(), user, id);
            orders.insertItem(id, item);
        }
        splitIntoSubs(id, shops, baseQuote, quote, coupon);
        // 订单、库存、事件在同一数据库事务中提交或回滚。
        events.publish(id, user, "ORDER_CREATED", "订单已创建，应付 ¥" + quote.payable() + "，等待模拟支付。");
        // 同步应用事件：清袋的订阅方在本事务内执行，与落单同生共死。
        // 放在发件箱事件之后——后者是「异步通知」，这里是「同事务副作用」，不要混用。
        appEvents.publishEvent(new OrderPlacedEvent(id, user));
        // 走到这里，前面任何一步失败都会让整单回滚，这一行根本不会被执行——
        // 所以计数放在末尾，而不是入口：入口计数会把「价格已变动被拒」的失败单也算进去。
        registry.counter(METRIC_ORDER_PLACED).increment();
        return get(id, user);
    }

    /**
     * 按店拆出子订单。
     *
     * <p>买家只下一次单、只付一次钱，但<b>货是各家店各发各的</b>：一车里有三家的东西，
     * 就要有三个子订单，各有各的金额与物流。父单上那四列金额仍然是买家付的总数，
     * 子订单上的四列是<b>这家店该分到的数</b>——两个不同的事实，各留一份。
     *
     * <p>分摊规则（满减与运费按各家店小计占比拆分、取整余量按最大余额法派发）
     * 收在 {@link SubOrderSplitter} 里，那里的硬要求是
     * <b>Σ 子单金额 = 父单金额，四列逐列相等</b>；本方法只负责把它落库。
     *
     * <p>{@code id} = 父单号 + {@code -S} + 序号：它是 (order_id, sub_seq) 的纯函数，
     * 不需要「取回自增主键」那一趟往返，也就没有两套 id 对不上的可能。
     */
    private void splitIntoSubs(String orderId, List<SubOrderSplitter.Shop> shops,
                               Pricer.Quote baseQuote, Pricer.Quote quote, CouponApplication coupon) {
        // 只拆「满减 + 最终运费」：券减免已经由优惠券模块逐店算好了（含商家承担与平台补贴），
        // 再按比例拆一次会得到两份不同的券分摊，子单的 pay 与结算六列就会对不上。
        List<SubOrderSplitter.Share> shares =
                SubOrderSplitter.split(shops, baseQuote.discount(), quote.shippingFee());
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalPay = BigDecimal.ZERO;
        int seq = 1;
        for (SubOrderSplitter.Share share : shares) {
            CouponApplication.ShopSettlement settlement = coupon.shopOf(share.merchantId());
            // 子单的 discount = 满减分摊 + 券减免分摊；pay = 券前应付 − 券减免。
            BigDecimal discount = share.discount().add(settlement.couponDiscount());
            BigDecimal pay = share.payAmount().subtract(settlement.couponDiscount());
            // 结算六列的恒等式由数据库约束检查，这里按定义算出；运费归商家，所以它在 pay 里。
            BigDecimal merchantIncome = pay.add(settlement.platformSubsidy()).subtract(settlement.platformFee());
            subs.insert(orderId + "-S" + seq, orderId, seq, share.merchantId(),
                    share.subtotal(), discount, share.shippingFee(), pay,
                    settlement.couponDiscount(), settlement.merchantDiscount(), settlement.platformSubsidy(),
                    settlement.platformFee(), merchantIncome, settlement.platformIncome());
            totalDiscount = totalDiscount.add(discount);
            totalPay = totalPay.add(pay);
            seq++;
        }
        // 这是整条链路最后一道金额守恒检查：任何一家店的数字看着合理都不算数，
        // 只有「Σ 子单 = 父单」才说明这笔订单的钱有且只有一个主人。
        if (totalDiscount.compareTo(quote.discount()) != 0 || totalPay.compareTo(quote.payable()) != 0) {
            throw new IllegalStateException("子订单金额与父单不一致：discount "
                    + totalDiscount + " / " + quote.discount() + "，pay " + totalPay + " / " + quote.payable());
        }
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
        // ⚠ 这一步不再是无副作用的模拟：DEMO_WALLET 通道会真的从买家钱包里扣钱。
        // username 必须一起传进去——钱包得知道扣的是谁的钱，而订单行已在上面校验过归属，
        // 于是往下走的所有副作用都建立在「这笔钱确实是这个用户的」这个前提上。
        String receipt = gateway.pay(new PayRequest(id, user, row.total()));
        // 带条件的更新：真被支付成功的只有一次。理论上行锁已经串行化了这里，
        // 但「谁改成了 PAID 谁负责」这条规则不该依赖调用方记得加锁。
        // 支付时刻与关单时刻同一个形状：由 Clock 注入，退款窗口、对账才有确定的起点。
        LocalDateTime paidAt = LocalDateTime.now(clock);
        if (orders.markPaid(id, method, receipt, paidAt) != 1) throw notPayable(orders.lock(id).orElse(row));
        // 券在支付成功这一刻才从「锁定」变成「核销」。放在 markPaid 之后：
        // 只有真正收到钱的那一次转换，才配把券用掉。
        coupons.commit(id);
        events.publish(id, user, "ORDER_PAID", "模拟支付成功，订单 " + id.substring(0, 8) + " 已完成。");
        // 计数在「确认是我改成 PAID」之后：重复支付走上面的幂等分支直接返回，不会记第二次。
        registry.counter(METRIC_ORDER_PAID).increment();
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
        // 用带 SKIP LOCKED 的那一份：多实例部署时，另一个实例（或正在付款的买家）
        // 可能正锁着这行。等锁没有意义——等到了也只是发现状态已经变了，
        // 不如立刻放手，下一轮再来。分片已经让这种情况很少见，这是第二道保险。
        OrderRow row = orders.lockForClose(id).orElse(null);
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
        // 拿到关单权之后才释放券：与库存同一个顺序——谁终结了这笔待支付订单，
        // 谁负责把券还回去、把平台补贴预算退掉。不是我就一步都别动。
        coupons.release(row.id());
        List<OrderView.Item> lines = orders.items(row.id());
        for (OrderView.Item item : lines)
            catalog.release(item.productId(), item.quantity(), row.username(), row.id());
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
    public List<OrderView> list(String user) { return orders.views(orders.list(user)); }
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
