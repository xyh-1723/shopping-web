package dev.learning.mall.catalog.api;

/**
 * 商品列表的排序方式。
 *
 * <p>这里只描述<b>意图</b>（"按价格从低到高"），SQL 片段由商品模块自己拼——
 * 排序方式是对外契约的一部分，而"它对应哪段 {@code ORDER BY}"是实现细节，
 * 一旦让调用方传 SQL 字符串，任何一次方言调整都会波及所有调用方。
 */
public enum ProductSort {
    /** 上架顺序：稳定、可复现，适合"随便逛逛"。 */
    ID_ASC,
    /** 价格从低到高：适合划了范围之后比价。 */
    PRICE_ASC,
    /** 价格从高到低：适合"预算内挑最贵的"，把预算用足。 */
    PRICE_DESC
}
