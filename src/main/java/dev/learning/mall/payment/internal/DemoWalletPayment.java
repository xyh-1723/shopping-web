package dev.learning.mall.payment.internal;

import dev.learning.mall.payment.api.PaymentGateway;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class DemoWalletPayment implements PaymentGateway {
    public String method() { return "DEMO_WALLET"; }
    public String pay(String orderId, BigDecimal amount) { return "SIM-WALLET-" + orderId; }
}
