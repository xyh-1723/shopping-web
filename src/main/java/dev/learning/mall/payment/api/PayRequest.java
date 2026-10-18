package dev.learning.mall.payment.api;

import java.math.BigDecimal;

/**
 * 收款请求。
 *
 * <p><b>为什么不是 {@code pay(String orderId, BigDecimal amount)} 再加一个参数？</b>
 * 三个理由，按重要性排序：
 * <ol>
 *   <li><b>钱包必须知道扣谁的钱。</b>金额是「订单的钱」，账户是「买家的钱」，
 *       只有 orderId 的签名里这两件事混在一起，落到真实钱包时才发现缺主体。</li>
 *   <li><b>位置写反的成本是不对称的。</b>{@link RefundRequest} 有四个字段、
 *       其中两个是相邻的 {@code String}（{@code orderId} 与 {@code originalReceipt}），
 *       位置写反<b>编译通过、运行通过，只在对账时才发现</b>。命名 record 字段把这个可能性消掉。</li>
 *   <li>将来再加字段（幂等键、渠道号）不必改动所有调用点。</li>
 * </ol>
 *
 * <p><b>金额单位</b>：一律「元」，两位小数。元→分的换算只能由
 * {@code pricing.api.MoneyUnits} 完成——它超过两位小数会抛异常而不是四舍五入，
 * 支付通道不做任何自己的换算。
 *
 * @param orderId  被支付的订单号，同时也是钱包的 {@code order_id}（同一订单只能扣一次的幂等键）
 * @param username <b>钱从谁的钱包里出</b>；由订单侧在校验过归属之后给出
 * @param amount   应付金额（元，两位小数）
 */
public record PayRequest(String orderId, String username, BigDecimal amount) {

    /**
     * 入参校验刻意放在这里而不是每个实现里：四个字段里有三个是 NOT NULL 的语义，
     * 让每个通道各自判断，最后一定会有一个通道忘了判断。
     */
    public PayRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("付款账户不能为空：钱包必须知道这笔钱记在谁名下");
        }
        if (amount == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
    }
}
