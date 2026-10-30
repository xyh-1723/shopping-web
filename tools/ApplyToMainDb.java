import java.sql.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 把迁移应用到<b>真的那个库</b>（{@code mall}，应用正在用的库），只做 migrate 与核对，<b>绝不删库</b>。
 *
 * <p>{@code MerchantScaleCheck} 会先 DROP DATABASE 再重放，那是给一次性核对准备的；
 * 主库里有真实订单与钱包流水，不能用那种方式验证。这个文件存在的唯一目的就是：
 * 让「V24 在真库上跑得通」这件事可以在不破坏数据的前提下被验证一次。
 *
 * <pre>{@code java -cp ... ApplyToMainDb }</pre>
 */
public class ApplyToMainDb {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mall"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        SimpleDriverDataSource source =
                new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), url, "root", "123456");

        var r = Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration", "filesystem:src/main/resources/db/dev-seed").load().migrate();
        System.out.println("已应用 " + r.migrationsExecuted + " 个迁移，当前版本 " + r.targetSchemaVersion);

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            System.out.println();
            q(s, "商家数", "SELECT COUNT(*) FROM merchant");
            q(s, "商品数", "SELECT COUNT(*) FROM product");
            q(s, "没有归属的商品（应为 0）", "SELECT COUNT(*) FROM product WHERE merchant_id IS NULL");
            q(s, "每店 SKU min/max",
                    "SELECT CONCAT(MIN(n), ' / ', MAX(n)) FROM"
                            + " (SELECT merchant_id, COUNT(*) n FROM product GROUP BY merchant_id) t");
            q(s, "订单明细总数", "SELECT COUNT(*) FROM order_item");
            q(s, "明细归属为空（应为 0）", "SELECT COUNT(*) FROM order_item WHERE merchant_id IS NULL");

            System.out.println();
            System.out.println("【1 号店的待审批队列】（应只含 1 号店商品所在的订单）：");
            try (ResultSet rs = s.executeQuery("SELECT r.id, r.order_id, r.amount FROM refund r"
                    + " WHERE r.status = 'PENDING_APPROVAL'"
                    + " AND EXISTS (SELECT 1 FROM order_item i"
                    + "              WHERE i.order_id = r.order_id AND i.merchant_id = 1)")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                    System.out.printf("  %s  订单 %s  ¥%s%n", rs.getString(1), rs.getString(2), rs.getBigDecimal(3));
                }
                System.out.println("  共 " + n + " 条");
            }
        }
    }

    private static void q(Statement s, String label, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            System.out.printf("  %-24s %s%n", label, rs.getString(1));
        }
    }
}
