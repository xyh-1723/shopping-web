package dev.learning.mall.assistant.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductQuery;
import dev.learning.mall.catalog.api.ProductSort;
import dev.learning.mall.catalog.api.ProductView;
import dev.langchain4j.agent.tool.Tool;
import java.math.BigDecimal;
import java.util.List;
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
 *
 * <p><b>而且上限必须下推到 SQL</b>：以前这里是先 {@code catalog.list()} 把整个目录
 * 拉进 Java 再 filter + limit。目录 506 件时无感；按 5 万件算，一次「随便看看」
 * 就要把 5 万行读出来、建 5 万个对象，然后扔掉 49988 个只为留下 12 件。
 * 现在筛选、排序、截断都由数据库完成。
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
        List<ProductView> picked = browsing
                ? catalog.sampleAcrossCategories(PER_CATEGORY, MAX_RESULTS)
                : search(kw, cat, maxPrice);
        ArrayNode array = json.createArrayNode();
        picked.forEach(p -> array.add(brief(p)));
        return array.toString();
    }

    /**
     * 带条件的搜索：有过滤条件时保持「先筛再比价」的原语义——
     * 用户已经划了范围，在这个范围里比价才有意义。
     */
    private List<ProductView> search(String keyword, String category, Double maxPrice) {
        ProductQuery query = new ProductQuery(
                keyword.isEmpty() ? null : keyword,
                category.isEmpty() ? null : category,
                maxPrice == null ? null : BigDecimal.valueOf(maxPrice),
                false, ProductSort.PRICE_ASC, 0, MAX_RESULTS);
        return catalog.search(query).items();
    }

    @Tool("按场景（如送礼、自用、办公）和预算（元）推荐商品，返回不超过预算的商品 JSON 数组，含 id、name、price、stock、reason。")
    public String recommendByBudget(String scenario, double budget) {
        String scene = scenario == null || scenario.isBlank() ? "日常" : scenario.strip();
        // 价格降序：预算内挑最贵的，把预算用足（送 100 元的礼不该推 9 块 9 的）。
        // stock > 0 同样下推到 SQL——推一件售罄的商品给用户，比少推一件糟得多。
        ProductQuery query = new ProductQuery(null, null, BigDecimal.valueOf(budget),
                true, ProductSort.PRICE_DESC, 0, 5);
        List<ProductView> candidates = catalog.search(query).items();
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
