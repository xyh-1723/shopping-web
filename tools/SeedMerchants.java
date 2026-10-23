import java.sql.*;

/**
 * 商户扩容工具：把「店」的数量与商品量一起放大（<b>固定每店 25 件 SKU</b>）。
 *
 * <p><b>为什么不进迁移</b>：与 {@code SeedCatalog} 同一个理由——压测量不是产品需求，
 * 写进 Flyway 会让每次重建都背着几千家假店，还会让「系统里有几家店」有两个答案。
 * 常驻档（200 家 × 25 件 = 5,000 件）在 V24 的迁移里，压测档用这个工具灌、用完删。
 *
 * <p><b>为什么商家数与商品数必须一起放大</b>：单独把商家数从 200 加到 2,000，
 * 得到的是 2,000 家店分 5,000 件货、每家 2.5 件——那不叫扩容，那叫把「每店 25 件」
 * 这个前提悄悄改掉。隔离的压测意义（一个商家只能看到自己那 1/2000 的单）
 * 依赖于每家店真的有货、真的有单，所以两者按同一个比例走。
 *
 * <p><b>压测店名刻意叫「压测店铺-N」</b>：与 {@code SeedCatalog} 的「压测商品-N」
 * 同一个取舍——一眼认得出、清得干净、不会和常驻那 200 家真店混淆。
 *
 * <pre>{@code
 *   # 扩到 2,000 家店（自动把商品配到 50,000 件）
 *   java -cp ... SeedMerchants 2000
 *   # 清回常驻档
 *   java -cp ... SeedMerchants reset
 * }</pre>
 */
public class SeedMerchants {

    /** 常驻档：V24 落地的 200 家店、5,000 件货。 */
    private static final long RESIDENT_SHOPS = 200L;

    /** 每家店的 SKU 数，与 V24 的分配结果一致。 */
    private static final int SKU_PER_SHOP = 25;

    /** 每批行数。 */
    private static final int BATCH = 5_000;

    private static final String INSERT_SHOP = """
            INSERT INTO merchant (id, code, name, owner_username)
            WITH RECURSIVE digit(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9),
            seq(k) AS (
                SELECT d1.n + d2.n * 10 + d3.n * 100 + d4.n * 1000
                  FROM digit d1, digit d2, digit d3, digit d4
            )
            SELECT
                ? + k,
                CONCAT('S', LPAD(CONCAT('', ? + k), 5, '0')),
                CONCAT('压测店铺-', ? + k),
                CONCAT('s', LPAD(CONCAT('', ? + k), 5, '0'))
            FROM seq
            WHERE k < ?
            """;

