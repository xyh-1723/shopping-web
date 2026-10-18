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

    /**
     * 追加一条幂等流水：<b>同一笔订单在同一个理由下对同一件商品只允许一行</b>。
     *
     * <p>它是退款归还库存的最后一道闸门（退款方案 §5.2 闸门 ③）。前三道以外的情形——
     * 应急脚本、复制错的实现、被绕过的服务——走到这里都会被去重挡住：
     * 第一次 {@code update()} 返回 1，之后的重复调用返回 0，<b>不报错</b>。
     *
     * <p>为什么是 {@code INSERT … SELECT … WHERE NOT EXISTS} 而不是「先查再插」：
     * 查与插是两步，中间夹着一次持有行锁的窗口判断就已经有了竞态；一条语句把这个窗口消掉。
     *
     * @return 实际插入的行数（1 = 发生了，0 = 已经存在，什么都没做）
     */
    int appendOnce(long productId, int delta, int stockBefore, int stockAfter,
                   StockReason reason, String actor, String orderId) {
        return jdbc.sql("INSERT INTO stock_ledger"
                        + "(product_id, delta, stock_before, stock_after, reason, actor, order_id)"
                        + " SELECT p.id, :delta, :before, :after, :reason, :actor, :orderId"
                        + "   FROM product p"
                        + "  WHERE p.id = :productId"
                        + "    AND NOT EXISTS (SELECT 1 FROM stock_ledger l"
                        + "                     WHERE l.order_id = :orderId"
                        + "                       AND l.reason = :reason"
                        + "                       AND l.product_id = :productId)")
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
