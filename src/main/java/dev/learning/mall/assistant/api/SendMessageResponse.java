package dev.learning.mall.assistant.api;

import java.util.List;

/**
 * 发送消息的结果：回复本体 + 本次请求被守卫怎么处置的 + 本轮是否产生了待确认动作。
 *
 * <p>decision 取值 ALLOW / MASK / BLOCK / RATE，前端据此给用户一个明确的反馈，
 * 而不是「消息发出去了但回复莫名其妙」。
 *
 * @param pendingAction 非空时，前端渲染 human-in-the-loop 确认卡片（如支付确认）
 * @param suggestions   非空时，前端把追问的候选答案渲染成可点击的快捷选项。
 *                      这是导购体验里最省事也最有效的一环：用户不想打字，只想点一下。
 */
public record SendMessageResponse(ChatMessageView reply, String decision, String notice,
                                  PendingActionView pendingAction, List<String> suggestions) {

    public SendMessageResponse(ChatMessageView reply, String decision, String notice,
                               PendingActionView pendingAction) {
        this(reply, decision, notice, pendingAction, List.of());
    }
}
