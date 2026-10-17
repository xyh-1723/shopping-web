package dev.learning.mall.assistant.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.assistant.api.PendingActionView;
import dev.learning.mall.assistant.harness.TurnScope;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.assistant.security.AssistantSecurityProperties;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderCancellation;
import dev.learning.mall.order.api.OrderCheckout;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.QuoteSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Human-in-the-loop 的核心：模型发起的写操作先落成一条「待确认动作」，
 * 真正的执行只在用户本人通过页面确认后发生。
 *
 * <p>为什么需要它？工具权限控制的是「哪个角色能用哪个工具」，但即便授权合法，
 * 一次成功的提示词注入也可能诱导模型替用户做他没想做的操作（LLM06 过度代理）。
 * 所以写操作再加一道人类闸门：
 * <ul>
 *   <li><b>模型只能发起</b>：工具调用只创建 PENDING 记录，零副作用；</li>
 *   <li><b>人才能确认</b>：确认走独立的登录态接口，模型没有任何路径触达；</li>
 *   <li><b>防重放/防越权/防过期</b>：一次性状态迁移（原子 UPDATE）、归属硬校验、TTL 过期。</li>
 * </ul>
 *
 * <p><b>这里曾经只为支付服务</b>（{@code kind} 列只有 {@code ORDER_PAYMENT} 一种，
 * {@code order_id} 与 {@code payment_method} 都是 NOT NULL）。泛化之后，
 * 「下单」与「取消订单」也走同一道闸门——<b>没有新建第二张表</b>：
 * 归属校验、一次性原子迁移、TTL、审计这套逻辑是安全关键代码，
 * 复制出第二份，就是这些规则分叉的开始（项目决策第 2 条）。
 */
@Service
public class PendingActionService {

    public static final String KIND_ORDER_PAYMENT = "ORDER_PAYMENT";
    public static final String KIND_ORDER_CREATE = "ORDER_CREATE";
    public static final String KIND_ORDER_CANCEL = "ORDER_CANCEL";

    /**
     * 本轮产生的待确认动作，挂在 {@link TurnScope} 的轮次上下文里。
     *
     * <p>为什么不再用本类自己的静态 {@code ThreadLocal}：工具可能被并行调度到别的线程上执行，
     * 而线程池是复用的——裸 ThreadLocal 会让 A 用户的轮次读到 B 用户遗留在同一线程上的值
     * （静默的跨用户串味，卡片会挂到别人的会话里）。{@link TurnScope} 负责
     * 「进入时装载、退出时精确还原」，并发下才有隔离保证。
     */
    private static final String ATTRIBUTE_CREATED = "pending-action.created";

    private record ActionRow(String id, String username, String conversationId, String kind,
                             String orderId, BigDecimal amount, String method, String status,
                             String idempotencyKey, String linesJson, String quoteId,
                             LocalDateTime expiresAt) {}

    /** 确认/取消的结果：动作最新视图 + 执行后的订单（未产生订单变更时为 null）。 */
    public record Resolution(PendingActionView action, OrderView order) {}

    private static final String SELECT =
            "SELECT id, username, conversation_id, kind, order_id, amount, payment_method AS method, "
                    + "status, idempotency_key, lines_json, quote_id, expires_at "
                    + "FROM assistant_pending_action ";

    private final JdbcClient jdbc;
    private final OrderInsights insights;
    private final OrderPayments payments;
    private final OrderCheckout checkout;
    private final OrderCancellation cancellation;
    private final QuoteSnapshot quotes;
    private final AssistantSecurityProperties security;
    private final AssistantAudit audit;
    private final ObjectMapper json;
    // 自代理：resolve（非事务，拒绝路径的审计要独立提交）调用 execute（事务，状态迁移+执行同生共死）。
    private final ObjectProvider<PendingActionService> self;

    public PendingActionService(JdbcClient jdbc, OrderInsights insights, OrderPayments payments,
                                OrderCheckout checkout, OrderCancellation cancellation, QuoteSnapshot quotes,
                                AssistantSecurityProperties security, AssistantAudit audit,
                                ObjectMapper json, ObjectProvider<PendingActionService> self) {
        this.jdbc = jdbc;
        this.insights = insights;
        this.payments = payments;
        this.checkout = checkout;
        this.cancellation = cancellation;
        this.quotes = quotes;
        this.security = security;
        this.audit = audit;
        this.json = json;
        this.self = self;
    }

    /** 待确认动作的人类可读名字，用于错误文案与审计日志（支付路径的措辞保持原样）。 */
    private static String label(String kind) {
        return switch (kind) {
            case KIND_ORDER_CREATE -> "下单确认";
            case KIND_ORDER_CANCEL -> "取消确认";
            default -> "支付确认";
        };
    }

