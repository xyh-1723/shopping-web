package dev.learning.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.assistant.internal.CatalogTools;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code searchProducts} 的规模守护（B2）。
 *
 * <p>守的是两件事，缺一件这条测试就是半条：
 * <ol>
 *   <li><b>条数上限</b>：结果会被 harness 裁到 {@code max-tool-result-chars}（4000 字符）。
 *       506 件时空搜索产出 27,417 字符，裁完之后模型只看得见最便宜的一段——
 *       不设上限等于让「价格升序」悄悄变成了「筛选条件」。</li>
 *   <li><b>浏览语义</b>：三个条件全空是「你们有什么」，不是「最便宜的 12 件」。
 *       500 件下这两者的差距足以让助手退化成只会推廉价货的助手——
 *       用户问「有什么数码产品」和「有什么好东西」，得到的会是同一批便宜货。</li>
 * </ol>
 *
 * <p>反向验证要点：变异（a）去掉上限 → 断言①红；变异（b）保留上限但改回纯价格升序 → 断言②红。
 * 只红一条就说明还差一半。
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogToolsScaleLimitTest {

    /** 造出来的商品 id 起点：既有种子只占 1..6，清理时按 id > 900000 删。 */
    private static final long FIXTURE_ID_BASE = 900_000L;

    /** 每个分类造几件：三档价格各 12 件，纯价格升序取前 12 会全落在「生活」里。 */
    private static final int PER_CATEGORY = 12;

    private static final String[] CATEGORIES = {"生活", "文具", "数码"};

    /** 三个分类的起始价格，刻意分档，让「最便宜的 12 件」只来自一个分类。 */
    private static final int[] PRICE_BASES = {1, 100, 200};

    @Autowired CatalogTools tools;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM product WHERE id > :base").param("base", FIXTURE_ID_BASE).update();
    }

    @Test
    @DisplayName("浏览（三个条件全空）时必须跨分类取样，而不是吐出全店最便宜的一批")
    void browseSpreadsAcrossCategoriesAndStaysUnderLimit() throws Exception {
        seedFixture();

        JsonNode array = readArray(tools.searchProducts("", null, null));

        // 断言①：条数上限。去掉 .limit(12) 后这里会拿到 36 件 → 红。
        assertThat(array)
                .as("空搜索必须限制条数：不限制的话结果被裁到 4000 字符后只剩最便宜的一段")
                .hasSizeLessThanOrEqualTo(12);

        // 断言②：跨分类。改回纯价格升序后这里只会有「生活」→ 红。
        assertThat(categoriesOf(array))
                .as("「你们有什么」的答案里，三个分类都该有代表商品")
                .containsExactlyInAnyOrder("生活", "文具", "数码");
    }

    @Test
    @DisplayName("空白的 keyword / category 也算「无条件」，仍然走跨分类取样")
    void blankArgumentsAreStillBrowsing() throws Exception {
        seedFixture();

        assertThat(categoriesOf(readArray(tools.searchProducts("   ", "  ", null))))
                .as("空白参数等价于不带条件")
                .containsExactlyInAnyOrder("生活", "文具", "数码");
        assertThat(categoriesOf(readArray(tools.searchProducts(null, null, null))))
                .as("null 参数等价于不带条件")
                .containsExactlyInAnyOrder("生活", "文具", "数码");
    }

    @Test
    @DisplayName("带过滤条件时保持原语义：先筛再按价格升序，且仍不超过 12 件")
    void filteredSearchKeepsPriceOrderAndLimit() throws Exception {
        seedFixture();

        JsonNode byCategory = readArray(tools.searchProducts("", "数码", null));
        assertThat(byCategory).hasSizeLessThanOrEqualTo(12);
        assertThat(categoriesOf(byCategory)).containsExactly("数码");
        assertThat(pricesOf(byCategory)).isSorted();

        JsonNode byPrice = readArray(tools.searchProducts("", null, 50.0));
        assertThat(byPrice).hasSizeLessThanOrEqualTo(12);
        assertThat(pricesOf(byPrice)).isSorted().allMatch(p -> p <= 50.0);

        JsonNode byKeyword = readArray(tools.searchProducts("数码", null, null));
        assertThat(byKeyword).hasSizeLessThanOrEqualTo(12);
        assertThat(categoriesOf(byKeyword)).containsExactly("数码");
    }

    private JsonNode readArray(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        assertThat(node.isArray()).as("searchProducts 返回 JSON 数组").isTrue();
        return node;
    }

    /** brief() 不含 category 字段，所以用商品名前缀反查分类。 */
    private static Set<String> categoriesOf(JsonNode array) {
        Set<String> cats = new LinkedHashSet<>();
        array.forEach(node -> {
            String name = node.get("name").asText();
            for (String c : CATEGORIES) {
                if (name.startsWith(c)) cats.add(c);
            }
        });
        return cats;
    }

    private static List<Double> pricesOf(JsonNode array) {
        List<Double> prices = new ArrayList<>();
        array.forEach(node -> prices.add(node.get("price").asDouble()));
        return prices;
    }

    private void seedFixture() {
        for (int c = 0; c < CATEGORIES.length; c++) {
            for (int i = 0; i < PER_CATEGORY; i++) {
                long id = FIXTURE_ID_BASE + (long) c * PER_CATEGORY + i + 1;
                jdbc.sql("INSERT INTO product(id, name, description, category, illustration, price, stock) "
                                + "VALUES(:id,:n,:d,:c,'box',:p,:s)")
                        .param("id", id)
                        .param("n", CATEGORIES[c] + "商品" + i)
                        .param("d", "扩容填充商品，用于验证搜索结果上限与跨分类取样。")
                        .param("c", CATEGORIES[c])
                        .param("p", PRICE_BASES[c] + i)
                        .param("s", 50)
                        .update();
            }
        }
    }
}
