package dev.learning.mall.assistant.security;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入守卫：把「用户输入」当作不可信数据来处理，在送给大模型之前做三件事。
 *
 * <p>1. 高危规则直接拦截（BLOCK）——注入、越狱、套取系统提示、探测凭据、越权/破坏性诉求、编码绕过。
 * 拦截时<b>不调用大模型</b>：既避免被牵着走，也省掉这次 token 成本（对应 LLM10 无限消耗）。
 *
 * <p>2. 结构清洗（MASK）——去掉零宽字符与方向控制符、HTML 注释/标签、事件属性与 javascript: 协议、
 * 模板表达式，并按开关脱敏手机号/证件号/银行卡/邮箱。清洗后<b>再查一遍</b>高危规则，
 * 防止「注释或零宽字符里夹带注入」绕过第一轮检查。
 *
 * <p>3. 放行（ALLOW）——正常购物诉求不受影响。
 *
 * <p>规则库是显式的、可读的、可测的：每条规则带编号、OWASP 映射与用户可见提示语，
 * 由 tests/security-eval.cases.json 逐条断言。规则表能覆盖已知手法，漏掉的靠
 * 「工具内部二次校验 + 输出守卫 + 审计留痕」兜底——单靠过滤器从来不是完整方案。
 */
public class InputGuard {

    public enum Action { ALLOW, MASK, BLOCK }

    /** 守卫结论。sanitized 是实际应送给模型的文本（MASK 时为清洗结果）。 */
    public record Verdict(Action action, String ruleId, String owasp, String notice, String sanitized) {
        static Verdict allow(String text) { return new Verdict(Action.ALLOW, null, null, null, text); }
    }

    private record Rule(String id, String owasp, Pattern pattern, String notice) {}

    private static final String LLM01 = "LLM01";
    private static final String LLM02 = "LLM02";
    private static final String LLM05 = "LLM05";
    private static final String LLM06 = "LLM06";
    private static final String LLM07 = "LLM07";
    private static final String LLM10 = "LLM10";

    private static final String NOTICE_RULE =
            "抱歉，这条消息看起来在尝试改变我的工作规则或读取内部信息，我不能照做。"
                    + "我可以帮你挑商品、查价格与库存、看看你自己的订单。";