    /**
     * 审计码前缀。支付路径必须产出既有的 {@code HITL_PAY_*}——那是既有测试与安全看板
     * 认的名字，泛化时改掉它会让「支付确认」这条最有风险的链路从监控里消失。
     */
    private static String auditPrefix(String kind) {
        return switch (kind) {
            case KIND_ORDER_CREATE -> "HITL_ORDER";
            case KIND_ORDER_CANCEL -> "HITL_CANCEL";
            default -> "HITL_PAY";
        };
    }

    /**
     * 本轮对话中由模型发起、尚未被用户确认的动作（没有则为 null）。
     * 由调用方在轮次内读取——轮次结束后上下文已被还原，读不到了。
     */
    public PendingActionView createdInTurn() {
        Object created = TurnScope.attribute(ATTRIBUTE_CREATED);
        return created instanceof PendingActionView view ? view : null;
    }

    /**
     * 工具入口：为本人一笔待支付订单创建支付确认请求。<b>不发生任何支付</b>。
     * 归属与状态在这里校验一次，确认执行时订单模块还会再校验一次（纵深防御）。
     */
    public PendingActionView requestOrderPayment(String username, String orderId, String method) {
        String m = (method == null || method.isBlank()) ? "DEMO_CARD" : method.strip().toUpperCase(Locale.ROOT);
        if (!payments.methods().contains(m)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT",
                    "不支持的模拟支付方式：" + m + "（可选：" + String.join("/", payments.methods()) + "）");
        }
        OrderView order = insights.ordersOf(username).stream()
                .filter(o -> o.id().equals(orderId)).findFirst()
                .orElseThrow(() -> BusinessException.notFound("订单不存在或不属于当前用户"));
        if (!"PENDING".equals(order.status())) {
            throw BusinessException.conflict("INVALID_STATE", "只有待支付的订单才能发起支付确认");
        }
        // 同一订单已有未过期的待确认请求时直接复用，避免重复刷卡片。
        Optional<ActionRow> existing = jdbc.sql(SELECT
                        + "WHERE username = :user AND order_id = :order AND status = 'PENDING' "
                        + "AND expires_at > CURRENT_TIMESTAMP ORDER BY seq DESC LIMIT 1")
                .param("user", username).param("order", orderId)
                .query(ActionRow.class).optional();
        if (existing.isPresent()) return toView(existing.get());

