import java.sql.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * 阶段 3b 的数据核对：在<b>真 MySQL</b> 上从空库重放全部迁移（含 V25），
 * 然后按「应用将来会怎么写」手工造一笔<b>跨三店</b>的订单，核对：
 * <ol>
 *   <li>V25 能不能跑完（订单表当时是空的，回填语句应当 0 行、不报错）；</li>
 *   <li>子订单金额的分摊是否<b>分毫不差</b>等于父单（Σ 四列逐列相等）；</li>
 *   <li>「父单未支付不许发货」这条跨表规则在真库上是否真的拦得住；</li>
 *   <li>按店收窄的退款队列（现在走 order_sub.merchant_id）走不走索引；</li>
 *   <li>一个子订单两笔在途退款是否被唯一索引挡住。</li>
 * </ol>
 */
public class SubOrderCheck {

    /** V25 第二节的回填语句，原样复制——真 MySQL 上用真数据再跑一遍。 */
    private static final String BACKFILL = """
            INSERT INTO order_sub (id, order_id, sub_seq, merchant_id, subtotal, discount, shipping_fee, pay_amount)
            WITH agg AS (
              SELECT i.order_id AS order_id, i.merchant_id AS merchant_id,
                     SUM(i.unit_price * i.quantity) AS raw_subtotal
                FROM order_item i
               GROUP BY i.order_id, i.merchant_id
            ), cut AS (
              SELECT a.order_id AS order_id, a.merchant_id AS merchant_id,
                     a.raw_subtotal AS raw_subtotal,
                     o.subtotal AS o_subtotal, o.discount AS o_discount, o.shipping_fee AS o_ship,
                     (SELECT COUNT(*) FROM agg b
                       WHERE b.order_id = a.order_id AND b.merchant_id <= a.merchant_id) AS seq,
                     (SELECT COUNT(*) FROM agg b WHERE b.order_id = a.order_id) AS cnt,
                     (SELECT COALESCE(SUM(ROUND(b.raw_subtotal, 2)), 0) FROM agg b
                       WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_sub,
                     (SELECT COALESCE(SUM(ROUND(o.discount * b.raw_subtotal / NULLIF(o.subtotal, 0), 2)), 0)
                        FROM agg b WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_dis,
                     (SELECT COALESCE(SUM(ROUND(o.shipping_fee * b.raw_subtotal / NULLIF(o.subtotal, 0), 2)), 0)
                        FROM agg b WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_ship
                FROM agg a JOIN mall_order o ON o.id = a.order_id
            ), alloc AS (
              SELECT c.order_id AS order_id, c.seq AS seq, c.merchant_id AS merchant_id,
                     CASE WHEN c.seq = c.cnt THEN c.o_subtotal - c.prev_sub ELSE ROUND(c.raw_subtotal, 2) END AS subtotal,
                     CASE WHEN c.seq = c.cnt THEN c.o_discount - c.prev_dis
                          ELSE ROUND(c.o_discount * c.raw_subtotal / NULLIF(c.o_subtotal, 0), 2) END AS discount,
                     CASE WHEN c.seq = c.cnt THEN c.o_ship - c.prev_ship
                          ELSE ROUND(c.o_ship * c.raw_subtotal / NULLIF(c.o_subtotal, 0), 2) END AS shipping_fee
                FROM cut c
            )
            SELECT CONCAT(a.order_id, '-S', a.seq), a.order_id, a.seq, a.merchant_id,
                   a.subtotal, a.discount, a.shipping_fee,
                   a.subtotal - a.discount + a.shipping_fee
              FROM alloc a
             WHERE NOT EXISTS (SELECT 1 FROM order_sub s WHERE s.order_id = a.order_id)""";

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
            System.out.println("【结构】V25 之后");
            q(s, "mall_order 还有 fulfillment_status 吗（应为 0 列）",
                    "SELECT COUNT(*) FROM information_schema.columns"
                            + " WHERE table_schema='mall_check' AND table_name='mall_order'"
                            + " AND column_name='fulfillment_status'");
            q(s, "order_sub 的列数", "SELECT COUNT(*) FROM information_schema.columns"
                    + " WHERE table_schema='mall_check' AND table_name='order_sub'");
            q(s, "refund.order_sub_id 可空吗（应为 NO）",
                    "SELECT is_nullable FROM information_schema.columns"
                            + " WHERE table_schema='mall_check' AND table_name='refund'"
                            + " AND column_name='order_sub_id'");
            q(s, "存量订单数 / 存量子订单数",
                    "SELECT CONCAT((SELECT COUNT(*) FROM mall_order), ' / ', (SELECT COUNT(*) FROM order_sub))");

