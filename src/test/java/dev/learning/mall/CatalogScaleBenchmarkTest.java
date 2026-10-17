package dev.learning.mall;

import dev.learning.mall.assistant.internal.CatalogTools;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductView;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 商品规模基准：<b>用来论证 P2-1（服务端检索下推）是不是真问题</b>，不是回归断言。
 *
 * <p>为什么默认 {@code @Disabled}：它产出的是数字，不是真伪。基准会随机器、随 JIT 预热漂移，
 * 把它写进回归套件，等于让一个不稳定的数字决定构建红绿——那正是「测试替 bug 站岗」的另一面：
 * 一个不该做断言的东西做了断言。需要它时手动跑：
 *
 * <pre>{@code mvn -o test -Dtest=CatalogScaleBenchmarkTest -DfailIfNoTests=false}</pre>
 *
 * <p><b>要回答的问题</b>：商品从 6 件扩到几百件后，「助手搜索」这条路径坏在哪？
 * 一个想当然的答案是「性能慢」。但真正的怀疑在别处——
 * {@code CatalogTools#searchProducts} 把筛选后的结果<b>全部</b>塞进返回给模型的 JSON，
 * 没有条数上限（同一个类里的 {@code recommendByBudget} 却有 {@code .limit(5)}）。
 * 所以代价可能不是毫秒，而是 <b>token</b>：助手的上下文预算被一次工具调用吃光。
 *
 * <p>这个基准就是把「毫秒」和「token」两个数字都量出来，让结论来自数据而不是直觉。
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("基准：产出数字供决策，不作为回归断言。需要时手动跑。"
        + "实测（300 件）：空关键词搜索返回 306 件 / 16505 字符 / 45ms；"
        + "带分类+价格筛选 3312 字符 / 4ms。结论：性能不是瓶颈，token 才是。详见优化计划-P1。")
class CatalogScaleBenchmarkTest {

    /** 每类 100 件，共 300 件——用户确认的目标量级。 */
    private static final int SCALE = 300;

    @Autowired CatalogTools tools;
    @Autowired ProductCatalog catalog;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM product WHERE id > 1000").update();
    }

    @Test
    @DisplayName("量出「毫秒」与「token」两个数：全表拉取在几百件商品下到底贵在哪")
    void measureCostOfFullTableScan() {
        seed(SCALE);

        // 预热：让 JIT 与 H2 的缓存都进入稳定态，否则第一次调用的耗时没有参考价值。
        for (int i = 0; i < 5; i++) catalog.list();

        long t0 = System.nanoTime();
        List<ProductView> all = catalog.list();
        long listMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        String emptyKeyword = tools.searchProducts("", null, null);
        long searchMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        String narrowed = tools.searchProducts("", "数码", 100.0);
        long narrowedMs = (System.nanoTime() - t0) / 1_000_000;

        int returned = emptyKeyword.split("\"id\"", -1).length - 1;

        System.out.println();
        System.out.println("=========== 商品规模基准（" + SCALE + " 件） ===========");
        System.out.printf("  全表 list()            : %d 件, %d ms%n", all.size(), listMs);
        System.out.printf("  searchProducts(\"\")     : 返回 %d 件, %d ms, JSON %d 字符%n",
                returned, searchMs, emptyKeyword.length());
        System.out.printf("  searchProducts(数码≤100): %d ms, JSON %d 字符%n",
                narrowedMs, narrowed.length());
        // 中文按 1 字符 ≈ 1 token 估算，英文约 4 字符 1 token；这里取保守值。
        System.out.printf("  → 一次空搜索塞给模型    : 约 %d token（按 2 字符/token 估）%n",
                emptyKeyword.length() / 2);
        System.out.printf("  → 有上限版本（取前 8）  : 约 %d 字符%n",
                estimateWithLimit(emptyKeyword, 8));
        System.out.println("======================================================");
        System.out.println();
    }

    /** 估算「只取前 N 件」时的 JSON 长度：按平均每件长度折算，够用来看数量级。 */
    private static int estimateWithLimit(String json, int limit) {
        double perItem = (double) json.length() / Math.max(1, json.split("\"id\"", -1).length - 1);
        return (int) (perItem * limit) + 2;
    }

    private void seed(int count) {
        String[] categories = {"生活", "文具", "数码"};
        for (int i = 0; i < count; i++) {
            // id 从 1001 起：既有种子数据占 1..6，清理时按 id > 1000 删，互不干扰。
            jdbc.sql("INSERT INTO product(id, name, description, category, illustration, price, stock) "
                            + "VALUES(:id,:n,:d,:c,'box',:p,:s)")
                    .param("id", 1001L + i)
                    .param("n", "基准商品" + i)
                    .param("d", "用于规模基准的填充商品，编号 " + i + "，描述文本按真实长度构造以便估算 token。")
                    .param("c", categories[i % categories.length])
                    .param("p", 10 + (i % 200))
                    .param("s", 50)
                    .update();
        }
    }
}
