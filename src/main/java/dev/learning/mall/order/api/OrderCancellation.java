package dev.learning.mall.order.api;

/**
 * 订单模块对外暴露的「取消」接口，供 assistant 的 human-in-the-loop 确认流程使用。
 *
 * <p>和 {@link OrderPayments} 同理：归属与状态校验在订单模块<b>内部</b>再做一次
 * （订单必须属于传入的用户名、且处于待支付状态），不信任「调用方说自己校验过了」。
 *
 * <p>取消不可撤销，所以它也要过人类闸门——但它和下单是两种不同的待确认动作，
 * 卡片文案必须区分开。
 */
public interface OrderCancellation {

    /** 取消 username 名下的一笔待支付订单，返回取消后的订单视图。 */
    OrderView cancel(String orderId, String username);
}