            // ——————————————————— 造一笔跨三店的已支付订单 ———————————————————
            // 父单：小计 199.90，满减 20.00，运费 8.00 → 应付 187.90
            // 三家店分别为 100.00 / 60.04 / 39.86，分摊后 Σ 必须逐列相等
            s.execute("INSERT INTO mall_order(id, username, idempotency_key, request_hash, total,"
                    + " subtotal, discount, shipping_fee, pay_amount, status, expires_at, expiry_shard, paid_at)"
                    + " VALUES ('sub-1','checker','k1','h1',187.90,199.90,20.00,8.00,187.90,'PAID',"
                    + " DATE_ADD(NOW(), INTERVAL 15 MINUTE), 3, NOW())");
            s.execute("INSERT INTO order_item(order_id, product_id, product_name, unit_price, quantity, merchant_id)"
                    + " VALUES ('sub-1', 1, '甲店货', 100.00, 1, 1),"
                    + "        ('sub-1', 2, '乙店货', 30.02, 2, 2),"
                    + "        ('sub-1', 3, '丙店货', 39.86, 1, 3)");
            // 只插父单与明细，<b>不插子订单</b>：接着把 V25 的回填语句原样跑一遍，
            // 于是「V25 之前就存在的老订单」这条回填路径在真 MySQL 上也被真数据验过。
            int backfilled = s.executeUpdate(BACKFILL);

