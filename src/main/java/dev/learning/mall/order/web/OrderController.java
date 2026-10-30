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

    // ——————————————————— 履约：发货（店员 / 本店商家）/ 收货、完成（买家） ———————————————————

    /**
     * 发货。<b>单位是子订单</b>：谁家的货谁发，发的是自己那一段。
     *
     * <p>URL 里的 {@code subId} 是子订单号，不是父订单号——父单上已经没有
     * 「发没发货」这个字段了（三家店两家发了，父单该写什么？）。
     *
     * <p><b>URL 级角色限制在 {@code SecurityConfig}</b>（{@code hasAnyRole("STAFF","MERCHANT")}）。
     * 与「谁能发这一单」的第二层判定（商家只能发自己店的）之间的分工是刻意的：
     * URL 层管「你是不是经营侧的人」，{@code FulfillmentService} 管「这一单是不是你的店」——
     * 后者需要读库才知道答案，塞进 URL 匹配器里做不到。
     * 两个入口（HTTP 与 Service）都判，是因为「绕过 HTTP 直接调 Service」这条路
     * 不该因为没有 HTTP 就失守。
     */
    @PostMapping("/subs/{subId}/ship")
    public OrderView ship(@PathVariable String subId, Principal principal, @Valid @RequestBody ShipRequest request) {
        return fulfillment.ship(subId, principal.getName(), request.shippingNo());
    }

    /** 买家确认收货：不是自己的子订单返回 404（归属校验在 {@code FulfillmentService} 的 UPDATE 条件里）。 */
    @PostMapping("/subs/{subId}/receive")
    public OrderView receive(@PathVariable String subId, Principal principal) { return fulfillment.receive(subId, principal.getName()); }

    /** 买家确认完成：同上，只认本人。 */
    @PostMapping("/subs/{subId}/complete")
    public OrderView complete(@PathVariable String subId, Principal principal) { return fulfillment.complete(subId, principal.getName()); }

    public record PaymentRequest(@NotBlank(message = "请选择模拟支付方式") String method) {}

    public record ShipRequest(@NotBlank(message = "请填写运单号") @Size(max = 40, message = "运单号不能超过 40 位") String shippingNo) {}
}
