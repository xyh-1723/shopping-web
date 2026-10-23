import java.sql.*;
import java.util.*;

public class ModeCaseProbe {
    public static void main(String[] args) throws Exception {
        List<String[]> urls = List.of(
                new String[]{"MODE=MySQL + TO_LOWER", "jdbc:h2:mem:a1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"},
                new String[]{"MODE=MySQL only      ", "jdbc:h2:mem:a2;MODE=MySQL;DB_CLOSE_DELAY=-1"});
        for (String[] u : urls) {
            System.out.println("\n==== " + u[0] + " ====");
            try (Connection c = DriverManager.getConnection(u[1], "sa", "")) {
                try (Statement s = c.createStatement()) {
                    s.execute("DROP TABLE IF EXISTS p_t");
                    s.execute("CREATE TABLE p_t (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id VARCHAR(36), reason VARCHAR(20), content VARCHAR(50) NOT NULL)");
                    s.execute("CREATE UNIQUE INDEX uq_p_t_order_reason ON p_t(order_id, reason)");
                    s.execute("ALTER TABLE p_t ADD CONSTRAINT ck_p_t_reason CHECK (reason IS NULL OR reason IN ('PAYMENT','REFUND'))");
                    s.execute("ALTER TABLE p_t MODIFY COLUMN content VARCHAR(500) NOT NULL");
                    System.out.println("  OK   MODIFY COLUMN");
                } catch (SQLException e) {
                    System.out.println("  FAIL DDL :: " + e.getMessage().split("\n")[0]);
                    continue;
                }
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO p_t(order_id, reason, content) VALUES ('o1','PAYMENT','x')");
                    s.execute("INSERT INTO p_t(order_id, reason, content) VALUES ('o1','PAYMENT','x')");
                    System.out.println("  !!   唯一索引没拦住");
                } catch (SQLException e) {
                    String m = e.getMessage().split("\n")[0];
                    System.out.println("  MSG  " + (m.contains("UQ_P_T_ORDER_REASON") ? "UPPERCASE ✓" : m.contains("uq_p_t_order_reason") ? "lowercase ✗" : "??? " + m));
                }
                // information_schema 里约束名的大小写（V14MigrationTest 直接断言这些名字）
                try (Statement s = c.createStatement()) {
                    ResultSet rs = s.executeQuery("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = 'p_t'");
                    List<String> names = new ArrayList<>();
                    while (rs.next()) names.add(rs.getString(1));
                    System.out.println("  CATALOG names = " + names);
                } catch (SQLException e) {
                    System.out.println("  FAIL catalog :: " + e.getMessage().split("\n")[0]);
                }
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO p_t(order_id, reason, content) VALUES ('o9','BOGUS','x')");
                    System.out.println("  !!   CHECK 没拦住");
                } catch (SQLException e) {
                    String m = e.getMessage().split("\n")[0];
                    System.out.println("  MSG  CHECK " + (m.contains("CK_P_T_REASON") ? "UPPERCASE ✓" : m.contains("ck_p_t_reason") ? "lowercase ✗" : "??? " + m));
                }
            }
        }
    }
}
