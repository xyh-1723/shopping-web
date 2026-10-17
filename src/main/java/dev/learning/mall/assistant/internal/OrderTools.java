package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.action.PendingActionService;
import dev.learning.mall.assistant.harness.Effectful;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.common.BusinessException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/**
 * 订单的写操作工具（提交订单 / 取消订单）。
 *
 * <p><b>补齐的是闭环缺的那一跳。</b>在此之前，助手能把商品加进购物袋、能发起支付确认，
 * 却<b>不能下单</b>——因为「创建订单」的唯一入口是页面接口，而支付工具只能对付已存在的订单。
 * 用户说「帮我买下」，助手加完购就只能让用户自己去点。
 *
 * <p><b>为什么下单要 human-in-the-loop，加购不要</b>：下单占用库存、且对店员可见，
 * 不可撤销程度高于加购。所以这里只创建一条 PENDING 的待确认记录，
 * 真正的落单在用户本人于页面点「确认下单」之后才发生。
 *
 * <p><b>确认之前绝不能声称「已下单」</b>——工具返回的是「已发起确认」，
 * 这两件事的区别必须原样告诉用户，否则 HITL 闸门就形同虚设。
 *
 * <p>标注 {@link Effectful}：它会写库（待确认记录），并且产物需要回流到本轮上下文
 * 才能渲染确认卡片——并行调度会把卡片弄丢。
 */
@Effectful("写库（创建待确认记录），且产物需要回流到调用线程的轮次上下文")
@Component
public class OrderTools {

    private final PendingActionService pendingActions;
    private final CartService cart;
    private final ObjectMapper json;

    OrderTools(PendingActionService pendingActions, CartService cart, ObjectMapper json) {
        this.pendingActions = pendingActions;
        this.cart = cart;
        this.json = json;
    }

    @Tool("为当前用户购物袋里的内容发起【下单确认】。当用户说「帮我下单/买了/结算袋子里的东西/就这些了」时使用。"
            + "重要：这一步【不会】真的创建订单，只是生成一张待确认卡片，由用户本人在页面上确认后才会下单；"
            + "因此返回后你必须告诉用户「已经帮你发起下单确认，请点击卡片上的确认下单」，"
            + "绝不能说「已下单」「订单已生成」。若购物袋为空则会失败，此时应提示用户先挑选商品。返回 JSON。")
    public String createOrder(@ToolMemoryId String username) {
        // 行与金额都取自购物袋的当前快照：价格按现在的商品表算，
        // 助手报出的数字与用户即将确认的数字因此是同一个。
        var view = cart.view(username);
        try {
            var pending = pendingActions.requestOrderCreate(username, cart.orderLines(username), view.total());
            ObjectNode node = json.createObjectNode();
            node.put("status", "PENDING_CONFIRMATION");
            node.put("actionId", pending.id());
            node.put("kind", pending.kind());
            node.put("amount", pending.amount());
            node.put("subtotal", view.subtotal());
            node.put("discount", view.discount());
            node.put("shippingFee", view.shippingFee());
            node.set("items", json.valueToTree(view.items().stream()
                    .map(i -> i.name() + " ×" + i.quantity()).toList()));
            node.put("instruction",
                    "已为用户发起下单确认（尚未创建订单、尚未扣库存）。应付金额由系统算出，请直接引用，不要自己加减；"
                            + "请提示用户点击页面卡片上的「确认下单」，在用户确认之前绝不能说「已下单」。");
            return node.toString();
        } catch (BusinessException e) {
            return error(e.code(), e.getMessage());
        }
    }

    @Tool("为当前用户的一笔【待支付订单】发起【取消确认】。当用户说「这单我不要了/取消订单/别买了」时使用。"
            + "参数：orderId 订单号（先用 myOrders 查到）。重要：这一步【不会】真的取消订单，"
            + "只是生成一张待确认卡片，由用户本人在页面确认后才会取消；"
            + "返回后请告诉用户「已发起取消确认，请点击卡片上的确认」。已支付的订单不能取消。返回 JSON。")
    public String cancelOrder(@ToolMemoryId String username, String orderId) {
        try {
            var pending = pendingActions.requestOrderCancel(username, orderId);
            ObjectNode node = json.createObjectNode();
            node.put("status", "PENDING_CONFIRMATION");
            node.put("actionId", pending.id());
            node.put("kind", pending.kind());
            node.put("orderId", pending.orderId());
            node.put("amount", pending.amount());
            node.put("instruction",
                    "已为用户发起取消确认（尚未取消订单）。请提示用户点击页面卡片上的「确认」，"
                            + "在用户确认之前绝不能说「已取消」。");
            return node.toString();
        } catch (BusinessException e) {
            return error(e.code(), e.getMessage());
        }
    }

    private String error(String code, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        node.put("hint", "请据此如实告诉用户原因（例如购物袋是空的、订单已支付），不要编造结果。");
        return node.toString();
    }
}
