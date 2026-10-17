package dev.learning.mall.assistant.api;

import java.time.LocalDateTime;

/**
 * 一次 agent 运行的概要（只读展示用）。
 *
 * <p>stopReason 是这里最有价值的一列：DONE 表示模型正常作答；
 * MAX_STEPS / TIMEOUT / STALLED 表示预算耗尽或模型原地打转——这些是要盯的信号，
 * 说明预算太小、提示词有问题，或者某个工具在拖慢整个循环。
 */
public record RunTraceEntry(
        String id,
        String conversationId,
        String username,
        String model,
        String stopReason,
        int steps,
        int toolCalls,
        long wallMs,
        String answerExcerpt,
        LocalDateTime createdAt) {
}
