import java.sql.*;

/**
 * 阶段 3 的方言风险探针：<b>商家地基（V24）里的每一条 DDL / DML 在两种库上是否都成立</b>。
 *
 * <p>要回答的具体问题：
 * <ol>
 *   <li>{@code ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT n}：给已有大表加非空列的可移植写法。</li>
 *   <li>加完之后怎么把 DEFAULT 去掉——留着它意味着「以后忘写 merchant_id 也能插进去」，
 *       而那个默认值会静默把商品判给 1 号商家，正是本项目最反对的「静默半成功」。</li>
 *   <li>{@code ALTER COLUMN ... SET NOT NULL} 与 {@code MODIFY COLUMN}：两种库谁的写法能被对方接受。</li>
 *   <li>生成式 SQL 造 200 个商家（沿用 V23 的数位笛卡尔积，避开 MySQL 的递归深度上限）。</li>
 *   <li>按「乘同余」给 5000 件商品分商家：只用 MOD，不用除法（MySQL 的 / 是小数除法）。</li>
 *   <li>order_item 的回填：关联子查询 {@code UPDATE ... SET c = (SELECT ...)}。</li>
 * </ol>
 */
public class MerchantDdlProbe {

    private static final String H2 = "jdbc:h2:mem:merchant-probe;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String MYSQL = "jdbc:mysql://127.0.0.1:3306/mall_check"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(H2, "sa", "")) {
            System.out.println("===== H2 " + c.getMetaData().getDatabaseProductVersion() + " =====");
            run(c);
        }
        try (Connection c = DriverManager.getConnection(MYSQL, "root", "123456")) {
            System.out.println();
            System.out.println("===== MySQL " + c.getMetaData().getDatabaseProductVersion() + " =====");
            run(c);
        }
    }

    private static void run(Connection c) throws SQLException {
        prepare(c);

        // ① 建 merchant 表（生成式插入 200 行）
        try_(c, "建表 merchant", """
                CREATE TABLE merchant (
                  id BIGINT PRIMARY KEY,
                  code VARCHAR(40) NOT NULL,
                  name VARCHAR(100) NOT NULL,
                  owner_username VARCHAR(100) NOT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT uq_merchant_code UNIQUE (code),
                  CONSTRAINT uq_merchant_owner UNIQUE (owner_username)
                )""");

        // MySQL 不认 CAST(x AS VARCHAR(n))，只认 CHAR；H2 两者都认。
        // 这里连 CAST 都不用：CONCAT 与 LPAD 是两边的交集。
        try_(c, "V1 生成式插入（CONCAT+LPAD，无 CAST）", """
                INSERT INTO merchant (id, code, name, owner_username)
                WITH RECURSIVE digit(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9),
                seq(k) AS (SELECT d1.n + d2.n * 10 + d3.n * 100
                             FROM digit d1, digit d2, digit d3
                            WHERE d3.n < 2),
                limited(k) AS (SELECT k FROM seq WHERE k < 200)
                SELECT
                    1 + k,
                    CONCAT('M', LPAD(CONCAT('', 1 + k), 4, '0')),
                    CONCAT(
                        CASE MOD(k, 10)
                            WHEN 0 THEN '南山' WHEN 1 THEN '北屿' WHEN 2 THEN '晨雾' WHEN 3 THEN '海盐'
                            WHEN 4 THEN '藤紫' WHEN 5 THEN '墨蓝' WHEN 6 THEN '晴山' WHEN 7 THEN '晚枫'
                            WHEN 8 THEN '雪松' ELSE '青柠'
                        END,
                        CASE MOD(k, 7)
                            WHEN 0 THEN '生活馆' WHEN 1 THEN '文具社' WHEN 2 THEN '数码铺' WHEN 3 THEN '家居坊'
                            WHEN 4 THEN '手作屋' WHEN 5 THEN '选物集' ELSE '杂货店'
                        END),
                    CASE WHEN k = 0 THEN 'merchant'
                         ELSE CONCAT('m', LPAD(CONCAT('', 1 + k), 4, '0')) END
                FROM limited""");
        try_(c, "V2 对照：CAST AS VARCHAR（预期 MySQL 拒绝）", """
                INSERT INTO merchant (id, code, name, owner_username)
                WITH RECURSIVE digit(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9),
                seq(k) AS (SELECT d1.n + d2.n * 10 + d3.n * 100
                             FROM digit d1, digit d2, digit d3
                            WHERE d3.n < 2),
                limited(k) AS (SELECT k FROM seq WHERE k < 200)
                SELECT
                    1001 + k,
                    CONCAT('X', LPAD(CAST(1 + k AS VARCHAR(10)), 4, '0')),
                    '对照',
                    CONCAT('x', CAST(1 + k AS VARCHAR(10)))
                FROM limited""");
        try_(c, "V3 对照：CAST AS CHAR（预期两边都接受）", """
                INSERT INTO merchant (id, code, name, owner_username)
                WITH RECURSIVE digit(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9),
                seq(k) AS (SELECT d1.n + d2.n * 10 + d3.n * 100
                             FROM digit d1, digit d2, digit d3
                            WHERE d3.n < 2),
                limited(k) AS (SELECT k FROM seq WHERE k < 200)
                SELECT
                    2001 + k,
                    CONCAT('Y', LPAD(CAST(1 + k AS CHAR(10)), 4, '0')),
                    '对照二',
                    CONCAT('y', CAST(1 + k AS CHAR(10)))
                FROM limited""");

        // ② 给 product 加 merchant_id：三种写法各试一遍
        try_(c, "ADD COLUMN ... NOT NULL DEFAULT 1",
                "ALTER TABLE probe_product ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1");
        try_(c, "按乘同余分商家（只用 MOD）",
                "UPDATE probe_product SET merchant_id = 1 + MOD((id - 1) * 37, 200)");
        try_(c, "DROP DEFAULT（去掉静默兜底）",
                "ALTER TABLE probe_product ALTER COLUMN merchant_id DROP DEFAULT");
        try_(c, "ALTER COLUMN ... SET NOT NULL",
                "ALTER TABLE probe_product ALTER COLUMN merchant_id SET NOT NULL");
        try_(c, "MODIFY COLUMN ... NOT NULL（MySQL 写法）",
                "ALTER TABLE probe_product MODIFY COLUMN merchant_id BIGINT NOT NULL");
        try_(c, "加外键", "ALTER TABLE probe_product ADD CONSTRAINT fk_probe_merchant"
                + " FOREIGN KEY (merchant_id) REFERENCES merchant(id)");
        try_(c, "建索引", "CREATE INDEX idx_probe_merchant ON probe_product(merchant_id)");

        // ③ order_item 回填：关联子查询
        try_(c, "order_item 回填 merchant_id（关联子查询）",
                "UPDATE probe_item oi SET oi.merchant_id ="
                        + " (SELECT p.merchant_id FROM probe_product p WHERE p.id = oi.product_id)");

        report(c);
    }

    private static void prepare(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS probe_item");
            s.execute("DROP TABLE IF EXISTS probe_product");
            s.execute("DROP TABLE IF EXISTS merchant");
            s.execute("CREATE TABLE probe_product (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            s.execute("CREATE TABLE probe_item (order_id VARCHAR(36), product_id BIGINT,"
                    + " merchant_id BIGINT, PRIMARY KEY (order_id, product_id))");
            // 造 5000 件（与 V23 之后的真实规模一致）+ 3 条 order_item
            s.execute("INSERT INTO probe_product(id, name)"
                    + " WITH RECURSIVE digit(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM digit WHERE n < 9),"
                    + " seq(k) AS (SELECT d1.n + d2.n * 10 + d3.n * 100 + d4.n * 1000"
                    + " FROM digit d1, digit d2, digit d3, digit d4 WHERE d4.n < 5)"
                    + " SELECT 1 + k, CONCAT('p', k) FROM seq WHERE k < 5000");
            s.execute("INSERT INTO probe_item(order_id, product_id) VALUES"
                    + " ('o1', 1), ('o1', 2500), ('o1', 5000)");
        }
    }

    private static void try_(Connection c, String label, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            int n = s.executeUpdate(sql);
            System.out.printf("  OK   %-40s → %d%n", label, n);
        } catch (SQLException e) {
            System.out.printf("  FAIL %-40s → [%s] %s%n", label, e.getSQLState(), oneLine(e.getMessage()));
        }
    }

    private static void report(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM merchant");
            rs.next();
            System.out.println("       merchant 行数 = " + rs.getInt(1));

            rs = s.executeQuery("SELECT MIN(cnt), MAX(cnt), COUNT(*) FROM ("
                    + "SELECT merchant_id, COUNT(*) AS cnt FROM probe_product GROUP BY merchant_id) t");
            rs.next();
            System.out.printf("       每商家商品数 min=%d max=%d，共 %d 个商家有货%n",
                    rs.getInt(1), rs.getInt(2), rs.getInt(3));

            rs = s.executeQuery("SELECT order_id, product_id, merchant_id FROM probe_item ORDER BY product_id");
            while (rs.next()) {
                System.out.printf("       item %s/%s → merchant %s%n",
                        rs.getString(1), rs.getLong(2), rs.getObject(3));
            }
        }
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }
}
