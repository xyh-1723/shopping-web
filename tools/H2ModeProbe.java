import java.sql.*;
import java.util.*;

public class H2ModeProbe {

    public static void main(String[] args) throws Exception {
        List<String[]> urls = List.of(
                new String[]{"H2 MySQL MODE", "jdbc:h2:mem:probem;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"},
                new String[]{"H2 default   ", "jdbc:h2:mem:probed;DB_CLOSE_DELAY=-1"});

        for (String[] u : urls) {
            System.out.println("\n============ " + u[0] + " :: " + u[1] + " ============");
            try (Connection c = DriverManager.getConnection(u[1], "sa", "")) {
                drop(c, "p_t");
                drop(c, "p_refund");

                ddl(c, "AUTO_INCREMENT  ", "CREATE TABLE p_t (id BIGINT AUTO_INCREMENT PRIMARY KEY, ts TIMESTAMP, content VARCHAR(50) NOT NULL, order_id VARCHAR(36))");
                q(c, "ts+INTERVAL 15   ", "SELECT ts + INTERVAL 15 MINUTE FROM p_t");
                q(c, "DATE_ADD         ", "SELECT DATE_ADD(ts, INTERVAL 15 MINUTE) FROM p_t");
                q(c, "TIMESTAMPADD     ", "SELECT TIMESTAMPADD(MINUTE, 15, ts) FROM p_t");
                ddl(c, "MODIFY COLUMN    ", "ALTER TABLE p_t MODIFY COLUMN content VARCHAR(500) NOT NULL");
                ddl(c, "MODIFY NULL      ", "ALTER TABLE p_t MODIFY COLUMN order_id VARCHAR(36) NULL");
                ddl(c, "CHANGE COLUMN    ", "ALTER TABLE p_t CHANGE COLUMN content content VARCHAR(600) NOT NULL");
                q(c, "FOR UPDATE       ", "SELECT id FROM p_t WHERE id = 1 FOR UPDATE");
                q(c, "SKIP LOCKED      ", "SELECT id FROM p_t WHERE id = 1 FOR UPDATE SKIP LOCKED");
                q(c, "LIMIT            ", "SELECT id FROM p_t LIMIT 10");

                // V17 style: generated column + unique index
                ddl(c, "generated col    ", "CREATE TABLE p_refund (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id VARCHAR(36), status VARCHAR(20), in_flight VARCHAR(36) GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING_APPROVAL','APPROVED') THEN order_id END))");
                ddl(c, "unique index     ", "CREATE UNIQUE INDEX uq_p_refund_inflight ON p_refund (in_flight)");
                ddl(c, "idx dup guard    ", "INSERT INTO p_refund (order_id, status) VALUES ('o1','PENDING_APPROVAL')");
                ddl(c, "idx dup 2nd      ", "INSERT INTO p_refund (order_id, status) VALUES ('o1','PENDING_APPROVAL')");
                ddl(c, "idx allow done   ", "INSERT INTO p_refund (order_id, status) VALUES ('o2','REFUNDED')");
                ddl(c, "idx allow done2  ", "INSERT INTO p_refund (order_id, status) VALUES ('o2','REFUNDED')");

                drop(c, "p_t");
                drop(c, "p_refund");
            } catch (SQLException e) {
                System.out.println("  CONNECT FAIL " + e.getMessage().split("\n")[0]);
            }
        }
    }

    static void drop(Connection c, String t) {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS " + t);
        } catch (SQLException ignored) {
        }
    }

    static void ddl(Connection c, String label, String sql) {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
            System.out.println("  OK   " + label);
        } catch (SQLException e) {
            System.out.println("  FAIL " + label + " :: " + firstLine(e));
        }
    }

    static void q(Connection c, String label, String sql) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            String v = rs.next() ? String.valueOf(rs.getObject(1)) : "(no rows)";
            System.out.println("  OK   " + label + " => " + v);
        } catch (SQLException e) {
            System.out.println("  FAIL " + label + " :: " + firstLine(e));
        }
    }

    static String firstLine(SQLException e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.split("\n")[0].replace("\r", "");
    }
}
