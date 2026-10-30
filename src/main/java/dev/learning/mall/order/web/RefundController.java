package dev.learning.mall.order.web;

import dev.learning.mall.order.api.OrderRefunds;
import dev.learning.mall.order.api.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家的退款端点。
 *
 * <p>这里刻意<b>薄</b>：控制器只做三件事——取出登录用户名、把请求体翻译成领域参数、
 * 把 {@link RefundView} 原样返回。<b>「这笔能不能自助退」不在翻译层判断</b>：
 * 分流规则只有 {@code RefundService} 一份，这里要是先判一次，就会出现
 * 「页面说可以退、服务说要等商家」这类两头不一致。
 *
 * <p><b>归属是这里唯一的安全职责</b>：用户名取自 {@link Principal}而不是请求体，
 * 模型 / 前端都没办法把它换成别人的。服务层还会再校验一次订单归属（纵深防御）。
 */
@RestController
@RequestMapping("/api")
public class RefundController {

    private final OrderRefunds refunds;

    public RefundController(OrderRefunds refunds) {
        this.refunds = refunds;
    }

    /**
     * 申请退款。返回之前可能已经放款（{@code AUTO} 路由），也可能只是进了商家队列——
     * 调用方请读 {@code route} 与 {@code status}，不要假设「点了就会立刻到账」。
     *
     * <p><b>单位是子订单</b>：{@code subId} 回答「退哪家店的」。跨店订单不给它会被明确拒绝
     * （并列出候选），单店订单可以省略。
     */
    @PostMapping("/orders/{orderId}/refunds")
    public RefundView apply(@PathVariable String orderId, Principal principal,
            @Valid @RequestBody ApplyRefundRequest request) {
        return refunds.apply(orderId, request.subId(), principal.getName(), request.amount(),
                request.reasonCode(), request.reasonText());
    }

    /** 我发起过的退款（含在途与终态），倒序。 */
    @GetMapping("/refunds/mine")
    public List<RefundView> mine(Principal principal) {
        return refunds.refundsOf(principal.getName());
    }

    /**
     * 退款申请单。
     *
     * <p><b>没有 {@code reasonCode} 的必填校验</b>：不填就是「无理由退款」，
     * 那正是无理由退款的定义；反过来要求用户必须选一个原因，只会逼大家乱填。
     *
     * <p><b>{@code subId} 可以为空</b>：只在「这笔订单只有一家店」时才成立，
     * 跨店订单必须指定——退款的金额上限、发货判据、还货范围都以「哪家店」为单位。
     *
     * <p><b>{@code amount} 可以为空</b>：本次只支持全额退款，空就是「退这个子订单的全部」——
     * 注意它是<b>这家店那一份</b>，不是整单实付。
     */
    public record ApplyRefundRequest(String subId, BigDecimal amount, String reasonCode,
                                     @Size(max = 200, message = "退款说明不能超过 200 字") String reasonText) {}
}
