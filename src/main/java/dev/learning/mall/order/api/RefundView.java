package dev.learning.mall.order.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一笔退款的对外视图。
 *
 * <p><b>退款为什么是它自己的一张表，而不是 {@code mall_order.status} 的第五个取值。</b>
 * 订单的 {@code status} 回答的是「钱有没有收到」（PENDING / PAID / CANCELLED），
 * 而一笔订单可以被退多次、需要留下多条历史（将来放开部分退款尤其明显），
 * 状态列只有一个位置，装不下。做成正交实体之后，订单可以停在 {@code PAID}，
 * 退款走到哪一步由 {@code refund.status} 回答，两边互不挤占。
 *
 * <p><b>三个「谁」容易混淆，这里一次说清</b>：
 * <ul>
 *   <li>{@code username}：<b>这笔钱是谁的</b>——放款一定退到这个人的钱包；</li>
 *   <li>{@code requestedBy}：谁发起的这次申请。今天恒等于 {@code username}：
 *       页面自助按键，或助手发起、由本人在 HITL 卡片上确认，两条路径都已校验身份，
 *       于是「替别人申请退款」在结构上不会发生；</li>
 *   <li>{@code decidedBy}：谁批的。{@code AUTO} 路由固定是 {@code SYSTEM}，
 *       {@code MERCHANT_APPROVAL} 路由是那位商家的账号——
 *       数据库还有一条 {@code ck_refund_auto_decided_by_system} 钉着
 *       「AUTO 路径不许出现人类审批人」。</li>
 * </ul>
 *
 * <p><b>{@code stockReturned} 的含义容易被读反</b>：它是 {@code false} 不代表漏还，
 * 因为<b>已发货 / 已收货的订单退款只退钱、不还货</b>（货在买家手上，还回去就是凭空造货），
 * 那种情况下 {@code false} 才是正确答案。
 *
 * <h3>{@code orderSubId}：退款的归属是子订单（V25）</h3>
 *
 * <p>{@code orderId} 回答「这笔钱是从哪次交易里出来的」，{@code orderSubId} 回答
 * 「这笔钱是<b>哪家店</b>的」。跨店订单下这两个答案不再等价，而退款的每一条规则
 * ——谁能看见、谁能批、最多能退多少、退完要还哪几件货——问的都是后者。
 *
 * <p>它非空：V25 把 {@code refund.order_sub_id} 改成了 {@code NOT NULL}，
 * 于是「一笔退款没有归属」在数据库里就写不进去，不依赖调用方记得传。
 */
public record RefundView(String id, String orderId, String orderSubId, String username, BigDecimal amount,
                         String reasonCode, String reasonText, String route, String status,
                         String requestedBy, String decidedBy, LocalDateTime decidedAt,
                         String decisionNote, boolean stockReturned, LocalDateTime createdAt) {

    /** 分流结果：无理由退款，买家自助，不等任何人审批。 */
    public static final String ROUTE_AUTO = "AUTO";

    /** 分流结果：需要商家确认后才能放款。 */
    public static final String ROUTE_MERCHANT_APPROVAL = "MERCHANT_APPROVAL";

    /** 退款在途：等待商家点头。 */
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";

    /** 商家已批准、放款进行中。批了不等于钱已到账，见放款的两段写法。 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 商家驳回。这是本次退款申请的终点。 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 钱已经回到买家手里。只有这个状态才允许归还库存。 */
    public static final String STATUS_REFUNDED = "REFUNDED";

    /** 买家自己撤回。 */
    public static final String STATUS_CANCELLED = "CANCELLED";
}
