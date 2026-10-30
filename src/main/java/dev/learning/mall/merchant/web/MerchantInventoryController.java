package dev.learning.mall.merchant.web;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.catalog.api.ProductPage;
import dev.learning.mall.catalog.api.ProductQuery;
import dev.learning.mall.catalog.api.ProductSort;
import dev.learning.mall.merchant.api.MerchantContext;
import dev.learning.mall.merchant.api.MerchantInventory;
import dev.learning.mall.merchant.api.MerchantScoped;
import dev.learning.mall.merchant.api.MerchantView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家中心：看<b>自己店</b>的商品与库存。
 *
 * <p>和公开货架的区别只有一个，但很要紧：{@code merchantId} <b>不从请求里读</b>。
 * 它由登录账号推出来（{@code merchant.owner_username} 是唯一绑定点），
 * 所以「改一下 URL 里的店铺号就能看别人家库存」这条路根本不存在——
 * 而不是靠一个校验把它堵住。
 *
 * <p>URL 级的第一层限制在 {@code SecurityConfig}（必须带 MERCHANT 角色）；
 * 第二层由 {@code @MerchantScoped} 切面在方法入口完成：把当前登录账号的店铺号
 * 写入 {@link MerchantContext}，方法内直接取，不再自己调 {@code ofOwner}。
 */
@RestController
@RequestMapping("/api/merchant")
@Validated
public class MerchantInventoryController {

    private final ProductCatalog catalog;

    public MerchantInventoryController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/inventory")
    @MerchantScoped
    public MerchantInventory inventory(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(ProductQuery.MAX_SIZE) int size,
            @RequestParam(required = false) String keyword) {
        // 店铺由切面从登录账号解析后放进 MerchantContext；这里直接取，
        // 拿不到（没店）会抛统一的 MERCHANT_SHOP_MISSING 403。
        MerchantView shop = MerchantContext.requireShop();
        // 排序固定按编号升序：这里是「盘点库存」，不是逛货架。按价格排会让同一件商品
        // 在补货后跳到一个新位置，而商家正盯着行号对账。
        ProductQuery query = new ProductQuery(keyword, null, null, false, ProductSort.ID_ASC, page, size);
        ProductPage products = catalog.searchOfMerchant(shop.id(), query);
        return new MerchantInventory(shop, catalog.stockSummaryOfMerchant(shop.id()), products);
    }
}