        String conversationId = TurnScope.conversationId();
        String id = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.now().plusMinutes(security.getPaymentConfirmTtlMinutes());
        jdbc.sql("INSERT INTO assistant_pending_action(id,username,conversation_id,kind,order_id,"
                        + "payment_method,amount,status,expires_at) "
                        + "VALUES(:id,:user,:conv,:kind,:order,:method,:amount,'PENDING',:expires)")
                .param("id", id)
                .param("user", username)
                .param("conv", conversationId)
                .param("kind", KIND_ORDER_PAYMENT)
                .param("order", orderId)
                .param("method", m)
                // 应付金额：V12 之后它就是「用户要付的钱」（含满减与运费），
                // 不再只是商品小计。确认卡片上显示的必须和订单上的数一致。
                .param("amount", order.payAmount())
                .param("expires", expires)
                .update();
        audit.record(username, conversationId, AssistantAudit.STAGE_TOOL, "PENDING",
                "HITL_PAY_REQUEST", "LLM06",
                "发起支付确认：订单 " + orderId.substring(0, 8) + "，金额 ¥" + order.payAmount());
        PendingActionView view = toView(rowById(id).orElseThrow());
        // 记到轮次上下文，随本轮响应回给前端渲染确认卡片。
        TurnScope.attribute(ATTRIBUTE_CREATED, view);
        return view;
    }

    /**
     * 工具入口：为本人购物袋里的内容创建一条下单确认请求。<b>不建订单、不扣库存</b>。
     *
     * <p>幂等键在这里由服务端生成并落库：模型物理上没有传值入口，
     * 也就无法用构造出来的键绕过防重。确认时原样取出交给订单模块。
     */
    public PendingActionView requestOrderCreate(String username, List<CreateOrderRequest.Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw BusinessException.badRequest("CART_EMPTY", "购物袋是空的，先挑几件商品再来下单");
        }
        // 金额由服务端快照产出，调用方只能提交商品与数量。确认卡片与之后的
        // PRICE_CHANGED 比对因此引用同一张快照，不存在两个金额来源。
        QuoteSnapshot.Snapshot snapshot = quotes.capture(username, lines);
        BigDecimal amount = snapshot.quote().payable();
        String key = "ord-" + UUID.randomUUID().toString().replace("-", "");
        String conversationId = TurnScope.conversationId();
        String id = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.now().plusMinutes(security.getPaymentConfirmTtlMinutes());
        jdbc.sql("INSERT INTO assistant_pending_action(id,username,conversation_id,kind,"
                        + "idempotency_key,lines_json,quote_id,amount,status,expires_at) "
                        + "VALUES(:id,:user,:conv,:kind,:key,:lines,:quoteId,:amount,'PENDING',:expires)")
                .param("id", id)
                .param("user", username)
                .param("conv", conversationId)
                .param("kind", KIND_ORDER_CREATE)
                .param("key", key)
                .param("lines", writeLines(lines))
                .param("quoteId", snapshot.id())
                .param("amount", amount)
                .param("expires", expires)
                .update();
        audit.record(username, conversationId, AssistantAudit.STAGE_TOOL, "PENDING",
                "HITL_ORDER_REQUEST", "LLM06", "发起下单确认：应付 ¥" + amount);
        PendingActionView view = toView(rowById(id).orElseThrow());
        TurnScope.attribute(ATTRIBUTE_CREATED, view);
        return view;
    }

    /** 工具入口：为本人一笔待支付订单创建取消确认请求。<b>不取消</b>。 */
    public PendingActionView requestOrderCancel(String username, String orderId) {
        OrderView order = insights.ordersOf(username).stream()
                .filter(o -> o.id().equals(orderId)).findFirst()
                .orElseThrow(() -> BusinessException.notFound("订单不存在或不属于当前用户"));
        if (!"PENDING".equals(order.status())) {
            throw BusinessException.conflict("INVALID_STATE", "只有待支付的订单才能取消");
        }
        Optional<ActionRow> existing = jdbc.sql(SELECT
                        + "WHERE username = :user AND order_id = :order AND kind = :kind "
                        + "AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP ORDER BY seq DESC LIMIT 1")
                .param("user", username).param("order", orderId).param("kind", KIND_ORDER_CANCEL)
                .query(ActionRow.class).optional();
        if (existing.isPresent()) return toView(existing.get());

        String conversationId = TurnScope.conversationId();
        String id = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.now().plusMinutes(security.getPaymentConfirmTtlMinutes());
        jdbc.sql("INSERT INTO assistant_pending_action(id,username,conversation_id,kind,order_id,"
                        + "amount,status,expires_at) "
                        + "VALUES(:id,:user,:conv,:kind,:order,:amount,'PENDING',:expires)")
                .param("id", id).param("user", username).param("conv", conversationId)
                .param("kind", KIND_ORDER_CANCEL).param("order", orderId)
                .param("amount", order.payAmount()).param("expires", expires).update();
        audit.record(username, conversationId, AssistantAudit.STAGE_TOOL, "PENDING",
                "HITL_CANCEL_REQUEST", "LLM06", "发起取消确认：订单 " + orderId.substring(0, 8));
        PendingActionView view = toView(rowById(id).orElseThrow());
        TurnScope.attribute(ATTRIBUTE_CREATED, view);
        return view;
    }

    /** 某会话下仍未过期的待确认动作（页面刷新后重新找回确认卡片）。 */
    public List<PendingActionView> pendingOf(String username, String conversationId) {
        return jdbc.sql(SELECT + "WHERE username = :user AND conversation_id = :conv "
                        + "AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP ORDER BY seq")
                .param("user", username).param("conv", conversationId)
                .query(ActionRow.class).list().stream().map(this::toView).toList();
    }

    /**
     * 确认或取消。安全闸门按顺序：存在性 → 归属（他人一律 404，不暴露存在性）
     * → 过期 → 一次性原子迁移（并发双击/重放只有一个能成功）→ 才真正执行。
     *
     * <p><b>故意不加 @Transactional</b>：拒绝路径的审计必须独立提交——如果整个方法在一个事务里，
     * 抛出业务异常回滚时会把「有人试图越权/重放」的审计记录一起抹掉，等于攻击者可以消除痕迹。
     * 真正需要事务的「状态迁移 + 执行」收在 {@link #execute} 里。
     */
    public Resolution resolve(String id, String username, boolean approve) {
        ActionRow row = rowById(id).orElseThrow(() -> BusinessException.notFound(label(KIND_ORDER_PAYMENT) + "请求不存在"));
        if (!row.username().equals(username)) {
            audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "DENY",
                    "HITL_FOREIGN", "LLM06", "试图处理他人的待确认动作 " + id);
            throw BusinessException.notFound(label(row.kind()) + "请求不存在");
        }
        if ("PENDING".equals(row.status()) && row.expiresAt().isBefore(LocalDateTime.now())) {
            transition(id, "EXPIRED");
            audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "DENY",
                    auditPrefix(row.kind()) + "_EXPIRED", "LLM06", "确认一个已过期的待确认动作 " + id);
            // 支付路径保留原有错误码，测试和前端都认它。
            throw KIND_ORDER_PAYMENT.equals(row.kind())
                    ? BusinessException.conflict("PAYMENT_EXPIRED", "这笔支付确认已过期，请重新发起")
                    : BusinessException.conflict("CONFIRM_EXPIRED", "这笔" + label(row.kind()) + "已过期，请重新发起");
        }
        if (!"PENDING".equals(row.status())) {
            audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "DENY",
                    "HITL_REPLAY", "LLM06", "重复处理已办结的待确认动作 " + id);
            throw BusinessException.conflict("ALREADY_RESOLVED", "这笔" + label(row.kind()) + "已经处理过了");
        }
        try {
            return self.getObject().execute(id, username, approve, row);
        } catch (BusinessException e) {
            // 并发下两个请求都通过了上面的预检，只有一个能在原子迁移中胜出；
            // 落败方的事务已回滚，在这里补记重放审计（此刻不在任何事务里）。
            if ("ALREADY_RESOLVED".equals(e.code())) {
                audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "DENY",
                        "HITL_REPLAY", "LLM06", "并发重复处理待确认动作 " + id);
            }
            throw e;
        }
    }

    /**
     * 状态迁移 + 真正执行（事务）。原子 UPDATE 保证一次性；执行失败时整个事务回滚，
     * 状态不会停在「已确认但未执行」的中间态。
     */
    @Transactional
    public Resolution execute(String id, String username, boolean approve, ActionRow row) {
        String target = approve ? "CONFIRMED" : "CANCELLED";
        if (transition(id, target) != 1) {
            throw BusinessException.conflict("ALREADY_RESOLVED", "这笔" + label(row.kind()) + "已经处理过了");
        }
        OrderView result = null;
        if (approve) {
            result = switch (row.kind()) {
                case KIND_ORDER_PAYMENT -> {
                    // 订单模块内部会再查一次归属与状态——即便这里的校验被绕过，支付也落不到别人头上。
                    OrderView paid = payments.pay(row.orderId(), username, row.method());
                    audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "CONFIRM",
                            "HITL_PAY_CONFIRM", "LLM06",
                            "本人确认支付：订单 " + row.orderId().substring(0, 8) + "，金额 ¥" + row.amount());
                    yield paid;
                }
                case KIND_ORDER_CREATE -> {
                    // 走的就是页面 POST /api/orders 那一份实现，不是「给助手写一套下单」。
                    // 清袋不再由这里做：订单落库会发 OrderPlacedEvent，购物袋自己来听。
                    // 于是页面路径与助手路径共用同一份「落单即清袋」，不会再分叉。
                    OrderView created = checkout.create(username, row.idempotencyKey(),
                            new CreateOrderRequest(readLines(row.linesJson()), row.quoteId()));
                    audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "CONFIRM",
                            "HITL_ORDER_CONFIRM", "LLM06",
                            "本人确认下单：订单 " + created.id().substring(0, 8) + "，应付 ¥" + row.amount());
                    yield created;
                }
                case KIND_ORDER_CANCEL -> {
                    OrderView cancelled = cancellation.cancel(row.orderId(), username);
                    audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "CONFIRM",
                            "HITL_CANCEL_CONFIRM", "LLM06",
                            "本人确认取消订单：" + row.orderId().substring(0, 8));
                    yield cancelled;
                }
                default -> throw new IllegalStateException("未知的待确认动作类型：" + row.kind());
            };
        } else {
            audit.record(username, row.conversationId(), AssistantAudit.STAGE_TOOL, "CANCEL",
                    auditPrefix(row.kind()) + "_CANCEL", "LLM06",
                    "本人取消" + label(row.kind()) + "：" + row.id());
        }
        return new Resolution(toView(rowById(id).orElseThrow()), result);
    }

    /** 一次性状态迁移：只有当前还是 PENDING 的行才会被更新。 */
    private int transition(String id, String target) {
        return jdbc.sql("UPDATE assistant_pending_action SET status = :target, resolved_at = CURRENT_TIMESTAMP "
                        + "WHERE id = :id AND status = 'PENDING'")
                .param("target", target).param("id", id).update();
    }

    private Optional<ActionRow> rowById(String id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(ActionRow.class).optional();
    }

    private PendingActionView toView(ActionRow row) {
        return new PendingActionView(row.id(), row.conversationId(), row.kind(), row.orderId(),
                row.amount(), row.method(), row.status(), row.expiresAt());
    }

    private String writeLines(List<CreateOrderRequest.Line> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (IOException e) {
            throw new IllegalStateException("下单内容无法序列化", e);
        }
    }

    private List<CreateOrderRequest.Line> readLines(String linesJson) {
        try {
            return json.readValue(linesJson, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalStateException("待确认动作里的商品行无法解析", e);
        }
    }
}
