package dev.learning.mall.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty(message = "购物车不能为空") @Size(max = 20, message = "一次最多购买 20 种商品")
        List<@NotNull @Valid Line> items) {
    public record Line(@Positive(message = "商品编号必须为正数") long productId,
                       @Min(value = 1, message = "数量至少为 1") @Max(value = 99, message = "每种商品最多购买 99 件") int quantity) {}
}