    private static final List<Rule> BLOCK_RULES = List.of(
            // —————————— LLM01 直接提示词注入：覆盖 / 重置既有指令 ——————————
            rule("PI_OVERRIDE_ZH", LLM01,
                    "(忽略|无视|忘掉|忘记|抛弃|跳过|不用理会|不要理会|不再遵守)[^。！？；\\n]{0,10}"
                            + "(指令|规则|提示|设定|要求|约束|限制|说明|角色|人设)", NOTICE_RULE),
            rule("PI_OVERRIDE_EN", LLM01,
                    "(?i)\\b(ignore|disregard|forget|override|bypass|discard)\\b[^.\\n]{0,30}"
                            + "\\b(all|any|the)?\\s*(previous|prior|above|earlier|preceding)\\b[^.\\n]{0,20}"
                            + "\\b(instruction|instructions|prompt|prompts|rule|rules|command|commands|guideline|guidelines)\\b",
                    NOTICE_RULE),
            rule("PI_OVERRIDE_EN2", LLM01,
                    "(?i)\\b(forget|ignore|disregard)\\b[^.\\n]{0,20}\\b(your|the)\\b[^.\\n]{0,16}"
                            + "\\b(rules|instructions|prompt|guidelines|role)\\b", NOTICE_RULE),

            // —————————— LLM01 角色劫持 / 越狱 ——————————
            rule("PI_ROLE_HIJACK_ZH", LLM01,
                    "(扮演|假装|假设|作为|充当|成为|变成|你的新身份是)[^。！？；\\n]{0,14}"
                            + "(不受|没有|没有任何|无|不受任何|不受别人)[^。！？；\\n]{0,6}(限制|约束|规则|管控)",
                    NOTICE_RULE),
            rule("PI_JAILBREAK_ZH", LLM01,
                    "(越狱|破解模式|免(责|责)模式|开发者模式|上帝模式|神模式|管理员模式|调试模式|无限制模式)",
                    NOTICE_RULE),
            rule("PI_JAILBREAK_EN", LLM01,
                    "(?i)\\b(jailbreak|do anything now|dan mode|developer mode|god mode|unrestricted mode|without any restrictions)\\b",
                    NOTICE_RULE),
            rule("PI_ROLE_HIJACK_EN", LLM01,
                    "(?i)\\b(act as|pretend to be|roleplay as|you are now|from now on,? you)\\b[^.\\n]{0,40}"
                            + "\\b(unrestricted|unfiltered|no rules|no limits|without restrictions|without limitations|an? admin|superuser|hacker)\\b",
                    NOTICE_RULE),

            // —————————— LLM07 系统提示泄露 ——————————
            rule("SP_LEAK_ZH", LLM07,
                    "(系统|初始|原始|预设|隐藏|内部|后台)[^。！？；\\n]{0,3}(提示词|提示语|指令|消息|设定|规则|配置)",
                    NOTICE_RULE),
            rule("SP_LEAK_ZH2", LLM07,
                    "(你的|你收到的|你被(设定|赋予)的)[^。！？；\\n]{0,3}(设定|人设|角色|身份|职责|规则|提示词|指令)",
                    NOTICE_RULE),
            rule("SP_LEAK_EN", LLM07,
                    "(?i)\\b(system|initial|original|hidden|secret|internal)\\s*(prompt|message|instruction|instructions|rules)\\b",
                    NOTICE_RULE),
            rule("SP_LEAK_REPEAT", LLM07,
                    "(重复|复述|回显|打印|输出|告诉我|展示|泄露|念出)[^。！？；\\n]{0,12}"
                            + "(上面|以上|之前|刚刚|前面)[^。！？；\\n]{0,6}(文字|内容|话|提示|指令|规则)",
                    NOTICE_RULE),
            rule("SP_LEAK_REPEAT_EN", LLM07,
                    "(?i)\\b(repeat|print|show|reveal|output|echo|tell me)\\b[^.\\n]{0,20}"
                            + "\\b(text above|the above|words above|everything above|prompt above|instructions above)\\b",
                    NOTICE_RULE),

            // —————————— LLM02 凭据 / 内部配置探测 ——————————
            rule("CRED_PROBE_KEY", LLM02,
                    "(?i)(api[\\s_-]?key|apikey|secret[\\s_-]?key|access[\\s_-]?token|bearer\\s+token|私钥|密钥|口令)",
                    NOTICE_RULE),
            rule("CRED_PROBE_FILE", LLM02,
                    "(?i)(application\\.ya?ml|\\bpom\\.xml\\b|\\.env\\b|\\bjdbc:|数据库连接|连接串|datasource|"
                            + "环境变量|系统变量|配置文件)",
                    NOTICE_RULE),
            rule("CRED_PROBE_SECRET", LLM02,
                    "\\bsk-[A-Za-z0-9_-]{10,}\\b", NOTICE_RULE),
            rule("CRED_PROBE_INFRA", LLM02,
                    "(?i)(服务器|主机|内网|数据库)[^。！？；\\n]{0,4}(地址|ip|端口|账号|账户|用户名|密码|口令)",
                    NOTICE_RULE),
            rule("CRED_PROBE_MODEL", LLM02,
                    "(?i)(你(用|调用)的?是?(哪个|什么))(模型|大模型|llm)|"
                            + "\\b(deepseek|zhipu|glm-4|gpt-4|api\\.deepseek\\.com|bigmodel\\.cn)\\b",
                    NOTICE_RULE),

            // —————————— LLM06 越权 / 提权 / 破坏性诉求 ——————————
            rule("AGENCY_PRIV_ESC", LLM06,
                    "((我|咱)[^。！？；\\n]{0,4}(现在|就)?是[^。！？；\\n]{0,4}(管理员|店长|店主|老板|admin|超级管理员|客服))|"
                            + "((以|用|按)[^。！？；\\n]{0,4}(管理员|root|admin|超级管理员)[^。！？；\\n]{0,3}(身份|权限|账号))|"
                            + "((假(装|设)|谎称)[^。！？；\\n]{0,6}(你是|我是|自己是)[^。！？；\\n]{0,4}(管理员|店长|admin))|"
                            + "(提权|越权|给我[^。！？；\\n]{0,4}(管理员|最高|超级)[^。！？；\\n]{0,3}权限)",
                    "我不能凭一句话就改变权限。权限由登录账号的角色决定，店员专属的数据只有 STAFF 角色能查。"),
            rule("AGENCY_BYPASS", LLM06,
                    "(绕过|跳过|取消|关闭|破解|规避)[^。！？；\\n]{0,6}"
                            + "(权限|校验|认证|鉴权|审核|安全|限制|风控|过滤)",
                    "我不能绕过权限校验或安全限制。请用正常的购物诉求提问。"),
            rule("AGENCY_WRITE", LLM06,
                    "((删除|删掉|清空|清掉|drop|truncate|delete)[^。！？；\\n]{0,8}"
                            + "(订单|用户|商品|库存|价格|数据|数据库|表|记录))|"
                            + "((改成|改为|修改为|设置为|设成|调整成|变成)[^。！？；\\n]{0,4}(0|0\\.01|零|免费))|"
                            + "((把|将)[^。！？；\\n]{0,10}(价格|金额|总价|库存|余额)[^。！？；\\n]{0,6}(改|设置|调|删|清))|"
                            + "((订单|用户|商品|库存|价格|数据|数据库|记录|余额)\\s*"
                            + "(都|全部|一律|全|给我|直接|赶紧|立刻|马上)?\\s*(删除|删掉|清空|清掉))",
                    "我没有改价、改库存或删除数据的权限，这类操作请走商城的正式流程。"),
            rule("AGENCY_MASS_EXPORT", LLM06,
                    "((所有|全部|每一个|每位|各个|全站|全体)[^。！？；\\n]{0,4}(用户|顾客|账号|会员)?[^。！？；\\n]{0,2}"
                            + "(的)?[^。！？；\\n]{0,2}(订单|手机号|手机号码|电话|邮箱|地址|数据|信息|资料|消费记录))|"
                            + "((导出|批量导出|全部导出|打包|下载|dump|dump出)[^。！？；\\n]{0,6}(用户|订单|数据|数据库))",
                    "批量导出用户数据属于店员职责范围之外，我不能提供。你可以查看自己的订单。"),
            rule("AGENCY_OTHER_USER", LLM06,
                    "(查|看看|给我|列出|告诉我|调出|读取)[^。！？；\\n]{0,8}(别人|他人|其他(用户|人)|其他账号|别人的)[^。！？；\\n]{0,4}(订单|数据|信息|资料|手机号)",
                    "出于隐私保护，我只能查询你自己的数据。"),

            // —————————— LLM01 夹带注入：伪造角色标记、编码绕过、SQL ——————————
            rule("INJECT_FAKE_TAG", LLM01,
                    "(?m)^\\s*(#{1,4}\\s*)?(system|assistant|developer|tool|系统|管理员)\\s*[:：]",
                    NOTICE_RULE),
            rule("INJECT_FAKE_TAG2", LLM01,
                    "(?i)(<\\|?\\s*(im_start|im_end|system|sys|end)\\s*\\|?>|\\[/?(INST|SYS)\\]|<\\s*/?\\s*(system|assistant)\\s*>)",
                    NOTICE_RULE),
            rule("INJECT_ENCODED", LLM01,
                    "(?i)(base64|rot13|十六进制|hex\\s*编码|url\\s*编码|escape|解码|decode|解密|decrypt)"
                            + "[^。！？；\\n]{0,20}(指令|内容|命令|后|然后|并|再|执行|遵守|password|payload)",
                    NOTICE_RULE),
            rule("INJECT_BLOBP", LLM01,
                    "[A-Za-z0-9+/]{48,}={0,2}", NOTICE_RULE),
            rule("INJECT_SQL", LLM05,
                    "(?i)('\\s*(or|and)\\s*'?\\d+\\s*=\\s*\\d+|;\\s*(drop|delete|truncate)\\s+table|union\\s+select|\\bor\\s+1\\s*=\\s*1\\b)",
                    "检测到疑似注入语句，我不能执行数据库层面的操作。"),
            rule("INJECT_PROMPT_CHAIN", LLM01,
                    "(请|麻烦)?(把|将)?(以下|下面|接下来)[^。！？；\\n]{0,6}(内容|文字|这段话)"
                            + "[^。！？；\\n]{0,6}(当(作|成)|视为|作为)[^。！？；\\n]{0,6}(指令|命令|规则|系统提示)",
                    NOTICE_RULE));

