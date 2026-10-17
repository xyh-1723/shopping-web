package dev.learning.mall.order.api;

/**
 * 下单端口。
 *
 * <p>助手确认下单后要走这里，而它<b>就是</b>页面 {@code POST /api/orders} 走的那一份实现
 * （{@code OrderFacade.create}）。不是"给助手写一套下单"，而是"让助手复用同一套"。
 * 拆成两份的那天开始，就会出现「页面下单会算满减、助手下单忘了算」这类分叉，
 * 而且它不会立刻暴露——项目决策第 2 条：安全关键逻辑只有一份实现。
 */
public interface OrderCheckout {

    /**
     * @param key 幂等键。助手路径由服务端生成（模型物理上没有传键的入口），
     *            页面路径来自 {@code Idempotency-Key} 请求头。
     */
    OrderView create(String username, String idempotencyKey, CreateOrderRequest request);
}
