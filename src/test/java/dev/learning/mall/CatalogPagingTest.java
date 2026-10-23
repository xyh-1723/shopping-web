package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.learning.mall.catalog.api.LowStockReport;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductPage;
import dev.learning.mall.catalog.api.ProductQuery;
import dev.learning.mall.catalog.api.ProductSort;
import dev.learning.mall.catalog.api.ProductView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 商品列表<b>分页</b>的守护：守的是「目录变大时，列表的代价不跟着变大」。
 *
 * <p>为什么必须有这个类：改造之前的实测是——目录从 506 件涨到 1006 件，
 * {@code GET /api/products} 的响应体从 116KB 涨到 227KB，翻一倍涨一倍。
 * 那是"没有分页"的直接后果，而它不会让任何既有断言变红：
 * 接口照样 200、数据照样对，只是每一次列表都多传一倍字节。
 * 这类问题只能靠"专门为它写一条断言"来守住。
 *
 * <p><b>反向验证</b>：把 {@link ProductController} 改回不带 {@code size} 的
 * {@code SELECT * FROM product}，{@link #responseBytesStayFlatWhenCatalogDoubles()}
 * 必须立刻变红（实测会从"差几字节"变成"差一倍"）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogPagingTest {

    /** 填充商品的起始 id：既有目录占 1..506，清理时按 {@code id >= 本值} 删，互不干扰。 */
    private static final long FILLER_ID_BASE = 800_000L;

    @Autowired ProductCatalog catalog;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @AfterEach
    void removeFillers() {
        jdbc.sql("DELETE FROM stock_ledger WHERE product_id >= :base").param("base", FILLER_ID_BASE).update();
        jdbc.sql("DELETE FROM product WHERE id >= :base").param("base", FILLER_ID_BASE).update();
    }

    @Test
    @DisplayName("目录翻倍，列表接口的响应字节数几乎不变：这就是分页的意义")
    void responseBytesStayFlatWhenCatalogDoubles() throws Exception {
        int before = bytesOf("/api/products");
        int beforeCount = countProducts();
        seed(500);
        int after = bytesOf("/api/products");
        int afterCount = countProducts();

        assertThat(afterCount).isEqualTo(beforeCount + 500);
        // 分页的意义就在这条断言上：目录规模翻了一倍，而响应体只多了"total 多一位数字"。
        // 改造前这一对数字是 116KB → 227KB（+96%）；现在应该只差几个字节。
        assertThat(after - before)
                .as("目录从 %d 件涨到 %d 件，列表响应体从 %d 字节变成 %d 字节——差值应当只来自 total 的位数",
                        beforeCount, afterCount, before, after)
                .isBetween(-8, 8);
        System.out.printf("  目录 %d → %d 件：列表响应体 %d → %d 字节（差值 %d）%n",
                beforeCount, afterCount, before, after, after - before);
    }

    @Test
    @DisplayName("total 报全库件数，items 只有一页：两个数不能混")
    void totalIsWholeCatalogWhileItemsAreCapped() {
        ProductPage page = catalog.search(new ProductQuery(null, null, null, false, ProductSort.ID_ASC, 0, 5));
        assertThat(page.total()).isEqualTo(countProducts());
        assertThat(page.items()).hasSize(5);
        assertThat(page.size()).isEqualTo(5);
        assertThat(page.page()).isZero();
    }

    @Test
    @DisplayName("翻页不重不漏：连续几页拼起来正好是全库，且没有重复")
    void pagesTileTheCatalogWithoutOverlap() {
        int total = countProducts();
        List<Long> seen = new java.util.ArrayList<>();
        for (int p = 0; p * 100 < total; p++) {
            seen.addAll(catalog.search(new ProductQuery(null, null, null, false, ProductSort.ID_ASC, p, 100))
                    .items().stream().map(ProductView::id).toList());
        }
        assertThat(seen).as("所有页拼起来应当正好等于全库件数").hasSize(total);
        assertThat(new java.util.HashSet<>(seen)).as("同一件商品不该出现在两页上").hasSize(total);
    }

    @Test
    @DisplayName("筛选条件下推到 SQL：关键词、分类、价格上限、只看有货都真的生效")
    void filtersAreApplied() {
        // 关键词：命中数必须严格小于全库，否则说明条件没生效（或退化成全表）。
        ProductPage byKeyword = catalog.search(new ProductQuery("笔记本", null, null, false, ProductSort.ID_ASC, 0, 100));
        assertThat(byKeyword.total()).isPositive().isLessThan(countProducts());
        assertThat(byKeyword.items()).allMatch(p -> (p.name() + p.description()).contains("笔记本"));

        ProductPage byCategory = catalog.search(new ProductQuery(null, "数码", null, false, ProductSort.ID_ASC, 0, 100));
        assertThat(byCategory.total()).isPositive().isLessThan(countProducts());
        assertThat(byCategory.items()).allMatch(p -> p.category().equals("数码"));

        ProductPage byPrice = catalog.search(new ProductQuery(null, null, new BigDecimal("30.00"),
                false, ProductSort.ID_ASC, 0, 100));
        assertThat(byPrice.items()).allMatch(p -> p.price().compareTo(new BigDecimal("30.00")) <= 0);

        // 只看有货：把 1 号商品库存打到 0 之后，它必须从结果里消失。
        jdbc.sql("UPDATE product SET stock = 0 WHERE id = 1").update();
        try {
            // 注意别用 ProductQuery.firstPage()：它是 inStockOnly = false 的便捷构造。
            ProductQuery onlyInStock = new ProductQuery(null, null, null, true, ProductSort.ID_ASC, 0, 100);
            assertThat(catalog.search(onlyInStock).items()).noneMatch(p -> p.id() == 1L);
            // inStockOnly = false 时它仍应在（两种语义都要有断言，否则反转一下也照样绿）。
            assertThat(catalog.search(ProductQuery.firstPage(100)).items()).anyMatch(p -> p.id() == 1L);
        } finally {
            jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
        }
    }

    @Test
    @DisplayName("排序：价格升序 / 降序真的生效，且同价时结果稳定")
    void sortingIsApplied() {
        List<ProductView> asc = catalog.search(new ProductQuery(null, null, null, false,
                ProductSort.PRICE_ASC, 0, 20)).items();
        for (int i = 1; i < asc.size(); i++)
            assertThat(asc.get(i).price()).isGreaterThanOrEqualTo(asc.get(i - 1).price());

        List<ProductView> desc = catalog.search(new ProductQuery(null, null, null, false,
                ProductSort.PRICE_DESC, 0, 20)).items();
        for (int i = 1; i < desc.size(); i++)
            assertThat(desc.get(i).price()).isLessThanOrEqualTo(desc.get(i - 1).price());

        // 同价时按 id 兜底：同一条查询跑两次必须得到同一个顺序，否则前端翻页会重复/漏行。
        assertThat(catalog.search(ProductQuery.firstPage(20)).items())
                .containsExactlyElementsOf(catalog.search(ProductQuery.firstPage(20)).items());
    }

    /**
     * 用户输入的 {@code %} 与 {@code _} 必须被转义。
     *
     * <p>不转义时 {@code LIKE '%%'} 匹配一切、{@code LIKE '%_%'} 也匹配一切——
     * 于是搜一个带下划线的型号会返回整个目录。它不报错，只是"结果莫名其妙地多"，
     * 所以必须专门断言"命中数远小于全库"。
     */
    @Test
    @DisplayName("关键词里的 % 和 _ 被转义：不会退化成通配符把整个目录捞回来")
    void likeWildcardsInKeywordAreEscaped() {
        int total = countProducts();
        for (String tricky : List.of("50%", "A_B", "%", "_", "%%")) {
            ProductPage page = catalog.search(new ProductQuery(tricky, null, null, false, ProductSort.ID_ASC, 0, 100));
            assertThat(page.total())
                    .as("关键词 %s 不能退化成通配符", tricky)
                    .isLessThan(total);
        }
        // 正向对照：确实存在名字里带真实内容的关键词仍然搜得到。
        assertThat(catalog.search(new ProductQuery("马克杯", null, null, false, ProductSort.ID_ASC, 0, 100)).total())
                .isPositive();
    }

    /**
     * 跨分类取样的两条性质：<b>每个分类都出代表</b>，<b>总数不超过上限</b>。
     *
     * <p>为什么不断言"每个分类不超过配额"：配额不足时实现会<b>故意</b>去其余商品里
     * 补齐到 {@code limit}（分类之间数量不均衡时，空着的名额不给白不给）。
     * 所以"每类 ≤ perCategory"只在配额够用的时候成立，不是一条通用性质。
     */
    @Test
    @DisplayName("跨分类取样：每个分类都出代表，总数不超过上限")
    void sampleAcrossCategoriesRespectsCaps() {
        int categoryCount = jdbc.sql("SELECT COUNT(DISTINCT category) FROM product").query(Integer.class).single();

        // 每类只给 1 个名额、上限正好等于分类数：此时不可能触发补足，
        // 于是"每个分类恰好 1 件"是这条性质最干净的断言形式。
        List<ProductView> spread = catalog.sampleAcrossCategories(1, categoryCount);
        Map<String, Long> byCategory = new TreeMap<>();
        spread.forEach(p -> byCategory.merge(p.category(), 1L, Long::sum));
        assertThat(byCategory).hasSize(categoryCount);
        assertThat(byCategory.values()).containsOnly(1L);

        // 上限是硬约束：不管目录多大，一次取样都不会超过 limit 件。
        List<ProductView> capped = catalog.sampleAcrossCategories(4, 12);
        assertThat(capped).hasSizeLessThanOrEqualTo(12);
        assertThat(capped.stream().map(ProductView::category).collect(java.util.stream.Collectors.toSet()))
                .as("取样必须覆盖到多个分类，只从一个分类里取不算「逛全店」")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("低库存告警：明细被截断，但 total 报的是真实总数（不是这一页的件数）")
    void lowStockTotalIsNotCappedByTheItemLimit() {
        // 造出比 limit 更多的低库存商品，让"总数"和"列出的件数"必须是两个不同的数。
        seed(30);
        LowStockReport report = catalog.lowStock(10, 5);
        assertThat(report.total()).isGreaterThan(report.items().size());
        assertThat(report.items()).hasSize(5);
        // 明细按库存从少到多：最紧张的必须排在最前面。
        for (int i = 1; i < report.items().size(); i++)
            assertThat(report.items().get(i).stock()).isGreaterThanOrEqualTo(report.items().get(i - 1).stock());
        assertThat(report.items()).allMatch(p -> p.stock() < 10);
    }

    @Test
    @DisplayName("分页参数越界返回 400 而不是 500：客户端传错参数是 4xx")
    void outOfRangePagingParamsAreRejected() throws Exception {
        assertThat(statusOf("/api/products?size=101"))
                .as("size 超过单页上限应返回 400，而不是让 ProductQuery 抛异常变成 500")
                .isEqualTo(400);
        assertThat(statusOf("/api/products?size=0")).isEqualTo(400);
        assertThat(statusOf("/api/products?page=-1")).isEqualTo(400);
        assertThat(statusOf("/api/products?size=24")).isEqualTo(200);
    }

    // ————————————————————————— 小工具 —————————————————————————

    private int statusOf(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getStatus();
    }

    private int bytesOf(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getContentAsByteArray().length;
    }

    private int countProducts() {
        return jdbc.sql("SELECT COUNT(*) FROM product").query(Integer.class).single();
    }

    /**
     * 插 {@code count} 件填充商品。<b>全部库存设为 1</b>，
     * 这样 {@link #lowStockTotalIsNotCappedByTheItemLimit} 能造出足够多的低库存行。
     */
    private void seed(int count) {
        String[] categories = {"生活", "文具", "数码"};
        for (int i = 0; i < count; i++) {
            jdbc.sql("INSERT INTO product(id, name, description, category, illustration, price, stock,"
                            + " merchant_id) VALUES(:id,:n,:d,:c,'box',:p,:s,1)")
                    // merchant_id 在 V24 之后是 NOT NULL 且没有默认值：造商品必须回答「谁的货」。
                    // 这些填充商品与归属无关（用例验证的是分页 / 压缩 / 告警上限），
                    // 一律挂 1 号店——写死一个确定的答案，比在这里再发明一套分配规则好。
                    .param("id", FILLER_ID_BASE + i)
                    .param("n", "填充商品" + i)
                    .param("d", "分页守护用的填充商品，编号 " + i + "。")
                    .param("c", categories[i % categories.length])
                    .param("p", 10 + (i % 200))
                    .param("s", 1)
                    .update();
        }
    }
}
