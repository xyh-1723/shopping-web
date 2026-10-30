package dev.learning.mall.web;

import dev.learning.mall.catalog.api.ProductCatalog;
import dev.learning.mall.merchant.api.MerchantDirectory;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {
    private final ProductCatalog catalog;
    private final MerchantDirectory merchants;

    public PageController(ProductCatalog catalog, MerchantDirectory merchants) {
        this.catalog = catalog;
        this.merchants = merchants;
    }

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        String username = principal == null ? "" : principal.getName();
        model.addAttribute("username", username);
        // 「商家中心」入口只对真正有店的账号显示：让买家点进去再看 403，
        // 等于把配置问题翻译成用户体验问题。
        model.addAttribute("merchantCenter", !username.isEmpty() && merchants.ofOwner(username).isPresent());
        return "index";
    }
    @GetMapping("/login") public String login() { return "login"; }
    @GetMapping("/learn") public String learn() { return "learn"; }

    // 购物助手：独立会话页，需登录（会话按用户保存）。
    @GetMapping("/assistant")
    public String assistant(Principal principal, Model model) {
        model.addAttribute("username", principal == null ? "" : principal.getName());
        return "assistant";
    }

    /**
     * 商家中心。页面本身只做壳：数据全部来自 {@code /api/merchant/inventory}，
     * 由那个接口按登录账号收窄到本店——页面参数里不放店铺号。
     *
     * <p>这里允许任何登录账号打开，包括没有店铺的买家：页面会告诉他
     * 「这个账号没有关联店铺」，而真正的数据接口仍然是 403。
     * 把页面本身做成 403 会让浏览器直接弹一段 JSON，那是给机器看的拒绝，不是给人看的。
     */
    @GetMapping("/merchant")
    public String merchant(Principal principal, Model model) {
        model.addAttribute("username", principal.getName());
        model.addAttribute("merchant", merchants.ofOwner(principal.getName()).orElse(null));
        return "merchant";
    }

    // 商品详情页：复用 catalog 公开边界，不存在的编号交由全局异常处理返回 404。
    @GetMapping("/product/{id}")
    public String product(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("product", catalog.get(id));
        model.addAttribute("username", principal == null ? "" : principal.getName());
        return "product";
    }
}
