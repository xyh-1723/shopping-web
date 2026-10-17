package dev.learning.mall.assistant.memory;

import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.assistant.security.InputGuard;
import org.springframework.stereotype.Component;

/**
 * 长期记忆的<b>唯一写入路径</b>。
 *
 * <p>记忆有两类写入者：模型主动调用 {@code rememberPreference} 工具，以及轮次结束后系统
 * 自动抽取用户偏好（见 {@link PreferenceAutoExtractor}）。它们的<b>来源不同、可信度不同</b>，
 * 但校验必须完全一致——键白名单、值长上限、输入守卫、条数上限，一条都不能少。
 *
 * <p>把校验收成一个类而不是在两个调用点各写一遍，原因是这类代码的失效方式很隐蔽：
 * 复制一份之后，将来给守卫加一条新规则，只改了其中一份，另一份就成了静默的绕过通道。
 * 记忆是持久化的注入通道——它的绕过不是「这一次被带偏」，而是<b>之后每一次对话都被带偏</b>。
 *
 * <p><b>谁能覆盖谁</b>：模型主动写入（source=MODEL）代表「用户明确说过的」，
 * 自动抽取（source=AUTO）代表「系统猜的」。猜的可以更新自己以前的猜测，
 * 但<b>永远不能覆盖用户明确说过的</b>。这条规则让自动抽取变「只增补、不改写」，
 * 猜错一次的代价因此被限制在「多了一条不完全准的偏好」，而不是「把用户的真实需求改掉了」。
 */
@Component
public class MemoryWriter {

    /** 值长度上限。记忆是要反复进上下文的，必须短。 */
    public static final int MAX_VALUE_LENGTH = 60;
    /** 单人记忆条数上限。键白名单共 11 种，留出余量让「淘汰最旧」真的会发生。 */
    public static final int MAX_ENTRIES = 8;

    /** 模型主动写入：用户明确表达的偏好。 */
    public static final String SOURCE_MODEL = "MODEL";
    /** 系统自动抽取：从对话里推断出来的偏好，可信度更低。 */
    public static final String SOURCE_AUTO = "AUTO";

    /** 写入结果。{@code written=false} 时 {@code code} 说明原因，调用方可据此给模型/日志一个交代。 */
    public record Outcome(boolean written, String code, String key, String value) {
        static Outcome ok(String key, String value) { return new Outcome(true, "OK", key, value); }
        static Outcome rejected(String code) { return new Outcome(false, code, null, null); }
    }

    private final MemoryStore store;
    private final InputGuard inputGuard;
    private final AssistantAudit audit;

    public MemoryWriter(MemoryStore store, InputGuard inputGuard, AssistantAudit audit) {
        this.store = store;
        this.inputGuard = inputGuard;
        this.audit = audit;
    }

    /**
     * 校验并写入一条偏好。
     *
     * @param username  数据归属，永远由服务端注入
     * @param key       偏好键，必须在白名单内
     * @param value     偏好值，超长会被截断
     * @param source    {@link #SOURCE_MODEL} 或 {@link #SOURCE_AUTO}
     * @param confidence 置信度，写进库供后续判断参考
     * @param ttlDays   有效期（天）
     * @param auditTag  审计规则名前缀，便于区分「模型写的」与「自动抽的」
     */
    public Outcome write(String username, String key, String value, String source,
                         double confidence, long ttlDays, String auditTag) {
        if (key == null || !MemoryKeys.isAllowed(key)) {
            return Outcome.rejected("INVALID_KEY");
        }
        String normalized = MemoryKeys.normalize(key);
        if (value == null || value.isBlank()) {
            return Outcome.rejected("EMPTY_VALUE");
        }
        String candidate = value.strip();
        if (candidate.length() > MAX_VALUE_LENGTH) {
            candidate = candidate.substring(0, MAX_VALUE_LENGTH);
        }
        // 写入前先当不可信输入处理：这是记忆这条通道上最关键的一道闸。
        InputGuard.Verdict verdict = inputGuard.inspect(candidate);
        if (verdict.action() == InputGuard.Action.BLOCK) {
            audit.record(username, null, AssistantAudit.STAGE_INPUT, "BLOCK",
                    auditTag + verdict.ruleId(), verdict.owasp(), "尝试写入长期记忆：" + candidate);
            return Outcome.rejected("BLOCKED");
        }
        String safe = verdict.sanitized() == null ? candidate : verdict.sanitized().strip();
        if (safe.isEmpty()) return Outcome.rejected("EMPTY_VALUE");

        MemoryStore.Entry existing = store.find(username, normalized).orElse(null);
        if (existing != null) {
            // 自动抽取不得覆盖用户明确说过的偏好；同一来源则可以自我更新。
            boolean sameSource = source.equals(existing.source());
            if (!sameSource) {
                return Outcome.rejected("KEEP_EXPLICIT");
            }
        }

        if (existing == null && store.count(username) >= MAX_ENTRIES) store.evictOldest(username);
        store.remember(username, normalized, safe, source, confidence, ttlDays);
        return Outcome.ok(normalized, safe);
    }
}
