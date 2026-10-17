package dev.learning.mall.catalog.web;

import dev.learning.mall.catalog.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductCatalog catalog;
    public ProductController(ProductCatalog catalog) { this.catalog = catalog; }
    @GetMapping public List<ProductView> list() { return catalog.list(); }
    @GetMapping("/{id}") public ProductView get(@PathVariable long id) { return catalog.get(id); }

    public record PriceRequest(@NotNull @DecimalMin("0.01") BigDecimal price) {}
    public record StockRequest(@NotNull @Min(0) @Max(9999) int stock) {}

    /**
     * 改价 / 改库存——<b>仅店员</b>（SecurityConfig 里做了 URL 级限制，形成双重防线）。
     *
     * <p>这两个接口的存在不只是为了运营：它们是「主动服务」的<b>触发源</b>。
     * 一处「用户在等降价提醒」的价值，只有在价格真的会变时才成立。
     * 服务内部会广播商品变更事件，主动服务收到后立刻扫描关注——
     * 于是演示路径是完整的：店员改价 → 关注的人当场收到提醒。
     */
    @PostMapping("/{id}/price")
    public ProductView updatePrice(@PathVariable long id, @Valid @RequestBody PriceRequest request) {
        return catalog.updatePrice(id, request.price());
    }

    /**
     * 补货账号取自 {@link Principal} 而不是请求体：请求体是客户端给的，可以被伪造，
     * 而「谁补的货」是要进库存流水留痕的——留痕的第一要求是它不可抵赖。
     */
    @PostMapping("/{id}/stock")
    public ProductView updateStock(@PathVariable long id, Principal principal,
            @Valid @RequestBody StockRequest request) {
        return catalog.updateStock(id, request.stock(), principal == null ? null : principal.getName());
    }
}