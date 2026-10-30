package dev.learning.mall.order.web;

import dev.learning.mall.order.api.CheckoutPreview;
import dev.learning.mall.order.internal.CheckoutPreviewService;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 结算预览：按购物袋内容与所选券，返回券后应付（不锁券）。 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutPreviewController {

    private final CheckoutPreviewService previews;

    public CheckoutPreviewController(CheckoutPreviewService previews) {
        this.previews = previews;
    }

    @PostMapping("/preview")
    public CheckoutPreview preview(Principal principal,
                                    @RequestBody(required = false) PreviewRequest request) {
        return previews.preview(principal.getName(), request == null ? null : request.userCouponId());
    }

    public record PreviewRequest(String userCouponId) {}
}
