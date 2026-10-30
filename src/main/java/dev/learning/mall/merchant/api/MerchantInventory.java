package dev.learning.mall.merchant.api;

import dev.learning.mall.catalog.api.MerchantStockSummary;
import dev.learning.mall.catalog.api.ProductPage;

/**
 * 商家中心一屏所需的全部数据：<b>这家店</b>、整店库存概览、当前这一页商品。
 *
 * <p>三块拼成一个对象返回，是为了让页面拿到的是<b>同一次读取</b>的结果：
 * 分成三个接口各拉一次，用户会在概览里看到「25 种商品」，而表格里却是刚被下架后的 24 行。
 *
 * @param merchant 店本身（名字、编号、店主账号）
 * @param summary  整店库存概况
 * @param page     当前页商品（按店收窄后的分页结果）
 */
public record MerchantInventory(MerchantView merchant, MerchantStockSummary summary, ProductPage page) {}
