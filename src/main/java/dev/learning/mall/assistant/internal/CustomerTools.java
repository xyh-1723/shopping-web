package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderView;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 顾客自助工具：只能看到「自己」的数据。
 * 与 {@link StaffTools#customerOrders} 对照看——同一个数据源，作用域由角色决定，
 * 而不是由对话内容或模型判断决定。
 */
@Component
public class CustomerTools {

    private final OrderInsights insights;
    private final ObjectMapper json;

    CustomerTools(OrderInsights insights, ObjectMapper json) {
        this.insights = insights;
        this.json = json;
    }

    @Tool("查询当前登录用户自己的订单列表（含状态、金额、下单时间）。无需参数。返回 JSON 数组。")
    public String myOrders(@ToolMemoryId String username) {
        List<OrderView> orders = insights.ordersOf(username);
        ArrayNode array = json.createArrayNode();
        for (OrderView o : orders) {
            ObjectNode node = json.createObjectNode();
            node.put("id", o.id());
            node.put("status", o.status());
            node.put("total", o.total());
            node.put("createdAt", String.valueOf(o.createdAt()));
            node.put("owner", "本人");
            array.add(node);
        }
        return array.toString();
    }
}
