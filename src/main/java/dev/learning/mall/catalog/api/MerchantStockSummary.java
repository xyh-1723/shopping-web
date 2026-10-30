package dev.learning.mall.catalog.api;

/**
 * 一家店的库存概览。
 *
 * <p>为什么三个数字要一起返回、而且用<b>一条 SQL</b> 算出来：它们会被并排显示在
 * 商家中心顶部，用户会把它们当成同一时刻的快照。分三条 SQL 取，中间任何一笔下单都可能
 * 让「种类数 25、库存总量 0、售罄 0」这种自相矛盾的组合出现在屏幕上。
 *
 * @param productCount    这家店的在册商品种类数（含售罄）
 * @param stockTotal      全部商品的库存件数之和
 * @param outOfStockCount {@code stock = 0} 的商品种类数
 */
public record MerchantStockSummary(long productCount, long stockTotal, long outOfStockCount) {}
