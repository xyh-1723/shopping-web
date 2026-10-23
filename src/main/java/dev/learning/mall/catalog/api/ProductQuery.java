package dev.learning.mall.catalog.api;

import java.math.BigDecimal;

/**
 * 商品查询条件：关键词、分类、价格上限、只看有货、排序、分页。
 *
 * <p><b>所有筛选条件都可选，只有分页是强制的</b>。这不是洁癖：没有 {@code size} 的查询
 * 在目录只有 506 件时无害，扩到 5 万件时就是一次 11MB 的全表传输——
 * 而调用方往往只想要前 12 件（助手工具）或前 24 件（货架首屏）。
 * 把上限写进类型，比指望每个调用方记得加 {@code .limit()} 可靠得多。
 *
 * <p>构造时归一化：空白字符串一律折算成 {@code null}（"不限"），避免
 * "空串"和"null"两种表示在 SQL 分叉处被区别对待。
 */
public record ProductQuery(String keyword, String category, BigDecimal maxPrice,
                           boolean inStockOnly, ProductSort sort, int page, int size) {

    /** 单页上限：再大就不是"翻页"而是"导出"了，导出该走别的接口。 */
    public static final int MAX_SIZE = 100;
    /** 默认页大小：一屏货架能放下的量。 */
    public static final int DEFAULT_SIZE = 24;

    public ProductQuery {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        category = category == null || category.isBlank() ? null : category.strip();
        sort = sort == null ? ProductSort.ID_ASC : sort;
        if (page < 0) throw new IllegalArgumentException("page 不能为负：" + page);
        if (size < 1 || size > MAX_SIZE)
            throw new IllegalArgumentException("size 必须在 1 到 " + MAX_SIZE + " 之间：" + size);
    }

    /** 首页：最常用的构造方式。 */
    public static ProductQuery firstPage(int size) {
        return new ProductQuery(null, null, null, false, ProductSort.ID_ASC, 0, size);
    }

    /** SQL 的 {@code OFFSET}。用 long 是因为 {@code page * size} 理论上会溢出 int。 */
    public long offset() {
        return (long) page * size;
    }
}