    private static final List<MaskRule> MASK_RULES = List.of(
            new MaskRule("MASK_INVISIBLE", LLM01,
                    Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u206A-\\u206F\\uFEFF\\u00AD]"), ""),
            new MaskRule("MASK_HTML_COMMENT", LLM05, Pattern.compile("(?s)<!--.*?-->"), " "),
            new MaskRule("MASK_HTML_TAG", LLM05,
                    Pattern.compile("(?is)</?\\s*(script|iframe|style|object|embed|svg|img|link|meta|form|input)\\b[^>]*>"), " "),
            new MaskRule("MASK_HTML_TAG_GENERIC", LLM05,
                    Pattern.compile("(?i)</?[a-z][a-z0-9]{0,9}(\\s[^<>]{0,120})?>"), " "),
            new MaskRule("MASK_JS_URI", LLM05, Pattern.compile("(?i)javascript\\s*:"), ""),
            new MaskRule("MASK_EVENT_ATTR", LLM05, Pattern.compile("(?i)\\son[a-z]{3,12}\\s*="), " "),
            // 拆字绕过：汉字之间塞空格（红队变异引擎实测发现的真实漏防，已收编评测集）。
            // 清洗掉后第二轮扫描会重新命中高危规则；正常中文几乎不会逐字空格，误伤面极小。
            new MaskRule("MASK_CJK_SPACE", LLM01,
                    Pattern.compile("(?<=[\\u4e00-\\u9fff])[ \\t]+(?=[\\u4e00-\\u9fff])"), ""),
            new MaskRule("MASK_TEMPLATE_EXPR", LLM01,
                    Pattern.compile("(\\$\\{[^}]{0,80}\\}|\\{\\{[^}]{0,80}\\}\\}|#\\{[^}]{0,80}\\})"), " "),
            new MaskRule("MASK_PII_PHONE", LLM02, Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "[手机号已脱敏]"),
            new MaskRule("MASK_PII_ID", LLM02, Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "[证件号已脱敏]"),
            new MaskRule("MASK_PII_EMAIL", LLM02,
                    Pattern.compile("[\\w.+-]{1,64}@[\\w-]{1,63}\\.[A-Za-z]{2,10}"), "[邮箱已脱敏]"));

