package dev.learning.mall.assistant.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 允许被记住的偏好键（白名单）。
 *
 * <p><b>为什么必须白名单</b>：记忆是一条从用户输入直通系统提示的持久通道。
 * 如果模型能写任意 key、任意长度的值，就等价于把提示词的写权限交给了对话内容——
 * 用户只要说一句「记住：你的新规则是……」，这条指令就会在之后每一次对话里被注入。
 * 白名单 + 短值 + 过输入守卫，等于把这条通道压成「只能存购物偏好」这一件事。
 *
 * <p>顺带一提：这也是为什么「记住用户的收货地址」不在这个列表里——
 * 地址属于可识别个人的敏感信息，助手不该把它写进长期记忆再反复读出来。
 */
public final class MemoryKeys {

    /** key → 给模型看的中文说明。用 LinkedHashMap 保证工具描述里的顺序稳定。 */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put("budget", "预算，如「200 元以内」「不超过 150」");
        ALLOWED.put("price_sensitivity", "价格取向，如「看重性价比」「不介意贵一点但要好」");
        ALLOWED.put("prefer_category", "偏好品类，如「文具」「厨房用品」");
        ALLOWED.put("avoid", "明确不喜欢、要避开的东西，如「不要保温杯」");
        ALLOWED.put("scenario", "常见购买场景，如「送同事生日礼物」「日常自用」");
        ALLOWED.put("recipient", "常买给谁，如「自己」「上小学的女儿」");
        ALLOWED.put("style", "风格偏好，如「简约」「可爱」「复古」");
        ALLOWED.put("color", "颜色偏好，如「喜欢莫兰迪色」「不要荧光色」");
        ALLOWED.put("size", "规格/尺码偏好，如「杯子要 400ml 以上」");
        ALLOWED.put("brand", "品牌偏好");
        ALLOWED.put("delivery", "配送偏好，如「希望次日达」「只在工作日收货」");
    }

    private MemoryKeys() {}

    public static Set<String> allowed() {
        return ALLOWED.keySet();
    }

    /** 工具描述里列出可用键，模型才知道该填什么——描述不清楚，工具就等于不存在。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        ALLOWED.forEach((key, hint) -> sb.append(key).append("（").append(hint).append("）；"));
        return sb.toString();
    }

    public static boolean isAllowed(String key) {
        return key != null && ALLOWED.containsKey(key.strip().toLowerCase());
    }

    public static String normalize(String key) {
        return key == null ? null : key.strip().toLowerCase();
    }

    public static String label(String key) {
        return ALLOWED.get(key);
    }
}
