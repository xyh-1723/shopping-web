package dev.learning.mall.order.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.QuoteSnapshot;
import dev.learning.mall.pricing.api.Pricer;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将服务端定价结果持久化到 {@code price_quote}。 */
@Service
public class QuoteSnapshotService implements QuoteSnapshot {

    private record QuoteRow(String id, String linesJson, BigDecimal subtotal,
                            BigDecimal discount, BigDecimal shippingFee,
                            BigDecimal payAmount) {}

    private final JdbcClient jdbc;
    private final ProductCatalog catalog;
    private final Pricer pricer;
    private final ObjectMapper json;
    private final OrderProperties properties;
    private final Clock clock;

    public QuoteSnapshotService(JdbcClient jdbc, ProductCatalog catalog, Pricer pricer,
                                ObjectMapper json, OrderProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.pricer = pricer;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 拍摄金额快照。商品价格在这里从商品模块读取，调用方无法夹带价格。
     */
    @Override
    @Transactional
    public Snapshot capture(String username, List<CreateOrderRequest.Line> lines) {
        List<CreateOrderRequest.Line> copiedLines = List.copyOf(lines);
        Pricer.Quote quote = OrderPricing.quoteOf(catalog, pricer, copiedLines);
        String id = UUID.randomUUID().toString();
        LocalDateTime expiresAt = expirationFrom(LocalDateTime.now(clock));
        jdbc.sql("""
                        INSERT INTO price_quote(id,username,lines_json,subtotal,discount,
                                                shipping_fee,pay_amount,expires_at)
                        VALUES(:id,:user,:lines,:subtotal,:discount,:shippingFee,:payAmount,:expiresAt)""")
                .param("id", id)
                .param("user", username)
                .param("lines", writeLines(copiedLines))
                .param("subtotal", quote.subtotal())
                .param("discount", quote.discount())
                .param("shippingFee", quote.shippingFee())
                .param("payAmount", quote.payable())
                .param("expiresAt", expiresAt)
                .update();
        return new Snapshot(id, copiedLines, quote);
    }

    /**
     * 按「编号 + 本人 + 未过期」同时查找，避免把他人的快照用于当前订单。
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> find(String id, String username) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbc.sql("""
                        SELECT id,lines_json,subtotal,discount,shipping_fee,pay_amount
                          FROM price_quote
                         WHERE id = :id AND username = :user AND expires_at > :now""")
                .param("id", id)
                .param("user", username)
                .param("now", LocalDateTime.now(clock))
                .query(QuoteRow.class)
                .optional()
                .map(this::toSnapshot);
    }

    /**
     * HTTP 响应中的有效期与落库规则共用同一配置口径。
     *
     * <p>快照从展示到提交只需要覆盖一个短暂交互窗口；复用订单支付时限，
     * 避免再引入一项无人维护的配置。
     */
    public LocalDateTime expirationFrom(LocalDateTime capturedAt) {
        return capturedAt.plusMinutes(properties.getPaymentTimeoutMinutes());
    }

    private Snapshot toSnapshot(QuoteRow row) {
        Pricer.Quote quote = new Pricer.Quote(row.subtotal(), row.discount(), row.shippingFee(),
                row.payAmount(), List.of());
        return new Snapshot(row.id(), readLines(row.linesJson()), quote);
    }

    private String writeLines(List<CreateOrderRequest.Line> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (IOException e) {
            throw new IllegalStateException("金额快照中的商品行无法序列化", e);
        }
    }

    private List<CreateOrderRequest.Line> readLines(String linesJson) {
        try {
            List<CreateOrderRequest.Line> lines = json.readValue(linesJson, new TypeReference<>() {});
            return List.copyOf(lines);
        } catch (IOException e) {
            throw new IllegalStateException("金额快照中的商品行无法解析", e);
        }
    }
}
