package dev.learning.mall.catalog.api;

import java.math.BigDecimal;
import java.util.List;

/** 商品模块的公开边界：订单只依赖此接口，不访问商品 Repository。 */
public interface ProductCatalog {
    List<ProductView> list();
    ProductView get(long id);

    /**
     * 预占库存（下单时）。
     *
     * @param actor   谁动的（下单者用户名），进流水 {@code actor} 列
     * @param orderId 关联订单号，进流水 {@code order_id} 列；非订单场景传 {@code null}
     */
    void reserve(long productId, int quantity, String actor, String orderId);

    /**
     * 归还库存（取消 / 超时关单时）。参数含义同 {@link #reserve}。
     */
    void release(long productId, int quantity, String actor, String orderId);

    /**
     * 归还库存（退款成功后）。参数含义同 {@link #reserve}。
     *
     * <p>与 {@link #release} 的差异只有一个：<b>流水记成 {@code REFUND} 而不是 {@code RELEASE}</b>。
     * 为什么要一个独立的方法而不是让 {@link #release} 多收一个 {@code reason} 参数：
     * 交给调用方传，就等于允许退款路径传 {@code 'RELEASE'} 把售后退货伪装成弃单释放，
     * 于是「这笔订单还过货没有」这条幂等判据失效（退款方案 §5）。
     * 固定 reason 之后，两类归还仍然收敛在同一个写节点 {@code ProductService} 上。
     *
     * @param actor   谁退的这笔钱（退 AUTO 时是 {@code SYSTEM}，商家批准时是商家账号）
     */
    void returnStock(long productId, int quantity, String actor, String orderId);

    /**
     * 改价（仅店员可用，权限在 Web 层与 SecurityConfig 双重限制）。
     *
     * <p>放在商品模块而不是运营后台，是因为价格<b>属于商品</b>：改价要改的是商品表的那一列，
     * 谁都可以改的话，价格就会变成「谁都能写、没人负责」的字段。
     */
    ProductView updatePrice(long productId, BigDecimal price);

    /**
     * 改库存（上架 / 补货）。同样仅店员可用。
     *
     * @param actor 操作的店员账号，进流水 {@code actor} 列（补货没有关联订单，{@code order_id} 为 null）
     */
    ProductView updateStock(long productId, int stock, String actor);
}
