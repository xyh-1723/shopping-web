import java.sql.*;
import java.util.*;

/**
 * 在<b>真 MySQL 8</b> 上跑一遍阶段 1 新增的三条商品查询。
 *
 * <p>为什么必须有这个探针：回归测试跑在 H2（MODE=MySQL）上，而主库是 MySQL。
 * 「H2 接受了」不等于「MySQL 接受」——窗口函数、{@code LIMIT ? OFFSET ?}
 * 这两种东西恰恰是两种方言最容易分叉的地方。这里直接对着真库执行，
 * 让分歧在改代码的当天暴露，而不是等部署。
 *
 * <p>复用 {@code mall_check} 库：它已经由 {@code MysqlMigrateCheck} 重放过全部迁移，
 * 里面有 506 件商品，正好能验证取样与告警的真实结果。
 */
public class CatalogSqlProbe {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mall_check"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(url, "root", "123456")) {
            System.out.println("连接：" + c.getMetaData().getDatabaseProductVersion());

            check(c, "COUNT(*) 无条件", "SELECT COUNT(*) FROM product");

            check(c, "1 分页查询（带关键词 + 分类 + 价格上限 + 排序 + LIMIT/OFFSET）",
                    "SELECT id, name, description, category, illustration, price, stock FROM product"
                            + " WHERE (LOWER(name) LIKE '%笔记%' ESCAPE '!' OR LOWER(description) LIKE '%笔记%' ESCAPE '!')"
                            + " AND category = '文具' AND price <= 100"
                            + " ORDER BY price, id LIMIT 5 OFFSET 0");

            check(c, "2 跨分类取样（ROW_NUMBER 窗口函数 + 每类上限）",
                    "SELECT id, name, description, category, illustration, price, stock FROM ("
                            + " SELECT p.id, p.name, p.description, p.category, p.illustration, p.price, p.stock,"
                            + "        ROW_NUMBER() OVER (PARTITION BY p.category ORDER BY p.price, p.id) AS rn"
                            + "   FROM product p"
                            + " ) ranked WHERE rn <= 4 ORDER BY rn, price, id LIMIT 12");

            check(c, "3 低库存告警（阈值 + 按库存升序 + LIMIT）",
                    "SELECT id, name, description, category, illustration, price, stock FROM product"
                            + " WHERE stock < 10 ORDER BY stock, id LIMIT 20");

            check(c, "3b 低库存总数（不受 LIMIT 影响）", "SELECT COUNT(*) FROM product WHERE stock < 10");

            check(c, "4 转义测试：关键词含 % 与 _ 时不该退化成通配符",
                    "SELECT COUNT(*) FROM product WHERE LOWER(name) LIKE '%!_%' ESCAPE '!'");

            // 执行计划：确认分页查询真的走了 V22 建的索引，而不是全表扫。
            System.out.println();
            System.out.println("【执行计划】按分类 + 价格上限（应当走 idx_product_category_price）：");
            explain(c, "SELECT id, name, description, category, illustration, price, stock FROM product"
                    + " WHERE category = '文具' AND price <= 100 ORDER BY price, id LIMIT 24 OFFSET 0");
            System.out.println("【执行计划】低库存（应当走 idx_product_stock）：");
            explain(c, "SELECT id, name, description, category, illustration, price, stock FROM product"
                    + " WHERE stock < 10 ORDER BY stock, id LIMIT 20");
        }
    }

    private static void check(Connection c, String label, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int rows = 0;
            StringBuilder sample = new StringBuilder();
            while (rs.next()) {
                rows++;
                if (rows <= 3) {
                    sample.append("\n      ");
                    for (int i = 1; i <= md.getColumnCount(); i++)
                        sample.append(md.getColumnLabel(i)).append("=").append(rs.getString(i)).append("  ");
                }
            }
            System.out.printf("  ✔ %-56s → %d 行%s%n", label, rows, sample);
        } catch (SQLException e) {
            System.out.printf("  ✘ %-56s → SQLState=%s %s%n", label, e.getSQLState(), e.getMessage());
            throw e;
        }
    }

    private static void explain(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                System.out.printf("      table=%-8s type=%-6s key=%-28s rows=%-8s extra=%s%n",
                        rs.getString("table"), rs.getString("type"), rs.getString("key"),
                        rs.getString("rows"), rs.getString("Extra"));
            }
        }
    }
}
