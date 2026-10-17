package dev.learning.mall.payment.api;

import dev.learning.mall.common.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentRouter {
    private final Map<String, PaymentGateway> gateways;
    // IoC 会自动注入所有实现，不需要在订单代码里 new 或逐个 if/else。
    public PaymentRouter(List<PaymentGateway> gateways) {
        this.gateways = gateways.stream().collect(Collectors.toUnmodifiableMap(PaymentGateway::method, Function.identity()));
    }
    public PaymentGateway get(String method) {
        PaymentGateway gateway = gateways.get(method);
        if (gateway == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT", "不支持的模拟支付方式");
        return gateway;
    }
    /** 当前注册的全部模拟支付方式，供调用方做参数预校验。 */
    public List<String> methods() {
        return List.copyOf(gateways.keySet());
    }
}
