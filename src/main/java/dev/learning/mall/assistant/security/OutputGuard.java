package dev.learning.mall.assistant.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出守卫：模型返回的文本同样不可信，必须当作「外部输入」再校验一遍。
 *
 * <p>对应风险：LLM05 输出处理不当（模型输出被下游当作可信内容）、LLM02 敏感信息泄露、
 * LLM07 系统提示泄露。命中不同级别的规则采取不同处置：
 * <ul>
 *   <li>REPLACE——整段替换为安全话术。泄密型命中不做局部涂抹：只要系统提示被引出、密钥被带出，
 *       局部脱敏往往仍会泄露结构与上下文，整段丢掉最稳。</li>
 *   <li>REDACT——去掉 HTML 标签/事件属性、脱敏 PII、超长截断。回复主体保留，破坏性内容剔除。</li>
 *   <li>ALLOW——正常回复。</li>
 * </ul>
 */
public class OutputGuard {

    public enum Action { ALLOW, REDACT, REPLACE }

    public record Verdict(Action action, String text, String ruleId, String owasp, String note) {
        public static Verdict allow(String text) { return new Verdict(Action.ALLOW, text, null, null, null); }
    }

    private record Rule(String id, String owasp, Pattern pattern) {}

    private static final String LLM02 = "LLM02";
    private static final String LLM05 = "LLM05";
    private static final String LLM07 = "LLM07";
    private static final String LLM10 = "LLM10";

    /** 系统提示里的原句。模型一旦把原句复述出来，说明提示被套走了。 */
    private static final List<Rule> LEAK_RULES = List.of(
            rule("OUT_SYSTEM_PROMPT", LLM07, "(不要凭空编造|商城现有商品请通过工具查询|"
                    + "每次推荐不超过\\s*3\\s*件商品|你是小拾，小小商城的购物助手|回答简洁友好|"
                    + "你只能把商品放进购物袋|这是你区别于|关注与主动服务|绝不能编造图片里的内容)"),
            rule("OUT_SYSTEM_PROMPT_EN", LLM07, "(?i)(my\\s+system\\s+prompt|system\\s+message\\s+is|"
                    + "my\\s+instructions\\s+are|as\\s+instructed\\s+in\\s+my\\s+system)"),
            rule("OUT_SECRET", LLM02, "\\bsk-[A-Za-z0-9_-]{8,}\\b"),
            rule("OUT_SECRET_KV", LLM02, "(?i)(api[_-]?key|secret[_-]?key|access[_-]?token|db[_-]?password)"
                    + "\\s*[:=]\\s*\\S{5,}"),
            rule("OUT_JDBC", LLM02, "(?i)jdbc:[a-z0-9]+:\\S+"),
            rule("OUT_BEARER", LLM02, "(?i)bearer\\s+[A-Za-z0-9._-]{16,}"),
            rule("OUT_INTERNAL", LLM02, "(?i)(langchain4j|api\\.deepseek\\.com|bigmodel\\.cn|"
                    + "zhipu_api_key|deepseek_api_key|mall\\.assistant|application\\.ya?ml)"));

    /** 需要剔除但不必整段替换的破坏性内容。 */
    private static final List<Rule> REDACT_RULES = List.of(
            rule("OUT_HTML_SCRIPT", LLM05, "(?is)<\\s*/?\\s*(script|iframe|object|embed|link|meta)\\b[^>]*>"),
            rule("OUT_HTML_TAG", LLM05, "(?i)</?[a-z][a-z0-9]{0,9}(\\s[^<>]{0,120})?>"),
            rule("OUT_JS_URI", LLM05, "(?i)javascript\\s*:"),
            rule("OUT_EVENT_ATTR", LLM05, "(?i)\\son[a-z]{3,12}\\s*="),
            rule("OUT_DATA_URI", LLM05, "(?i)data:text/html"));

    private static final Pattern PII_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PII_ID = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private static final String SAFE_FALLBACK =
            "抱歉，刚才那条回复触碰了安全策略，我已经把它拦下了。换个问法试试？"
                    + "比如让我按预算推荐商品、查某个商品的价格和库存，或者看看你自己的订单。";

    private final int maxOutputLength;

    public OutputGuard(int maxOutputLength) {
        this.maxOutputLength = Math.max(128, maxOutputLength);
    }

    private static Rule rule(String id, String owasp, String regex) {
        return new Rule(id, owasp, Pattern.compile(regex));
    }

    public Verdict inspect(String reply) {
        if (reply == null || reply.isBlank()) return Verdict.allow("");
        String text = reply;

        for (Rule r : LEAK_RULES) {
            if (r.pattern().matcher(text).find()) {
                return new Verdict(Action.REPLACE, SAFE_FALLBACK, r.id(), r.owasp(),
                        "回复命中泄密规则 " + r.id() + "，已整段替换");
            }
        }

        String redacted = text;
        String hitId = null;
        String hitOwasp = null;
        for (Rule r : REDACT_RULES) {
            String next = r.pattern().matcher(redacted).replaceAll(" ");
            if (!next.equals(redacted)) {
                redacted = next;
                if (hitId == null) { hitId = r.id(); hitOwasp = r.owasp(); }
            }
        }
        String masked = PII_PHONE.matcher(redacted).replaceAll("[手机号已脱敏]");
        masked = PII_ID.matcher(masked).replaceAll("[证件号已脱敏]");
        if (!masked.equals(redacted)) {
            redacted = masked;
            if (hitId == null) { hitId = "OUT_PII"; hitOwasp = LLM02; }
        }

        // 排版归一化（连续空格、首尾空白）只是清理，不是安全事件——
        // 不能因此把它记成「已脱敏」，否则审计里会出现一堆 ruleId 为空的噪音。
        String cleaned = redacted.replaceAll("[ \\t]{2,}", " ").strip();
        if (cleaned.length() > maxOutputLength) {
            cleaned = cleaned.substring(0, maxOutputLength) + "……（回复过长已截断）";
            if (hitId == null) {
                hitId = "OUT_TRUNCATE";
                hitOwasp = LLM10;
            }
        }
        if (hitId == null) {
            // 没有命中任何安全规则，放行（最多只做了一点排版清理）。
            return Verdict.allow(cleaned);
        }
        return new Verdict(Action.REDACT, cleaned, hitId, hitOwasp, "回复已按规则清洗");
    }

    /** 供测试与文档使用。 */
    public static List<String> ruleIds() {
        List<String> ids = new java.util.ArrayList<>();
        LEAK_RULES.forEach(r -> ids.add(r.id()));
        REDACT_RULES.forEach(r -> ids.add(r.id()));
        ids.add("OUT_PII");
        ids.add("OUT_TRUNCATE");
        return List.copyOf(ids);
    }

    /** 判断一段文本是否命中了任何输出规则（不改变文本）。 */
    public boolean wouldFlag(String reply) {
        if (reply == null) return false;
        return LEAK_RULES.stream().anyMatch(r -> r.pattern().matcher(reply).find());
    }
}
