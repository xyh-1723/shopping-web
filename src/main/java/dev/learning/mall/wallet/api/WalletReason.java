package dev.learning.mall.wallet.api;

/**
 * 资金变动的原因。
 *
 * <p>取值与 {@code wallet_ledger.reason} 的 CHECK 约束（V16 的 {@code ck_wallet_ledger_reason}）
 * 一一对应：数据库是最后一道防线，枚举是编译期防线，两边都不允许出现第五种原因。
 *
 * <p><b>方向是原因的一部分</b>：{@link #PAYMENT} 一定是出账（负 delta），
 * 另外三个一定是入账（正 delta）。数据库侧另有
 * {@code ck_wallet_ledger_payment_is_outflow} / {@code ck_wallet_ledger_refund_is_inflow}
 * 两条方向约束兜底——因为 {@code ck_wallet_ledger_arithmetic} 是一条无方向的恒等式，
 * 符号写反它照样成立。
 *
 * <p>为什么原因只有四类：因为全项目动 {@code wallet.balance_cents} 的地方只有四个——
 * 开户授予、演示充值、下单支付、退款放款。新增一条动钱的入口时，
 * 必须先在这里说清楚它属于哪一类。
 */
public enum WalletReason {

    /** 开户授予：首次触达钱包时按配置一次性给的演示额度。 */
    OPENING,

    /** 演示充值：受限的演示性授予（固定档位 + 累计上限），不是真实充值。 */
    TOPUP,

    /** 下单支付：从钱包扣掉的钱，必然是出账。 */
    PAYMENT,

    /** 退款放款：退回买家的钱，必然是入账。 */
    REFUND
}