    /** 与 SeedCatalog 同一条造数语句；merchant_id 按目标店数取模，落到哪一家由 id 决定。 */
    private static final String INSERT_PRODUCT = """
            INSERT INTO product (id, name, description, category, illustration, price, stock, merchant_id)
            WITH RECURSIVE digit(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9),
            seq(k) AS (
                SELECT d1.n + d2.n * 10 + d3.n * 100 + d4.n * 1000 AS k
                  FROM digit d1, digit d2, digit d3, digit d4
            )
            SELECT
                ? + k,
                CONCAT('压测商品-', ? + k),
                CONCAT('压测用填充数据，编号 ', ? + k, '，用于验证目录在更大量级下的表现。'),
                CASE MOD(k, 3) WHEN 0 THEN '生活' WHEN 1 THEN '文具' ELSE '数码' END,
                CASE MOD(k, 6) WHEN 0 THEN 'cup' WHEN 1 THEN 'notebook' WHEN 2 THEN 'lamp'
                               WHEN 3 THEN 'bag' WHEN 4 THEN 'keyboard' ELSE 'timer' END,
                CAST(10 + MOD(k * 7, 190) AS DECIMAL(10, 2)),
                MOD(k * 13, 120),
                1 + MOD((? + k) * 37, ?)
            FROM seq
            WHERE k < ?
            """;

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mall"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection c = DriverManager.getConnection(url, "root", "123456")) {
            c.setAutoCommit(false);
            if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
                reset(c);
            } else {
                long shops = args.length > 0 ? Long.parseLong(args[0]) : 2_000L;
                scale(c, shops);
            }
            report(c);
        }
    }

    private static void scale(Connection c, long shops) throws SQLException {
        long currentShops = count(c, "merchant");
        long targetProducts = shops * SKU_PER_SHOP;

        if (currentShops < shops) {
            System.out.printf("商家：%d → %d（新增 %d 家）%n", currentShops, shops, shops - currentShops);
            long nextId = maxId(c, "merchant") + 1;
            long remaining = shops - currentShops;
            try (PreparedStatement ps = c.prepareStatement(INSERT_SHOP)) {
                while (remaining > 0) {
                    int n = (int) Math.min(BATCH, remaining);
                    ps.setLong(1, nextId);
                    ps.setLong(2, nextId);
                    ps.setLong(3, nextId);
                    ps.setLong(4, nextId);
                    ps.setInt(5, n);
                    ps.executeUpdate();
                    nextId += n;
                    remaining -= n;
                }
            }
            c.commit();
        } else {
            System.out.printf("商家：已是 %d 家，不低于目标 %d。%n", currentShops, shops);
        }

        long currentProducts = count(c, "product");
        if (currentProducts < targetProducts) {
            System.out.printf("商品：%d → %d（新增 %d 件）%n",
                    currentProducts, targetProducts, targetProducts - currentProducts);
            long nextId = Math.max(5001L, maxId(c, "product") + 1);
            long remaining = targetProducts - currentProducts;
            long t0 = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(INSERT_PRODUCT)) {
                while (remaining > 0) {
                    int n = (int) Math.min(BATCH, remaining);
                    ps.setLong(1, nextId);
                    ps.setLong(2, nextId);
                    ps.setLong(3, nextId);
                    ps.setLong(4, nextId);
                    ps.setLong(5, shops);
                    ps.setInt(6, n);
                    ps.executeUpdate();
                    nextId += n;
                    remaining -= n;
                }
            }
            c.commit();
            System.out.printf("  灌数耗时 %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
        } else {
            System.out.printf("商品：已是 %d 件，不低于目标 %d。%n", currentProducts, targetProducts);
        }

        // 全量重分配：只给"新增的商品"按新模数分配，存量商品会停留在旧分布上，
        // 于是每家店的 SKU 数不再相等——那会让「一家店能看到多少单」这件事失去可比性。
        // 一次全表 UPDATE 换一个可以断言的不变量（每店恰好 25 件），值得。
        System.out.printf("按 %d 家店重新分配归属…%n", shops);
        long t1 = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE product SET merchant_id = 1 + MOD((id - 1) * 37, ?)")) {
            ps.setLong(1, shops);
            ps.executeUpdate();
        }
        c.commit();
        System.out.printf("  重分配耗时 %.1fs%n", (System.currentTimeMillis() - t1) / 1000.0);
    }

    private static void reset(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            // 明细上的 merchant_id 有外键指向 merchant：先把引用了压测店的明细清掉，
            // 否则删店会被外键挡住。商品同理。
            s.execute("DELETE FROM order_item WHERE product_id >= 5001 OR merchant_id > " + RESIDENT_SHOPS);
            s.execute("DELETE FROM stock_ledger WHERE product_id >= 5001");
            int products = s.executeUpdate("DELETE FROM product WHERE id >= 5001");
            int shops = s.executeUpdate("DELETE FROM merchant WHERE id > " + RESIDENT_SHOPS);
            c.commit();
            System.out.printf("已删除 %d 家压测店铺与 %d 件压测商品。%n", shops, products);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE product SET merchant_id = 1 + MOD((id - 1) * 37, ?)")) {
            ps.setLong(1, RESIDENT_SHOPS);
            ps.executeUpdate();
        }
        c.commit();
        System.out.printf("归属已还原为常驻档（%d 家店 × %d 件）。%n", RESIDENT_SHOPS, SKU_PER_SHOP);
    }

    private static void report(Connection c) throws SQLException {
        System.out.println();
        try (Statement s = c.createStatement()) {
            q(s, "商家数", "SELECT COUNT(*) FROM merchant");
            q(s, "商品数", "SELECT COUNT(*) FROM product");
            q(s, "每店 SKU min/max",
                    "SELECT CONCAT(MIN(n), ' / ', MAX(n)) FROM"
                            + " (SELECT merchant_id, COUNT(*) n FROM product GROUP BY merchant_id) t");
            q(s, "有货的店数", "SELECT COUNT(*) FROM (SELECT merchant_id FROM product GROUP BY merchant_id) t");

            long t0 = System.nanoTime();
            try (ResultSet rs = s.executeQuery("SELECT id FROM product ORDER BY id LIMIT 24")) {
                int n = 0;
                while (rs.next()) n++;
                System.out.printf("  首页 24 件            %.3fms%n", (System.nanoTime() - t0) / 1e6);
            }
        }
    }

    private static void q(Statement s, String label, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            System.out.printf("  %-18s %s%n", label, rs.getString(1));
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long maxId(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(id) FROM " + table)) {
            rs.next();
            long v = rs.getLong(1);
            return rs.wasNull() ? 0L : v;
        }
    }
}
