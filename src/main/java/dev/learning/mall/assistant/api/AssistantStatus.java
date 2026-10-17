package dev.learning.mall.assistant.api;

import java.util.List;

/**
 * 助手状态。除了「模型是否可用」，还要把当前账号的角色与能力清单暴露给前端——
 * 让用户在提问前就看到自己能问到什么，比事后被拒友好得多。
 */
public record AssistantStatus(
        boolean enabled,
        String role,
        List<String> capabilities,
        boolean guarded,
        int rateLimitPerMinute) {}
