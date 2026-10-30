import java.sql.*;

/**
 * 阶段 3b 的方言风险探针：<b>按店拆子单（V25）里的每一条 DDL / DML 在两种库上是否都成立</b>。
 *
 * <p>要回答的具体问题：
 * <ol>
 *   <li>{@code order_sub} 的建表：四条 CHECK（金额恒等式、履约状态、履约时刻、非负）两边都接受吗？</li>
 *   <li>存量回填的 INSERT…WITH…SELECT：用<b>关联子查询</b>而不是窗口函数做「最大余额法」分摊，
 *       分摊后 Σ 子单金额是否<b>分毫不差</b>等于父单（这是能被 SQL 断言的不变量）。</li>
 *   <li>子单的 UPDATE 怎么写「父单必须已支付」：没有跨表 CHECK，只能写进 WHERE 的 EXISTS。
 *       带别名 / 不带别名两种写法两边都接受吗？</li>
 *   <li>父单履约列的<b>下线</b>：DROP CONSTRAINT + DROP COLUMN 在两种库上的写法。</li>
 *   <li>{@code refund.in_flight} 生成列要从 {@code order_id} 改成 {@code order_sub_id}：
 *       MODIFY COLUMN 改表达式 / DROP COLUMN 重建，哪条路两边都通？DROP INDEX 的写法哪个可移植？</li>
 *   <li>{@code refund.order_sub_id} 加列 → 回填 → 收成 NOT NULL。</li>
 * </ol>
 */
public class SubOrderDdlProbe {

