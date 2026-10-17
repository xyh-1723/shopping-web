package dev.learning.mall.cart.web;

import dev.learning.mall.cart.api.CartView;
import dev.learning.mall.cart.internal.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物袋接口。助手的加购工具和页面的购物袋用的是同一套——
 * 所以助手放进去的东西，用户刷新页面就能看到，反之亦然。
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cart;

    public CartController(CartService cart) { this.cart = cart; }

    public record AddRequest(@NotNull Long productId, @Min(1) @Max(99) int quantity) {}

    /** 查看购物袋（含实时价格、库存与结算试算）。 */
    @GetMapping
    public CartView view(Authentication auth) {
        return cart.view(name(auth));
    }

    /** 加购。传回加购后的完整试算，前端无需再发一次查询。 */
    @PostMapping("/items")
    public ResponseEntity<CartView> add(Authentication auth, @Valid @RequestBody AddRequest request) {
        return ResponseEntity.ok(cart.add(name(auth), request.productId(), request.quantity()));
    }

    /** 直接设置数量（quantity <= 0 视为移除）。 */
    @PutMapping("/items/{productId}")
    public CartView setQuantity(Authentication auth, @PathVariable long productId,
                                @Valid @RequestBody AddRequest request) {
        return cart.setQuantity(name(auth), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartView remove(Authentication auth, @PathVariable long productId) {
        return cart.remove(name(auth), productId);
    }

    @DeleteMapping
    public CartView clear(Authentication auth) {
        return cart.clear(name(auth));
    }

    private static String name(Authentication auth) {
        // Principal#getName 在已登录时就是用户名；未登录请求根本到不了这里（SecurityConfig 拦截）。
        return auth == null ? null : auth.getName();
    }
}
