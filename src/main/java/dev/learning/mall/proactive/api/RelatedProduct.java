package dev.learning.mall.proactive.api;

import java.math.BigDecimal;

/**
 * 一条关联推荐。
 *
 * <p>{@code reason} 是这类推荐里最容易被砍掉、却最不该砍掉的一个字段：
 * 「推荐」和「为什么推荐你」是一体的。没有理由的推荐看起来像广告，
 * 有理由的推荐看起来像帮忙——这是导购和货架的区别。
 */
public record RelatedProduct(long id, String name, String illustration, String category,
                             BigDecimal price, int stock, String reason) {}
