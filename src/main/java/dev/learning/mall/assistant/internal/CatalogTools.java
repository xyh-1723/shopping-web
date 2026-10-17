package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.langchain4j.agent.tool.Tool;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 公开工具：任何已登录用户都能用的只读商品查询。对应「最小权限」里的最低档能力。
 * 返回 JSON 字符串（用 ObjectMapper 构造，避免手写字符串拼接带来的转义漏洞）。
 */
@Component
public class CatalogTools {

    private final ProductCatalog catalog;
    private final ObjectMapper json;

    CatalogTools(ProductCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Tool("按关键词、分类或价格上限搜索商品。keyword 可为空；category 取值：生活/文具/数码；maxPrice 可选，单位元。返回 JSON 数组，含 id、name、price、stock。")
    public String searchProducts(String keyword, String category, Double maxPrice) {
        String kw = keyword == null ? "" : keyword.strip().toLowerCase();
        List<ProductView> filtered = catalog.list().stream()
                .filter(p -> kw.isEmpty() || (p.name() + p.description()).toLowerCase().contains(kw))
                .filter(p -> category == null || category.isBlank() || p.category().equals(category.strip()))
                .filter(p -> maxPrice == null || p.price().doubleValue() <= maxPrice)
                .sorted(Comparator.comparing(ProductView::price))
                .toList();
        ArrayNode array = json.createArrayNode();
        filtered.forEach(p -> array.add(brief(p)));
        return array.toString();
    }

    @Tool("按场景（如送礼、自用、办公）和预算（元）推荐商品，返回不超过预算的商品 JSON 数组，含 id、name、price、stock、reason。")
    public String recommendByBudget(String scenario, double budget) {
        String scene = scenario == null || scenario.isBlank() ? "日常" : scenario.strip();
        List<ProductView> candidates = catalog.list().stream()
                .filter(p -> p.stock() > 0 && p.price().doubleValue() <= budget)
                .sorted(Comparator.comparing(ProductView::price).reversed())
                .limit(5)
                .toList();
        ArrayNode array = json.createArrayNode();
        for (ProductView p : candidates) {
            ObjectNode node = brief(p);
            node.put("reason", "适合" + scene);
            array.add(node);
        }
        return array.toString();
    }

    @Tool("查询单个商品的完整详情，包括名称、描述、分类、价格和库存。传入商品编号 id。返回 JSON 对象。")
    public String getProduct(long id) {
        ProductView p = catalog.get(id);
        ObjectNode node = brief(p);
        node.put("description", p.description());
        node.put("category", p.category());
        return node.toString();
    }

    /** 商品卡片需要的公共字段，顺序固定：id、name、price、stock。 */
    private ObjectNode brief(ProductView p) {
        ObjectNode node = json.createObjectNode();
        node.put("id", p.id());
        node.put("name", p.name());
        node.put("price", p.price());
        node.put("stock", p.stock());
        return node;
    }
}
