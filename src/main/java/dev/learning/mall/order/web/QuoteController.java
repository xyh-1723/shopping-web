package dev.learning.mall.order.web;

import dev.learning.mall.order.api.CreateOrderRequest;
// 单类型导入嵌套类型：这样下面能写简单名 Line。
// javac 不允许把 TYPE_USE 注解（@NotNull / @Valid）加在 CreateOrderRequest.Line
// 这种「限定名」上（报错：无法用 type-use 批注来批注确定作用域结构），但加在简单名上可以
// ——CreateOrderRequest 自己就是这么写的。改用简单名既保住校验语义，也不用退成 class。
import dev.learning.mall.order.api.CreateOrderRequest.Line;
import dev.learning.mall.order.api.QuoteSnapshot;
import dev.learning.mall.order.internal.QuoteSnapshotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 页面下单前的服务端金额快照接口。 */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteSnapshotService quotes;
    private final Clock clock;

    public QuoteController(QuoteSnapshotService quotes, Clock clock) {
        this.quotes = quotes;
        this.clock = clock;
    }

    /** 为当前登录用户提交的商品行拍摄金额快照。 */
    @PostMapping
    public QuoteResponse capture(Principal principal, @Valid @RequestBody QuoteRequest request) {
        LocalDateTime capturedAt = LocalDateTime.now(clock);
        QuoteSnapshot.Snapshot snapshot = quotes.capture(principal.getName(), request.items());
        var quote = snapshot.quote();
        return new QuoteResponse(snapshot.id(), quote.subtotal(), quote.discount(), quote.shippingFee(),
                quote.payable(), quotes.expirationFrom(capturedAt));
    }

    /** 页面只提交商品编号与数量，不提交任何价格。 */
    public record QuoteRequest(@NotEmpty(message = "购物车不能为空")
                               List<@NotNull @Valid Line> items) {}

    /** 金额快照及其有效期。 */
    public record QuoteResponse(String id, BigDecimal subtotal, BigDecimal discount,
                                BigDecimal shippingFee, BigDecimal payAmount,
                                LocalDateTime expiresAt) {}
}