            System.out.println("  （V25 回填语句回填出的子订单行数 = " + backfilled + "）");
            System.out.println();
            System.out.println("【分摊】Σ 子单 vs 父单");
            ResultSet rs = s.executeQuery("SELECT s.id, s.sub_seq, s.merchant_id, s.subtotal, s.discount,"
                    + " s.shipping_fee, s.pay_amount FROM order_sub s ORDER BY s.sub_seq");
            while (rs.next())
                System.out.printf("  %-10s seq=%d 店=%d 小计=%s 减=%s 运费=%s 应付=%s%n",
                        rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7));
            q(s, "四列是否逐列相等", "SELECT CASE WHEN"
                    + " (SELECT subtotal FROM mall_order WHERE id='sub-1')"
                    + "   = (SELECT SUM(subtotal) FROM order_sub WHERE order_id='sub-1')"
                    + " AND (SELECT discount FROM mall_order WHERE id='sub-1')"
                    + "   = (SELECT SUM(discount) FROM order_sub WHERE order_id='sub-1')"
                    + " AND (SELECT shipping_fee FROM mall_order WHERE id='sub-1')"
                    + "   = (SELECT SUM(shipping_fee) FROM order_sub WHERE order_id='sub-1')"
                    + " AND (SELECT pay_amount FROM mall_order WHERE id='sub-1')"
                    + "   = (SELECT SUM(pay_amount) FROM order_sub WHERE order_id='sub-1')"
                    + " THEN '是' ELSE '★ 否' END");

            // ——————————————————— 跨表规则：父单未支付不许发货 ———————————————————
            System.out.println();
            System.out.println("【跨表规则】");
            s.execute("INSERT INTO mall_order(id, username, idempotency_key, request_hash, total,"
                    + " subtotal, discount, shipping_fee, pay_amount, status, expires_at, expiry_shard)"
                    + " VALUES ('sub-2','checker','k2','h2',10.00,10.00,0.00,0.00,10.00,'PENDING',"
                    + " DATE_ADD(NOW(), INTERVAL 15 MINUTE), 4)");
            s.execute("INSERT INTO order_item(order_id, product_id, product_name, unit_price, quantity, merchant_id)"
                    + " VALUES ('sub-2', 1, '甲店货', 10.00, 1, 1)");
            s.execute("INSERT INTO order_sub(id, order_id, sub_seq, merchant_id,"
                    + " subtotal, discount, shipping_fee, pay_amount)"
                    + " VALUES ('sub-2-S1','sub-2',1,1,10.00,0.00,0.00,10.00)");
            int unpaid = s.executeUpdate("UPDATE order_sub SET fulfillment_status='SHIPPED',"
                    + " shipped_at=NOW(), shipped_by='admin', shipping_no='SF-BAD', auto_receive_at=NOW()"
                    + " WHERE id='sub-2-S1' AND fulfillment_status='NONE'"
                    + " AND EXISTS (SELECT 1 FROM mall_order o"
                    + "              WHERE o.id = order_sub.order_id AND o.status='PAID')");
            System.out.println("  未支付父单的子订单发货影响行数（应为 0）= " + unpaid);
            int paid = s.executeUpdate("UPDATE order_sub SET fulfillment_status='SHIPPED',"
                    + " shipped_at=NOW(), shipped_by='admin', shipping_no='SF-OK', auto_receive_at=NOW()"
                    + " WHERE id='sub-1-S1' AND fulfillment_status='NONE'"
                    + " AND EXISTS (SELECT 1 FROM mall_order o"
                    + "              WHERE o.id = order_sub.order_id AND o.status='PAID')");
            System.out.println("  已支付父单的子订单发货影响行数（应为 1）= " + paid);
            int wrongUser = s.executeUpdate("UPDATE order_sub SET fulfillment_status='RECEIVED',"
                    + " received_at=NOW() WHERE id='sub-1-S1' AND fulfillment_status='SHIPPED'"
                    + " AND EXISTS (SELECT 1 FROM mall_order o"
                    + "              WHERE o.id = order_sub.order_id AND o.username='别人')");
            System.out.println("  非本人确认收货影响行数（应为 0）= " + wrongUser);
            int rightUser = s.executeUpdate("UPDATE order_sub SET fulfillment_status='RECEIVED',"
                    + " received_at=NOW() WHERE id='sub-1-S1' AND fulfillment_status='SHIPPED'"
                    + " AND EXISTS (SELECT 1 FROM mall_order o"
                    + "              WHERE o.id = order_sub.order_id AND o.username='checker')");
            System.out.println("  本人确认收货影响行数（应为 1）= " + rightUser);

            // ——————————————————— 退款挂子订单 ———————————————————
            System.out.println();
            System.out.println("【退款】");
            s.execute("INSERT INTO refund(id, order_id, order_sub_id, username, amount, reason_code,"
                    + " route, requested_by) VALUES ('sub-r1','sub-1','sub-1-S2','checker',53.35,"
                    + " 'NO_REASON','MERCHANT_APPROVAL','checker')");
            q(s, "2 号店的待审批队列条数（应为 1）",
                    "SELECT COUNT(*) FROM refund r WHERE r.status='PENDING_APPROVAL'"
                            + " AND EXISTS (SELECT 1 FROM order_sub s"
                            + "              WHERE s.id = r.order_sub_id AND s.merchant_id = 2)");
            q(s, "3 号店的待审批队列条数（应为 0）",
                    "SELECT COUNT(*) FROM refund r WHERE r.status='PENDING_APPROVAL'"
                            + " AND EXISTS (SELECT 1 FROM order_sub s"
                            + "              WHERE s.id = r.order_sub_id AND s.merchant_id = 3)");
            long t0 = System.nanoTime();
            rs = s.executeQuery("SELECT id FROM refund r WHERE r.status='PENDING_APPROVAL'"
                    + " AND EXISTS (SELECT 1 FROM order_sub s"
                    + "              WHERE s.id = r.order_sub_id AND s.merchant_id = 2)"
                    + " ORDER BY r.created_at, r.id LIMIT 50");
            int n = 0;
            while (rs.next()) n++;
            System.out.printf("  队列查询：%d 条，耗时 %.3fms%n", n, (System.nanoTime() - t0) / 1e6);
            rs = s.executeQuery("EXPLAIN SELECT id FROM refund r WHERE r.status='PENDING_APPROVAL'"
                    + " AND EXISTS (SELECT 1 FROM order_sub s"
                    + "              WHERE s.id = r.order_sub_id AND s.merchant_id = 2)");
            while (rs.next())
                System.out.printf("      table=%-9s type=%-6s key=%-22s rows=%-6s extra=%s%n",
                        rs.getString("table"), rs.getString("type"), rs.getString("key"),
                        rs.getString("rows"), rs.getString("Extra"));

            try {
                s.execute("INSERT INTO refund(id, order_id, order_sub_id, username, amount, reason_code,"
                        + " route, requested_by) VALUES ('sub-r2','sub-1','sub-1-S2','checker',1.00,"
                        + " 'NO_REASON','MERCHANT_APPROVAL','checker')");
                System.out.println("  ★ 同一子订单第二笔在途退款：没被挡住");
            } catch (SQLException e) {
                System.out.println("  同一子订单第二笔在途退款被挡住 = " + e.getSQLState()
                        + "（" + e.getMessage().split("\n")[0] + "）");
            }
            try {
                s.execute("INSERT INTO refund(id, order_id, username, amount, reason_code,"
                        + " route, requested_by) VALUES ('sub-r3','sub-1','checker',1.00,"
                        + " 'NO_REASON','MERCHANT_APPROVAL','checker')");
                System.out.println("  ★ 不带 order_sub_id 也能插入（NOT NULL 没生效）");
            } catch (SQLException e) {
                System.out.println("  不带 order_sub_id 的插入被拒 = " + e.getSQLState());
            }
        }
    }

    private static void q(Statement s, String label, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            System.out.printf("  %-38s %s%n", label, rs.getString(1));
        }
    }
}