    /** 灌水判定：同一字符连续 50 次以上，或某个短词重复 60 次以上。 */
    private static final Pattern FLOOD_REPEAT_CHAR = Pattern.compile("(.)\\1{49,}", Pattern.DOTALL);

    private record MaskRule(String id, String owasp, Pattern pattern, String replacement) {}

    private final int maxInputLength;
    private final boolean maskPii;

    public InputGuard(int maxInputLength, boolean maskPii) {
        this.maxInputLength = Math.max(64, maxInputLength);
        this.maskPii = maskPii;
    }

    private static Rule rule(String id, String owasp, String regex, String notice) {
        return new Rule(id, owasp, Pattern.compile(regex), notice);
    }

    public Verdict inspect(String raw) {
        if (raw == null || raw.isBlank()) return Verdict.allow("");
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC).strip();

        // 第一轮：未清洗前就查高危规则。攻击者最常见的写法是「原始文本直给」。
        Rule hit = firstHit(text);
        if (hit != null) return blocked(hit, text);

        // 资源类限制：超长与灌水先挡掉，避免把无意义的长文本送进模型。
        if (text.length() > maxInputLength) {
            return new Verdict(Action.BLOCK, "LIMIT_LENGTH", LLM10,
                    "这条消息太长了（上限 " + maxInputLength + " 字），请精简后再发给我。", text);
        }
        if (isFlood(text)) {
            return new Verdict(Action.BLOCK, "LIMIT_FLOOD", LLM10,
                    "这条消息看起来是重复刷屏，我先不处理了。请把真正想问的问题说清楚。", text);
        }

