package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.api.PendingActionView;
import dev.learning.mall.assistant.harness.Effectful;
import dev.learning.mall.common.BusinessException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/**
 * 支付工具（human-in-the-loop 的「发起」端）。
 *
 * <p>这是全项目唯一的写操作工具，但它本身仍然是<b>零副作用</b>的：
 * 调用它不会支付，只会创建一条待确认记录。真正的支付只能由用户本人
 * 在页面点击确认——模型无论被怎么注入，都够不到那个确认接口。
 *
 * <p>标注 {@link Effectful} 有两个原因：它会写库；并且它把「本轮发起了哪笔支付确认」
 * 写回 {@code TurnScope} 的轮次上下文，而跨线程传播是单向快照——换到 worker 线程执行时
 * 读得到会话、写不回产物，结果是「不报错但确认卡片不出现」的静默失败。
 */
@Effectful("写库（创建待确认记录），且产物需要回流到调用线程的轮次上下文")
@Component
public class PaymentTools {

    private final PendingActionService pendingActions;
    private final ObjectMapper json;

    PaymentTools(PendingActionService pendingActions, ObjectMapper json) {
        this.pendingActions = pendingActions;
        this.json = json;
    }

    @Tool("为当前用户的一笔待支付订单发起支付确认。注意：这只是创建待确认请求，不会扣款；"
            + "系统会在页面向用户本人展示确认卡片，用户点击「确认支付」后才会真正执行。"
            + "当用户想支付/付款/结算某个订单时使用。参数：orderId 订单号（可先用 myOrders 查询获得）；"
            + "method 支付方式，DEMO_CARD=模拟银行卡，DEMO_WALLET=模拟钱包，缺省 DEMO_WALLET。"
            + "注意：只有 DEMO_WALLET 会真的从用户钱包里扣款，余额不足时用户确认后会提示余额不足；"
            + "DEMO_CARD 不扣任何钱，将来也就没有可以退回的落点，除非用户明确要求，不要主动选它。"
            + "返回待确认请求 JSON。在用户确认之前，绝不要声称支付已完成。")
    public String requestPayment(@ToolMemoryId String username, String orderId, String method) {
        if (orderId == null || orderId.isBlank()) {
            return error("INVALID_ARGUMENT", "请提供要支付的订单号，可先用 myOrders 查询");
        }
        try {
            PendingActionView v = pendingActions.requestOrderPayment(username, orderId.strip(), method);
            ObjectNode node = json.createObjectNode();
            node.put("pendingActionId", v.id());
            node.put("orderId", v.orderId());
            node.put("amount", v.amount());
            node.put("method", v.method());
            node.put("status", "PENDING_CONFIRMATION");
            node.put("instruction",
                    "支付确认卡片已展示给用户。请告知用户去页面点击确认；用户确认前支付不会执行，不要声称已支付。");
            return node.toString();
        } catch (BusinessException e) {
            return error(e.code(), e.getMessage());
        }
    }

    private String error(String code, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        return node.toString();
    }
}
