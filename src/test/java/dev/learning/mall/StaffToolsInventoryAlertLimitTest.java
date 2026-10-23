package dev.learning.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.assistant.internal.StaffTools;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code inventoryAlerts} 的规模守护（B3）。
 *
 * <p>500 件商品下低库存的可能有几百件。这个工具原来把筛选结果<b>全部</b>塞进返回给模型的
 * JSON，于是一次调用就把整个上下文预算吃光，再被 harness 裁到 4000 字符——
 * 工具等于失效：店员问「哪些要补货」，模型只看得到一部分，还以为看全了。
 *
 * <p>所以这里守两条，缺一条就是半条：
 * <ol>
 *   <li><b>明细条数有上限</b>（20 件）：店员要排的是「最该补的」，按库存升序取前 20 足够。</li>
 *   <li><b>total 是真实总数</b>：只给截断后的明细，店员会以为补完这 20 件就没事了。
 *       这是「截断」最容易骗人的地方——少了的那部分必须被显式说出来。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffToolsInventoryAlertLimitTest {

    private static final long FIXTURE_ID_BASE = 900_000L;

    /** 造多少件低库存商品：远多于 20 件的明细上限，才能验出截断。 */
    private static final int LOW_STOCK_COUNT = 40;

    /** 店员账号（SecurityConfig 里带 ROLE_STAFF）。 */
    private static final String STAFF = "admin";

    @Autowired StaffTools staff;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM product WHERE id > :base").param("base", FIXTURE_ID_BASE).update();
    }

    @Test
    @DisplayName("库存告警明细最多 20 件，但 total 必须报出低库存商品的真实总数")
    void inventoryAlertsCappedButReportsRealTotal() throws Exception {
        seedLowStock(LOW_STOCK_COUNT);

        JsonNode result = mapper.readTree(staff.inventoryAlerts(STAFF, null));

        // 断言②：真实总数。只给截断后的明细而不给总数，店员会以为补完了。
        assertThat(result.get("total").asInt())
                .as("total 必须是低库存商品的真实总数，不是截断后的条数")
                .isEqualTo(LOW_STOCK_COUNT);

        // 断言①：明细上限。去掉 .limit(20) 后这里会拿到 40 件 → 红。
        JsonNode items = result.get("items");
        assertThat(items).as("明细条数必须有上限").hasSize(20);

        // 列出的必须是库存最紧张的那一批：升序，且都低于默认阈值 10。
        List<Integer> stocks = stocksOf(items);
        assertThat(stocks).isSorted();
        assertThat(stocks).allMatch(s -> s < 10);
    }

    @Test
    @DisplayName("total 必须排在 items 之前：结果被裁剪时模型至少先看到总数")
    void totalPrecedesItemsSoItSurvivesTruncation() throws Exception {
        seedLowStock(LOW_STOCK_COUNT);

        JsonNode result = mapper.readTree(staff.inventoryAlerts(STAFF, null));

        List<String> fields = new ArrayList<>();
        result.fieldNames().forEachRemaining(fields::add);

        assertThat(fields)
                .as("工具结果超过 4000 字符会被从头截断，total 必须在最前面才活得下来")
                .startsWith("total");
    }

    @Test
    @DisplayName("低库存不足 20 件时如数列全，total 仍等于明细条数")
    void smallResultIsReturnedWhole() throws Exception {
        seedLowStock(5);

        JsonNode result = mapper.readTree(staff.inventoryAlerts(STAFF, null));

        assertThat(result.get("total").asInt()).isEqualTo(5);
        assertThat(result.get("items")).hasSize(5);
    }

    private static List<Integer> stocksOf(JsonNode items) {
        List<Integer> stocks = new ArrayList<>();
        items.forEach(node -> stocks.add(node.get("stock").asInt()));
        return stocks;
    }

    /** 造 count 件低库存商品：库存按 0..9 循环，全部低于默认阈值 10。 */
    private void seedLowStock(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("INSERT INTO product(id, name, description, category, illustration, price, stock,"
                            + " merchant_id) VALUES(:id,:n,:d,'生活','box',:p,:s,1)")
                    // merchant_id 在 V24 之后是 NOT NULL 且没有默认值：造商品必须回答「谁的货」。
                    // 这些填充商品与归属无关（用例验证的是分页 / 压缩 / 告警上限），
                    // 一律挂 1 号店——写死一个确定的答案，比在这里再发明一套分配规则好。
                    .param("id", FIXTURE_ID_BASE + i + 1)
                    .param("n", "低库存商品" + i)
                    .param("d", "用于验证库存告警的明细上限与 total 字段。")
                    .param("p", 9.9)
                    .param("s", i % 10)
                    .update();
        }
    }
}
