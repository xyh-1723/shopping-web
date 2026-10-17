package dev.learning.mall.catalog.api;

/**
 * 库存变动的原因。
 *
 * <p>取值与 {@code stock_ledger.reason} 的 CHECK 约束（V14 的 {@code ck_ledger_reason}）
 * 一一对应：数据库是最后一道防线，枚举是编译期防线，两边都不允许出现第四种原因。
 *
 * <p>为什么原因只有三类：因为全项目改 {@code product.stock} 的地方只有三个——
 * 下单预占、关单归还、店员补货。枚举的取值集合就是「谁有资格改库存」这份清单，
 * 新增一条改库存的路径时，必须先在这里说清楚它属于哪一类。
 */
public enum StockReason {

    /** 下单预占：库存被这笔订单锁住，等待支付。 */
    RESERVE,

    /** 关单归还：订单取消或超时未支付，预占的库存退回现货。 */
    RELEASE,

    /** 补货：店员把库存直接改成一个确定的值（上架 / 盘点 / 纠错）。 */
    RESTOCK
}
