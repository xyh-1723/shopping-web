package dev.learning.mall.order.web;

import dev.learning.mall.order.api.*;
import dev.learning.mall.order.internal.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderFacade facade;
    private final OrderTransactions transactions;
    public OrderController(OrderFacade facade, OrderTransactions transactions) { this.facade = facade; this.transactions = transactions; }
    @PostMapping
    public OrderView create(Principal principal, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateOrderRequest request) {
        return facade.create(principal.getName(), key, request);
    }
    @GetMapping public List<OrderView> list(Principal principal) { return transactions.list(principal.getName()); }
    @GetMapping("/{id}") public OrderView get(@PathVariable String id, Principal principal) { return transactions.get(id, principal.getName()); }
    @PostMapping("/{id}/pay")
    public OrderView pay(@PathVariable String id, Principal principal, @Valid @RequestBody PaymentRequest request) {
        return transactions.pay(id, principal.getName(), request.method());
    }
    @PostMapping("/{id}/cancel")
    public OrderView cancel(@PathVariable String id, Principal principal) { return transactions.cancel(id, principal.getName()); }
    public record PaymentRequest(@NotBlank(message = "请选择模拟支付方式") String method) {}
}
