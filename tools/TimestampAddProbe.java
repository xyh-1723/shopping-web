import java.sql.*;
import java.util.*;

public class TimestampAddProbe {
    public static void main(String[] args) throws Exception {
        List<String[]> ts = List.of(
                new String[]{"H2 MySQL MODE", "jdbc:h2:mem:p1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""},
                new String[]{"MySQL        ", "jdbc:mysql://127.0.0.1:3306/mall?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", "123456"});
        for (String[] t : ts) {
            System.out.println("\n==== " + t[0] + " ====");
            try (Connection c = DriverManager.getConnection(t[1], t[2], t[3])) {
                q(c, "TIMESTAMPADD(DAY,3,NOW)   ", "SELECT TIMESTAMPADD(DAY, 3, CURRENT_TIMESTAMP) FROM (SELECT 1) z");
                q(c, "TIMESTAMPADD(HOUR,1,NOW)  ", "SELECT TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP) FROM (SELECT 1) z");
                q(c, "TIMESTAMPADD(MINUTE,15,NOW)", "SELECT TIMESTAMPADD(MINUTE, 15, CURRENT_TIMESTAMP) FROM (SELECT 1) z");
                q(c, "INTERVAL '15' MINUTE      ", "SELECT CURRENT_TIMESTAMP + INTERVAL '15' MINUTE FROM (SELECT 1) z");
            } catch (SQLException e) {
                System.out.println("  CONNECT FAIL " + e.getMessage().split("\n")[0]);
            }
        }
    }

    static void q(Connection c, String label, String sql) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            System.out.println("  OK   " + label + " => " + (rs.next() ? rs.getObject(1) : "?"));
        } catch (SQLException e) {
            System.out.println("  FAIL " + label + " :: " + e.getMessage().split("\n")[0].replace("\r", ""));
        }
    }
}
