package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.harness.Effectful;
import dev.learning.mall.assistant.security.AssistantActors;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.order.api.OrderRefunds;
import dev.learning.mall.order.api.RefundView;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/**
 * 商家专属的退款<b>决策</b>工具（MERCHANT 角色）：批准 / 驳回。与 {@link StaffTools} 同一套三道闸门结构。
 *
 * <p><b>为什么工具内部还要再查一次角色</b>：工具清单虽然按角色下发了（第一道，见
 * {@link ToolRegistry#forActor(dev.learning.mall.assistant.security.AssistantActor)}），
 * 但模型是概率性的——一次成功的提示词注入完全可能让它「试图」调用它本不该有的工具。
 * 所以：
 * <ul>
 *   <li>第一道：按角色下发工具清单（模型根本看不到越权工具的声明）。</li>
 *   <li>第二道：工具方法内用登录账号的角色硬校验（对话里自称商家一律无效）。</li>
 *   <li>第三道：越权尝试写入审计表，便于发现有人在探测。</li>
 * </ul>
 *
 * <p><b>这里只放会动钱的动作，判据是 {@code isMerchant()}</b>。
 * 商家与店员是<b>并列</b>角色（{@code ROLE_MERCHANT} 刻意不带 {@code ROLE_STAFF}）：
 * <ul>
 *   <li>判据不能用 {@code isOperatingSide()}——那样店员也能批退款，等于打开
 *       「店员自己申请退款、自己批准」这条路；</li>
 *   <li>反过来，商家也不会因为 {@code isMerchant()} 而顺手拿到店员的发货 / 改价 / 改库存，
 *       那些方法在 {@link StaffTools} 里，由「工具注册给谁」决定。</li>
 * </ul>
 * 与 HTTP 层同一口径：{@code SecurityConfig} 里退款审批端点（{@code POST} 的批准 / 驳回）
 * 是 {@code hasRole("MERCHANT")}，而不是 {@code hasAnyRole("STAFF","MERCHANT")}。
 *
 * <p><b>只读的队列不在这里</b>：看队列（{@code pendingRefundQueue}）对<b>整个经营侧</b>开放
 * （店员 + 商家），与 HTTP 的 {@code GET /api/refunds/pending} 一致，见 {@link RefundQueueTools}。
 * 分成两个类而不是在一个类里按工具改判据，是为了让 {@link ToolRegistry} 只靠「注册给谁」
 * 就能表达权限——店员的清单里根本不会出现批准 / 驳回的声明，
 * 也就不需要一份「按工具名过滤」的第二份规则表（那种表最容易漏改）。
 *
 * <p><b>审批规则只有一份实现</b>：这里不写第二份判定，也不重复「该不该还货、怎么放款」。
 * 退款的领域实现只有 {@link OrderRefunds}（实现在 {@code RefundService}）一份，
 * HTTP 端点 {@code MerchantRefundController} 与这里的对话工具是它的两个入口。
 * 一旦在这个类里长出第二份审批逻辑，「批准了但钱没退」「退了钱但没还库存」这类
 * 只在某条路径上出错的分叉就只是时间问题。
 *
 * <p>{@code @ToolMemoryId} 参数由框架注入当前登录用户名，且不会出现在给模型看的参数列表里，
 * 模型无法伪造调用者身份——这一点比「把用户名当普通参数传」重要得多：
 * 审批人的账号会落进 {@code refund.decided_by}，也就是<b>放款责任人</b>。
 */
@Component
@Effectful("批准/驳回会写库并真的放款（不可回滚的外部调用），必须与同轮其它工具顺序执行")
public class MerchantTools {

    private static final String DENIED_NOTICE = "当前账号没有商家权限，无法审批退款。";

    private final OrderRefunds refunds;
    private final AssistantActors actors;
    private final AssistantAudit audit;
    private final ObjectMapper json;

    MerchantTools(OrderRefunds refunds, AssistantActors actors, AssistantAudit audit, ObjectMapper json) {
        this.refunds = refunds;
        this.actors = actors;
        this.audit = audit;
        this.json = json;
    }

    /**
     * 批准一笔待审批退款。
     *
     * <p><b>批准就是放款</b>：钱会真的退回买家钱包，未发货的订单还会归还库存。
     * 这些副作用都在 {@link OrderRefunds#approve} 的同一个事务里，这里不做任何补充判定，
     * 也不吞异常——业务冲突（已处理过、退款金额超限等）交给 {@link ToolRegistry}
     * 转成模型能读懂的结构化错误。
     */
    @Tool("商家专属：批准一笔待审批的退款。批准即放款——钱会退回买家钱包，未发货的订单会归还库存。"
            + "只有商家账号可调用。refundId 取自待审批队列；note 是审批意见，可省略。返回审批结果 JSON。")
    public String approveRefund(@ToolMemoryId String username, String refundId, String note) {
        if (denied(username, "approveRefund")) return denial();
        RefundView view = refunds.approve(refundId, username, note);
        ObjectNode node = decisionNode(view);
        node.put("message", "已批准退款 " + view.id() + "（¥" + view.amount() + "），"
                + "钱已退回买家 " + view.username() + " 的钱包。");
        return node.toString();
    }

    /** 驳回一笔待审批退款。驳回是这次申请的终点：不放款、不动库存。 */
    @Tool("商家专属：驳回一笔待审批的退款。驳回不放款、不动库存，理由会回给买家。"
            + "只有商家账号可调用。refundId 取自待审批队列；note 是驳回理由，建议填写。返回审批结果 JSON。")
    public String rejectRefund(@ToolMemoryId String username, String refundId, String note) {
        if (denied(username, "rejectRefund")) return denial();
        RefundView view = refunds.reject(refundId, username, note);
        ObjectNode node = decisionNode(view);
        node.put("message", "已驳回退款 " + view.id() + "（¥" + view.amount() + "），"
                + "没有退款给买家 " + view.username() + "。");
        return node.toString();
    }

    /** 角色硬校验（决策工具）：以登录账号的角色为准，任何时候都不信任对话内容。 */
    private boolean denied(String username, String toolName) {
        if (!actors.resolve(username).isMerchant()) {
            audit.record(username, null, AssistantAudit.STAGE_TOOL, "DENY",
                    "TOOL_FORBIDDEN_" + toolName, "LLM06", "越权调用商家工具 " + toolName);
            return true;
        }
        return false;
    }

    private String denial() {
        ObjectNode node = json.createObjectNode();
        node.put("error", "FORBIDDEN");
        node.put("message", DENIED_NOTICE);
        return node.toString();
    }

    /** 审批结果的公共字段。{@code decidedBy} 是放款责任人，对账时按它追责，必须回给模型。 */
    private ObjectNode decisionNode(RefundView view) {
        ObjectNode node = json.createObjectNode();
        node.put("id", view.id());
        node.put("orderId", view.orderId());
        node.put("username", view.username());
        node.put("amount", view.amount());
        node.put("status", view.status());
        node.put("decidedBy", view.decidedBy());
        node.put("decisionNote", view.decisionNote());
        node.put("stockReturned", view.stockReturned());
        return node;
    }
}
