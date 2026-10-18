package dev.learning.mall.payment.api;

import java.math.BigDecimal;

/**
 * 放款（退款）请求。与 {@link PayRequest} 严格对称，只多一个 {@code originalReceipt}。
 *
 * <p><b>为什么不是给 {@link PayRequest} 加一个可空字段复用？</b>
 * 复用就得让 {@code originalReceipt} 可空，而「写成 null ＝ 没有原凭证」这种暧昧，
 * 正是 record 要消灭的东西：收款本来就没有「原凭证」这个概念。
 *
 * <p><b>字段顺序按「业务相关性」而不是「调用方便」排</b>：
 * {@code orderId}（退哪笔）→ {@code username}（退给谁）→ {@code originalReceipt}（退的是哪次收款）
 * → {@code amount}（退多少）。用 record 之后顺序不再影响正确性，它只影响读代码的人的顺序感。
 *
 * @param orderId         被退款的订单号，同时是钱包 {@code REFUND} 流水的 {@code order_id}
 * @param username        <b>钱退到谁的钱包里</b>：必须与当初扣款的是同一个账户
 * @param originalReceipt {@link PaymentGateway#pay} 当初返回的支付凭证，供放款凭证追溯原笔收款；
 *                        可为 {@code null}——凭证缺失不影响放款本身，只影响事后反查
 * @param amount          退款金额（元，两位小数）
 */
public record RefundRequest(String orderId, String username, String originalReceipt,
                            BigDecimal amount) {

    /**
     * {@code originalReceipt} 刻意<b>不</b>做非空校验：
     * 它是「追溯信息」而不是「放款前提」。把它设成必填，等于给还没接上的历史数据
     * 再挖一个坑——放款没发生才是真正的损失。
     */
    public RefundRequest {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("订单号不能为空");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("收款账户不能为空：钱包必须知道这笔钱退给谁");
        }
        if (amount == null) {
            throw new IllegalArgumentException("退款金额不能为空");
        }
    }
}
