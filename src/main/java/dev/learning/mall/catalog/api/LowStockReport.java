package dev.learning.mall.catalog.api;

import java.util.List;

/**
 * 低库存告警：真实总数 + 截断后的明细。
 *
 * <p>两个数都要有的原因见 {@link ProductPage} 的注释——"列出 20 件"和"一共 200 件"
 * 是完全不同的运营含义，只给前者会让人低估问题的规模。
 *
 * @param total 库存低于阈值的商品总数（<b>不受 {@code items} 截断影响</b>）
 * @param items 按库存从少到多排列的前 N 件
 */
public record LowStockReport(long total, List<ProductView> items) {
    public LowStockReport {
        items = List.copyOf(items);
    }
}
