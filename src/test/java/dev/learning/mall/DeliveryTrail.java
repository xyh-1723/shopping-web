package dev.learning.mall;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 投递留痕（{@code notification_delivery}）的清理与自查（测试专用）。
 *
 * <p>为什么需要它：T15 之前 {@code NotificationSink} 只写 {@code notification}，所以凡是
 * 「断言站内信条数」的测试类，{@code @BeforeEach/@AfterEach} 里清的都是 {@code notification}。
 * T15 给留痕表接上 writer 之后，<b>每一次事件消费都会多写一行留痕</b>，
 * 而那些清理语句里没有这张表。眼下没有任何用例按全表计数断言留痕，
 * 所以漏掉的清理<b>不会让任何一条测试变红</b>——它只会以「上一个测试类留下的残迹」的形式，
 * 等着将来某条「留痕总共几条」的断言踩上去，症状是数字对不上、单跑绿全量红。
 * 这与 T15 里修掉的 {@code content} 长度不对称是同一类隐患：
 * <b>不红不报错，只在将来某个时刻变成排查成本很高的怪症状</b>。
 *
 * <p>为什么放在测试侧而不是生产代码：只有测试在问「跑完了还干不干净」，
 * 放进 {@code messaging.internal} 就是「没有消费者的端口」——
 * 与 {@link WalletProbe} 同一归宿（尺子归测试所有）。
 *
 * <h3>{@link #residueFor} 为什么要按订单号收窄</h3>
 *
 * <p>最直白的哨兵是「跑完本类后整张表为空」，但它会引入<b>执行顺序依赖</b>：
 * 这条断言成不成立取决于在它之前跑过哪些测试类，于是单跑绿、全量红——
 * 恰恰是本文件要消灭的那种脆弱性。按「本类自己建过的订单号」收窄之后，
 * 别的测试类留不留残迹都与它无关：这些订单号是本类随机生成的 UUID，别的类无从得知。
 */
final class DeliveryTrail {

    private DeliveryTrail() {
    }

    /**
     * 全表清理：不留残迹给下一个测试类。
     *
     * <p>刻意不按订单号收窄：V17 的 {@code notification_delivery.order_id} 明确不加外键，
     * 因此留痕行可以指向一个已经被删掉的订单（订单在别的 {@code @BeforeEach} 里被清了，
     * 留痕行还在）。按订单号删会漏掉这一类行，而这里要的是「整张表干净」。
     */
    static void purge(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM notification_delivery").update();
    }

    /**
     * 「本类建过的订单」在留痕表里还剩几行。
     *
     * <p>在 {@code @AfterAll} 里断言它为 0，就把「清理被删掉」变成一条会红的断言：
     * 删掉 {@link #purge} 那一行（乃至删掉整个 {@code @AfterEach}），这个数字立刻大于 0。
     * 没有这条断言的话，删掉清理全量测试照样全绿——清理是<b>无法被变异验证</b>的。
     */
    static int residueFor(JdbcClient jdbc, Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT COUNT(*) FROM notification_delivery WHERE order_id IN (:ids)")
                .param("ids", List.copyOf(orderIds))
                .query(Integer.class)
                .single();
    }
}
