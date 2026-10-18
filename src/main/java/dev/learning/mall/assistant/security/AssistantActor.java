package dev.learning.mall.assistant.security;

import java.util.Set;

/**
 * 助手视角下的「调用者身份」。权限判定的唯一依据是这里携带的角色集合，
 * 绝不依据自然语言（用户自称管理员、模型说可以，都不算数）。
 */
public record AssistantActor(String username, Set<String> roles) {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_STAFF = "ROLE_STAFF";
    public static final String ROLE_MERCHANT = "ROLE_MERCHANT";

    /**
     * 严格语义：只看 {@code ROLE_STAFF}。
     *
     * <p>商家<b>不是</b>店员的子类型，不通过带 STAFF 角色来「继承」店员权限——
     * 那样商家会自动获得改价 / 改库存 / 发货等运营执行权，只能靠在每个判定点补排除项来收回来，
     * 漏一处的后果就是多给权限。并列角色则相反：漏改是少给权限，失败方向是安全的。
     */
    public boolean isStaff() {
        return roles.contains(ROLE_STAFF);
    }

    /** 商家：经营决策方。刻意与 {@link #isStaff()} 互斥的语义，两者可以并存于同一账号。 */
    public boolean isMerchant() {
        return roles.contains(ROLE_MERCHANT);
    }

    /**
     * 经营侧身份的统称。
     *
     * <p>判「内部数据能不能对这个人展示」（知识库内部文档等）用 {@code isOperatingSide()}；
     * 判「能不能执行日常运营动作（改价 / 改库存 / 发货）」必须用 {@link #isStaff()}——
     * 这两件事在本系统的边界并不重合，混用就等于把执行权一并下发给商家。
     */
    public boolean isOperatingSide() {
        return isStaff() || isMerchant();
    }

    /** 用于系统提示与前端徽标的角色名。 */
    public String roleName() {
        // 顺序不能反：一个同时带 STAFF 与 MERCHANT 的 actor 必须仍报 "STAFF"。
        // 既有 admin 就是这个形状，反过来会让整个 admin 的角色在徽标与既有断言里漂移。
        if (isStaff()) return "STAFF";
        if (isMerchant()) return "MERCHANT";
        return "USER";
    }

    /**
     * 系统提示里告诉模型「当前账号能做什么」，仅作表达层参考，真正的闸门在工具内部。
     *
     * <p>三个分支都只描述<b>此刻真的存在</b>的能力：退款审批相关的能力等 MerchantTools
     * 落地（T13b，依赖 T14 的 refund 表）后再补。提前写进去，等于让模型对买家做出
     * 兑现不了的承诺——这正是本项目最忌讳的空承诺。
     */
    public String capabilityHint() {
        if (isStaff()) {
            return "当前账号是店员（STAFF），对话对象就是店员本人：可以查询商品、库存预警、经营概览、"
                    + "查询指定用户的订单；知识库检索还会命中内部运营文档，这些内部信息可以直接回答给店员本人。";
        }
        if (isMerchant()) {
            return "当前账号是商家（MERCHANT）：你和普通顾客一样可以查询商品、管理自己的购物袋与订单；"
                    + "知识库检索会命中内部运营文档（供应商、经营口径等），这些属于你的经营决策信息，"
                    + "可以直接回答给你本人。发货、改价、改库存属于店员（STAFF）职责，你没有这些权限；"
                    + "退款审批功能尚未开放，在工具就绪前不要向买家承诺可以处理退款。";
        }
        return "当前账号是普通顾客（USER）：只能查询公开商品信息、公开帮助文档和自己本人的订单，"
                + "不能查询其他用户的数据、经营数据或内部文档。";
    }
}
