package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.common.BusinessException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 导购能力工具：看购物袋、比商品、要澄清。
 *
 * <p>这三个工具对应专业导购最典型的三段动作，也是「能用工具」和「好用」之间的差别：
 * <ul>
 *   <li><b>viewCart</b>——用户问「我现在一共多少钱」时，助手必须给出和页面完全一致的数；</li>
 *   <li><b>compareProducts</b>——「这两款哪个划算」要能一次拿到两边的完整参数，
 *       而不是靠两轮搜索在上下文里拼;</li>
 *   <li><b>requestClarification</b>——需求不足以推荐时，<b>问</b>而不是瞎推。
 *       瞎推三件商品，比先问一句「大概什么预算」的体验差一个量级。</li>
 * </ul>
 */
@Component
public class GuideTools {

    private static final int MAX_COMPARE = 4;

    private final CartService cart;
    private final ProductCatalog catalog;
    private final ObjectMapper json;

    GuideTools(CartService cart, ProductCatalog catalog, ObjectMapper json) {
        this.cart = cart;
        this.catalog = catalog;
        this.json = json;
    }

    @Tool("查看当前用户购物袋里的商品和结算试算（小计、满减、运费、应付）。"
            + "当用户问「我的购物袋里有什么」「一共多少钱」「还差多少包邮」「怎么凑单」时使用。"
            + "金额一律以这里返回的为准，不要自己计算。返回 JSON。")
    public String viewCart(@ToolMemoryId String username) {
        CartView view = cart.view(username);
        ObjectNode node = (ObjectNode) json.valueToTree(view);
        node.put("instruction",
                "金额与优惠由系统计算，请直接引用；notes 是可直接告诉用户的凑单与包邮提示。");
        return node.toString();
    }

    @Tool("并排对比多件商品的参数（价格、库存、分类、描述）。"
            + "当用户问「A 和 B 哪个划算/有什么区别/帮我比较一下」时使用，一次拿到全部待比商品，"
            + "不要分多次搜索。参数：ids 为逗号分隔的商品编号，如 \"3,7\"，最多 4 个。"
            + "返回 JSON 数组，字段完整可比。")
    public String compareProducts(String ids) {
        if (ids == null || ids.isBlank()) {
            return error("INVALID_ARGUMENT", "请提供要对比的商品编号，如 \"3,7\"");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (String part : ids.split("[,，\\s]+")) {
            if (part.isBlank()) continue;
            try {
                unique.add(Long.parseLong(part.strip()));
            } catch (NumberFormatException ignored) {
                // 模型偶尔会把商品名塞进来，忽略即可，不因此整轮失败。
            }
        }
        if (unique.isEmpty()) return error("INVALID_ARGUMENT", "没有解析出有效的商品编号");
        if (unique.size() > MAX_COMPARE) return error("TOO_MANY", "一次最多对比 " + MAX_COMPARE + " 件商品");

        ArrayNode array = json.createArrayNode();
        List<String> missing = new ArrayList<>();
        for (Long id : unique) {
            try {
                ProductView p = catalog.get(id);
                ObjectNode node = json.createObjectNode();
                node.put("id", p.id());
                node.put("name", p.name());
                node.put("price", p.price());
                node.put("stock", p.stock());
                node.put("category", p.category());
                node.put("description", p.description());
                array.add(node);
            } catch (BusinessException e) {
                missing.add(String.valueOf(id));
            }
        }
        if (array.isEmpty()) return error("NOT_FOUND", "这些商品都不存在：" + String.join("、", missing));

        ObjectNode result = json.createObjectNode();
        result.set("items", array);
        result.put("instruction",
                "请基于这些真实参数对比，说明各自适合什么场景；不要编造上面没有的参数。"
                        + (missing.isEmpty() ? "" : "编号 " + String.join("、", missing) + " 不存在，已跳过。"));
        return result.toString();
    }

    @Tool("当用户的需求还不足以给出靠谱推荐时，用这个工具发起一次结构化追问，"
            + "系统会把它渲染成可点击的快捷选项，用户一点就能回答。"
            + "适用于：只知道品类不知道预算、不知道送给谁、几个候选难以取舍等情况。"
            + "参数：question 你要问的那句话（简短，一句）；options 2 到 4 个候选答案，用「|」分隔，"
            + "例如 \"100 元以内|100-300 元|300 元以上\"。"
            + "调用后你仍要用自然语言把这个问题说出来，不要只调工具不说话。返回 JSON。")
    public String requestClarification(String question, String options) {
        if (question == null || question.isBlank()) {
            return error("INVALID_ARGUMENT", "请提供要问的问题");
        }
        List<String> chips = new ArrayList<>();
        if (options != null) {
            for (String part : options.split("\\|")) {
                String text = part.strip();
                if (!text.isEmpty() && chips.size() < 4) chips.add(text);
            }
        }
        ObjectNode node = json.createObjectNode();
        node.put("type", "CLARIFICATION");
        node.put("question", question.strip());
        ArrayNode array = json.createArrayNode();
        chips.forEach(array::add);
        node.set("options", array);
        node.put("instruction", "已把选项展示给用户。请用自然语言把这个问题问出来，等用户回答后再推荐。");
        return node.toString();
    }

    private String error(String code, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        return node.toString();
    }
}
