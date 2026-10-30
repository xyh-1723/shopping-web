package dev.learning.mall.coupon.web;

import dev.learning.mall.coupon.api.CouponCatalog;
import dev.learning.mall.coupon.api.CouponCreateRequest;
import dev.learning.mall.coupon.api.CouponView;
import dev.learning.mall.coupon.api.UserCouponView;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 优惠券入口。
 *
 * <p><b>创建券为什么还要看角色？</b>URL 层已经限制了 STAFF / MERCHANT，
 * 但「商家只能建本店券」这件事 URL 匹配器不知道——它需要查库才能回答。
 * 所以这里把「是不是商家」传给服务层，服务层再用登录账号反查店铺号。
 * 请求体里没有可篡改的店铺号：商家券的 {@code merchantId} 由账号推出，不是客户端填的。
 */
@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final CouponCatalog coupons;

    public CouponController(CouponCatalog coupons) {
        this.coupons = coupons;
    }

    /** 可领券列表。 */
    @GetMapping
    public List<CouponView> available(Principal principal) {
        return coupons.available(principal.getName());
    }

    /** 我的券。 */
    @GetMapping("/mine")
    public List<UserCouponView> mine(Principal principal) {
        return coupons.mine(principal.getName());
    }

    /** 领券。 */
    @PostMapping("/{id}/claim")
    public UserCouponView claim(@PathVariable String id, Principal principal) {
        return coupons.claim(principal.getName(), id);
    }

    /**
     * 创建券模板。
     *
     * <p>不用 {@code @PreAuthorize}：本项目的授权规则统一写在 {@code SecurityConfig} 的 URL 匹配器里，
     * 这里只负责把「是不是商家」这一事实交给服务层。两处都校验，是因为绕过 HTTP 直接调服务
     * 也应当守住同一条规则。
     */
    @PostMapping
    public CouponView create(Principal principal, Authentication authentication,
                             @Valid @RequestBody CouponCreateRequest request) {
        boolean merchantActor = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_MERCHANT"::equals);
        return coupons.create(principal.getName(), merchantActor, request);
    }
}
