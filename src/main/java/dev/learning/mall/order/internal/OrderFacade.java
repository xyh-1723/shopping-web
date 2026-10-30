package dev.learning.mall.order.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OrderFacade implements OrderCheckout {
    private final OrderRepository orders;
    private final OrderTransactions transactions;
    private final QuoteSnapshot quotes;

    public OrderFacade(OrderRepository orders, OrderTransactions transactions, QuoteSnapshot quotes) {
        this.orders = orders;
        this.transactions = transactions;
        this.quotes = quotes;
    }
    // 特意不加 @Transactional：唯一键异常必须先从另一个 Bean 的事务中退出并回滚，再查询旧订单。
    @Override
    public OrderView create(String user, String key, CreateOrderRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_KEY", "请求编号须为 8 至 80 位字母、数字、下划线或横线");
        if (request == null || request.items() == null || request.items().isEmpty() || request.items().size() > 20
                || request.items().stream().anyMatch(line -> line == null || line.productId() <= 0 || line.quantity() < 1 || line.quantity() > 99))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ITEMS", "请检查商品和数量");
        if (request.userCouponId() != null && request.userCouponId().length() > 64)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_COUPON", "优惠券编号过长");
        List<CreateOrderRequest.Line> lines = request.items().stream().sorted(Comparator.comparingLong(CreateOrderRequest.Line::productId)).toList();
        if (lines.stream().map(CreateOrderRequest.Line::productId).distinct().count() != lines.size())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT", "同一种商品请合并数量后提交");
        String canonical = lines.stream().map(line -> line.productId() + ":" + line.quantity()).reduce("", (a, b) -> a + b + ";");
        // 券也是「买了什么」的一部分：同一个请求编号换一张券，属于不同的下单意图。
        // 不把它纳入哈希，就会出现「换券重放却拿到上一张券的旧订单」。
        String hash = hash(canonical + ";coupon=" + (request.userCouponId() == null ? "" : request.userCouponId()));
        var existing = orders.byKey(user, key);
        if (existing.isPresent()) return replay(existing.get(), hash);
        // 拿不到快照（未提交、已过期或属于他人）时保持向后兼容，不阻断下单。
        // 快照只保护用户免受静默改价，不承担鉴权职责。
        BigDecimal expectedPayable = quotes.find(request.quoteId(), user)
                .map(snapshot -> snapshot.quote().payable())
                .orElse(null);
        try {
            return transactions.create(user, key, hash, lines, expectedPayable, request.userCouponId());
        } catch (DuplicateKeyException conflict) {
            OrderRow row = orders.byKey(user, key).orElseThrow(() -> conflict);
            return replay(row, hash);
        }
    }
    private OrderView replay(OrderRow row, String hash) {
        if (!row.requestHash().equals(hash)) throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "同一请求编号不能用于不同的购物车内容");
        return orders.view(row);
    }
    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
