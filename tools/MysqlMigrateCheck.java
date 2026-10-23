import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.*;
import java.util.*;

/** 在真 MySQL 上从空库顺序重放全部迁移，并报告最终表清单。用于验证迁移脚本的 MySQL 方言正确性。 */
public class MysqlMigrateCheck {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/mall_check"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true";
        SimpleDriverDataSource source = new SimpleDriverDataSource(new com.mysql.cj.jdbc.Driver(), url, "root", "123456");

        // 先清库：改过历史迁移脚本后校验和会变，旧库必须重建（Flyway 不允许改已应用的迁移）。
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS mall_check");
            s.execute("CREATE DATABASE mall_check DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            System.out.println("已重建库 mall_check");
        }

        var result = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        System.out.println("迁移成功：" + result.migrationsExecuted + " 个，最终版本 " + result.targetSchemaVersion);
        for (var m : result.migrations) {
            System.out.printf("  %-46s %-9s %sms%n", m.description, m.version, m.executionTime);
        }

        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            ResultSet rs = s.executeQuery("SHOW TABLES");
            while (rs.next()) tables.add(rs.getString(1));
            Collections.sort(tables);
            System.out.println("表 (" + tables.size() + ")：" + String.join(", ", tables));
        }
    }
}
