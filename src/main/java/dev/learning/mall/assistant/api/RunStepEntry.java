package dev.learning.mall.assistant.api;

/**
 * 一次运行里某一步工具调用的轨迹（只读展示用）。
 *
 * <p>status 取值：
 * <ul>
 *   <li>{@code OK} 正常返回；</li>
 *   <li>{@code ERROR} 工具执行失败，已把结构化错误交回模型自纠；</li>
 *   <li>{@code DENIED} 模型请求了当前角色不可用的工具，被路由层拒绝；</li>
 *   <li>{@code DUPLICATE} 与上一轮完全相同的调用，未重复执行（结果不会变）；</li>
 *   <li>{@code TIMEOUT} 并行工具调用超时未返回。</li>
 * </ul>
 */
public record RunStepEntry(
        int stepNo,
        int round,
        String toolName,
        String argsExcerpt,
        String status,
        long latencyMs,
        int resultChars,
        boolean truncated) {
}
