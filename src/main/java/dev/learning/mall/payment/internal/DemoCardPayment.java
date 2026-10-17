package dev.learning.mall.payment.internal;

import dev.learning.mall.payment.api.PaymentGateway;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class DemoCardPayment implements PaymentGateway {
    public String method() { return "DEMO_CARD"; }
    public String pay(String orderId, BigDecimal amount) { return "SIM-CARD-" + orderId; }
}
