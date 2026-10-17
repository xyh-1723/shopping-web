package dev.learning.mall.assistant.security;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 安全审计。规则会漏，漏了必须有痕迹：每次拦截、脱敏、越权拒绝、限流都落一条记录。
 * 这不只是「留档」，更是调优规则的输入——从真实命中里找误报与漏网。
 */
@Component
public class AssistantAudit {

    public static final String STAGE_INPUT = "INPUT";
    public static final String STAGE_OUTPUT = "OUTPUT";
    public static final String STAGE_TOOL = "TOOL";
    public static final String STAGE_RATE = "RATE";
    /** RAG 检索阶段：租户过滤拒绝、投毒片段拦截与清洗。 */
    public static final String STAGE_RAG = "RAG";

    private final JdbcClient jdbc;

    public AssistantAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String username, String conversationId, String stage, String action,
                       String ruleId, String owasp, String excerpt) {
        jdbc.sql("INSERT INTO assistant_audit(id,username,conversation_id,stage,action,rule_id,owasp,excerpt) "
                        + "VALUES(:id,:user,:conv,:stage,:action,:rule,:owasp,:excerpt)")
                .param("id", UUID.randomUUID().toString())
                .param("user", username)
                .param("conv", conversationId)
                .param("stage", stage)
                .param("action", action)
                .param("rule", ruleId)
                .param("owasp", owasp)
                .param("excerpt", clip(excerpt))
                .update();
    }

    /** 最近的安全事件，倒序。供店员在助手页的安全审计面板查看。 */
    public List<dev.learning.mall.assistant.api.AuditEntry> recent(int limit) {
        return jdbc.sql("SELECT id,username,conversation_id,stage,action,rule_id,owasp,excerpt,created_at "
                        + "FROM assistant_audit ORDER BY seq DESC LIMIT :limit")
                .param("limit", Math.clamp(limit, 1, 200))
                .query(dev.learning.mall.assistant.api.AuditEntry.class)
                .list();
    }

    public long countByAction(String action) {
        Long n = jdbc.sql("SELECT COUNT(*) FROM assistant_audit WHERE action = :action")
                .param("action", action).query(Long.class).single();
        return n == null ? 0 : n;
    }

    private static String clip(String text) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
