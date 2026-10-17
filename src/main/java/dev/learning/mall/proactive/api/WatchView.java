package dev.learning.mall.proactive.api;

import java.math.BigDecimal;

/**
 * 用户关注的一件商品。
 *
 * <p>{@code baselinePrice} 是「关注时的价格」而不是「当前价格」——它是提醒的判据：
 * 现价低于它即视为降价。把它返回给前端，是为了让用户能一眼看出「从多少降到多少」。
 */
public record WatchView(long productId, String productName, String illustration, String category,
                        BigDecimal price, BigDecimal baselinePrice, int stock, boolean inStock) {}
