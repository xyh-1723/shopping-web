package dev.learning.mall.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "购物车不能为空") @Size(max = 20, message = "一次最多购买 20 种商品")
        List<@NotNull @Valid Line> items,
        @Size(max = 36, message = "金额快照编号最长 36 位") String quoteId) {
    /** 兼容无需金额快照的既有调用点。 */
    public CreateOrderRequest(List<Line> items) {
        this(items, null);
    }

    public record Line(@Positive(message = "商品编号必须为正数") long productId,
                       @Min(value = 1, message = "数量至少为 1") @Max(value = 99, message = "每种商品最多购买 99 件") int quantity) {}
}
