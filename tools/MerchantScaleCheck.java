import java.sql.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 阶段 3a 的数据核对：在<b>真 MySQL</b> 上从空库重放全部迁移（含 V24），
 * 然后核对商家地基的每一项——商家数、店名是否重复、每家店的 SKU 数是否均衡、
 * 商品归属有没有漏、以及<b>按店收窄的退款队列查询走不走索引</b>。
 *
 * <p>为什么不能只看「迁移没报错」：与阶段 2 同一个教训。生成式脚本最典型的失败
 * 是安静地造出一堆重名店铺或把商品全塞给头几家——迁移照样成功，
 * 而「每家店 25 件」这句话一旦不成立，压测出来的数字就全是假的。
 */
public class MerchantScaleCheck {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mall_check"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true";
        SimpleDriverDataSource source =
                new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), url, "root", "123456");

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS mall_check");
            s.execute("CREATE DATABASE mall_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            System.out.println("已重建库 mall_check");
        }

        var r = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration", "filesystem:src/main/resources/db/dev-seed").load().migrate();
        System.out.println("迁移成功：" + r.migrationsExecuted + " 个，最终版本 " + r.targetSchemaVersion);

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            System.out.println();
            q(s, "商家数", "SELECT COUNT(*) FROM merchant");
            q(s, "重名店铺数（应为 0）",
                    "SELECT COUNT(*) FROM (SELECT name FROM merchant GROUP BY name HAVING COUNT(*) > 1) t");
            q(s, "店主账号重复数（应为 0）",
                    "SELECT COUNT(*) FROM (SELECT owner_username FROM merchant"
                            + " GROUP BY owner_username HAVING COUNT(*) > 1) t");
            q(s, "1 号店店主（应沿用 merchant）",
                    "SELECT owner_username FROM merchant WHERE id = 1");
            q(s, "商品总数", "SELECT COUNT(*) FROM product");
            q(s, "没有归属的商品（应为 0）", "SELECT COUNT(*) FROM product WHERE merchant_id IS NULL");
            q(s, "归属越界的商品（应为 0）",
                    "SELECT COUNT(*) FROM product p LEFT JOIN merchant m ON m.id = p.merchant_id"
                            + " WHERE m.id IS NULL");
            q(s, "每店 SKU 数 min/max",
                    "SELECT CONCAT(MIN(n), ' / ', MAX(n)) FROM"
                            + " (SELECT merchant_id, COUNT(*) n FROM product GROUP BY merchant_id) t");
            q(s, "有货的店铺数", "SELECT COUNT(*) FROM (SELECT merchant_id FROM product GROUP BY merchant_id) t");
            q(s, "单店跨品类数（1 号店）",
                    "SELECT COUNT(DISTINCT category) FROM product WHERE merchant_id = 1");

            System.out.println();
            System.out.println("【样例】1 / 2 / 100 / 200 号店：");
            ResultSet rs = s.executeQuery("SELECT id, code, name, owner_username,"
                    + " (SELECT COUNT(*) FROM product p WHERE p.merchant_id = m.id) AS sku"
                    + " FROM merchant m WHERE id IN (1, 2, 100, 200) ORDER BY id");
            while (rs.next())
                System.out.printf("  %-4d %-6s %-14s %-10s SKU=%s%n",
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));

            System.out.println();
            System.out.println("【归属查询的代价】按店收窄的退款队列（EXISTS order_item.merchant_id）：");
            // 造一笔订单与明细，否则 EXPLAIN 的 rows 全靠猜
            s.execute("INSERT INTO mall_order(id, username, idempotency_key, request_hash, total, status, paid_at)"
                    + " VALUES ('chk-1', 'checker', 'k1', 'h1', 10.00, 'PAID', NOW())");
            s.execute("INSERT INTO order_item(order_id, product_id, product_name, unit_price, quantity, merchant_id)"
                    + " VALUES ('chk-1', 5000, '探针商品', 10.00, 1, 164)");
            s.execute("INSERT INTO refund(id, order_id, username, amount, reason_code, route, requested_by)"
                    + " VALUES ('chk-r1', 'chk-1', 'checker', 10.00, 'NO_REASON', 'MERCHANT_APPROVAL', 'checker')");
            long t0 = System.nanoTime();
            ResultSet p = s.executeQuery("SELECT id, order_id, username, amount FROM refund r"
                    + " WHERE r.status = 'PENDING_APPROVAL'"
                    + " AND EXISTS (SELECT 1 FROM order_item i"
                    + "              WHERE i.order_id = r.order_id AND i.merchant_id = 164)"
                    + " ORDER BY r.created_at, r.id LIMIT 50");
            int n = 0;
            while (p.next()) n++;
            System.out.printf("  164 号店的队列：%d 条，耗时 %.3fms%n", n, (System.nanoTime() - t0) / 1e6);

            ResultSet e = s.executeQuery("EXPLAIN SELECT id FROM refund r"
                    + " WHERE r.status = 'PENDING_APPROVAL'"
                    + " AND EXISTS (SELECT 1 FROM order_item i"
                    + "              WHERE i.order_id = r.order_id AND i.merchant_id = 164)");
            while (e.next())
                System.out.printf("      table=%-8s type=%-6s key=%-22s rows=%-6s extra=%s%n",
                        e.getString("table"), e.getString("type"), e.getString("key"),
                        e.getString("rows"), e.getString("Extra"));
        }
    }

    private static void q(Statement s, String label, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            System.out.printf("  %-26s %s%n", label, rs.getString(1));
        }
    }
}
