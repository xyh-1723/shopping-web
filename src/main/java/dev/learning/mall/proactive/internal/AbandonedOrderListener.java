package dev.learning.mall.proactive.internal;

import dev.learning.mall.order.api.OrderAbandonedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 弃单挽回的触发点。
 *
 * <p>必须等事务提交（AFTER_COMMIT）：在关单事务里被唤醒时，去查商品库存读到的还是
 * 归还之前的旧值——于是提醒里写「还剩 0 件」，而库里其实已经归还了。
 * 「事件先于数据到达」是事件驱动里最经典的坑，这里和降价提醒踩的是同一个。
 */
@Component
class AbandonedOrderListener {

    private final AbandonedOrderService service;

    AbandonedOrderListener(AbandonedOrderService service) { this.service = service; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onAbandoned(OrderAbandonedEvent event) {
        service.recover(event);
    }
}
