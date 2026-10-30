package dev.learning.mall.order.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款行。
 *
 * <p>与 {@link OrderRow} 同一个形状：它由 {@code JdbcClient} 直接映射
 * （{@code refund} 表的列名是小写下划线，record 组件是驼峰），
 * 再由 {@link RefundRepository} 逐字段翻译成对外的 {@code RefundView}。
 *
 * <p>多了一层而不是直接映射到 {@code RefundView}，是因为两处对同一个字段的形状要求不同：
 * 库里 {@code stock_returned} 是 {@code INT}，而 API 应该是 {@code boolean}。
 * 把这个翻译放在唯一的Mapper 处，比指望 JDBC 的类型猜测更好——后者一旦行为变化，
 * 出错的是「库存到底还有没有还」，而它恰恰是退款最不能含糊的事。
 *
 * <p><b>注意 {@code inFlight} 不在这里</b>：那一列是 {@code GENERATED ALWAYS AS}，
 * H2 不允许手工赋值，代码里也不该出现它（RT migrations 注释里写了这条实测结论）。
 *
 * <h3>{@code orderSubId}：退款挂在<b>子订单</b>上，不是父订单上（V25）</h3>
 *
 * <p>这是阶段 3b 的核心搬动。之前一笔退款只写 {@code order_id}，
 * 于是一笔跨店订单（同时含 A 与 B 的商品）在库里是「一个订单、一笔退款」：
 * A 与 B 都能在队列里看到它，谁先批谁就能把<b>整单</b>金额退掉。
 * 那不是判漏了，而是「退款挂在订单上」这个结构的直接后果——
 * 修法只能是让退款挂到一个<b>只属于一家店</b>的实体上。
 *
 * <p>挂上子订单之后，「A 批准了 B 的钱」这句话第一次<b>无法被表达</b>：
 * 一笔退款在结构上只可能属于一个子订单。
 */
record RefundRow(String id, String orderId, String orderSubId, String username, BigDecimal amount,
                 String reasonCode, String reasonText, String route, String status, String requestedBy,
                 String decidedBy, LocalDateTime decidedAt, String decisionNote, int stockReturned,
                 LocalDateTime createdAt) {

    /** 库存是否已归还。库里是 0/1，业务语义是布尔。 */
    boolean stockReturnedFlag() {
        return stockReturned == 1;
    }
}
