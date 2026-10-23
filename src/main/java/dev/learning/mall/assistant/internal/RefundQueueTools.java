package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.security.AssistantActors;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.order.api.OrderRefunds;
import dev.learning.mall.order.api.RefundView;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 待审批退款队列的<b>经营侧只读</b>视图：店员（STAFF）与商家（MERCHANT）都能看。
 *
 * <p><b>为什么判据是 {@code isOperatingSide()} 而不是 {@code isMerchant()}</b>：
 * HTTP 层 {@code GET /api/refunds/pending} 是 {@code hasAnyRole("STAFF","MERCHANT")}，
 * 退款审批端点（{@code POST} 的批准 / 驳回）才是 {@code hasRole("MERCHANT")}——
 * 「队列：经营侧只读；审批：仅商家」。对话里若把队列也收成 {@code isMerchant()}，
 * 同一件事就有了第三套规则；两套真相一旦分家，谁先被发现谁就是 bug。
 * 看得到但批不了不构成「店员自己申请、自己批准」的风险（决策工具在
 * {@link MerchantTools} 里，判据仍是 {@code isMerchant()}），而店员本来就看得到
 * 经营数据与其他用户的订单，看队列也不是新的信息泄露。
 *
 * <p><b>只读，因此不标 {@link dev.learning.mall.assistant.harness.Effectful}</b>：
 * 与写工具分开成类之后它可以安全地被并行调用；和审批工具挤在一个类里时，
 * 它会被类的效果型标记一并拖成串行。
 *
 * <p>三道闸门与 {@link StaffTools} / {@link MerchantTools} 完全一致：清单按角色下发是第一道，
 * 方法内用登录账号的角色硬校验是第二道（模型是概率性的，注入成功就可能「试图」调用），
 * 越权写入审计表是第三道。
 */
@Component
public class RefundQueueTools {

    private static final String DENIED_NOTICE = "当前账号不是经营侧成员（店员或商家），无法查看待审批退款队列。";

    /**
     * 队列的单次返回上限。
     *
     * <p>不做成配置：它由「一个人一次该看多少」这份产品判断决定，
     * 而到目前为止没有人提出要调它（做成配置 = 一个改了也不起作用的旋钮）。
     */
    private static final int QUEUE_LIMIT = 20;

    private final OrderRefunds refunds;
    private final AssistantActors actors;
    private final AssistantAudit audit;
    private final ObjectMapper json;

    RefundQueueTools(OrderRefunds refunds, AssistantActors actors, AssistantAudit audit, ObjectMapper json) {
        this.refunds = refunds;
        this.actors = actors;
        this.audit = audit;
        this.json = json;
    }

    @Tool("列出待商家审批的退款队列（先申请的排在前面），含退款单号、订单号、买家账号、金额与原因。"
            + "店员与商家账号可调用；队列里的退款只能由商家批准或驳回。返回 JSON 数组；队列为空时返回空数组。")
    public String pendingRefundQueue(@ToolMemoryId String username) {
        if (denied(username, "pendingRefundQueue")) return denial();
        // 可见范围按调用者推导：店员看全站，商家只看自己那家店。
        // 这里刻意不传任何「只要自己店的」开关——那种参数每多一个入口就多一次传错的机会，
        // 而传错的后果就是某个商家看到了别家店的退款。
        List<RefundView> queue = refunds.pendingQueueFor(username, QUEUE_LIMIT);
        ArrayNode array = json.createArrayNode();
        for (RefundView r : queue) {
            ObjectNode node = json.createObjectNode();
            node.put("id", r.id());
            node.put("orderId", r.orderId());
            node.put("username", r.username());
            node.put("amount", r.amount());
            node.put("reasonCode", r.reasonCode());
            node.put("reasonText", r.reasonText());
            node.put("createdAt", String.valueOf(r.createdAt()));
            array.add(node);
        }
        return array.toString();
    }

    /** 角色硬校验（只读工具）：判据是「经营侧」，比决策工具宽一级。 */
    private boolean denied(String username, String toolName) {
        if (!actors.resolve(username).isOperatingSide()) {
            audit.record(username, null, AssistantAudit.STAGE_TOOL, "DENY",
                    "TOOL_FORBIDDEN_" + toolName, "LLM06", "越权调用经营侧工具 " + toolName);
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
}
