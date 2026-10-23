package dev.learning.mall;

import dev.learning.mall.assistant.internal.CatalogTools;
import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductPage;
import dev.learning.mall.catalog.api.ProductQuery;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.QuoteSnapshotService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 商品规模基准：<b>回答「商品从 6 件扩到几百件之后，哪些路径会变」</b>。它产出的是数字，不是红绿。
 *
 * <p><b>为什么默认不进全量回归</b>：基准随机器、随 JIT 预热漂移，把它写进回归套件，
 * 等于让一个不稳定的数字决定构建红绿。它现在由 {@code pom.xml} 里 surefire 的
 * {@code <excludes>**}{@code /*BenchmarkTest.java</excludes>} 挡在常规回归之外，
 * 需要时单跑（{@code -Dtest} 会覆盖 excludes）：
 *
 * <pre>{@code mvn -o test -Dtest=CatalogScaleBenchmarkTest -DfailIfNoTests=false}</pre>
 *
 * <p><b>这一版要回答的问题</b>：上一版只量了「助手搜索」这一条，结论是「性能不是瓶颈，token 才是」。
 * 但那是<b>单点测过</b>，不是系统跑过——列表、详情、加购、试算、下单、库存锁都没在几百件下跑过。
 * 这一版把六条真实路径全部量一遍，并且<b>每条都跑 6 件与 500 件两遍</b>，
 * 让「有没有退化」这个问题由同一台机器上的一对数字回答，而不是靠估算。
 *
 * <p><b>数据从哪来</b>：500 件商品在测试内用 {@code id >= 1001} 现插、跑完即删。
 * 刻意<b>不动</b> {@code V2__seed_products.sql}——「几百件商品」在这一次是规模验证，
 * 不是产品需求；往已落盘的生产种子里插几百条假商品，会让所有既有测试的数据前提变形。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogScaleBenchmarkTest {

    /** 目标量级：用户早先拍板过「商品会扩到几百件」，取上沿 500。 */
    private static final int SCALE = 500;

    /** 预热次数：让 JIT 与 H2 的缓存进入稳定态，否则第一次调用的耗时没有参考价值。 */
    private static final int WARMUP = 5;

    /** 读路径采样次数。 */
    private static final int READ_RUNS = 30;

    /** 写路径采样次数（下单会真的落订单与流水，采样数刻意小于读路径）。 */
    private static final int WRITE_RUNS = 10;

    /** 基准专用账号：与其他测试类的用户名不重叠，清理时按它收窄。 */
    private static final String USER = "bench-scale";

    /** 基准专用商品：下单与加购都打在 1 号商品上，保证 6 件与 500 件两遍测的是同一行。 */
    private static final long PID = 1L;

    @Autowired CatalogTools tools;
    @Autowired ProductCatalog catalog;
    @Autowired CartService cart;
    @Autowired QuoteSnapshotService quotes;
    @Autowired OrderFacade facade;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @AfterEach
    void cleanup() {
        // 删除顺序按外键：notification ← outbox_event ← mall_order ← order_item。
        jdbc.sql("DELETE FROM notification WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM notification_delivery WHERE order_id IN "
                        + "(SELECT id FROM mall_order WHERE username = :u)")
                .param("u", USER).update();
        jdbc.sql("DELETE FROM outbox_event WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM order_item WHERE order_id IN "
                        + "(SELECT id FROM mall_order WHERE username = :u)")
                .param("u", USER).update();
        // 流水刻意不随订单被删（V14），这里只删本基准自己造的、挂在订单上的那几行。
        jdbc.sql("DELETE FROM stock_ledger WHERE order_id IN "
                        + "(SELECT id FROM mall_order WHERE username = :u)")
                .param("u", USER).update();
        jdbc.sql("DELETE FROM mall_order WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM price_quote WHERE username = :u").param("u", USER).update();
        jdbc.sql("DELETE FROM mall_cart_item WHERE username = :u").param("u", USER).update();
        // 填充商品的流水先走，再删商品：stock_ledger.product_id 没有 ON DELETE CASCADE。
        jdbc.sql("DELETE FROM stock_ledger WHERE product_id > 1000").update();
        jdbc.sql("DELETE FROM product WHERE id > 1000").update();
    }

    @Test
    @DisplayName("六条真实路径各跑 6 件与 500 件两遍：把「有没有退化」量成一对数字")
    void measureAllPathsAtSixVersusFiveHundred() throws Exception {
        // 第一轮整体丢弃。原因不是仪式感，是实测教训：第一批样本里混着类加载、JIT 预热
        // 与 Jackson 序列化器的首次构建，拿它当「6 件」的基线会出现「6 件比 500 件还慢」
        // 这种纯属冷启动的假结论（cart.add 实测 4.376ms vs 0.498ms，同一行数据）。
        // 丢弃一整轮之后，被比较的两个数才是在同一个 JVM 状态上取的。
        round();

        List<List<Sample>> measured = round();
        report(measured.get(0), measured.get(1));
    }

    /**
     * 跑一轮「6 件 → 插到 500 件」，返回两份样本。
     *
     * <p>每轮开始时把 1 号商品库存抬到上限：下单采样会真的扣库存，
     * 不能让它成为这次测量的天花板；走服务而不是直接 UPDATE，是为了让 stock_ledger 仍然自洽。
     */
    private List<List<Sample>> round() throws Exception {
        catalog.updateStock(PID, 9999, "bench");
        List<Sample> at6 = measureAll();
        seed(SCALE);
        List<Sample> at500 = measureAll();
        cleanup();
        return List.of(at6, at500);
    }

    // ————————————————————————— 被测路径 —————————————————————————

    /** 跑一遍全部路径。 */
    private List<Sample> measureAll() throws Exception {
        List<Sample> out = new ArrayList<>();

        // 1. 商品列表（服务端分页）：返回件数应当恒定，不随目录规模增长。
        out.add(measure("1 商品列表 catalog.search(page)", READ_RUNS, () -> {}, () -> {
            ProductPage page = catalog.search(ProductQuery.firstPage(24));
            return page.items().size() + " 件 / 共 " + page.total() + " 件";
        }));

        // 2. 列表接口（HTTP + JSON 序列化）：页面首页每一次加载都要拉它。
        out.add(measure("2 HTTP GET /api/products", READ_RUNS, () -> {}, () -> {
            MvcResult r = perform("/api/products");
            return r.getResponse().getContentAsByteArray().length + " 字节";
        }));

        // 3. 首页 HTML（服务端渲染）：预期与商品数无关——商品是 JS 另外拉的。
        out.add(measure("3 HTTP GET /", READ_RUNS, () -> {}, () -> {
            MvcResult r = perform("/");
            return r.getResponse().getContentAsByteArray().length + " 字节";
        }));

        // 4. 商品详情（服务端）：主键点查，预期与规模无关——这一条是「索引有没有用上」的对照。
        out.add(measure("4 商品详情 catalog.get()", READ_RUNS, () -> {}, () -> {
            catalog.get(PID);
            return "1 件";
        }));

        // 5. 详情接口（HTTP + 序列化）。
        out.add(measure("5 HTTP GET /api/products/1", READ_RUNS, () -> {}, () -> {
            MvcResult r = perform("/api/products/" + PID);
            return r.getResponse().getContentAsByteArray().length + " 字节";
        }));

        // 6. 助手搜索·空关键词：最坏情况，等于把整个商品表塞进工具结果。
        out.add(measure("6 助手搜索 searchProducts(\"\")", READ_RUNS, () -> {}, () -> {
            String json = tools.searchProducts("", null, null);
            return count(json) + " 件 / " + json.length() + " 字符";
        }));

        // 7. 助手搜索·分类 + 价格上限：日常问法，看筛选后还剩多少。
        out.add(measure("7 助手搜索 searchProducts(数码≤100)", READ_RUNS, () -> {}, () -> {
            String json = tools.searchProducts("", "数码", 100.0);
            return count(json) + " 件 / " + json.length() + " 字符";
        }));

        // 8. 加购物袋：每次清空再加，保证每次都是同一条路径。
        out.add(measure("8 加购物袋 cart.add()", WRITE_RUNS, () -> cart.clear(USER), () -> {
            CartView v = cart.add(USER, PID, 1);
            return v.items().size() + " 行";
        }));

        // 9. 结算试算（购物袋）：价格与库存当场查商品表，按袋子行数取，不按商品总数取。
        out.add(measure("9 结算试算 cart.view()", READ_RUNS, () -> {}, () -> {
            CartView v = cart.view(USER);
            return v.items().size() + " 行";
        }));

        // 10. 结算试算（金额快照 /api/quotes）：写 price_quote。
        List<CreateOrderRequest.Line> lines = List.of(new CreateOrderRequest.Line(PID, 1));
        out.add(measure("10 金额快照 quotes.capture()", WRITE_RUNS, () -> {}, () -> {
            quotes.capture(USER, lines);
            return "1 快照";
        }));

        // 11. 下单：含库存扣减（行锁）与 stock_ledger 写入。
        out.add(measure("11 下单 facade.create()", WRITE_RUNS, () -> {}, () -> {
            OrderView o = facade.create(USER, UUID.randomUUID().toString(),
                    new CreateOrderRequest(lines));
            return "1 单 " + o.status();
        }));

        return out;
    }

    // ————————————————————————— 计时与打印 —————————————————————————

    /** 一条路径的一次测量：P50 与 max 都取真实样本，不做估算。 */
    private record Sample(String path, long p50Nanos, long maxNanos, String size) {}

    /**
     * 采样一条路径。
     *
     * @param before 每次采样前跑，<b>不计入耗时</b>（例如清空购物袋）
     * @param op     被测操作；返回它的「规模说明」用于打印
     */
    private Sample measure(String path, int runs, Runnable before, Supplier<String> op) {
        for (int i = 0; i < WARMUP; i++) {
            before.run();
            op.get();
        }
        long[] ns = new long[runs];
        String size = "";
        for (int i = 0; i < runs; i++) {
            before.run();
            long t0 = System.nanoTime();
            size = op.get();
            ns[i] = System.nanoTime() - t0;
        }
        Arrays.sort(ns);
        return new Sample(path, ns[runs / 2], ns[runs - 1], size);
    }

    private void report(List<Sample> at6, List<Sample> at500) {
        System.out.println();
        System.out.println("=========== 商品规模基准：6 件 vs 500 件（真实采样，非估算） ===========");
        System.out.println("方法：整轮预热一次并丢弃（避开冷启动），然后每条路径再预热 5 次，"
                + "读路径采样 30 次 / 写路径 10 次；");
        System.out.println("      P50 = 样本中位数，max = 最慢一次。两个规模跑在同一个 JVM、同一台机器上。");
        System.out.println();
        System.out.printf("%-32s | %-19s | %-19s | %s%n",
                "路径", "6 件 P50 / max", "500 件 P50 / max", "规模 6 → 500");
        System.out.println("-".repeat(108));
        for (int i = 0; i < at6.size(); i++) {
            Sample a = at6.get(i);
            Sample b = at500.get(i);
            System.out.printf("%-32s | %-19s | %-19s | %s%n",
                    a.path(), ms(a.p50Nanos()) + " / " + ms(a.maxNanos()),
                    ms(b.p50Nanos()) + " / " + ms(b.maxNanos()),
                    a.size() + " → " + b.size());
        }
        System.out.println("-".repeat(108));

        Sample search6 = at6.get(5);
        Sample search500 = at500.get(5);
        System.out.println();
        System.out.println("【助手侧：一次工具调用会塞给模型多少】");
        System.out.printf("  searchProducts(\"\") JSON 长度：6 件 %s → 500 件 %s%n",
                search6.size(), search500.size());
        System.out.printf("  按 2 字符/token 估：6 件约 %s → 500 件约 %s%n",
                tokenEstimate(search6.size()), tokenEstimate(search500.size()));
        System.out.printf("  harness 会把工具结果裁到 %d 字符（mall.assistant.harness.max-tool-result-chars），"
                        + "并明确告知模型「已截断」%n", 4000);
        System.out.println("  注意：截断保住了 token 预算，但也意味着模型只看得到按价格升序的**前一段**结果——");
        System.out.println("        这是一个质量/正确性问题，不是性能问题。");
        System.out.println();
        System.out.println("【分页】ProductCatalog 只有 search(ProductQuery) 一个列表入口，page/size 是必填的：");
        System.out.println("        单页上限 " + ProductQuery.MAX_SIZE + " 件，默认 24 件；"
                + "GET /api/products 的响应字节数不再随商品总数增长。");
        System.out.println();
        System.out.println("【全表扫描调用点】已清零。原先 catalog.list() 的 4 处调用——ProductController#list、");
        System.out.println("        CatalogTools#searchProducts、CatalogTools#recommendByBudget、StaffTools（低库存）——");
        System.out.println("        已分别改为 search / sampleAcrossCategories / lowStock，筛选与截断全部下推到 SQL。");
        System.out.println("======================================================================");
        System.out.println();
    }

    // ————————————————————————— 小工具 —————————————————————————

    private MvcResult perform(String url) {
        try {
            return mvc.perform(get(url)).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException("MockMvc 请求失败: " + url, e);
        }
    }

    /** 工具结果里的商品件数：数 JSON 里 "id" 出现的次数。 */
    private static int count(String json) {
        return json.split("\"id\"", -1).length - 1;
    }

    private static String ms(long nanos) {
        return String.format("%.3fms", nanos / 1_000_000.0);
    }

    /**
     * 从「N 件 / M 字符」里取字符数，按 2 字符/token 折算（中文保守估计）。
     *
     * <p>用正则而不是手数下标：规模说明是给人看的文本，下标解析会被一个空格的变化打穿。
     */
    private static String tokenEstimate(String sizeNote) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*字符").matcher(sizeNote);
        if (!m.find()) return "-";
        return (Integer.parseInt(m.group(1)) / 2) + " token";
    }

    /**
     * 插 {@code count} 件填充商品。
     *
     * <p>{@code id} 从 1001 起：既有种子数据占 1..6，清理时按 {@code id > 1000} 删，互不干扰。
     */
    private void seed(int count) {
        String[] categories = {"生活", "文具", "数码"};
        for (int i = 0; i < count; i++) {
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
