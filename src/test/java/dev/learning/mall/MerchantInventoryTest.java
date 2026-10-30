package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 商家中心：<b>按店看自己的商品与库存</b>。
 *
 * <p>这里守的不是「多了一个页面」，而是一条换了实现方式就会被推翻的性质：
 * <b>商家 A 打不开、也搜不到商家 B 的货</b>。请求里根本没有店铺号这个参数——
 * 归属由 {@code merchant.owner_username} 与登录账号推导，所以「改 URL 看别家库存」
 * 这条路不是被拦住的，而是不存在。
 *
 * <h3>两条纪律（与 {@link MerchantOwnershipTest} 同源）</h3>
 * <ul>
 *   <li><b>身份走真实链路</b>：账号交给 {@link UserDetailsService} 解析，不手造
 *       {@code ROLE_MERCHANT} 的 principal——否则 SecurityConfig 那条规则没被真正走到。</li>
 *   <li><b>数字回查数据库</b>：概览里的存量不写死，用 SQL 现算。V24 的商品分配一旦调整，
 *       写死的数字会红一大批与「按店隔离」毫无关系的用例。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantInventoryTest {

    /** 每家店的 SKU 数（V24：5000 件 / 200 家）。 */
    private static final int SKU_PER_SHOP = 25;

    @Autowired MockMvc mvc;
    @Autowired MerchantDirectory merchants;
    @Autowired UserDetailsService userDetails;
    @Autowired JdbcClient jdbc;

    // ——————————————————— ① 只看自己店 ———————————————————

    @Test
    @DisplayName("① 商家只拿到自己店的商品：每一行的 merchantId 都是本店，且没有可传的店铺参数")
    void merchantSeesOnlyItsOwnShop() throws Exception {
        MerchantView shop = shopOf("merchant");

        String body = inventoryOf("merchant", 0, 100, null);

        assertThat((Integer) JsonPath.read(body, "$.merchant.id")).isEqualTo((int) shop.id());
        assertThat((Integer) JsonPath.read(body, "$.page.total")).isEqualTo(SKU_PER_SHOP);
        assertThat((Integer) JsonPath.read(body, "$.page.items.length()")).isEqualTo(SKU_PER_SHOP);
        List<Integer> owners = JsonPath.read(body, "$.page.items[*].merchantId");
        assertThat(owners).as("表格里不能混进任何一家别家的货").containsOnly((int) shop.id());
    }

    @Test
    @DisplayName("② 概览覆盖整家店，不随分页变化：翻到第二页，三个数字必须原地不动")
    void summaryDescribesTheWholeShopNotTheCurrentPage() throws Exception {
        MerchantView shop = shopOf("merchant");
        int count = jdbc.sql("SELECT COUNT(*) FROM product WHERE merchant_id = :m")
                .param("m", shop.id()).query(Integer.class).single();
        int stock = jdbc.sql("SELECT COALESCE(SUM(stock), 0) FROM product WHERE merchant_id = :m")
                .param("m", shop.id()).query(Integer.class).single();
        int out = jdbc.sql("SELECT COUNT(*) FROM product WHERE merchant_id = :m AND stock = 0")
                .param("m", shop.id()).query(Integer.class).single();

        String first = inventoryOf("merchant", 0, 5, null);
        String second = inventoryOf("merchant", 1, 5, null);

        assertThat((Integer) JsonPath.read(first, "$.summary.productCount")).isEqualTo(count);
        assertThat((Integer) JsonPath.read(first, "$.summary.stockTotal")).isEqualTo(stock);
        assertThat((Integer) JsonPath.read(first, "$.summary.outOfStockCount")).isEqualTo(out);

        // 反向控制：分页真的生效了（否则「两页一致」可能只是根本没翻页）。
        assertThat((Integer) JsonPath.read(first, "$.page.items.length()")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(second, "$.page.items.length()")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(second, "$.page.page")).isEqualTo(1);

        Object summaryOnFirstPage = JsonPath.read(first, "$.summary");
        Object summaryOnSecondPage = JsonPath.read(second, "$.summary");
        assertThat(summaryOnSecondPage)
                .as("概览回答的是整家店，用当前页累加会得到一个随翻页变化的假数字")
                .isEqualTo(summaryOnFirstPage);
    }

    // ——————————————————— ② 两家店互不相见 ———————————————————

    @Test
    @DisplayName("③ 两家店互不相见：商品集合不重叠，A 店里搜不到的货在 B 店也搜不到")
    void twoShopsNeverSeeEachOther() throws Exception {
        MerchantView mine = shopOf("merchant");
        MerchantView other = merchants.all().stream()
                .filter(shop -> shop.id() != mine.id()).findFirst().orElseThrow();

        List<Integer> myIds = JsonPath.read(
                inventoryOf("merchant", 0, 100, null), "$.page.items[*].id");
        List<Integer> theirIds = JsonPath.read(
                inventoryOf(other.ownerUsername(), 0, 100, null), "$.page.items[*].id");

        assertThat(myIds).hasSize(SKU_PER_SHOP);
        assertThat(theirIds).hasSize(SKU_PER_SHOP);
        assertThat(myIds).as("两家的商品集合必须完全不相交").doesNotContainAnyElementsOf(theirIds);

        // 关键词也只在本店生效。选一个「对方的商品名与描述里完全不出现」的名字，
        // 否则 LIKE 的包含匹配会把别人的商品也算进来，用例就变成在赌文案。
        String unique = jdbc.sql("SELECT p.name FROM product p WHERE p.merchant_id = :mine"
                        + " AND NOT EXISTS (SELECT 1 FROM product q WHERE q.merchant_id = :other"
                        + "     AND (q.name LIKE CONCAT('%', p.name, '%')"
                        + "       OR q.description LIKE CONCAT('%', p.name, '%')))"
                        + " ORDER BY p.id LIMIT 1")
                .param("mine", mine.id()).param("other", other.id())
                .query(String.class).single();

        int mineHits = JsonPath.read(inventoryOf("merchant", 0, 24, unique), "$.page.total");
        int theirHits = JsonPath.read(
                inventoryOf(other.ownerUsername(), 0, 24, unique), "$.page.total");

        assertThat(mineHits).as("本店必须搜得到自己的货").isGreaterThanOrEqualTo(1);
        assertThat(theirHits).as("别家店搜同一个词必须一无所获——这就是「按店收窄」").isZero();
    }

    // ——————————————————— ③ 谁能进 ———————————————————

    @Test
    @DisplayName("④ 买家与店员都读不到：这是按店收窄的数据，不是运营报表；未登录是 401")
    void onlyMerchantsCanReadInventory() throws Exception {
        mvc.perform(get("/api/merchant/inventory").with(account("student")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/inventory").with(account("admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/inventory"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⑤ 页面是壳、接口才是边界：商家打开看到自己的店名，买家看到「没有关联店铺」而不是一段 JSON 403")
    void pageIsOnlyAShell() throws Exception {
        MerchantView shop = shopOf("merchant");

        mvc.perform(get("/merchant").with(account("merchant")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(shop.name())))
                .andExpect(content().string(containsString(shop.code())));

        mvc.perform(get("/merchant").with(account("student")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("当前账号没有关联的店铺")));
    }

    // ——————————————————— 工具 ———————————————————

    /**
     * 一次商家中心读取。关键词走 {@code .param(...)} 而不是拼进 URL：
     * 商品名里有空格与标点，手工拼查询串会把「编码是否正确」混进这组用例的成败里。
     */
    private String inventoryOf(String username, int page, int size, String keyword) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/merchant/inventory")
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size));
        if (keyword != null) request = request.param("keyword", keyword);
        return mvc.perform(request.with(account(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private MerchantView shopOf(String owner) {
        return merchants.ofOwner(owner).orElseThrow();
    }

    /** 身份一律从 {@link UserDetailsService} 解析，避免手造一个带角色的 principal。 */
    private RequestPostProcessor account(String username) {
        return user(userDetails.loadUserByUsername(username));
    }
}
