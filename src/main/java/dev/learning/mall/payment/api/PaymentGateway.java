package dev.learning.mall.payment.api;

/**
 * 支付通道策略接口。
 *
 * <p><b>边界</b>：这里只有站内模拟通道，不连银行、不做清算。
 * 但「模拟」指的是<b>不接真实资金网络</b>，不是「不真的动账」——
 * {@code DEMO_WALLET} 通道会真的从买家钱包里把钱扣掉、真的把退款打回去，
 * 否则「退款把钱退给买家」这句话从头到尾都没有被验证过。
 *
 * <p><b>两个方法的分工</b>：
 * <ul>
 *   <li>{@link #pay} 收款——钱从买家钱包流出；</li>
 *   <li>{@link #refund} 放款——钱流回买家钱包。</li>
 * </ul>
 * 二者是同一 contract 的两半：一个能收款却不能放款的通道，
 * 等于宣称支持退款却从未兑现过。
 *
 * <p><b>谁决定「要不要放款」</b>：不是本接口。
 * 一笔订单是否被放款、放款几次，由订单/退款侧的状态机决定；
 * gateway 只负责「让它真的发生一次」。
 *
 * <p><b>依赖方向</b>：{@code payment → wallet → pricing}，单向无环。
 */
public interface PaymentGateway {

    /** 支付方式代号，例如 {@code DEMO_CARD} / {@code DEMO_WALLET}；同时是 {@link PaymentRouter} 的 key。 */
    String method();

    /**
     * 收款。
     *
     * @param request 收款请求（订单号、付款账户、金额）
     * @return 支付凭证，落进 {@code mall_order.payment_receipt}
     * @throws dev.learning.mall.common.BusinessException
     *         {@code INSUFFICIENT_FUNDS}（409）钱包余额不足等明确业务拒绝
     */
    String pay(PayRequest request);

    /**
     * 放款（退款）。与 {@link #pay} 严格对称：钱真的动一下，方向相反。
     *
     * <p>幂等由钱包流水上 {@code (order_id, reason)} 的唯一索引兜底：
     * 同一笔订单重复放款会被数据库拒绝，而不是记两笔入账。
     *
     * @param request 放款请求：订单号、收款账户、原支付凭证、金额，
     *                以及<b>放款责任人</b>（无理由退款是 {@code SYSTEM}，商家批准是那位商家的账号）
     * @return 放款凭证
     * @throws dev.learning.mall.common.BusinessException
     *         {@code REFUND_UNSUPPORTED}（400）该通道形不成退款（钱压根没进来过）
     */
    String refund(RefundRequest request);
}
