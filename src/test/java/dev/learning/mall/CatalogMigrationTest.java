package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 商品目录迁移的验收测试：迁移序列可重放、种子数据契约成立、扩容所需的地基在位。
 *
 * <p><b>这里刻意不钉死商品总数。</b>它的前身 {@code V20MigrationTest} 断言过
 * {@code COUNT(*) == 506} / {@code MAX(id) == 506}，那在目录扩容时必然变红——
 * 而变红的原因不是代码坏了，只是数字变了。规模的正确断言方式是<b>性质</b>：
 * 数量达到可用下限、id 连续无缺号、分布不失衡、取值域合法。
 * 这些断言在 506 件与 50000 件下都成立，扩容时不必回头改测试。
 *
 * <p><b>所有断言都跑在一个独立重放的库上，不碰共享测试库。</b>
 * 这不是洁癖：共享库里 {@code product.stock} 会被别的用例成批改写
 * （例如 PriceChangeGuardTest 的清库），在它上面断言种子数据，数到的是别人的改动。
 * 前身 {@code V20MigrationTest} 就踩过这个坑——它靠执行顺序侥幸成立，
 * 一旦类名变了（顺序变了）立刻变红。数据契约必须与执行顺序无关。
 *
 * <p>也因此这个类<b>不起 Spring 上下文</b>：它验的是迁移脚本本身，
 * 与 Bean 无关，纯 JUnit 跑完只要几十毫秒。
 */
class CatalogMigrationTest {

    private static final Set<String> SUPPORTED_CATEGORIES = Set.of("生活", "文具", "数码");
    private static final Set<String> SUPPORTED_ILLUSTRATIONS =
            Set.of("cup", "notebook", "lamp", "bag", "keyboard", "timer");
    /** 目录要撑得起列表、搜索与告警，低于这个数就退化成「只有几件样品」。 */
    private static final int MIN_CATALOG_SIZE = 500;
    /** 每个分类都得有足够的货，否则「按分类浏览」会撞上空页。 */
    private static final int MIN_PER_CATEGORY = 50;

    private static final List<ProductSnapshot> V2_PRODUCTS = List.of(
            new ProductSnapshot(1L, "云朵马克杯", "温润陶瓷，陪你写完第一行 Java。",
                    "生活", "cup", new BigDecimal("39.00"), 30),
            new ProductSnapshot(2L, "灵感笔记本", "点阵内页，把复杂的问题一点点写清楚。",
                    "文具", "notebook", new BigDecimal("29.00"), 40),
            new ProductSnapshot(3L, "桌面小夜灯", "柔和暖光，给认真学习的夜晚。",
                    "数码", "lamp", new BigDecimal("89.00"), 15),
            new ProductSnapshot(4L, "轻量帆布袋", "装下电脑、书本，还有新想法。",
                    "生活", "bag", new BigDecimal("49.00"), 25),
            new ProductSnapshot(5L, "机械键盘", "清晰反馈，每一次输入都算数。",
                    "数码", "keyboard", new BigDecimal("199.00"), 10),
            new ProductSnapshot(6L, "专注计时器", "留出二十五分钟，开始一件小事。",
                    "文具", "timer", new BigDecimal("59.00"), 20));

    /** 空库顺序重放全部迁移后的连接：本类所有断言的输入。 */
    private static Connection replayed;

    @BeforeAll static void replayAllMigrations() throws SQLException {
        String url = TestH2.inMemory("catalog-replay-" + UUID.randomUUID());
        SimpleDriverDataSource source = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        replayed = source.getConnection();
    }

    @AfterAll static void dropReplayed() throws SQLException {
        if (replayed == null) {
            return;
        }
        try (Connection c = replayed) {
            replayed = null;
            c.createStatement().execute("SHUTDOWN");
        }
    }

    @Test
    @DisplayName("目录规模达到可用下限，且 id 从 1 连续无缺号")
    void catalogIsLargeEnoughAndIdsAreContiguous() throws SQLException {
        int count = intOf("SELECT COUNT(*) FROM product");
        assertThat(count).as("商品总数").isGreaterThanOrEqualTo(MIN_CATALOG_SIZE);
        assertThat(longOf("SELECT MIN(id) FROM product")).isEqualTo(1L);
        // 连续无缺号：生成式脚本最容易出的错就是漏号或跳号，那时 MAX-MIN+1 会大于 COUNT。
        assertThat(longOf("SELECT MAX(id) - MIN(id) + 1 FROM product"))
                .as("id 区间跨度应与总数相等（缺号会让它偏大）")
                .isEqualTo((long) count);
    }

