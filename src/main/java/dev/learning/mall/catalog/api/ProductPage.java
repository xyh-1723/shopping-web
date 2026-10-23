package dev.learning.mall.catalog.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 一页商品 + 命中总数。
 *
 * <p>{@code total} 必须和 {@code items} 一起返回：只给一页而不给总数，前端就写不出
 * 分页器，用户会以为"这家店只有 24 件商品"。同理，库存告警里的 {@code total} 也要
 * 在裁剪之前交给模型——否则店员看到 20 条就以为补完这 20 件没事了。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductPage(List<ProductView> items, long total, int page, int size) {
    public ProductPage {
        items = List.copyOf(items);
    }
}
