package dev.learning.mall.order.web;

import dev.learning.mall.order.api.*;
import dev.learning.mall.order.internal.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderFacade facade;
    private final OrderTransactions transactions;
    private final FulfillmentService fulfillment;
    public OrderController(OrderFacade facade, OrderTransactions transactions, FulfillmentService fulfillment) {
        this.facade = facade; this.transactions = transactions; this.fulfillment = fulfillment;
    }
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

    // ——————————————————— 履约：发货（店员）/ 收货、完成（买家） ———————————————————

    /**
     * 发货。
     *
     * <p><b>TODO(T13 商家账户合并后补)</b>：{@code POST /api/orders/*}{@code /ship} 待补
     * {@code hasRole("STAFF")}，与商家角色规则一起在 {@code config/SecurityConfig.java} 里加。
     * 本次没有挂 URL 级角色限制，是因为另一个分支正在并行改 {@code SecurityConfig}
     * （加 merchant 账号与商家角色规则），两边同时改同一个文件会撞车。
     * 服务端此刻的防线是「只有已支付且未发货的订单能被推进」——那是写在 UPDATE 的 WHERE 里的，
     * 不依赖这一层；但「谁能点发货」这件事在补上角色限制之前确实是敞开的，别让这个 TODO 悄悄消失。
     */
    @PostMapping("/{id}/ship")
    public OrderView ship(@PathVariable String id, Principal principal, @Valid @RequestBody ShipRequest request) {
        return fulfillment.ship(id, principal.getName(), request.shippingNo());
    }

    /** 买家确认收货：不是自己的订单返回 404（归属校验在 {@code FulfillmentService} 的 UPDATE 条件里）。 */
    @PostMapping("/{id}/receive")
    public OrderView receive(@PathVariable String id, Principal principal) { return fulfillment.receive(id, principal.getName()); }

    /** 买家确认完成：同上，只认本人。 */
    @PostMapping("/{id}/complete")
    public OrderView complete(@PathVariable String id, Principal principal) { return fulfillment.complete(id, principal.getName()); }

    public record PaymentRequest(@NotBlank(message = "请选择模拟支付方式") String method) {}

    public record ShipRequest(@NotBlank(message = "请填写运单号") @Size(max = 40, message = "运单号不能超过 40 位") String shippingNo) {}
}
