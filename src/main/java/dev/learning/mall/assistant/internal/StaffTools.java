package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.security.AssistantActors;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.learning.mall.order.api.OrderInsights;
import dev.learning.mall.order.api.OrderView;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 店员专属工具（STAFF 角色）。这是权限分级的第二道闸门，也是最关键的一道。
 *
 * <p>为什么工具内部还要再查一次角色？因为工具清单虽然按角色下发了，但模型是概率性的：
 * 一次成功的提示词注入完全可能让它「试图」调用它本不该有的工具。所以：
 * <ul>
 *   <li>第一道：按角色下发工具清单（模型根本看不到越权工具的声明）。</li>
 *   <li>第二道：工具方法内用登录账号的角色硬校验（对话里自称管理员一律无效）。</li>
 *   <li>第三道：越权尝试写入审计表，便于发现有人在探测。</li>
 * </ul>
 *
 * <p>{@code @ToolMemoryId} 参数由框架注入当前登录用户名，且不会出现在给模型看的参数列表里，
 * 模型无法伪造调用者身份——这一点比「把用户名当普通参数传」重要得多。
 */
@Component
public class StaffTools {

    private static final String DENIED_NOTICE = "当前账号没有店员权限，无法查询经营数据或其他用户的订单。";

    private final ProductCatalog catalog;
    private final OrderInsights insights;
    private final AssistantActors actors;
    private final AssistantAudit audit;
    private final ObjectMapper json;

    StaffTools(ProductCatalog catalog, OrderInsights insights, AssistantActors actors,
               AssistantAudit audit, ObjectMapper json) {
        this.catalog = catalog;
        this.insights = insights;
        this.actors = actors;
        this.audit = audit;
        this.json = json;
    }

    @Tool("店员专属：列出库存低于阈值（默认 10 件）的商品与补货建议。只有店员账号可调用。返回 JSON 数组。")
    public String inventoryAlerts(@ToolMemoryId String username, Double threshold) {
        if (denied(username, "inventoryAlerts")) return denial();
        double limit = threshold == null || threshold <= 0 ? 10 : threshold;
        List<ProductView> low = catalog.list().stream()
                .filter(p -> p.stock() < limit)
                .sorted(Comparator.comparingInt(ProductView::stock))
                .toList();
        ArrayNode array = json.createArrayNode();
        for (ProductView p : low) {
            ObjectNode node = json.createObjectNode();
            node.put("id", p.id());
            node.put("name", p.name());
            node.put("stock", p.stock());
            node.put("suggestion", p.stock() == 0 ? "已售罄，建议优先补货" : "库存偏低，建议补货");
            array.add(node);
        }
        return array.toString();
    }

    @Tool("店员专属：查看全站经营概览——订单总数、各状态数量、已支付金额与销量前三的商品。只有店员账号可调用。")
    public String salesOverview(@ToolMemoryId String username) {
        if (denied(username, "salesOverview")) return denial();
        OrderInsights.OrderStats stats = insights.stats();
        ObjectNode node = json.createObjectNode();
        node.put("totalOrders", stats.total());
        node.put("pending", stats.pending());
        node.put("paid", stats.paid());
        node.put("cancelled", stats.cancelled());
        node.put("paidAmount", stats.paidAmount());
        ArrayNode top = node.putArray("topProducts");
        for (OrderInsights.TopProduct p : stats.topProducts()) {
            ObjectNode item = json.createObjectNode();
            item.put("id", p.productId());
            item.put("name", p.productName());
            item.put("quantity", p.quantity());
            top.add(item);
        }
        return node.toString();
    }

    @Tool("店员专属：按用户名查询该用户的订单列表。只有店员账号可调用；普通顾客只能查看自己的订单。返回 JSON 数组。")
    public String customerOrders(@ToolMemoryId String username, String targetUser) {
        if (denied(username, "customerOrders")) return denial();
        String target = targetUser == null ? "" : targetUser.strip();
        if (target.isEmpty()) {
            ObjectNode err = json.createObjectNode();
            err.put("error", "INVALID_ARGUMENT");
            err.put("message", "请提供要查询的用户名");
            return err.toString();
        }
        List<OrderView> orders = insights.ordersOf(target);
        ArrayNode array = json.createArrayNode();
        for (OrderView o : orders) {
            ObjectNode node = json.createObjectNode();
            node.put("id", o.id());
            node.put("username", target);
            node.put("status", o.status());
            node.put("total", o.total());
            node.put("createdAt", String.valueOf(o.createdAt()));
            array.add(node);
        }
        return array.toString();
    }

    /** 角色硬校验：以登录账号的角色为准，任何时候都不信任对话内容。 */
    private boolean denied(String username, String toolName) {
        if (!actors.resolve(username).isStaff()) {
            audit.record(username, null, AssistantAudit.STAGE_TOOL, "DENY",
                    "TOOL_FORBIDDEN_" + toolName, "LLM06", "越权调用店员工具 " + toolName);
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
