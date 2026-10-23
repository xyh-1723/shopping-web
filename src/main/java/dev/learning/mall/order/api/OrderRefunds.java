package dev.learning.mall.order.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单模块对外暴露的「退款」接口。
 *
 * <p>它是退款的<b>唯一</b>门户：页面自助、商家审批、助手代发起（human-in-the-loop 确认后）
 * 三条路径最终都调这里，于是分流规则、库存幂等、放款条件只有一份实现。
 * 分裂出第二份的那天开始，就会出现「自助退款还了库存、商家批准忘了还」这种只在某条路径上出错的分叉。
 *
 * <p><b>为什么要有一个接口而不是直接注入实现类</b>：与 {@link OrderPayments} 同一个形状——
 * 助手模块 {@code assistant} 已经依赖 {@code order}，直接依赖 {@code order.internal} 的实现类
 * 会把内部结构展开成依赖；这里刻意只暴露「作为 asm 退款」这个能力。
 */
public interface OrderRefunds {

    /**
     * 申请一笔退款。
     *
     * <p><b>判定在这一次调用里做完</b>：符合无理由条件（未发货 + 时间窗口内 + 金额阈值内）
     * 的申请<b>当场放款</b>，不等任何人；其余则留在待审批队列里等商家处理。
     * 「自动的还是人工的」这个结论会落进 {@code refund.route}，事后可复盘。
     *
     * <p>归属校验在这里而不是调用方：调用方传进来的必须是订单主人本人，
     * 助手路径已由 HITL 的「只能本人确认」保证过一次，这里是第二道（纵深防御）。
     *
     * @param username  发起申请的人，必须同时是订单主人
     * @param amount    申请退款的金额（元，两位小数）；为 {@code null} 表示全额（订单实付）
     * @param reasonCode 退款原因码；为空按 {@code NO_REASON} 处理
     * @throws dev.learning.mall.common.BusinessException
     *         {@code INVALID_STATE}（409）订单不是已支付状态；
     *         {@code REFUND_EXCEEDS_PAID}（409）累计退款超过实付；
     *         {@code ALREADY_RESOLVED}（409）该订单已有一笔在途退款
     */
    RefundView apply(String orderId, String username, BigDecimal amount,
                     String reasonCode, String reasonText);

    /** 某用户自己的退款记录（含在途与终态），按创建时间倒序。 */
    List<RefundView> refundsOf(String username);

    /**
     * 商家批准一笔待审批退款，并立刻放款。
     *
     * @param merchant 审批人账号；必须是 {@code ROLE_MERCHANT}，否则 403
     * @throws dev.learning.mall.common.BusinessException
     *         {@code ALREADY_RESOLVED}（409）已被处理过（含并发落败）；
     *         {@code REFUND_UNSUPPORTED}（400）该订单当初的支付方式形不成退款
     */
    RefundView approve(String refundId, String merchant, String note);

    /**
     * 商家驳回一笔待审批退款。驳回是终点，不放款、不动库存。
     *
     * <p>与 {@link #approve} 一样受归属约束：只有订单里含自己店铺商品的商家才能驳回。
     */
    RefundView reject(String refundId, String merchant, String note);

    /**
     * 待审批队列，按申请时间正序（先来先判）。
     *
     * <p><b>可见范围由 {@code viewer} 这个账号自己推导，不由调用方指定</b>：
     * 店员看全站，商家只看自己那家店，两者都不是则 403。
     * 让它成为一个「调用方传 limit 就返回全站」的方法，是加商家表之前的形状——
     * 商家会因此看到别家店的退款，而「看得到」通常紧接着就是「批得了」。
     *
     * @param viewer 查看者账号（店员或商家）
     * @throws dev.learning.mall.common.BusinessException
     *         {@code REFUND_QUEUE_FORBIDDEN}（403）既不是店员也不是商家
     */
    List<RefundView> pendingQueueFor(String viewer, int limit);
}
