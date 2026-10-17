package dev.learning.mall.payment.api;

import java.math.BigDecimal;

/** 策略接口：这里仅为本地模拟，不连接银行，不发生真实扣款。 */
public interface PaymentGateway {
    String method();
    String pay(String orderId, BigDecimal amount);
}
