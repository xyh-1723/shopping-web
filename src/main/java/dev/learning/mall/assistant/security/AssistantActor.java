package dev.learning.mall.assistant.security;

import java.util.Set;

/**
 * 助手视角下的「调用者身份」。权限判定的唯一依据是这里携带的角色集合，
 * 绝不依据自然语言（用户自称管理员、模型说可以，都不算数）。
 */
public record AssistantActor(String username, Set<String> roles) {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_STAFF = "ROLE_STAFF";

    public boolean isStaff() {
        return roles.contains(ROLE_STAFF);
    }

    /** 用于系统提示与前端徽标的角色名。 */
    public String roleName() {
        return isStaff() ? "STAFF" : "USER";
    }

    /** 系统提示里告诉模型「当前账号能做什么」，仅作表达层参考，真正的闸门在工具内部。 */
    public String capabilityHint() {
        return isStaff()
                ? "当前账号是店员（STAFF），对话对象就是店员本人：可以查询商品、库存预警、经营概览、"
                  + "查询指定用户的订单；知识库检索还会命中内部运营文档，这些内部信息可以直接回答给店员本人。"
                : "当前账号是普通顾客（USER）：只能查询公开商品信息、公开帮助文档和自己本人的订单，"
                  + "不能查询其他用户的数据、经营数据或内部文档。";
    }
}
