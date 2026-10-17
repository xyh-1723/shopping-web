package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.harness.Effectful;
import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.common.BusinessException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/**
 * 购物袋的写操作工具（加购 / 改量 / 移除 / 清空）。
 *
 * <p>与支付的区别值得说清楚：<b>加购不需要 human-in-the-loop 确认，支付需要</b>。
 * 判据不是「是不是写操作」，而是「写错了代价多大、能不能撤销」：
 * <ul>
 *   <li>加购：不产生资金变动、用户在页面上立刻看得见、一键可撤销;</li>
 *   <li>支付：不可逆，而且发生在后台，用户看不见。</li>
 * </ul>
 * 把所有写操作一律塞进确认流程，会让确认卡片变成噪音——而对噪音麻木的用户，
 * 最终连真正的支付确认也会随手点掉。风险分级才是正确的做法。
 *
 * <p>仍然保留的硬约束：库存校验、单品上限、袋内行数上限都在 {@link CartService} 里，
 * 模型绕不过去——它只能请求「加一件」，加不进去由服务端说了算。
 *
 * <p>标注 {@link Effectful}：它会写库，必须留在调用线程执行（见该注解说明）。
 */
@Effectful("写库（修改用户购物袋），且结果需要回流到本轮上下文")
@Component
public class CartTools {

    private final CartService cart;
    private final ObjectMapper json;

    CartTools(CartService cart, ObjectMapper json) {
        this.cart = cart;
        this.json = json;
    }

    @Tool("把商品加入当前用户的购物袋。当用户说「加入购物车/放进购物袋/先存着/我要这件」时使用。"
            + "参数：productId 商品编号（先用搜索工具拿到）；quantity 数量，缺省 1。"
            + "加购后返回购物袋完整内容和小计、优惠、运费、应付金额，请把合计告诉用户并简要说明优惠。"
            + "注意：这一步不是下单，也不会扣款。返回 JSON。")
    public String addToCart(@ToolMemoryId String username, long productId, Integer quantity) {
        int qty = quantity == null || quantity <= 0 ? 1 : quantity;
        try {
            return describe(cart.add(username, productId, qty));
        } catch (BusinessException e) {
            return error(e.code(), e.getMessage());
        }
    }

    @Tool("把购物袋里某件商品的数量直接改成指定值；quantity 传 0 表示从购物袋移除。"
            + "当用户说「改成两件」「不要这件了」时使用。返回修改后的购物袋 JSON。")
    public String setCartQuantity(@ToolMemoryId String username, long productId, int quantity) {
        try {
            return describe(cart.setQuantity(username, productId, quantity));
        } catch (BusinessException e) {
            return error(e.code(), e.getMessage());
        }
    }

    @Tool("从购物袋移除某件商品。参数 productId 商品编号。返回修改后的购物袋 JSON。")
    public String removeFromCart(@ToolMemoryId String username, long productId) {
        return describe(cart.remove(username, productId));
    }

    @Tool("清空整个购物袋。只在用户明确说「清空购物袋」「都不要了」时使用。"
            + "这会影响用户所有已选商品，若用户只说不要某一件，请改用 removeFromCart。返回 JSON。")
    public String clearCart(@ToolMemoryId String username) {
        return describe(cart.clear(username));
    }

    /** 把购物袋渲染成模型能直接读、也便于转述给用户的结构。 */
    private String describe(CartView view) {
        ObjectNode node = (ObjectNode) json.valueToTree(view);
        node.put("instruction",
                "这是购物袋的最新状态与结算试算。金额由系统计算，请直接引用，不要自己加减或估算优惠；"
                        + "购物袋里的商品用户在首页购物袋中能看到。这不是下单，也没有扣款。");
        return node.toString();
    }

    private String error(String code, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        node.put("hint", "请据此如实告诉用户原因（如库存不足），并可建议换一件或减少数量。");
        return node.toString();
    }
}
