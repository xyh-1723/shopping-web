package dev.learning.mall.cart.internal;

import dev.learning.mall.order.api.OrderPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 订单落库后清空本人购物袋。
 *
 * <p><b>为什么用监听而不是让调用方记得清</b>：修复前，「下单后清袋」这件事散在三个地方，
 * 而且只有一处是对的：
 * <ul>
 *   <li>助手确认下单：<b>服务端</b>清，与落单同一事务 —— 正确；</li>
 *   <li>页面下单：<b>前端</b>清，{@code app.js} 里 {@code DELETE /api/cart}，
 *       并且失败被 {@code .catch(() => {})} 静默吞掉 —— 袋子可能留着，用户毫不知情；</li>
 *   <li>直接调 API 下单：没人清。</li>
 * </ul>
 * 同一个业务动作三种结果。判断标准不是「有没有代码在清」，而是
 * <b>用户能不能靠行为预测结果</b>——不能，所以这是一致性缺陷。
 *
 * <p><b>为什么不放在调用方</b>：放在调用方就得在页面和助手各写一遍，
 * 而「安全关键逻辑只有一份实现」是这个项目的硬规则——复制出第二份，
 * 就是这两条路径再次分叉的开始。
 *
 * <p><b>为什么可以依赖订单模块的事件</b>：方向是 cart → order，这是允许的方向。
 * 反过来（订单依赖购物袋）不行——当初正是为了这个才把定价器独立成 {@code pricing} 包。
 *
 * <p><b>事务边界</b>：{@code OrderPlacedEvent} 在下单事务<b>之内</b>同步发布，
 * 所以这里的清袋与落单同生共死：要么都有，要么都没有，不会出现幽灵袋。
 * 这里的 {@code @Transactional} 是 REQUIRED，加入已存在的事务，不会另起一个。
 *
 * <p><b>失败即回滚，不吞异常</b>：清袋是本地写，它失败意味着数据库出了问题，
 * 那时订单本来也不该单独成立。刻意<b>不</b>用 try/catch 把它吞掉——
 * 静默失败正是原来前端那一行的毛病，不能搬到服务端重犯。
 */
@Component
public class CartClearingOnOrderPlaced {

    private final CartService cart;

    CartClearingOnOrderPlaced(CartService cart) { this.cart = cart; }

    /**
     * 刻意<b>不加</b> {@code @Transactional}。
     *
     * <p>这不是遗漏：实测在这里加 {@code @Transactional} 会让
     * {@code OrderExpiryTest#expiredOrderIsClosedAndStockReturns} 变红
     * （断言最新 outbox 事件是 {@code ORDER_CLOSED_TIMEOUT}，实际拿到 {@code ORDER_CREATED}）。
     * 原因是给 {@code @EventListener} 叠事务注解会注册额外的事务同步器，
     * 干扰订单发件箱 {@code AFTER_COMMIT} 的时序。
     *
     * <p>不加也照样在同一个事务里：{@code OrderPlacedEvent} 是<b>同步</b>发布的，
     * 监听器与下单方同线程、同事务，清袋天然与落单同生共死。
     * 事务边界由发布方决定，不由监听方决定——这条比「每个方法都挂个注解」更可靠。
     */
    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        cart.clear(event.username());
    }
}
