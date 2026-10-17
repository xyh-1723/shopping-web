package dev.learning.mall.catalog.api;

/**
 * 商品变更事件（价格或库存）。
 *
 * <p>由商品模块发布、由主动服务消费。发布方<b>不认识</b>订阅方——商品模块不需要知道
 * 「有人在等降价提醒」，它只负责如实广播「这个商品变了」。这样加一个新玩法
 * （降价提醒、补货提醒、比价、价格趋势）都不需要再动商品模块。
 *
 * <p>消费者必须等事务提交后再动作（{@code @TransactionalEventListener(AFTER_COMMIT)}）：
 * 在事务内被唤醒去查库，读到的还是旧值——「事件先于数据到达」是事件驱动里最经典的坑。
 *
 * <p>只有一种变化时也用一个事件类而不是两个：订阅方的动作完全一样（重新扫描一遍关注），
 * 拆成两个只会让「新增一种变化」变成「新增一个类 + 一个监听方法」。
 */
public record ProductChangedEvent(long productId, Change change) {

    public enum Change {
        /** 价格变了——可能触发降价提醒。 */
        PRICE,
        /** 库存变了——可能触发补货提醒。 */
        STOCK
    }
}
