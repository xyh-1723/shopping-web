package dev.learning.mall.order.api;

/**
 * 订单已落库——<b>在下单事务之内发布</b>，不是提交之后。
 *
 * <p>为什么必须强调这一点：购物袋的清空依赖这个事件。如果是提交后（{@code AFTER_COMMIT}）
 * 才发，那么「订单已生成、袋子里东西还在」就会有一个真实可见的窗口——
 * 用户在那个瞬间刷新页面，会以为自己还没买，于是再下一单。
 *
 * <p>Spring 的 {@code ApplicationEventPublisher} 默认是<b>同步</b>的：
 * 监听器与发布者在同一个线程、同一个事务里执行。所以订阅方做的本地写
 * （如清袋）会与落单一起提交或一起回滚，不需要额外的事务传播配置。
 *
 * <p><b>为什么用事件而不是让订单模块直接调购物袋</b>：依赖方向不允许反着来。
 * 订单模块依赖购物袋是禁止的（当初正是为了不让订单依赖 cart，才把定价器
 * 独立成 {@code pricing} 包）。改成「订单只说一句我落单了，谁关心谁自己来听」，
 * 方向就变成了 cart → order，规则得以保持。
 *
 * <p>对照：{@code DomainEventPublisher}（订单发件箱）走的是数据库表 + 异步投递，
 * 用于「跨进程、允许延迟」的通知；这里要的是「进程内、必须同生共死」，
 * 所以是应用事件，不要混用。
 */
public record OrderPlacedEvent(String orderId, String username) {}
