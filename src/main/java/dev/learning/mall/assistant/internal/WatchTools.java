package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.harness.Effectful;
import dev.learning.mall.proactive.api.WatchView;
import dev.learning.mall.proactive.internal.WatchService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 关注工具：把「有点想要，但暂时不买」变成一条可执行的信号。
 *
 * <p>这是导购类助手最容易被忽略、转化却往往最高的一句话。用户在对话里说
 * 「有点超出预算了」时，一个只会说「好的」的助手把这个人送走了；
 * 而一个会说「我帮你盯着，降了就告诉你」的助手留下了这条线索。
 *
 * <p>标 {@link Effectful}：它是写操作（会改关注表），留在调用线程顺序执行，
 * 避免与同轮的只读查询并行时产生不确定的中间状态。
 */
@Component
@Effectful("关注/取消关注会写入 mall_product_watch，属于写操作")
public class WatchTools {

    private final WatchService watches;
    private final ObjectMapper json;

    WatchTools(WatchService watches, ObjectMapper json) {
        this.watches = watches;
        this.json = json;
    }

    @Tool("为当前用户关注一件商品：以后它降价、或补货时，系统会主动提醒他。"
            + "当用户表达「先不买/等降价/有点贵/等有货再说/帮我留意一下」这类意思时使用。"
            + "参数：productId 是商品编号（先用搜索工具拿到真实编号，不要猜）。"
            + "返回 JSON，含关注后的基线价格与当前价格。")
    public String watchProduct(@ToolMemoryId String username, long productId) {
        WatchView view;
        try {
            view = watches.watch(username, productId);
        } catch (RuntimeException e) {
            return error("WATCH_FAILED", e.getMessage());
        }
        ObjectNode node = json.createObjectNode();
        node.put("status", "WATCHING");
        node.put("productId", view.productId());
        node.put("productName", view.productName());
        node.put("price", view.price());
        node.put("baselinePrice", view.baselinePrice());
        node.put("inStock", view.inStock());
        node.put("note", "已开始关注。之后价格低于当前价、或从售罄变为有货时会主动提醒该用户；"
                + "请简短告诉他你会盯价/盯货，不要承诺具体时间。");
        return node.toString();
    }

    @Tool("取消关注某件商品（用户说「不用盯着了」「取关」「不关注了」时使用）。"
            + "参数：productId 商品编号。返回 JSON。")
    public String unwatchProduct(@ToolMemoryId String username, long productId) {
        watches.unwatch(username, productId);
        ObjectNode node = json.createObjectNode();
        node.put("status", "UNWATCHED");
        node.put("productId", productId);
        node.put("note", "已取消关注，之后不会再就这件商品提醒他。");
        return node.toString();
    }

    @Tool("查看当前用户正在关注（盯价 / 盯货）的商品列表。无需参数。返回 JSON 数组。")
    public String myWatches(@ToolMemoryId String username) {
        List<WatchView> list = watches.watchesOf(username);
        ArrayNode array = json.createArrayNode();
        for (WatchView view : list) {
            ObjectNode node = json.createObjectNode();
            node.put("productId", view.productId());
            node.put("name", view.productName());
            node.put("price", view.price());
            node.put("baselinePrice", view.baselinePrice());
            node.put("inStock", view.inStock());
            array.add(node);
        }
        return array.toString();
    }

    private String error(String code, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message == null ? "操作失败" : message);
        return node.toString();
    }
}
