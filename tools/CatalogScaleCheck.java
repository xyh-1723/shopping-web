import java.sql.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 阶段 2 的数据核对：在<b>真 MySQL</b> 上从空库重放全部迁移（含 V23），
 * 然后逐项核对「生成的 4,494 件商品」是否真的可用——
 * 件数、id 连续性、分类分布、<b>同分类内有没有重名</b>、售罄与低库存是否存在。
 *
 * <p>为什么不能只看"迁移没报错"：生成式脚本最常见的失败不是报错，
 * 而是<b>安静地造出一堆重名商品或跳号 id</b>——迁移照样成功，
 * 问题要等用户在页面上翻到第二页才会看见。
 */
public class CatalogScaleCheck {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mall_check"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true";
        SimpleDriverDataSource source = new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), url, "root", "123456");

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS mall_check");
            s.execute("CREATE DATABASE mall_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            System.out.println("已重建库 mall_check");
        }

        var r = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration").load().migrate();
        System.out.println("迁移成功：" + r.migrationsExecuted + " 个，最终版本 " + r.targetSchemaVersion);

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            System.out.println();
            q(s, "总件数", "SELECT COUNT(*) FROM product");
            q(s, "id 区间", "SELECT CONCAT(MIN(id), ' .. ', MAX(id)) FROM product");
            q(s, "缺号数（应为 0）",
                    "SELECT MAX(id) - MIN(id) + 1 - COUNT(*) FROM product");
            q(s, "同分类内重名的商品数（应为 0）",
                    "SELECT COUNT(*) FROM (SELECT category, name FROM product GROUP BY category, name"
                            + " HAVING COUNT(*) > 1) t");
            q(s, "售罄（stock=0）件数", "SELECT COUNT(*) FROM product WHERE stock = 0");
            q(s, "低库存（stock<10）件数", "SELECT COUNT(*) FROM product WHERE stock < 10");
            q(s, "价格区间", "SELECT CONCAT(MIN(price), ' .. ', MAX(price)) FROM product");
            q(s, "分类分布", "SELECT GROUP_CONCAT(CONCAT(category, '=', n) SEPARATOR '  ') FROM"
                    + " (SELECT category, COUNT(*) n FROM product GROUP BY category) t");
            q(s, "插图分布", "SELECT GROUP_CONCAT(CONCAT(illustration, '=', n) SEPARATOR '  ') FROM"
                    + " (SELECT illustration, COUNT(*) n FROM product GROUP BY illustration) t");

            System.out.println();
            System.out.println("【样例】id 507 / 2005 / 3503 / 5000：");
            ResultSet rs = s.executeQuery("SELECT id, name, category, illustration, price, stock FROM product"
                    + " WHERE id IN (507, 508, 2005, 3503, 5000) ORDER BY id");
            while (rs.next())
                System.out.printf("  %-5d %-18s %-4s %-9s %-7s %d%n", rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getInt(6));

            System.out.println();
            System.out.println("【分页代价】第 1 页与第 200 页的响应规模（各 24 件）：");
            for (int page : new int[] {0, 200}) {
                long t0 = System.nanoTime();
                ResultSet p = s.executeQuery("SELECT id, name, description, category, illustration, price, stock"
                        + " FROM product ORDER BY id LIMIT 24 OFFSET " + (page * 24));
                int n = 0;
                while (p.next()) n++;
                System.out.printf("  page=%-4d %d 行，耗时 %.3fms%n", page, n, (System.nanoTime() - t0) / 1e6);
            }

            System.out.println();
            System.out.println("【执行计划】深翻页（OFFSET 4800）应当仍然走主键而不是扫全表：");
            ResultSet e = s.executeQuery("EXPLAIN SELECT id, name, description, category, illustration, price, stock"
                    + " FROM product ORDER BY id LIMIT 24 OFFSET 4800");
            while (e.next())
                System.out.printf("      type=%-6s key=%-12s rows=%-8s extra=%s%n",
                        e.getString("type"), e.getString("key"), e.getString("rows"), e.getString("Extra"));
        }
    }

    private static void q(Statement s, String label, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            System.out.printf("  %-28s %s%n", label, rs.getString(1));
        }
    }
}
