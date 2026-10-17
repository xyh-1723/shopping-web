package dev.learning.mall.order.api;

import java.util.List;

/**
 * 订单被超时关闭（用户下单了但没付款）。
 *
 * <p>只由「超时关闭」发出，<b>用户主动取消不发</b>。这个区别不是形式：主动取消意味着
 * 「我不想买了」，再去推荐就是打扰；超时关闭更可能是「忘了 / 犹豫 / 支付卡住了」，
 * 这才值得提醒一句「东西还在」。把两者塞进同一个状态里，就失去了区分它们的能力。
 *
 * <p>和 {@link dev.learning.mall.catalog.api.ProductChangedEvent} 同样的规矩：
 * 发布方不认识订阅方。订单模块只负责说「有笔订单因为超时关了，里面有这几件商品」，
 * 至于要不要召回、怎么推荐，是主动服务的事。
 */
public record OrderAbandonedEvent(String orderId, String username, List<Long> productIds) {
}