    private static final String H2 = "jdbc:h2:mem:suborder-probe;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static final String MYSQL = "jdbc:mysql://127.0.0.1:3306/mall_check"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    private static final String MAIN = "jdbc:mysql://127.0.0.1:3306/mall"
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(MAIN, "root", "123456")) {
            System.out.println("===== 真实主库 " + c.getMetaData().getDatabaseProductVersion() + " =====");
            try (Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT"
                        + " (SELECT COUNT(*) FROM mall_order),"
                        + " (SELECT COUNT(*) FROM order_item),"
                        + " (SELECT COUNT(*) FROM refund),"
                        + " (SELECT COUNT(*) FROM assistant_pending_action)");
                rs.next();
                System.out.printf("       mall_order=%d order_item=%d refund=%d pending_action=%d%n",
                        rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                rs = s.executeQuery("SELECT COUNT(*) FROM (SELECT order_id, COUNT(DISTINCT merchant_id) c"
                        + " FROM order_item GROUP BY order_id) t WHERE c > 1");
                rs.next();
                System.out.println("       跨店订单数（>1 家店）= " + rs.getInt(1));
            }
        }
        try (Connection c = DriverManager.getConnection(H2, "sa", "")) {
            System.out.println();
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

        // ——————————————————— ① 建 order_sub ———————————————————
        try_(c, "建表 order_sub（含金额恒等式 CHECK）", """
                CREATE TABLE order_sub (
                  id VARCHAR(64) PRIMARY KEY,
                  order_id VARCHAR(36) NOT NULL,
                  sub_seq INT NOT NULL,
                  merchant_id BIGINT NOT NULL,
                  subtotal DECIMAL(12,2) NOT NULL,
                  discount DECIMAL(12,2) NOT NULL,
                  shipping_fee DECIMAL(12,2) NOT NULL,
                  pay_amount DECIMAL(12,2) NOT NULL,
                  fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
                  shipped_at TIMESTAMP,
                  shipped_by VARCHAR(100),
                  shipping_no VARCHAR(40),
                  received_at TIMESTAMP,
                  completed_at TIMESTAMP,
                  auto_receive_at TIMESTAMP,
                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  CONSTRAINT uq_sub_merchant UNIQUE (order_id, merchant_id),
                  CONSTRAINT uq_sub_seq UNIQUE (order_id, sub_seq),
                  CONSTRAINT fk_sub_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
                  CONSTRAINT ck_sub_fulfillment_status
                    CHECK (fulfillment_status IN ('NONE','SHIPPED','RECEIVED','COMPLETED')),
                  CONSTRAINT ck_sub_fulfillment_fields CHECK (
                    (fulfillment_status = 'NONE'      AND shipped_at IS NULL     AND received_at IS NULL     AND completed_at IS NULL)
                 OR (fulfillment_status = 'SHIPPED'   AND shipped_at IS NOT NULL AND received_at IS NULL     AND completed_at IS NULL)
                 OR (fulfillment_status = 'RECEIVED'  AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND completed_at IS NULL)
                 OR (fulfillment_status = 'COMPLETED' AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND completed_at IS NOT NULL)),
                  CONSTRAINT ck_sub_amounts
                    CHECK (pay_amount = subtotal - discount + shipping_fee
                           AND subtotal >= 0 AND discount >= 0 AND shipping_fee >= 0 AND pay_amount >= 0)
                )""");
        try_(c, "索引 idx_sub_order", "CREATE INDEX idx_sub_order ON order_sub(order_id)");
        try_(c, "索引 idx_sub_merchant_status",
                "CREATE INDEX idx_sub_merchant_status ON order_sub(merchant_id, fulfillment_status)");
        try_(c, "索引 idx_sub_autoreceive",
                "CREATE INDEX idx_sub_autoreceive ON order_sub(fulfillment_status, auto_receive_at)");

        // ——————————————————— ② 存量回填：按店聚合 + 最大余额法分摊 ———————————————————
        try_(c, "回填 order_sub（关联子查询做最大余额法）", """
                INSERT INTO order_sub (id, order_id, sub_seq, merchant_id,
                                       subtotal, discount, shipping_fee, pay_amount)
                WITH agg AS (
                  SELECT i.order_id AS order_id, i.merchant_id AS merchant_id,
                         SUM(i.unit_price * i.quantity) AS raw_subtotal
                    FROM probe_item i
                   GROUP BY i.order_id, i.merchant_id
                ), cut AS (
                  SELECT a.order_id AS order_id, a.merchant_id AS merchant_id,
                         a.raw_subtotal AS raw_subtotal, o.subtotal AS o_subtotal,
                         o.discount AS o_discount, o.shipping_fee AS o_ship,
                         (SELECT COUNT(*) FROM agg b
                           WHERE b.order_id = a.order_id AND b.merchant_id <= a.merchant_id) AS seq,
                         (SELECT COUNT(*) FROM agg b WHERE b.order_id = a.order_id) AS cnt,
                         (SELECT COALESCE(SUM(ROUND(b.raw_subtotal, 2)), 0) FROM agg b
                           WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_sub,
                         (SELECT COALESCE(SUM(ROUND(o.discount * b.raw_subtotal
                                                     / NULLIF(o.subtotal, 0), 2)), 0) FROM agg b
                           WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_dis,
                         (SELECT COALESCE(SUM(ROUND(o.shipping_fee * b.raw_subtotal
                                                     / NULLIF(o.subtotal, 0), 2)), 0) FROM agg b
                           WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_ship
                    FROM agg a JOIN probe_order o ON o.id = a.order_id
                ), alloc AS (
                  SELECT c.order_id AS order_id, c.seq AS seq, c.merchant_id AS merchant_id,
                       CASE WHEN c.seq = c.cnt THEN c.o_subtotal - c.prev_sub
                            ELSE ROUND(c.raw_subtotal, 2) END AS subtotal,
                       CASE WHEN c.seq = c.cnt THEN c.o_discount - c.prev_dis
                            ELSE ROUND(c.o_discount * c.raw_subtotal / NULLIF(c.o_subtotal, 0), 2) END AS discount,
                       CASE WHEN c.seq = c.cnt THEN c.o_ship - c.prev_ship
                            ELSE ROUND(c.o_ship * c.raw_subtotal / NULLIF(c.o_subtotal, 0), 2) END AS shipping_fee
                    FROM cut c
                )
                SELECT CONCAT(a.order_id, '-S', a.seq), a.order_id, a.seq, a.merchant_id,
                       a.subtotal, a.discount, a.shipping_fee,
                       a.subtotal - a.discount + a.shipping_fee
                  FROM alloc a""");

        // ck_sub_amounts 现在建在 CREATE TABLE 里（三层 CTE 一次算出 pay_amount，不再需要后置回填）。

        // ——————————————————— ③ 子单 UPDATE：把「父单已支付」写进 WHERE ———————————————————
        try_(c, "发货（不带别名 + EXISTS 父单 PAID）",
                "UPDATE order_sub SET fulfillment_status='SHIPPED', shipped_at=CURRENT_TIMESTAMP,"
                        + " shipped_by='admin', shipping_no='SF1', auto_receive_at=CURRENT_TIMESTAMP"
                        + " WHERE id = 'o1-S1' AND fulfillment_status='NONE'"
                        + " AND EXISTS (SELECT 1 FROM probe_order o"
                        + "              WHERE o.id = order_sub.order_id AND o.status='PAID')");
        try_(c, "发货（带别名 s）",
                "UPDATE order_sub s SET s.fulfillment_status='SHIPPED', s.shipped_at=CURRENT_TIMESTAMP,"
                        + " s.shipped_by='admin', s.shipping_no='SF2', s.auto_receive_at=CURRENT_TIMESTAMP"
                        + " WHERE s.id = 'o2-S1' AND s.fulfillment_status='NONE'"
                        + " AND EXISTS (SELECT 1 FROM probe_order o"
                        + "              WHERE o.id = s.order_id AND o.status='PAID')");
        try_(c, "收货（带别名 + 归属校验在父单上）",
                "UPDATE order_sub s SET s.fulfillment_status='RECEIVED', s.received_at=CURRENT_TIMESTAMP"
                        + " WHERE s.id = 'o1-S1' AND s.fulfillment_status='SHIPPED'"
                        + " AND EXISTS (SELECT 1 FROM probe_order o"
                        + "              WHERE o.id = s.order_id AND o.username='student')");
        try_(c, "完成（不带别名 + 归属校验）",
                "UPDATE order_sub SET fulfillment_status='COMPLETED', completed_at=CURRENT_TIMESTAMP"
                        + " WHERE id = 'o1-S1' AND fulfillment_status='RECEIVED'"
                        + " AND EXISTS (SELECT 1 FROM probe_order o"
                        + "              WHERE o.id = order_sub.order_id AND o.username='student')");

        // ——————————————————— ④ 父单履约列下线 ———————————————————
        try_(c, "DROP INDEX（DROP INDEX n ON t）", "DROP INDEX idx_probe_fulfill ON probe_order");
        try_(c, "DROP INDEX（裸 DROP INDEX n）", "DROP INDEX idx_probe_autoreceive");
        try_(c, "DROP CONSTRAINT ck_fulfillment_status",
                "ALTER TABLE probe_order DROP CONSTRAINT ck_probe_fulfillment_status");
        try_(c, "DROP CONSTRAINT ck_fulfillment_fields",
                "ALTER TABLE probe_order DROP CONSTRAINT ck_probe_fulfillment_fields");
        try_(c, "DROP CONSTRAINT ck_fulfillment_needs_paid",
                "ALTER TABLE probe_order DROP CONSTRAINT ck_probe_fulfillment_needs_paid");
        try_(c, "DROP COLUMN fulfillment_status",
                "ALTER TABLE probe_order DROP COLUMN fulfillment_status");
        try_(c, "DROP COLUMN shipped_at", "ALTER TABLE probe_order DROP COLUMN shipped_at");
        try_(c, "DROP COLUMN auto_receive_at", "ALTER TABLE probe_order DROP COLUMN auto_receive_at");

        // ——————————————————— ⑤ refund.in_flight 从 order_id 改成 order_sub_id ———————————————————
        try_(c, "refund ADD COLUMN order_sub_id VARCHAR(64)",
                "ALTER TABLE probe_refund ADD COLUMN order_sub_id VARCHAR(64)");
        try_(c, "回填 refund.order_sub_id",
                "UPDATE probe_refund r SET r.order_sub_id ="
                        + " (SELECT s.id FROM order_sub s WHERE s.order_id = r.order_id"
                        + "   ORDER BY s.sub_seq LIMIT 1)");
        try_(c, "DROP INDEX（ALTER TABLE … DROP INDEX n）",
                "ALTER TABLE probe_refund DROP INDEX uq_probe_inflight");
        try_(c, "DROP COLUMN in_flight", "ALTER TABLE probe_refund DROP COLUMN in_flight");
        try_(c, "重建生成列（指向 order_sub_id）",
                "ALTER TABLE probe_refund ADD COLUMN in_flight VARCHAR(64) GENERATED ALWAYS AS"
                        + " (CASE WHEN status IN ('PENDING_APPROVAL','APPROVED') THEN order_sub_id END)");
        try_(c, "重建唯一索引", "CREATE UNIQUE INDEX uq_probe_inflight ON probe_refund(in_flight)");
        try_(c, "order_sub_id 收成 NOT NULL（MODIFY COLUMN）",
                "ALTER TABLE probe_refund MODIFY COLUMN order_sub_id VARCHAR(64) NOT NULL");

        // ——————————————————— ⑥ pending_action 加子单列 ———————————————————
        try_(c, "pending_action ADD COLUMN order_sub_id",
                "ALTER TABLE probe_pending ADD COLUMN order_sub_id VARCHAR(64)");

        report(c);
    }

    private static void prepare(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            // MySQL 的 mall_check 里 product 带着指向 merchant 的外键，直接 DROP merchant 会被拒；
            // H2 不认这条语句，忽略失败即可。
            try {
                s.execute("SET FOREIGN_KEY_CHECKS=0");
            } catch (SQLException ignored) {
                // H2 没有这个变量，本探针里的表本来就没有交叉外键，不需要它
            }
            s.execute("DROP TABLE IF EXISTS probe_refund");
            s.execute("DROP TABLE IF EXISTS order_sub");
            s.execute("DROP TABLE IF EXISTS probe_item");
            s.execute("DROP TABLE IF EXISTS probe_order");
            s.execute("DROP TABLE IF EXISTS probe_pending");
            s.execute("DROP TABLE IF EXISTS merchant");
            s.execute("CREATE TABLE merchant (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            s.execute("INSERT INTO merchant(id, name) VALUES (1,'甲店'), (2,'乙店'), (3,'丙店')");
            s.execute("CREATE TABLE probe_order ("
                    + " id VARCHAR(36) PRIMARY KEY, username VARCHAR(100) NOT NULL,"
                    + " status VARCHAR(20) NOT NULL, subtotal DECIMAL(12,2) NOT NULL,"
                    + " discount DECIMAL(12,2) NOT NULL, shipping_fee DECIMAL(12,2) NOT NULL,"
                    + " pay_amount DECIMAL(12,2) NOT NULL, paid_at TIMESTAMP,"
                    + " fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'NONE',"
                    + " shipped_at TIMESTAMP, shipped_by VARCHAR(100), shipping_no VARCHAR(40),"
                    + " received_at TIMESTAMP, completed_at TIMESTAMP, auto_receive_at TIMESTAMP,"
                    + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " CONSTRAINT ck_probe_fulfillment_status"
                    + "   CHECK (fulfillment_status IN ('NONE','SHIPPED','RECEIVED','COMPLETED')),"
                    + " CONSTRAINT ck_probe_fulfillment_fields CHECK (fulfillment_status = 'NONE'),"
                    + " CONSTRAINT ck_probe_fulfillment_needs_paid"
                    + "   CHECK (fulfillment_status = 'NONE' OR status = 'PAID'))");
            s.execute("CREATE INDEX idx_probe_fulfill ON probe_order(fulfillment_status, shipped_at)");
            s.execute("CREATE INDEX idx_probe_autoreceive ON probe_order(fulfillment_status, auto_receive_at)");
            s.execute("CREATE TABLE probe_item (order_id VARCHAR(36), product_id BIGINT,"
                    + " unit_price DECIMAL(12,2), quantity INT, merchant_id BIGINT,"
                    + " PRIMARY KEY (order_id, product_id))");
            s.execute("CREATE TABLE probe_refund (id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL,"
                    + " username VARCHAR(100) NOT NULL, amount DECIMAL(12,2) NOT NULL,"
                    + " status VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL',"
                    + " in_flight VARCHAR(36) GENERATED ALWAYS AS"
                    + "   (CASE WHEN status IN ('PENDING_APPROVAL','APPROVED') THEN order_id END))");
            s.execute("CREATE UNIQUE INDEX uq_probe_inflight ON probe_refund(in_flight)");
            s.execute("CREATE TABLE probe_pending (id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36))");

            // o1：跨三店，小计 100 + 60.05 + 39.95 = 200，满减 30.03，运费 7.77 → 应付 177.74
            s.execute("INSERT INTO probe_order(id, username, status, subtotal, discount, shipping_fee,"
                    + " pay_amount, paid_at) VALUES"
                    + " ('o1','student','PAID',200.00,30.03,7.77,177.74,CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO probe_item(order_id, product_id, unit_price, quantity, merchant_id)"
                    + " VALUES ('o1',1,100.00,1,1), ('o1',2,30.02,2,2), ('o1',3,39.95,1,3)");
            // o2：单店
            s.execute("INSERT INTO probe_order(id, username, status, subtotal, discount, shipping_fee,"
                    + " pay_amount, paid_at) VALUES"
                    + " ('o2','classmate','PAID',50.00,0.00,12.00,62.00,CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO probe_item(order_id, product_id, unit_price, quantity, merchant_id)"
                    + " VALUES ('o2',1,50.00,1,2)");
            // o3：未支付（用于验证「父单未支付不许发货」）
            s.execute("INSERT INTO probe_order(id, username, status, subtotal, discount, shipping_fee,"
                    + " pay_amount) VALUES ('o3','student','PENDING',10.00,0.00,0.00,10.00)");
            s.execute("INSERT INTO probe_item(order_id, product_id, unit_price, quantity, merchant_id)"
                    + " VALUES ('o3',1,10.00,1,1)");
            s.execute("INSERT INTO probe_refund(id, order_id, username, amount) VALUES"
                    + " ('r1','o1','student',20.00), ('r2','o2','classmate',5.00)");
        }
    }

    private static void try_(Connection c, String label, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            int n = s.executeUpdate(sql);
            System.out.printf("  OK   %-42s → %d%n", label, n);
        } catch (SQLException e) {
            System.out.printf("  FAIL %-42s → [%s] %s%n", label, e.getSQLState(), oneLine(e.getMessage()));
        }
    }

    private static void report(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            System.out.println("       —— order_sub ——");
            ResultSet rs = s.executeQuery("SELECT s.id, s.sub_seq, s.merchant_id, s.subtotal, s.discount,"
                    + " s.shipping_fee, s.pay_amount, s.fulfillment_status"
                    + " FROM order_sub s ORDER BY s.order_id, s.sub_seq");
            while (rs.next()) {
                System.out.printf("       %-10s seq=%d m=%d 小计=%s 减=%s 运费=%s 应付=%s %s%n",
                        rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8));
            }
            System.out.println("       —— 分摊对账（Σ 子单 vs 父单，必须全等）——");
            rs = s.executeQuery("SELECT o.id, o.subtotal, o.discount, o.shipping_fee, o.pay_amount,"
                    + " (SELECT COALESCE(SUM(s.subtotal),0) FROM order_sub s WHERE s.order_id=o.id),"
                    + " (SELECT COALESCE(SUM(s.discount),0) FROM order_sub s WHERE s.order_id=o.id),"
                    + " (SELECT COALESCE(SUM(s.shipping_fee),0) FROM order_sub s WHERE s.order_id=o.id),"
                    + " (SELECT COALESCE(SUM(s.pay_amount),0) FROM order_sub s WHERE s.order_id=o.id)"
                    + " FROM probe_order o ORDER BY o.id");
            boolean allMatch = true;
            while (rs.next()) {
                boolean ok = rs.getBigDecimal(2).compareTo(rs.getBigDecimal(6)) == 0
                        && rs.getBigDecimal(3).compareTo(rs.getBigDecimal(7)) == 0
                        && rs.getBigDecimal(4).compareTo(rs.getBigDecimal(8)) == 0
                        && rs.getBigDecimal(5).compareTo(rs.getBigDecimal(9)) == 0;
                allMatch &= ok;
                System.out.printf("       %s 父[%s/%s/%s/%s] 子和[%s/%s/%s/%s] %s%n", rs.getString(1),
                        rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        ok ? "一致" : "★ 不一致");
            }
            System.out.println("       分摊全等 = " + allMatch);
            rs = s.executeQuery("SELECT id, order_sub_id, status FROM probe_refund ORDER BY id");
            while (rs.next()) {
                System.out.printf("       refund %s → sub %s (%s)%n",
                        rs.getString(1), rs.getString(2), rs.getString(3));
            }
        }
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ");
        return t.length() > 130 ? t.substring(0, 130) + "..." : t;
    }
}
