package dev.learning.mall.catalog.internal;

import dev.learning.mall.catalog.api.StockReason;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 库存变动流水（{@code stock_ledger}）的唯一写入口。
 *
 * <p>包可见是刻意的：流水与 {@code product.stock} 的写入必须成对发生，
 * 而这对操作只有 {@link ProductService} 知道怎么排（先加行锁取前值、再改现货、最后留痕）。
 * 把 Repository 暴露成公共 API，等于邀请调用方「只改库存不记账」——
 * 那正是流水存在的意义所反对的事。
 *
 * <p>这里<b>故意不提供 update / delete</b>：流水是审计留痕，错了就补一条反向记录，
 * 不允许改写历史。
 */
@Repository
class StockLedgerRepository {

    private final JdbcClient jdbc;

    StockLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条流水。
     *
     * <p>四个数字（前值、变化量、后值）全部由调用方传入，本方法不做任何推算：
     * {@code stock_after = stock_before + delta} 由数据库的 {@code ck_ledger_arithmetic}
     * 兜底，一旦调用方算错，报错的是数据库而不是一句日志。
     *
     * @param productId 商品编号
     * @param delta     变化量（正为增加，负为减少；不允许 0，无变化就不要记）
     * @param stockBefore 变动前的现货
     * @param stockAfter  变动后的现货
     * @param reason    变动原因
     * @param actor     谁动的：下单是用户名，补货是店员账号，系统建账是迁移脚本
     * @param orderId   关联订单号；补货等非订单变动为 {@code null}
     */
    void append(long productId, int delta, int stockBefore, int stockAfter,
                StockReason reason, String actor, String orderId) {
        jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor, order_id)"
                        + " VALUES (:productId, :delta, :before, :after, :reason, :actor, :orderId)")
                .param("productId", productId)
                .param("delta", delta)
                .param("before", stockBefore)
                .param("after", stockAfter)
                .param("reason", reason.name())
                .param("actor", actor)
                .param("orderId", orderId)
                .update();
    }
}
