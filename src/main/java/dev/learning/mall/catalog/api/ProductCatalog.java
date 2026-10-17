package dev.learning.mall.catalog.api;

import java.math.BigDecimal;
import java.util.List;

/** 商品模块的公开边界：订单只依赖此接口，不访问商品 Repository。 */
public interface ProductCatalog {
    List<ProductView> list();
    ProductView get(long id);
    void reserve(long productId, int quantity);
    void release(long productId, int quantity);

    /**
     * 改价（仅店员可用，权限在 Web 层与 SecurityConfig 双重限制）。
     *
     * <p>放在商品模块而不是运营后台，是因为价格<b>属于商品</b>：改价要改的是商品表的那一列，
     * 谁都可以改的话，价格就会变成「谁都能写、没人负责」的字段。
     */
    ProductView updatePrice(long productId, BigDecimal price);

    /** 改库存（上架 / 补货）。同样仅店员可用。 */
    ProductView updateStock(long productId, int stock);
}
