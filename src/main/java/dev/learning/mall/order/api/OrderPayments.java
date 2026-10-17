package dev.learning.mall.order.api;

import java.util.List;

/**
 * 订单模块对外暴露的「支付执行」接口。assistant 模块的 human-in-the-loop 确认流程
 * 通过它完成真正的支付——注意归属与状态校验仍在订单模块内部再做一次
 * （调用方传的用户名必须拥有该订单，且订单处于待支付状态），
 * 模块边界不放任任何「调用方说自己校验过了」的承诺。
 */
public interface OrderPayments {

    /** 为 username 名下的待支付订单执行模拟支付，返回支付后的订单视图。 */
    OrderView pay(String orderId, String username, String method);

    /** 当前可用的模拟支付方式（如 DEMO_CARD / DEMO_WALLET）。 */
    List<String> methods();
}
