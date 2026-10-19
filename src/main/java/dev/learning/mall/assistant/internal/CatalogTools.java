package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 公开工具：任何已登录用户都能用的只读商品查询。对应「最小权限」里的最低档能力。
 * 返回 JSON 字符串（用 ObjectMapper 构造，避免手写字符串拼接带来的转义漏洞）。
 *
 * <p><b>返回值必须有条数上限</b>：工具结果进入模型上下文前会被 harness 裁到
 * {@code mall.assistant.harness.max-tool-result-chars}（默认 4000 字符）。
 * 实测 506 件商品时空关键词搜索会产出 27,417 字符，被裁之后模型只看得见
 * <b>按价格升序的前一段</b>——于是「你们有什么」得到的答案永远是「最便宜的那批」。
 * 上限既是省 token，更是保证模型看到的是有代表性的样本而不是价格榜尾。
 */
@Component
public class CatalogTools {

    /** 单次搜索最多返回几件。约 54 字符/件 × 12 ≈ 650 字符，远在 4000 的裁剪线以下。 */
    private static final int MAX_RESULTS = 12;

    /** 浏览（三个条件全空）时每个分类最多贡献几件：12 件平均分给店里的三个分类。 */
    private static final int PER_CATEGORY = 4;

    private final ProductCatalog catalog;
    private final ObjectMapper json;

    CatalogTools(ProductCatalog catalog, ObjectMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Tool("按关键词、分类或价格上限搜索商品。keyword 可为空；category 取值：生活/文具/数码；maxPrice 可选，单位元。"
            + "最多返回 12 件：不带任何条件时按分类均匀取样（每类最多 4 件）帮你逛全店，带任意条件时按价格升序取前 12 件；"
            + "想看得更全，就用更具体的关键词或分类再查一次。返回 JSON 数组，含 id、name、price、stock。")
    public String searchProducts(String keyword, String category, Double maxPrice) {
        String kw = keyword == null ? "" : keyword.strip().toLowerCase();
        String cat = category == null ? "" : category.strip();
        // 「浏览」= 三个条件全空。此时若按价格升序截前 12 件，等于告诉模型
        // 「本店最值得展示的就是最便宜的 12 件」——500 件商品下这会让助手变成一个
        // 只会推廉价货的助手，用户问「有什么数码产品」和「有什么好东西」得到的是同一批便宜货。
        boolean browsing = kw.isEmpty() && cat.isEmpty() && maxPrice == null;
        List<ProductView> filtered = catalog.list().stream()
                .filter(p -> kw.isEmpty() || (p.name() + p.description()).toLowerCase().contains(kw))
                .filter(p -> cat.isEmpty() || p.category().equals(cat))
                .filter(p -> maxPrice == null || p.price().doubleValue() <= maxPrice)
                .sorted(Comparator.comparing(ProductView::price))
                .toList();
        // 有过滤条件时保持原语义：先筛再比价是合理的（用户已经划了范围，比价才有意义）。
        List<ProductView> picked = browsing
                ? spreadAcrossCategories(filtered)
                : filtered.stream().limit(MAX_RESULTS).toList();
        ArrayNode array = json.createArrayNode();
        picked.forEach(p -> array.add(brief(p)));
        return array.toString();
    }

    /**
     * 跨分类均匀取样：每个分类最多贡献 {@link #PER_CATEGORY} 件（分类内部保持价格升序），
     * 总数不超过 {@link #MAX_RESULTS}。
     *
     * <p>为什么是「按名次轮流取」而不是「按分类切段」：分类之间商品数量并不均衡
     * （数码可能只有 20 件、生活有 300 件），切段会让小分类一件都进不来。
     * 轮流取保证每个分类都出代表；某个分类不足 4 件时，空出的名额再从其余商品里
     * 按价格升序补齐，避免总结果数被最小的分类拖少。
     *
     * @param sortedByPrice 已按价格升序排好的候选集
     */
    private static List<ProductView> spreadAcrossCategories(List<ProductView> sortedByPrice) {
        // TreeMap：分类按名称排序，保证同一批商品的取样结果稳定可复现（不随 list() 返回顺序漂移）。
        Map<String, List<ProductView>> byCategory = new TreeMap<>();
        for (ProductView p : sortedByPrice) {
            byCategory.computeIfAbsent(p.category(), k -> new ArrayList<>()).add(p);
        }
        List<ProductView> picked = new ArrayList<>();
        for (int rank = 0; rank < PER_CATEGORY && picked.size() < MAX_RESULTS; rank++) {
            for (List<ProductView> items : byCategory.values()) {
                if (picked.size() >= MAX_RESULTS) break;
                if (rank < items.size()) picked.add(items.get(rank));
            }
        }
        if (picked.size() < MAX_RESULTS) {
            Set<Long> seen = new HashSet<>();
            picked.forEach(p -> seen.add(p.id()));
            for (ProductView p : sortedByPrice) {
                if (picked.size() >= MAX_RESULTS) break;
                if (seen.add(p.id())) picked.add(p);
            }
        }
        return List.copyOf(picked);
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
