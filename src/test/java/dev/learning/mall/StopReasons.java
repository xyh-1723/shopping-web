package dev.learning.mall;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 「取最近一次 run 的 stop_reason」的唯一入口。测试里不要内联另写一份 SQL，
 * 否则谁先被"简化"过就无从查证。
 *
 * <p><b>为什么 {@link #latest} 必须按 conversation_id 过滤（两个坑叠在一起）：</b>
 *
 * <ol>
 *   <li><b>H2 会缓存 {@code CURRENT_TIMESTAMP}</b>：连续 INSERT 的多条 run 会共享同一个
 *       {@code created_at}。实测连续 8 条只产生 1 个不同值（单独跑），
 *       全量套件里负载更杂时是 4 个不同值——无论哪种，都是「8 条里必有并列」。
 *       所以「按时间倒序取第一条」在它们之间根本分不出先后。
 *       （这个比例随机器负载变化，故不作断言，只作说明。）
 *   <li><b>并列时靠 {@code id} 兜底排序，而 {@code id} 是随机 UUID</b>：
 *       {@code assistant_run.id} 是 {@code VARCHAR(36)} 的 UUID，与插入顺序无关，
 *       于是并列时 {@code LIMIT 1} 取到哪一行取决于 UUID 字典序，是随机的。
 * </ol>
 *
 * <p>叠加的结果：只要库里同时存在两条属于<b>不同会话</b>的 run，
 * 不按会话收窄的写法随时会把别人的 {@code stop_reason} 当成这条会话的。
 *
 * <p>{@link StopReasonSessionScopingTest} 守护这一点：一旦把这里的
 * {@code conversation_id} 条件去掉，那个测试会红。
 */
final class StopReasons {

    private StopReasons() {
    }

    /**
     * 取指定会话最近一次 run 的停止原因。<b>不要去掉 conversation_id 条件</b>，理由见类注释。
     *
     * @return 没有该会话的 run 时返回 {@code null}（而不是抛异常）
     */
    static String latest(JdbcClient jdbc, String conversationId) {
        return jdbc.sql("SELECT stop_reason FROM assistant_run WHERE conversation_id = :c"
                        + " ORDER BY created_at DESC, id LIMIT 1")
                .param("c", conversationId).query(String.class).optional().orElse(null);
    }

    /**
     * 「不按会话收窄」的反例，<b>仅供 {@link StopReasonSessionScopingTest} 用来证明
     * {@link #latest} 为什么必须带 conversation_id</b>。业务测试不要调用它——
     * 它取到的是别人的 run。
     */
    static String latestIgnoringConversation(JdbcClient jdbc) {
        return jdbc.sql("SELECT stop_reason FROM assistant_run ORDER BY created_at DESC, id LIMIT 1")
                .query(String.class).optional().orElse(null);
    }
}
