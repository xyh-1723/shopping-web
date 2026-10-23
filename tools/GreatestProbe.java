import java.sql.*;
import java.util.*;
public class GreatestProbe {
    public static void main(String[] a) throws Exception {
        List<String[]> ts = List.of(
            new String[]{"H2 MODE=MySQL","jdbc:h2:mem:g;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""},
            new String[]{"MySQL 8.0.31 ","jdbc:mysql://127.0.0.1:3306/mall?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","123456"});
        for (String[] t : ts) {
            try (Connection c = DriverManager.getConnection(t[1],t[2],t[3]); Statement s = c.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT GREATEST(0, POSITION(SUBSTRING('zzz' FROM 1 FOR 1) IN '0123456789abcdef') - 1),"
                        + " GREATEST(0, POSITION(SUBSTRING('a1' FROM 1 FOR 1) IN '0123456789abcdef') - 1)");
                rs.next();
                System.out.println(t[0] + " => 非十六进制=" + rs.getInt(1) + "  a=" + rs.getInt(2));
            } catch (SQLException e) { System.out.println(t[0] + " FAIL " + e.getMessage().split("\n")[0]); }
        }
    }
}
