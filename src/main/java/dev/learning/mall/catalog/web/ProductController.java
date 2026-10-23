package dev.learning.mall.catalog.web;

import dev.learning.mall.catalog.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.Principal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@Validated // 让 @Min/@Max 作用于 @RequestParam：否则 page/size 越界会穿到 ProductQuery 里变成 500
public class ProductController {
    private final ProductCatalog catalog;
    public ProductController(ProductCatalog catalog) { this.catalog = catalog; }

    /**
     * 商品列表（<b>分页</b>）。
     *
     * <p>原来这里是无条件 {@code SELECT * FROM product}。实测目录 506 件时响应 116KB，
     * 1006 件时 227KB——每翻一倍流量也翻一倍，而首屏其实只看得见二十几件。
     * 现在筛选、排序、分页全部下推到 SQL，服务端最多传 {@link ProductQuery#MAX_SIZE} 件。
     *
     * <p>{@code total} 一并返回：没有总数前端就写不出分页器，
     * 用户会以为"这家店只有 24 件"。
     */
    @GetMapping
    public ProductPage list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String category,
                            @RequestParam(required = false) BigDecimal maxPrice,
                            @RequestParam(defaultValue = "false") boolean inStock,
                            @RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "24") @Min(1) @Max(ProductQuery.MAX_SIZE) int size) {
        return catalog.search(new ProductQuery(keyword, category, maxPrice, inStock, ProductSort.ID_ASC, page, size));
    }

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