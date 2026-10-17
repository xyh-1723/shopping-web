package dev.learning.mall.web;

import dev.learning.mall.catalog.api.ProductCatalog;
import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {
    private final ProductCatalog catalog;
    public PageController(ProductCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        model.addAttribute("username", principal == null ? "" : principal.getName());
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

    // 商品详情页：复用 catalog 公开边界，不存在的编号交由全局异常处理返回 404。
    @GetMapping("/product/{id}")
    public String product(@PathVariable long id, Principal principal, Model model) {
        model.addAttribute("product", catalog.get(id));
        model.addAttribute("username", principal == null ? "" : principal.getName());
        return "product";
    }
}
