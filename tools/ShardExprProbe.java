import java.sql.*;
import java.util.*;

/** 验证「订单号首字符 → 0..15」的分片表达式在 H2(MySQL 模式) 与 MySQL 上结果一致。 */
public class ShardExprProbe {
    public static void main(String[] args) throws Exception {
        List<String[]> ts = List.of(
                new String[]{"H2 MODE=MySQL", "jdbc:h2:mem:sh;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""},
                new String[]{"MySQL 8.0.31 ", "jdbc:mysql://127.0.0.1:3306/mall?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", "123456"});
        String expr = "POSITION(SUBSTRING(id FROM 1 FOR 1) IN '0123456789abcdef') - 1";
        for (String[] t : ts) {
            System.out.println("\n==== " + t[0] + " ====");
            try (Connection c = DriverManager.getConnection(t[1], t[2], t[3]); Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS shard_probe");
                s.execute("CREATE TABLE shard_probe (id VARCHAR(36) PRIMARY KEY, expiry_shard SMALLINT NOT NULL DEFAULT 0)");
                List<String> ids = List.of("0aaaaaaa-0000-0000-0000-000000000000", "7fffffff", "f0000000", "ab-cdef", "9xyz");
                for (String id : ids) {
                    s.execute("INSERT INTO shard_probe(id) VALUES ('" + id + "')");
                }
                s.execute("UPDATE shard_probe SET expiry_shard = " + expr);
                ResultSet rs = s.executeQuery("SELECT id, expiry_shard, MOD(expiry_shard, 4) AS m4 FROM shard_probe ORDER BY id");
                while (rs.next()) {
                    System.out.printf("  id=%-40s shard=%-3s mod4=%s%n", rs.getString(1), rs.getInt(2), rs.getInt(3));
                }
                // CHECK 约束 + 索引在两边都建得起来吗
                try {
                    s.execute("ALTER TABLE shard_probe ADD CONSTRAINT ck_probe_shard CHECK (expiry_shard BETWEEN 0 AND 15)");
                    s.execute("CREATE INDEX idx_probe_shard ON shard_probe(expiry_shard)");
                    System.out.println("  OK   CHECK + INDEX");
                } catch (SQLException e) {
                    System.out.println("  FAIL CHECK/INDEX :: " + e.getMessage().split("\n")[0]);
                }
                s.execute("DROP TABLE IF EXISTS shard_probe");
            } catch (SQLException e) {
                System.out.println("  FAIL :: " + e.getMessage().split("\n")[0]);
            }
        }
    }
}
