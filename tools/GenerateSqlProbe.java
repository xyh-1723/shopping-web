import java.sql.*;

/**
 * 阶段 2 的方言风险探针：<b>能否用"生成式 SQL"造出上万件商品</b>，
 * 而不是再往仓库里塞一个几百 KB 的硬编码 INSERT 文件（V20 的 500 件就是 82KB）。
 *
 * <p>候选写法：递归 CTE 生成序号 → INSERT ... SELECT 一次落 N 行。
 * H2 2.3 与 MySQL 8 对递归 CTE 的支持程度并不一致（尤其 {@code WITH} 放在
 * {@code INSERT} 前后的语法位置），所以两条路都试一遍，只保留两边都成立的那种。
 */
public class GenerateSqlProbe {

    private static final String H2 = "jdbc:h2:mem:gen-probe;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String MYSQL = "jdbc:mysql://127.0.0.1:3306/mall_check"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(H2, "sa", "")) {
            System.out.println("===== H2 " + c.getMetaData().getDatabaseProductVersion() + " =====");
            prepare(c);
            probeAll(c);
        }
        try (Connection c = DriverManager.getConnection(MYSQL, "root", "123456")) {
            System.out.println();
            System.out.println("===== MySQL " + c.getMetaData().getDatabaseProductVersion() + " =====");
            prepare(c);
            probeAll(c);
        }
    }

    private static void prepare(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS gen_probe");
            s.execute("CREATE TABLE gen_probe (id BIGINT PRIMARY KEY, name VARCHAR(64), seq INT)");
        }
    }

    private static void probeAll(Connection c) throws SQLException {
        // 写法 A：WITH 在 INSERT 之前
        try_(c, "A: WITH RECURSIVE ... INSERT INTO ... SELECT",
                "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100)"
                        + " INSERT INTO gen_probe(id, name, seq) SELECT 900000 + n, CONCAT('商品', n), n FROM seq");
        // 写法 B：WITH 在 SELECT 之前（INSERT ... WITH ... SELECT）
        try_(c, "B: INSERT INTO ... WITH RECURSIVE ... SELECT",
                "INSERT INTO gen_probe(id, name, seq) WITH RECURSIVE seq(n) AS"
                        + " (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 100)"
                        + " SELECT 910000 + n, CONCAT('商品', n), n FROM seq");
        // 写法 C：不用 CTE，用自连接翻倍（最保守，两边必然都支持）
        try_(c, "C: INSERT ... SELECT（自连接，无 CTE）",
                "INSERT INTO gen_probe(id, name, seq) SELECT 920000 + a.seq * 10 + b.seq,"
                        + " CONCAT('商品', a.seq, '-', b.seq), a.seq * 10 + b.seq FROM gen_probe a JOIN gen_probe b"
                        + " WHERE a.seq <= 10 AND b.seq <= 10");

        report(c);
        // 每条用例写进不同的 id 段，互不干扰；这里清空以便下一种写法从零开始计数。
        try (Statement s = c.createStatement()) {
            s.execute("DELETE FROM gen_probe");
        }
    }

    private static void try_(Connection c, String label, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            int n = s.executeUpdate(sql);
            System.out.printf("  ✔ %-48s → %d 行%n", label, n);
        } catch (SQLException e) {
            System.out.printf("  ✘ %-48s → [%s] %s%n", label, e.getSQLState(), oneLine(e.getMessage()));
        }
    }

    private static void report(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM gen_probe")) {
            rs.next();
            System.out.printf("      表内现有 %d 行%n", rs.getInt(1));
        }
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ");
        return t.length() > 110 ? t.substring(0, 110) + "…" : t;
    }
}
