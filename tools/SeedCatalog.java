import java.sql.*;
import java.util.*;

/**
 * 压测档位灌数工具：把商品目录从常驻的 5,000 件临时扩到 50,000 / 500,000 件。
 *
 * <p><b>为什么不进迁移</b>：这是压测用的临时数据，不是产品需求。写进 Flyway 会让
 * 每次启动都背着几十万行假商品，还会让"目录里到底有几件"这个问题有两个答案。
 * 所以常驻量（V23 的 5,000 件）进迁移，压测量用这个工具灌、用完删。
 *
 * <p><b>为什么名字是"压测商品-N"而不是走 V23 那套词表</b>：V23 的名字空间是
 * 20×7×11 = 1540 种/分类，够 5,000 件（每类约 1,667 件里有 1,540 个不重名，
 * 实测零重名），但撑不到 50 万件（每类需 16 万个不重名）。
 * 压测数据的目的是"把表撑大"，不是"看起来像真商品"，
 * 与其造一堆带序号后缀的半假名字，不如直白地叫"压测商品-N"——
 * 一眼认得出、清理时删得干净、不会和真实目录混淆。
 *
 * <pre>{@code
 *   # 灌到 5 万件（默认）
 *   java -cp ... SeedCatalog 50000
 *   # 再灌到 50 万件
 *   java -cp ... SeedCatalog 500000
 *   # 清回常驻的 5,000 件
 *   java -cp ... SeedCatalog reset
 * }</pre>
 */
public class SeedCatalog {

    /** 常驻数据占 id 1..5000（V2 的 6 件 + V20 的 500 件 + V23 的 4,494 件）。 */
    private static final long SEED_FROM = 5001L;

    /** 每批行数：太大一次 INSERT 会长时间锁表，太小则往返开销占主导。 */
    private static final int BATCH = 5_000;

    /** 造数用的递归 CTE：只递归到 10，行数靠笛卡尔积。见 V23 里同样的说明。 */
    private static final String INSERT = """
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
                -- V24 之后 merchant_id 是 NOT NULL 且没有默认值：漏写它插不进去，
                -- 而"默默落到 1 号店"正是这次要消灭的那种静默兜底。
                -- 与 V24 同一个乘同余公式，分母取"现在有几家店"，于是压测量也是按店均匀铺开。
                1 + MOD((? + k) * 37, (SELECT COUNT(*) FROM merchant))
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
                return;
            }
            long target = args.length > 0 ? Long.parseLong(args[0]) : 50_000L;
            seed(c, target);
            report(c);
        }
    }

    private static void seed(Connection c, long target) throws SQLException {
        long current = count(c);
        if (current >= target) {
            System.out.printf("已是 %d 件，不低于目标 %d，无需灌数。%n", current, target);
            return;
        }
        System.out.printf("目标 %d 件，当前 %d 件，需要新增 %d 件。%n", target, current, target - current);
        long nextId = Math.max(SEED_FROM, maxId(c) + 1);
        long remaining = target - current;
        long t0 = System.currentTimeMillis();
        int batches = 0;
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            while (remaining > 0) {
                int n = (int) Math.min(BATCH, remaining);
                // 前三个 ? 都是 id 基准（id、名字里的编号、描述里的编号），第四个是本批行数；
                // 顺序与它们在 INSERT 里出现的位置一致。
                ps.setLong(1, nextId);
                ps.setLong(2, nextId);
                ps.setLong(3, nextId);
                ps.setInt(4, n);
                ps.executeUpdate();
                nextId += n;
                remaining -= n;
                if (++batches % 10 == 0) {
                    c.commit();
                    System.out.printf("  已写入 %d 批 / %d 件…%n", batches, count(c));
                }
            }
        }
        c.commit();
        System.out.printf("完成：新增 %d 件，耗时 %.1fs%n", target - current, (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static void reset(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DELETE FROM stock_ledger WHERE product_id >= " + SEED_FROM);
            int n = s.executeUpdate("DELETE FROM product WHERE id >= " + SEED_FROM);
            c.commit();
            System.out.printf("已删除 %d 件压测商品，恢复到常驻的 %d 件。%n", n, count(c));
        }
    }

    private static void report(Connection c) throws SQLException {
        System.out.println();
        try (Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM product")) {
                rs.next();
                System.out.printf("  当前总件数：%d%n", rs.getInt(1));
            }
            for (int page : new int[] {0, 100, 1000}) {
                long t0 = System.nanoTime();
                try (ResultSet rs = s.executeQuery("SELECT id FROM product ORDER BY id LIMIT 24 OFFSET " + page * 24)) {
                    int n = 0;
                    while (rs.next()) n++;
                    System.out.printf("  page=%-5d %d 行，%.3fms%n", page, n, (System.nanoTime() - t0) / 1e6);
                }
            }
        }
    }

    private static long count(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM product")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long maxId(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(id) FROM product")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
