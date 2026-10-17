package dev.learning.mall.order.internal;

import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.payment.api.PaymentRouter;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link OrderPayments} 的实现：薄适配层，真正的幂等与状态机都在 {@link OrderTransactions}。 */
@Service
public class OrderPaymentsService implements OrderPayments {

    private final OrderTransactions transactions;
    private final PaymentRouter router;

    public OrderPaymentsService(OrderTransactions transactions, PaymentRouter router) {
        this.transactions = transactions;
        this.router = router;
    }

    @Override
    public OrderView pay(String orderId, String username, String method) {
        return transactions.pay(orderId, username, method);
    }

    @Override
    public List<String> methods() {
        return router.methods();
    }
}
