package dev.learning.mall.order.web;

import dev.learning.mall.order.api.OrderRefunds;
import dev.learning.mall.order.api.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家的退款审批端点。
 *
 * <p><b>URL 级角色限制在 {@code SecurityConfig}</b>（{@code hasRole("MERCHANT")}）；
 * {@code RefundService.approve} 内部还会再判一次，因为「绕过 HTTP 直接调 Service」这条路
 * 不该因为没有 HTTP 就失守（这是我们 Falcons 同类权限分两次判定的老规矩）。
 *
 * <p><b>为什么商家审批不进 HITL</b>（{@code assistant_pending_action}）：
 * HITL 的归属校验写死成「只能本人确认」，而<b>审批在定义上就是另一个人</b>。
 * 把它塞进那张表，只有两种可能：绕过归属校验（给安全关键逻辑挖洞），
 * 或把归属改成「可扩展」（那就是第二份实现）。两种都不接受，所以审批走这里。
 *
 * <p><b>为空的请求体要容忍</b>：前端「直接点批准/驳回」时可以不发 body，
 * 那不代表审批意见是空字符串——{@code note} 为 {@code null} 与 {@code ""} 在
 * 落库时被归一化成同一个值（没有理由），但在 API 上不必强迫调用方多传一个字段。
 *
 * <p><b>「容忍」必须写在 {@code @RequestBody(required = false)} 上，光在方法体里判
 * {@code request == null} 没有用</b>：{@code @RequestBody} 默认 {@code required = true}，
 * Spring 会在 controller 方法体执行<b>之前</b>就返回 400，那段 null 分支永远走不到——
 * 注释与实现说的是两回事，而只有注释的那一半是错的那一半。
 */
@RestController
@RequestMapping("/api/refunds")
public class MerchantRefundController {

    /**
     * 待审批队列的单次返回上限。
     *
     * <p>不做成配置：它由「一个商家一次能看多少」这份产品判断决定，
     * 而到目前为止没有人提出要调它（做成配置 = 一个改了也不起作用的旋钮）。
     */
    private static final int QUEUE_LIMIT = 50;

    private final OrderRefunds refunds;

    public MerchantRefundController(OrderRefunds refunds) {
        this.refunds = refunds;
    }

    /** 待审批队列，先申请的排在前面。 */
    @GetMapping("/pending")
    public List<RefundView> pendingQueue() {
        return refunds.pendingQueue(QUEUE_LIMIT);
    }

    /**
     * 批准：<b>批准就是放款</b>。内部走两段迁移：
     * {@code APPROVED}（承诺这笔钱）→ 真的把钱退回去 → {@code REFUNDED}（到账），
     * 中间任何一步失败，整笔事务回滚，不会留下「批准了但钱没动」这种半成品状态。
     */
    @PostMapping("/{refundId}/approve")
    public RefundView approve(@PathVariable String refundId, Principal principal,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        return refunds.approve(refundId, principal.getName(),
                request == null ? null : request.note());
    }

    /** 驳回：本次申请到此为止，不放款、不动库存。 */
    @PostMapping("/{refundId}/reject")
    public RefundView reject(@PathVariable String refundId, Principal principal,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        return refunds.reject(refundId, principal.getName(),
                request == null ? null : request.note());
    }

    /** 审批意见。驳回时建议填写理由：它会作为站内信正文回给买家。 */
    public record DecisionRequest(@Size(max = 200, message = "审批意见不能超过 200 字") String note) {}
}
