package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.proactive.api.AlertCenter;
import dev.learning.mall.proactive.api.RelatedProduct;
import dev.learning.mall.proactive.internal.AlertService;
import dev.learning.mall.proactive.internal.RelatedRecommendationService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 主动服务的只读工具：让助手能「先开口」。
 *
 * <p>一个只会被动回答的助手，用户不问就永远是沉默的。这一组工具给了助手三个开口的理由：
 * <ul>
 *   <li><b>myAlerts</b>——用户上次走后发生了什么（降价了、补货了）。最适合放在开场，
 *       比「你好，有什么可以帮你」有价值得多。</li>
 *   <li><b>recommendRelated</b>——基于他已有的东西推测他还要什么，每条都带理由。</li>
 *   <li><b>myWatches</b>——他正在盯什么（复用 {@link WatchTools} 的读接口）。</li>
 * </ul>
 *
 * <p>只读，所以不加 {@link dev.learning.mall.assistant.harness.Effectful}：可以和同轮的其他查询并行。
 */
@Component
public class ProactiveTools {

    private static final int MAX_ALERTS_IN_TOOL = 10;

    private final AlertService alerts;
    private final RelatedRecommendationService recommendations;
    private final ObjectMapper json;

    ProactiveTools(AlertService alerts, RelatedRecommendationService recommendations, ObjectMapper json) {
        this.alerts = alerts;
        this.recommendations = recommendations;
        this.json = json;
    }

    @Tool("查看当前用户收到的主动提醒（关注的商品降价了、补货了）。无需参数。"
            + "适合在对话开始时先看一眼：如果用户上次走后发生了好事，主动告诉他比等他问更有价值。"
            + "返回 JSON，含未读数与提醒列表。")
    public String myAlerts(@ToolMemoryId String username) {
        AlertCenter center = alerts.center(username);
        ArrayNode array = json.createArrayNode();
        center.alerts().stream().limit(MAX_ALERTS_IN_TOOL).forEach(alert -> {
            ObjectNode node = json.createObjectNode();
            node.put("type", alert.type());
            node.put("title", alert.title());
            node.put("content", alert.content());
            node.put("productId", alert.productId());
            node.put("read", alert.read());
            array.add(node);
        });
        ObjectNode result = json.createObjectNode();
        result.put("unread", center.unread());
        result.set("alerts", array);
        result.put("note", "这些是系统已经发过的提醒，不要编造新的降价或库存信息。");
        return result.toString();
    }

    @Tool("给当前用户推荐几件他可能感兴趣的商品。推荐依据是：他购物袋里的、他买过的商品的同类或常被一起购买的商品；"
            + "没有历史时会给出最近较受欢迎的商品。每件都带理由。"
            + "参数：limit 最多几件（1-5，默认 3）。"
            + "返回 JSON 数组，含 id、name、price、stock、reason。"
            + "向用户介绍时请把 reason 自然地说出来，不要只丢商品名。")
    public String recommendRelated(@ToolMemoryId String username, Integer limit) {
        List<RelatedProduct> products = recommendations.recommend(username, limit);
        ArrayNode array = json.createArrayNode();
        for (RelatedProduct product : products) {
            ObjectNode node = json.createObjectNode();
            node.put("id", product.id());
            node.put("name", product.name());
            node.put("category", product.category());
            node.put("price", product.price());
            node.put("stock", product.stock());
            node.put("reason", product.reason());
            array.add(node);
        }
        if (array.isEmpty()) {
            return "[]";
        }
        return array.toString();
    }
}
