package dev.learning.mall.proactive.web;

import dev.learning.mall.proactive.api.AlertCenter;
import dev.learning.mall.proactive.api.RelatedProduct;
import dev.learning.mall.proactive.api.WatchView;
import dev.learning.mall.proactive.internal.AlertService;
import dev.learning.mall.proactive.internal.RelatedRecommendationService;
import dev.learning.mall.proactive.internal.WatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主动服务接口：关注、提醒中心、关联推荐。
 *
 * <p>全部以登录用户为作用域，用户名来自 SecurityContext 而不是请求参数——
 * 「查看/修改别人的关注」在接口层面就没有表达方式。助手的关注工具走的是同一套 Service，
 * 所以「助手帮你关注」和「你自己点关注」是同一份数据。
 */
@RestController
@RequestMapping("/api")
public class ProactiveController {

    private final WatchService watchService;
    private final AlertService alertService;
    private final RelatedRecommendationService relatedService;

    public ProactiveController(WatchService watchService, AlertService alertService,
                               RelatedRecommendationService relatedService) {
        this.watchService = watchService;
        this.alertService = alertService;
        this.relatedService = relatedService;
    }

    public record WatchRequest(@NotNull Long productId) {}

    @GetMapping("/watches")
    public List<WatchView> watches(Principal principal) {
        return watchService.watchesOf(principal.getName());
    }

    @PostMapping("/watches")
    public WatchView watch(Principal principal, @Valid @RequestBody WatchRequest request) {
        return watchService.watch(principal.getName(), request.productId());
    }

    @DeleteMapping("/watches/{productId}")
    public void unwatch(Principal principal, @PathVariable long productId) {
        watchService.unwatch(principal.getName(), productId);
    }

    /** 提醒中心：未读数 + 最近提醒。 */
    @GetMapping("/alerts")
    public AlertCenter alerts(Principal principal) {
        return alertService.center(principal.getName());
    }

    @PostMapping("/alerts/{id}/read")
    public Map<String, Boolean> markRead(Principal principal, @PathVariable String id) {
        return Map.of("updated", alertService.markRead(principal.getName(), id));
    }

    @PostMapping("/alerts/read-all")
    public Map<String, Integer> markAllRead(Principal principal) {
        return Map.of("updated", alertService.markAllRead(principal.getName()));
    }

    /** 关联推荐：基于购物袋与历史订单。没有历史时退回热销，不返回空列表。 */
    @GetMapping("/recommendations/related")
    public List<RelatedProduct> related(Principal principal,
                                        @RequestParam(required = false) Integer limit) {
        return relatedService.recommend(principal.getName(), limit);
    }
}
