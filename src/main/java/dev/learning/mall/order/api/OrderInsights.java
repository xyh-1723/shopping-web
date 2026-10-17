package dev.learning.mall.order.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单模块对外暴露的「经营视角」只读接口。
 *
 * <p>注意这里刻意分成两个方法：{@link #stats()} 是全站聚合数据，{@link #ordersOf(String)}
 * 是按用户名查询。两者都属于店员职权，调用方（助手的店员工具）必须自己先做角色校验——
 * 模块边界只保证「谁能调」，不保证「调用者有没有权限」，权限判断留在业务层显式表达。
 */
public interface OrderInsights {

    OrderStats stats();

    List<OrderView> ordersOf(String username);

    /** 全站订单汇总。 */
    record OrderStats(long total, long pending, long paid, long cancelled,
                      BigDecimal paidAmount, List<TopProduct> topProducts) {}

    record TopProduct(long productId, String productName, long quantity) {}
}
