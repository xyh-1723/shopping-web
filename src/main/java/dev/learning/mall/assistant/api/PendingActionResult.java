package dev.learning.mall.assistant.api;

/**
 * 确认 / 取消一条待确认动作的结果：动作最新状态 + 追加进会话的结果消息。
 * 前端收到后把消息渲染成助手气泡，让用户看到「确认之后发生了什么」。
 */
public record PendingActionResult(PendingActionView action, ChatMessageView message) {}
