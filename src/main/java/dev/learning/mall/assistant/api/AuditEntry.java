package dev.learning.mall.assistant.api;

import java.time.LocalDateTime;

/**
 * 一条安全审计记录（只读展示用）。stage 取值 INPUT / OUTPUT / TOOL / RATE，
 * action 取值 ALLOW / MASK / BLOCK / REDACT / REPLACE / DENY。
 */
public record AuditEntry(
        String id,
        String username,
        String conversationId,
        String stage,
        String action,
        String ruleId,
        String owasp,
        String excerpt,
        LocalDateTime createdAt) {
}