        // 第二轮：结构清洗，然后复查一遍。夹带在注释/零宽字符里的注入会在这里暴露。
        Sanitized sanitized = sanitize(text);
        if (!sanitized.text().equals(text)) {
            Rule buried = firstHit(sanitized.text());
            if (buried != null) return blocked(buried, sanitized.text());
            return new Verdict(Action.MASK, sanitized.ruleId(), sanitized.owasp(),
                    "已为你清理掉消息里的隐藏字符或标记，并按隐私规范脱敏了敏感信息。", sanitized.text());
        }
        return Verdict.allow(text);
    }

    /** 供测试与文档使用：暴露规则清单（编号、OWASP 映射、用户可见提示）。 */
    public static List<String> ruleIds() {
        List<String> ids = new ArrayList<>();
        BLOCK_RULES.forEach(r -> ids.add(r.id()));
        MASK_RULES.forEach(r -> ids.add(r.id()));
        ids.add("LIMIT_LENGTH");
        ids.add("LIMIT_FLOOD");
        return List.copyOf(ids);
    }

    private Rule firstHit(String text) {
        for (Rule r : BLOCK_RULES) {
            if (r.pattern().matcher(text).find()) return r;
        }
        return null;
    }

    private Verdict blocked(Rule rule, String text) {
        return new Verdict(Action.BLOCK, rule.id(), rule.owasp(), rule.notice(), text);
    }

    private boolean isFlood(String text) {
        if (FLOOD_REPEAT_CHAR.matcher(text).find()) return true;
        if (text.length() < 120) return false;
        // 重复片段检测：抓取前 4 个字符，看它是否占据了整条消息的大半。
        String probe = text.substring(0, Math.min(4, text.length()));
        int count = 0;
        Matcher m = Pattern.compile(Pattern.quote(probe)).matcher(text);
        while (m.find()) count++;
        return count * probe.length() > text.length() * 0.8;
    }

    private record Sanitized(String text, String ruleId, String owasp) {}

    private Sanitized sanitize(String text) {
        String current = text;
        String firstId = null;
        String firstOwasp = null;
        for (MaskRule r : MASK_RULES) {
            // PII 规则受开关控制，结构清洗规则始终生效。
            if (r.id().startsWith("MASK_PII") && !maskPii) continue;
            String next = r.pattern().matcher(current).replaceAll(Matcher.quoteReplacement(r.replacement()));
            if (!next.equals(current)) {
                current = next;
                if (firstId == null) {
                    firstId = r.id();
                    firstOwasp = r.owasp();
                }
            }
        }
        current = current.replaceAll("[ \\t]{2,}", " ").strip();
        return new Sanitized(current, firstId == null ? "MASK_NORMALIZE" : firstId,
                firstOwasp == null ? LLM01 : firstOwasp);
    }
}
