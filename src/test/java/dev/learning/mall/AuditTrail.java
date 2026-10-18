package dev.learning.mall;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 助手审计流水（{@code assistant_audit}）的清理与自查（测试专用）。
 *
 * <p>为什么需要它（实测结论）：{@code TokenStreamingTest} 与 {@code VisionSearchTest}
 * 的 {@code @AfterEach} 清了 {@code assistant_run / assistant_message /
 * assistant_conversation}，唯独<b>没清</b> {@code assistant_audit}，各留下 1 行。
 * 眼下没有用例按全表计数断言这张表，所以它不红，只会等将来某条
 * 「审计里一共有几条」的断言踩上去。
 *
 * <h3>为什么按会话号收窄，而不是按用户名</h3>
 *
 * <p>这两个类的用户名常量 {@code ALICE} 的值是 <b>{@code "student"}</b>——
 * 就是种子用户，本项目里一大半的测试都在用它。按用户名收窄等于把别的类写的
 * 审计行也算进来，于是这条断言成不成立取决于之前跑过哪些类：
 * 单跑绿、全量红。这与 {@link DeliveryTrail#residueFor} 里那个「按订单号收窄」
 * 是同一个推理，只是这里能用的键是会话号。
 *
 * <p><b>已知边界</b>：{@code assistant_audit.conversation_id} 可空（V4 DDL 里没有
 * NOT NULL）。会话外的审计行（例如 RATE 阶段的限流记录）不在本工具的收窄范围内，
 * 既不会被 {@link #purge} 删掉，也不会被 {@link #residueFor} 数到。
 * 这两个类实测留下的都是有会话号的行，所以够用；
 * 将来若有会话外的审计残留，需要另找键（{@code id} 是 UUID 唯一键。
 */
final class AuditTrail {

    private AuditTrail() {
    }

    /** 删掉这些会话产生的审计行。 */
    static void purge(JdbcClient jdbc, Collection<String> conversationIds) {
        if (conversationIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM assistant_audit WHERE conversation_id IN (:ids)")
                .param("ids", List.copyOf(conversationIds))
                .update();
    }

    /**
     * 这些会话在审计表里还剩几行。
     *
     * <p>{@code @AfterAll} 里断言它为 0，就把「清理被删掉」变成一条会红的断言。
     */
    static int residueFor(JdbcClient jdbc, Collection<String> conversationIds) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE conversation_id IN (:ids)")
                .param("ids", List.copyOf(conversationIds))
                .query(Integer.class)
                .single();
    }
}
