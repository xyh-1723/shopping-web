package dev.learning.mall.assistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 记忆工具：让助手「下次还记得你」。
 *
 * <p>这是淘宝级导购最容易被低估的一条能力：一个每次都从零开始问「你预算多少」的助手，
 * 用户第三次就不想用了。而记忆的实现难点不在读写，在<b>边界</b>：
 * <ul>
 *   <li><b>键白名单</b>（{@link MemoryKeys}）：模型写不进任意结构，只能填购物偏好；</li>
 *   <li><b>值过输入守卫</b>：写进库之前先按对待「不可信输入」的方式处理一遍。
 *       记忆是持久化的注入通道——今天写进去一句话，会在之后每一次对话里被读进提示词。
 *       写入时拦一次、读取时再拦一次（见 {@link AssistantMemoryService}），双保险；</li>
 *   <li><b>有上限、会过期</b>：陈旧偏好比没有偏好更有害。</li>
 * </ul>
 *
 * <p>校验与写入的实现全部在 {@link MemoryWriter} 里，这里只负责「把它翻译成模型读得懂的话」。
 * 这样做的原因很实际：记忆有第二个写入者（轮后自动抽取），两份校验一旦分叉，
 * 分叉处就是一条静默的绕过通道。
 *
 * <p>用户身份由 {@code @ToolMemoryId} 注入，模型改不了——
 * 不存在「记住 admin 的偏好」这种调用。
 */
@Component
public class MemoryTools {

    private static final long TTL_DAYS = 90;
    private static final double CONFIDENCE = 0.80;

    /** 工具描述里直接列出可用键——模型不知道有哪些键，就永远不会用对。 */
    private static final String KEY_LIST_HINT = "budget（预算）、price_sensitivity（价格取向）、"
            + "prefer_category（偏好品类）、avoid（明确不要的东西）、scenario（购买场景）、"
            + "recipient（买给谁）、style（风格）、color（颜色）、size（规格）、"
            + "brand（品牌）、delivery（配送偏好）；";

    private final MemoryStore store;
    private final MemoryWriter writer;
    private final ObjectMapper json;

    public MemoryTools(MemoryStore store, MemoryWriter writer, ObjectMapper json) {
        this.store = store;
        this.writer = writer;
        this.json = json;
    }

    @Tool("记住当前用户明确表达过的购物偏好，之后每次对话都会自动带上。"
            + "只在用户明确说出自己的偏好时使用（例如「我预算 200 以内」「我不喜欢保温杯」「我买给上小学的女儿」）；"
            + "用户只是随口问一次商品，不要写入记忆。"
            + "参数：key 必须是以下之一——" + KEY_LIST_HINT
            + "value 是简短的偏好内容（不超过 60 字，不要写收货地址、手机号等个人信息）。"
            + "同一 key 再次写入会覆盖旧值。返回 JSON。")
    public String rememberPreference(@ToolMemoryId String username, String key, String value) {
        MemoryWriter.Outcome outcome = writer.write(username, key, value,
                MemoryWriter.SOURCE_MODEL, CONFIDENCE, TTL_DAYS, "MEMORY_");
        if (!outcome.written()) {
            // 校验逻辑与自动抽取共用一份实现（见 MemoryWriter），这里只负责把它翻译成
            // 模型能读懂的话——同一个原因，对人和对模型的说法不该一样。
            return switch (outcome.code()) {
                case "INVALID_KEY" -> error("INVALID_KEY", "key 必须是允许的类型之一：" + MemoryKeys.describe());
                case "EMPTY_VALUE" -> error("EMPTY_VALUE", "偏好内容不能为空");
                case "BLOCKED" -> error("BLOCKED", "这条内容包含可疑指令，已拒绝写入记忆。"
                        + "请改用正常的偏好描述，或直接回答用户。");
                default -> error(outcome.code(), "未能写入这条偏好");
            };
        }
        ObjectNode node = json.createObjectNode();
        node.put("status", "REMEMBERED");
        node.put("key", outcome.key());
        node.put("value", outcome.value());
        node.put("note", "已记住，之后 " + TTL_DAYS + " 天内的对话都会参考。可在回复里简短确认一句。");
        return node.toString();
    }

    @Tool("读取系统已记住的当前用户购物偏好。当你需要推荐商品、判断预算或选择品类时先调用它，"
            + "不要重复询问用户已经说过的偏好。返回 JSON 数组，含 key、label、value、source、updatedAt；"
            + "source 为 AUTO 表示这是系统根据以往对话推断的（可能不准，可向用户确认后修正），"
            + "为 MODEL 表示用户明确说过。没有任何记录时返回空数组。")
    public String recallPreferences(@ToolMemoryId String username) {
        List<MemoryStore.Entry> entries = store.active(username);
        ArrayNode array = json.createArrayNode();
        for (MemoryStore.Entry entry : entries) {
            ObjectNode node = json.createObjectNode();
            node.put("key", entry.key());
            node.put("label", MemoryKeys.label(entry.key()));
            node.put("value", entry.value());
            node.put("source", entry.source());
            node.put("confidence", entry.confidence());
            node.put("updatedAt", String.valueOf(entry.updatedAt()));
            array.add(node);
        }
        return array.toString();
    }

    @Tool("删除某一条已记住的偏好。当用户说「别记了」「我预算变了」「忘掉我的预算」等，"
            + "或明确纠正之前的偏好时使用。参数：key 为要删除的偏好类型；"
            + "传 ALL 表示清空该用户的全部记忆。返回 JSON。")
    public String forgetPreference(@ToolMemoryId String username, String key) {
        if (key == null || key.isBlank()) return error("INVALID_KEY", "请提供要删除的偏好类型");
        String normalized = MemoryKeys.normalize(key);
        if ("ALL".equalsIgnoreCase(normalized)) {
            store.forgetAll(username);
            ObjectNode node = json.createObjectNode();
            node.put("status", "FORGOTTEN");
            node.put("key", "ALL");
            node.put("note", "已清空这位用户的全部偏好记忆。");
            return node.toString();
        }
        if (!MemoryKeys.isAllowed(normalized)) {
            return error("INVALID_KEY", "key 必须是允许的类型之一，或 ALL：" + MemoryKeys.describe());
        }
        store.forget(username, normalized);
        ObjectNode node = json.createObjectNode();
        node.put("status", "FORGOTTEN");
        node.put("key", normalized);
        node.put("note", "已删除这条偏好记忆。");
        return node.toString();
    }

    private String error(String code, String message) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        return node.toString();
    }
}
