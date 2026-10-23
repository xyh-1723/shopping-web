package dev.learning.mall.order.internal;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.common.Observed;
import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import dev.learning.mall.messaging.api.DomainEventPublisher;
import dev.learning.mall.order.api.OrderRefunds;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.payment.api.PaymentRouter;
import dev.learning.mall.payment.api.RefundRequest;
import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款（N2）：<b>三类人的三条路径共用一份领域实现</b>。
 *
 * <pre>
 *   买家自己点退款     → RefundController         → apply(...)   → 路由判定
 *   助手代发起（HITL） → PendingActionService     → apply(...)   → 同上
 *   商家批准           → MerchantRefundController → approve(...) → disburse(...)
 * </pre>
 *
 * <h3>分流：两块落点，三条判据</h3>
 *
 * <p><b>AUTO</b>（无理由、买家自助、当场放款）必须同时满足三条：
 * <ol>
 *   <li>订单<b>未发货</b>（{@code fulfillment_status = 'NONE'}）——<b>硬规则，不可配</b>。
 *       货一旦出库，退款就要先走退货入库，那不是「点击即退」能自说自话的事；
 *       把它做成可配，等于给「货已签收还能一键退款」留一个开关，
 *       与本项目「安全边界不进配置」的一贯写法相悖。</li>
 *   <li>距 {@code paid_at} 不超过配置的<b>时间窗口</b>（默认 24 小时）；</li>
 *   <li>金额不超过配置的<b>阈值</b>（默认 ¥200）。</li>
 * </ol>
 * 任一不满足 → {@code MERCHANT_APPROVAL}，进商家队列等人判。
 * 分流结果落库在 {@code refund.route}，事后可以复盘「为什么这笔要等人」。
 *
 * <h3>放款是两段，不是一段</h3>
 *
 * <p>{@code APPROVED → REFUNDED} 中间隔着一个真实的外部动作（钱有没有真的出去）。
 * 规则因此写死成：<b>只有 {@code REFUNDED} 才允许归还库存</b>。
 * 否则「批准了但转账失败」会留下「库存回来了、钱没退」这种最难受的账。
 * AUTO 路由也必须走这两段（不能直接插成 {@code REFUNDED}）：
 * 那样这行不进 {@code uq_refund_inflight} 索引，等于给约束自己挖了个洞。
 *
 * <h3>幂等：转换权 = 责任</h3>
 *
 * <p>与 {@link OrderTransactions#close} 同一套写法：状态迁移都写成<b>带前置条件的 UPDATE</b>，
 * 影响 1 行才做副作用（放款、还货），0 行就安静跳过。
 * 再加两层：{@code uq_refund_inflight} 唯一索引保证「同订单只能有一笔在途」；
 * 订单行的 {@code FOR UPDATE} 负责让库存流水的前后值与「累计已退金额」的 SUM
 * 处在同一个串行点下。三层各管一件事，缺一层就守不住「换个地方调用」。
 */
@Service
public class RefundService implements OrderRefunds {

    /** AUTO 路由的决策人：没有任何人批过它，写 SYSTEM 而不是买家本人。 */
    static final String DECIDED_BY_SYSTEM = "SYSTEM";

    /**
     * 未发货：退款分流的硬规则基准值。
     *
     * <p>它是从 {@code mall_order.fulfillment_status} 读出来的同一个字面量，
     * 与 V15 的 {@code ck_order_fulfillment_status} 取值集合同源。
     */
    static final String FULFILLMENT_NONE = "NONE";

    /** 允许的退款原因码；与 {@code refund.reason_code} 的业务口径一致。 */
    static final List<String> REASON_CODES = List.of("NO_REASON", "DAMAGED", "WRONG_ITEM", "OTHER");

    /** 与 {@link dev.learning.mall.assistant.security.AssistantActor#ROLE_MERCHANT} 同一个字面量。 */
    private static final String ROLE_MERCHANT = "ROLE_MERCHANT";

    /** 同上：店员。这里不引用 assistant 包的常量，避免 order 反向依赖 assistant。 */
    private static final String ROLE_STAFF = "ROLE_STAFF";

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final ProductCatalog catalog;
    private final PaymentRouter payments;
    private final DomainEventPublisher events;
    private final OrderProperties properties;
    private final UserDetailsService users;
    private final MerchantDirectory merchants;
    private final Clock clock;

    public RefundService(OrderRepository orders, RefundRepository refunds, ProductCatalog catalog,
            PaymentRouter payments, DomainEventPublisher events, OrderProperties properties,
            UserDetailsService users, MerchantDirectory merchants, Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.catalog = catalog;
        this.payments = payments;
        this.events = events;
        this.properties = properties;
        this.users = users;
        this.merchants = merchants;
        this.clock = clock;
    }

    // ——————————————————— 买家 / 助手：申请退款 ———————————————————

    /**
     * 申请退款：<b>判定与可能的放款都在这一次调用里完成</b>。
     *
     * <p>调用方不需要、也不应该先自己判断「这笔能不能自助退」——
     * 那样判断会在第二个调用方那里长出第二份副本，两份副本迟早不一致。
     */
    @Transactional
    @Observed("request-refund")
    @Override
    public RefundView apply(String orderId, String username, BigDecimal amount,
            String reasonCode, String reasonText) {
        if (!properties.getRefund().isEnabled()) {
            throw BusinessException.conflict("REFUND_DISABLED", "退款功能当前未开启");
        }
        String code = normalizeReasonCode(reasonCode);
        LocalDateTime now = LocalDateTime.now(clock);
        // 订单行锁：它已经不是「防止双退款」的唯一防线（唯一索引挡在 INSERT 上），
        // 但仍负责两件事——给下面「累计已退金额」的 SUM 一个一致读快照，
        // 以及让库存流水的 stock_before / stock_after 处在同一个串行点下。
        OrderRow order = orders.lock(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        // 归属校验在这里而不是只在 Controller：助手路径已由 HITL 校验过「只能本人确认」，
        // 这里是同一条规则的第二次落地。「存在但不是你的」与「不存在」给同一个答复，
        // 免得把别人订单号的存在性泄露出去。
        if (!order.username().equals(username)) throw BusinessException.notFound("订单不存在");
        if (!"PAID".equals(order.status())) {
            throw BusinessException.conflict("INVALID_STATE", "只有已支付的订单才能申请退款");
        }
        if (refunds.hasInFlight(orderId)) {
            // 数据库确实会挡住第二笔在途，但那时抛出来的是一条约束异常（500）。
            // 这里先给一个说得清的答复：这是调用方能看懂的那一层。
            throw BusinessException.conflict("ALREADY_RESOLVED", "这笔订单已经有一笔退款在处理中");
        }
        BigDecimal requested = requestedAmount(amount, order);
        requireWithinRemaining(order, requested);
        String route = routeOf(order, requested, now);

        String id = UUID.randomUUID().toString();
        refunds.insert(id, orderId, order.username(), requested, code, trimToNull(reasonText), route, username);
        if (RefundView.ROUTE_AUTO.equals(route)) {
            disburse(id, DECIDED_BY_SYSTEM, null, order, requested);
        } else {
            events.publish(orderId, order.username(), "REFUND_REQUESTED",
                    "退款申请已提交（¥" + requested + "），等待商家确认。");
        }
        return refunds.view(refunds.find(id).orElseThrow(() -> BusinessException.notFound("退款不存在")));
    }

    @Transactional(readOnly = true)
    @Override
    public List<RefundView> refundsOf(String username) {
        return refunds.ofUser(username).stream().map(refunds::view).toList();
    }

    // ——————————————————— 商家：批准 / 驳回 ———————————————————

    @Transactional
    @Observed("approve-refund")
    @Override
    public RefundView approve(String refundId, String merchant, String note) {
        MerchantView shop = requireMerchant(merchant);
        RefundRow refund = refunds.find(refundId)
                .orElseThrow(() -> BusinessException.notFound("退款不存在"));
        // 归属校验：这笔退款的订单里必须有这家店的商品。
        // 少了这一步，「商家」就还是一顶「能批全部退款」的帽子——
        // 加 merchant 表之前的状态，只不过那时候连「谁的店」都问不出来。
        requireOwnShop(refund, shop);
        OrderRow order = orders.lock(refund.orderId())
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        disburse(refundId, merchant, trimToNull(note), order, refund.amount());
        return refunds.view(refunds.find(refundId).orElseThrow(() -> BusinessException.notFound("退款不存在")));
    }

    @Transactional
    @Observed("reject-refund")
    @Override
    public RefundView reject(String refundId, String merchant, String note) {
        MerchantView shop = requireMerchant(merchant);
        RefundRow refund = refunds.find(refundId)
                .orElseThrow(() -> BusinessException.notFound("退款不存在"));
        // 驳回同样要判归属：虽然它不放款，但它能让一笔申请彻底终结，
        // 「A 把 B 的退款申请驳回去」与「A 批准了 B 的退款」是同一个洞的两半。
        requireOwnShop(refund, shop);
        if (refunds.markRejected(refundId, merchant, trimToNull(note), LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("ALREADY_RESOLVED", "这笔退款已经处理过了");
        }
        String reason = trimToNull(note) == null ? "未说明理由" : trimToNull(note);
        events.publish(refund.orderId(), refund.username(), "REFUND_REJECTED",
                "退款申请已被商家驳回：" + reason);
        return refunds.view(refunds.find(refundId).orElseThrow(() -> BusinessException.notFound("退款不存在")));
    }

    /**
     * 待审批队列，按申请时间正序（先来先判）。
     *
     * <p><b>可见范围由账号是谁决定，而且只在这一处决定</b>：
     * <ul>
     *   <li>店员：全站。店员本来就能看经营数据与其他用户的订单，这不是新的信息泄露。</li>
     *   <li>商家：只有自己那家店的单（{@code order_item.merchant_id}）。</li>
     *   <li>都不是：403。</li>
     * </ul>
     * 把它写成「一个方法 + 调用方传一个 boolean 说要不要过滤」是最容易出事的做法：
     * 那个 boolean 迟早会在某个新入口处传错，而传错的后果是一个商家看到别人的退款。
     * 由账号自己推导范围，新入口就没有任何东西可以传错。
     *
     * @param viewer 查看者账号；只有店员与商家能看，其余 403
     */
    @Transactional(readOnly = true)
    @Override
    public List<RefundView> pendingQueueFor(String viewer, int limit) {
        Set<String> authorities = authoritiesOf(viewer);
        if (authorities.contains(ROLE_STAFF)) {
            return refunds.pendingQueue(limit).stream().map(refunds::view).toList();
        }
        if (authorities.contains(ROLE_MERCHANT)) {
            MerchantView shop = shopOf(viewer);
            return refunds.pendingQueueOf(shop.id(), limit).stream().map(refunds::view).toList();
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "REFUND_QUEUE_FORBIDDEN",
                "只有店员或商家才能查看待审批的退款队列");
    }

    // ——————————————————— 放款：唯一入口 ———————————————————

    /**
     * 放款。<b>钱、库存、状态在同一个事务里</b>，三者不可能出现「对了两个、第三个没跟上」。
     *
     * <p>三道闸门按顺序：
     * <ol>
     *   <li><b>一次性迁移</b>：{@code markApproved} 影响 1 行才算拿到「这次由我放款」的资格；
     *       0 行说明别人已经批过（并发双击 / 异步重放），此刻绝不能再放一次。</li>
     *   <li><b>该不该还货</b>：只有未发货的订单才归还库存——已发货 / 已收货的退款
     *       <b>只退钱不还货</b>。那种情况下钱走了、库存没回来，是正确行为而不是丢数据。</li>
     *   <li><b>真的把钱退回去</b>：{@code PaymentGateway.refund}。钱包通道会真的给买家记一笔入账，
     *       银行卡通道会<b>报错</b>（那笔钱压根没进过钱包，没有可以退的落点）。</li>
     * </ol>
     *
     * <p>{@code decidedBy} 同时是钱包流水的 actor：<b>放款责任人</b>。
     * AUTO 是 {@code SYSTEM}，商家批准是那位商家的账号。
     * 写成买家本人就等于记成「买家自己给自己退了一笔钱」，对账时会得出完全相反的结论。
     */
    private void disburse(String refundId, String decidedBy, String note, OrderRow order, BigDecimal amount) {
        LocalDateTime decidedAt = LocalDateTime.now(clock);
        if (refunds.markApproved(refundId, decidedBy, note, decidedAt) != 1) {
            throw BusinessException.conflict("ALREADY_RESOLVED", "这笔退款已经处理过了");
        }
        // 第二段：到了 REFUNDED 才动手。两个时刻是两次不同的取值，
        // 因为它们本来就是两件事——批准的时刻、钱真的动了（并从 corrupt 状态出来）的时刻。
        //
        // ⚠ 这里的 0 行分支必须「抛」，绝不能像上一版那样静默 return：
        // 静默 return 会让整笔退款停在 APPROVED——既没放款、也没发站内信，还不报错。
        // 「不红不报错」是失败里最坏的一种：对账时看到 APPROVED 会以为「正在处理」，
        // 线上会一直挂着，而唯一知道它发生了的人只有那个恰好翻到这条日志的人。
        //
        // 这段路径理论上不可达（同一个事务里 markApproved 刚影响 1 行，紧接着 markRefunded
        // 必然命中）。正因为不可达才更不能静默：一旦它被走到，就是并发或状态机被破坏的
        // 强信号——把「我以为不可能」变成一条 409 抛出去，是我们唯一能收到这个信号的方式。
        if (refunds.markRefunded(refundId, LocalDateTime.now(clock)) != 1) {
            throw BusinessException.conflict("ALREADY_RESOLVED", "这笔退款已经处理过了");
        }
        if (FULFILLMENT_NONE.equals(order.fulfillmentStatus())) {
            for (OrderView.Item item : orders.items(order.id())) {
                catalog.returnStock(item.productId(), item.quantity(), decidedBy, order.id());
            }
            refunds.markStockReturned(refundId);
        }
        // 最后一步才动钱：转账失败（例如 DEMO_CARD 通道直接拒绝）会让整个事务回滚，
        // 于是「库存回来了、钱没退」在这个顺序下不可能发生。
        payments.get(order.paymentMethod()).refund(
                new RefundRequest(order.id(), order.username(), order.paymentReceipt(), amount, decidedBy));
        events.publish(order.id(), order.username(), "ORDER_REFUNDED",
                "退款已完成，¥" + amount + " 已退回你的钱包。");
    }

    // ——————————————————— 判定 ———————————————————

    /**
     * 分流判定。<b>顺序是有意义的</b>：先判不可配的硬规则，再判两条可配的阈值，
     * 于是有人把窗口或金额阈值调到荒唐的值，也不会连带让「货已出门还能一键退款」成立。
     */
    private String routeOf(OrderRow order, BigDecimal amount, LocalDateTime now) {
        // ① 未发货：硬规则。
        if (!FULFILLMENT_NONE.equals(order.fulfillmentStatus())) return RefundView.ROUTE_MERCHANT_APPROVAL;
        // ② 时间窗口：按 paid_at 计，不是 created_at——后者是下单时刻，那时候钱还没进来。
        LocalDateTime paidAt = order.paidAt();
        // 拿不到支付时刻就无从计算窗口，交给人工而不是当成「在窗口内」放行：
        // 判据缺失时失败方向必须是少给权限。
        if (paidAt == null) return RefundView.ROUTE_MERCHANT_APPROVAL;
        long windowHours = properties.getRefund().getAutoRefundWindowHours();
        if (now.isAfter(paidAt.plusHours(windowHours))) return RefundView.ROUTE_MERCHANT_APPROVAL;
        // ③ 金额阈值（含边界）。换算成「分」再比：整数分是全项目唯一的金额真相。
        BigDecimal cap = properties.getRefund().getAutoRefundMaxAmount();
        if (MoneyUnits.toCents(amount) > MoneyUnits.toCents(cap)) return RefundView.ROUTE_MERCHANT_APPROVAL;
        return RefundView.ROUTE_AUTO;
    }

    /** 本次申请金额：{@code null} 表示全额（订单实付）。 */
    private static BigDecimal requestedAmount(BigDecimal amount, OrderRow order) {
        BigDecimal requested = amount == null ? order.payAmount() : amount;
        if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.badRequest("INVALID_AMOUNT", "退款金额必须大于 0");
        }
        // 与 MoneyUnits.toCents 同一把尺子：精度超过「分」直接拒绝，绝不四舍五入。
        if (requested.scale() > 2) {
            throw BusinessException.badRequest("INVALID_AMOUNT", "退款金额精度超过「分」：" + requested);
        }
        return requested;
    }

    /**
     * 累计退款不得超过实付。<b>必须发生在放款与还货之前</b>：
     * 事务回滚能撤掉库里的行，撤不掉已经发出去的 gateway 调用——
     * 那种调用一旦发生，「退款表零新增」这条断言是看不见它的。
     *
     * <p>比较在「分」上做：整数分是本项目唯一的金额真相，也避免业务代码自己去做金额加法。
     */
    private void requireWithinRemaining(OrderRow order, BigDecimal requested) {
        long paid = MoneyUnits.toCents(order.payAmount());
        long settled = MoneyUnits.toCents(refunds.settledAmount(order.id()));
        long asks = MoneyUnits.toCents(requested);
        if (settled + asks > paid) {
            throw BusinessException.conflict("REFUND_EXCEEDS_PAID",
                    "退款金额不能超过实付金额：已退 ¥" + MoneyUnits.toYuan(settled)
                            + "，本次 ¥" + requested + "，实付 ¥" + order.payAmount());
        }
    }

    /** 原因码归一化：空即「无理由」；认不出来的码明确拒绝，不让它悄悄变成 NO_REASON。 */
    private static String normalizeReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) return "NO_REASON";
        String normalized = reasonCode.strip().toUpperCase(Locale.ROOT);
        if (!REASON_CODES.contains(normalized)) {
            throw BusinessException.badRequest("INVALID_REASON",
                    "不支持的退款原因：" + reasonCode + "（可选：" + String.join("/", REASON_CODES) + "）");
        }
        return normalized;
    }

    /**
     * 服务层的纵深防御 + 归属解析：先确认这个账号是商家，再确认它是<b>哪一家</b>店。
     *
     * <p>URL 级 {@code hasRole("MERCHANT")} 是外层，这里再判一次——
     * 「直接注入 Service 调用」这条路不该因为没有 HTTP 就失守。
     *
     * <p>角色从 {@link UserDetailsService} 解析，而不是让调用方传一个自称的角色集合：
     * 传参等于把判定权交给调用方，这条线迟早会在某个新入口处被绕过。
     *
     * <p><b>第二步（账号 → 店）是这次新加的，也是这次真正堵的那个洞</b>：
     * 只判角色，得到的是「所有商家都能批所有退款」；
     * 判了归属，退款才第一次有「你的店」可言。
     *
     * @return 该账号对应的店；拿不到就抛 403（<b>归属缺失按无权处理</b>，
     *         绝不退化成「那就给他全部」或「那就给他第一家」）
     */
    private MerchantView requireMerchant(String username) {
        if (!authoritiesOf(username).contains(ROLE_MERCHANT)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REFUND_APPROVAL_FORBIDDEN",
                    "只有商家（MERCHANT）才能审批退款，当前账号无权执行");
        }
        return shopOf(username);
    }

    /**
     * 账号 → 店。查不到就是 403：一个带着 MERCHANT 角色却没有店铺行的账号，
     * 是配置错误不是权限——让它「先过再说」等于把配置错误翻译成越权。
     */
    private MerchantView shopOf(String username) {
        return merchants.ofOwner(username)
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "MERCHANT_SHOP_MISSING",
                        "账号没有关联的店铺，无法处理退款（归属缺失按无权处理）"));
    }

    /**
     * 这笔退款的订单里必须有这家店的商品。
     *
     * <p>「存在但不是你的」与「不存在」给同一个答复（404），
     * 免得把别人订单号的存在性泄露出去——与 {@code apply} 里那条归属校验同一个口径。
     */
    private void requireOwnShop(RefundRow refund, MerchantView shop) {
        if (!refunds.involvesMerchant(refund.orderId(), shop.id())) {
            throw BusinessException.notFound("退款不存在");
        }
    }

    /** 账号的权限集合；查不到账号 = 空集合，绝不因为查不到而放行。 */
    private Set<String> authoritiesOf(String username) {
        Set<String> authorities = new LinkedHashSet<>();
        try {
            UserDetails details = users.loadUserByUsername(username);
            details.getAuthorities().forEach(a -> authorities.add(a.getAuthority()));
        } catch (RuntimeException unknown) {
            authorities.clear();
        }
        return authorities;
    }

    private static String trimToNull(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
