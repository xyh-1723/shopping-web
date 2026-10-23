package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * V20 商品目录扩容迁移的验收测试。
 *
 * <p>这些断言同时守住规模与数据契约：V20 只能向 V2 的六件商品之后追加数据，不能修改
 * 已经被下单、推荐和前端展示依赖的既有商品；所有新增商品也必须落在前后端共同支持的分类、
 * 插图、价格和库存取值域内。
 */
@SpringBootTest
@ActiveProfiles("test")
class V20MigrationTest {

    private static final Set<String> SUPPORTED_CATEGORIES = Set.of("生活", "文具", "数码");
    private static final Set<String> SUPPORTED_ILLUSTRATIONS =
            Set.of("cup", "notebook", "lamp", "bag", "keyboard", "timer");

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

    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("V20 在 V2 的六件商品之后追加 500 件，目录总数为 506")
    void catalogContainsFiveHundredAndSixProducts() {
        assertThat(productCount(jdbc)).isEqualTo(506);
        assertThat(jdbc.sql("SELECT MIN(id) FROM product").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT MAX(id) FROM product").query(Long.class).single()).isEqualTo(506L);
    }

    @Test
    @DisplayName("V2 的 id 1..6 名称、描述、分类、插图、价格和库存一字未改")
    void originalSixProductsAreUnchanged() {
        List<ProductSnapshot> actual = jdbc.sql("SELECT id,name,description,category,illustration,price,stock "
                        + "FROM product WHERE id BETWEEN 1 AND 6 ORDER BY id")
                .query(ProductSnapshot.class)
                .list();

        assertThat(actual).containsExactlyElementsOf(V2_PRODUCTS);
    }

    @Test
    @DisplayName("每件商品都只使用前端存在的六种插图和工具声明的三种分类")
    void everyProductUsesSupportedCategoryAndIllustration() {
        Set<String> categories = Set.copyOf(
                jdbc.sql("SELECT DISTINCT category FROM product").query(String.class).list());
        Set<String> illustrations = Set.copyOf(
                jdbc.sql("SELECT DISTINCT illustration FROM product").query(String.class).list());

        assertThat(categories).isEqualTo(SUPPORTED_CATEGORIES);
        assertThat(illustrations).isEqualTo(SUPPORTED_ILLUSTRATIONS);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM product "
                        + "WHERE category NOT IN ('生活','文具','数码') "
                        + "OR illustration NOT IN ('cup','notebook','lamp','bag','keyboard','timer')")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("价格严格为正且库存非负，满足 V1 的数值约束并排除零价商品")
    void pricesAndStocksAreValid() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM product WHERE price <= 0 OR stock < 0")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT MIN(price) FROM product").query(BigDecimal.class).single())
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(jdbc.sql("SELECT MIN(stock) FROM product").query(Integer.class).single())
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("低库存商品保持在 10..60 件，既有真实告警又不会淹没工具上下文")
    void lowStockPopulationStaysWithinOperationalRange() throws SQLException {
        // 分布断言必须在「刚迁移完的全新库」上数：共享测试库里其它用例会成批改写
        // product.stock（例如 PriceChangeGuardTest 的清库把所有商品刷成 stock=50），
        // 在共享库上数低库存件数，数到的是别人的改动而不是种子数据本身。
        Connection connection = freshReplay();
        try {
            int lowStockCount = singleInt(connection, "SELECT COUNT(*) FROM product WHERE stock < 10");
            int soldOutCount = singleInt(connection, "SELECT COUNT(*) FROM product WHERE stock = 0");

            assertThat(lowStockCount).isBetween(10, 60);
            assertThat(soldOutCount)
                    .as("目录需要少量真实售罄商品，让前端的暂时售罄状态可被渲染")
                    .isBetween(1, 10);
        } finally {
            shutdownReplay(connection);
        }
    }

    @Test
    @DisplayName("迁移可在空库顺序重放到 V20，并得到同样的 506 件商品")
    void migrationReplaysOnAFreshDatabase() throws SQLException {
        Connection connection = freshReplay();
        try (PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*), MIN(id), MAX(id) FROM product");
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(506);
            assertThat(result.getLong(2)).isEqualTo(1L);
            assertThat(result.getLong(3)).isEqualTo(506L);
        } finally {
            shutdownReplay(connection);
        }
    }

    /** 开一个空库把迁移顺序重放到 V20，返回它的连接（用完必须 {@link #shutdownReplay}）。 */
    private static Connection freshReplay() throws SQLException {
        String url = "jdbc:h2:mem:v20-replay-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SimpleDriverDataSource source =
                new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .target("20")
                .load()
                .migrate();
        return source.getConnection();
    }

    private static void shutdownReplay(Connection connection) throws SQLException {
        try (connection) {
            connection.createStatement().execute("SHUTDOWN");
        }
    }

    private static int singleInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static int productCount(JdbcClient client) {
        return client.sql("SELECT COUNT(*) FROM product").query(Integer.class).single();
    }

    /** Immutable view of every product column, used to guard the original V2 rows exactly. */
    private record ProductSnapshot(long id, String name, String description, String category,
                                   String illustration, BigDecimal price, int stock) {}
}
