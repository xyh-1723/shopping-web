package dev.learning.mall;

/**
 * 测试里<b>自建</b> H2 连接时的 URL 工厂。
 *
 * <p>存在的理由只有一条：迁移脚本是按 MySQL 方言写的（生产库是 MySQL 8），
 * 而 {@code MODIFY COLUMN / CHANGE COLUMN} 这类 ALTER 在 H2 默认模式下是语法错误，
 * 只有 {@code MODE=MySQL} 才接受。曾经有 7 个测试各自手拼 {@code jdbc:h2:mem:xxx}，
 * 没带这个模式，于是迁移回放一跑就崩在 V13——同一个坑踩 7 次。
 * 统一从这里取 URL，别再手拼。
 *
 * <p>刻意<b>不加</b> {@code DATABASE_TO_LOWER=TRUE}：加了以后 H2 把标识符存成小写，
 * 于是「唯一索引冲突」的报错里索引名也变小写（{@code uq_xxx}），而一批测试断言的是
 * 生产库里那种大写形式（{@code UQ_WALLET_LEDGER_ORDER_REASON}）——一改全红。
 * 不加它，H2 保持默认的大写标识符，这批断言原样成立。
 *
 * <p>与 {@code src/test/resources/application-test.yml} 里的 datasource.url 必须保持一致：
 * 那里是 Spring 自己建的连接，这里是测试手动建的，两边跑的是同一批迁移脚本。
 */
public final class TestH2 {

    private TestH2() {
    }

    /** 进程内命名库：迁移回放用。名字自带随机后缀，避免并发用例抢同一个库。 */
    public static String inMemory(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    /** 文件库：跨「进程重启」验证持久化用。 */
    public static String file(String path) {
        return "jdbc:h2:file:" + path + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
    }
}