    @Test
    @DisplayName("每个分类都有足够的商品，分布不会失衡到某个分类近乎空")
    void everyCategoryIsPopulated() throws SQLException {
        Map<String, Integer> perCategory = new java.util.HashMap<>();
        try (PreparedStatement s = replayed.prepareStatement(
                     "SELECT category, COUNT(*) AS n FROM product GROUP BY category");
                ResultSet rs = s.executeQuery()) {
            while (rs.next()) perCategory.put(rs.getString(1), rs.getInt(2));
        }
        assertThat(perCategory.keySet()).isEqualTo(SUPPORTED_CATEGORIES);
        perCategory.forEach((category, n) ->
                assertThat(n).as("分类 %s 的商品数", category).isGreaterThanOrEqualTo(MIN_PER_CATEGORY));
    }

    @Test
    @DisplayName("V2 的 id 1..6 名称、描述、分类、插图、价格和库存一字未改")
    void originalSixProductsAreUnchanged() throws SQLException {
        List<ProductSnapshot> actual;
        try (PreparedStatement s = replayed.prepareStatement(
                     "SELECT id,name,description,category,illustration,price,stock "
                             + "FROM product WHERE id BETWEEN 1 AND 6 ORDER BY id");
                ResultSet rs = s.executeQuery()) {
            actual = new java.util.ArrayList<>();
            while (rs.next()) {
                actual.add(new ProductSnapshot(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getInt(7)));
            }
        }
        assertThat(actual).containsExactlyElementsOf(V2_PRODUCTS);
    }

    @Test
    @DisplayName("每件商品都只使用前端存在的六种插图和工具声明的三种分类")
    void everyProductUsesSupportedCategoryAndIllustration() throws SQLException {
        assertThat(stringsOf("SELECT DISTINCT category FROM product")).isEqualTo(SUPPORTED_CATEGORIES);
        assertThat(stringsOf("SELECT DISTINCT illustration FROM product")).isEqualTo(SUPPORTED_ILLUSTRATIONS);
    }

    @Test
    @DisplayName("价格严格为正且库存非负（ck_product_price_positive 兜住了绕过接口的写入）")
    void pricesAndStocksAreValid() throws SQLException {
        assertThat(intOf("SELECT COUNT(*) FROM product WHERE price <= 0 OR stock < 0")).isZero();
        assertThat(intOf("SELECT COUNT(*) FROM product WHERE price > 0 AND stock >= 0"))
                .isEqualTo(intOf("SELECT COUNT(*) FROM product"));
    }

    @Test
    @DisplayName("目录里真的有低库存和售罄商品，前端的售罄状态与库存告警才不是摆设")
    void lowStockAndSoldOutAreBothPresent() throws SQLException {
        assertThat(intOf("SELECT COUNT(*) FROM product WHERE stock < 10")).as("低库存商品件数").isPositive();
        assertThat(intOf("SELECT COUNT(*) FROM product WHERE stock = 0"))
                .as("目录需要少量真实售罄商品，让前端的暂时售罄状态可被渲染")
                .isPositive();
    }

    @Test
    @DisplayName("扩容依赖的商品索引都在位（少一个索引，几万件之后就是全表扫）")
    void scaleIndexesArePresent() throws SQLException {
        Set<String> indexes = stringsOf(
                "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'PRODUCT'");
        assertThat(indexes).contains("IDX_PRODUCT_CATEGORY_PRICE", "IDX_PRODUCT_STOCK", "IDX_PRODUCT_PRICE");
    }

    // ——————————————————— 辅助 ———————————————————

    private static int intOf(String sql) throws SQLException {
        try (PreparedStatement s = replayed.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static long longOf(String sql) throws SQLException {
        try (PreparedStatement s = replayed.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private static Set<String> stringsOf(String sql) throws SQLException {
        Set<String> values = new LinkedHashSet<>();
        try (PreparedStatement s = replayed.prepareStatement(sql); ResultSet rs = s.executeQuery()) {
            while (rs.next()) values.add(rs.getString(1));
        }
        return values;
    }

    /** Immutable view of every product column, used to guard the original V2 rows exactly. */
    private record ProductSnapshot(long id, String name, String description, String category,
                                   String illustration, BigDecimal price, int stock) {}
}
