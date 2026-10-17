package dev.learning.mall.assistant.harness;

/**
 * 轮次进度回调——把「agent 此刻在做什么」从黑盒变成可见的。
 *
 * <p>在此之前，一次回答对用户来说就是一个转圈：模型在思考、工具在查询、结果在汇总，
 * 全部发生在同一个不可见的黑箱里，用户只能等到最后一次性看到答案。而 agent 的一次回答
 * 常常要跑十几秒（几轮模型调用 + 若干工具），「十几秒的白屏」和「十几秒里一直有字在动」
 * 是完全不同的体验。
 *
 * <p>本接口只负责<b>把进度暴露出来</b>，不负责怎么展示——SSE、日志、轨迹落库都是它的消费者。
 * 默认实现全部是空操作，因此 {@link AgentLoop} 不关心有没有人在听，也不会因为没人听而变慢。
 *
 * <p><b>回调可能来自多个线程</b>：一轮里互不依赖的只读工具是并行的（见 {@link AgentLoop}），
 * 所以实现必须容忍并发调用；事件顺序不保证与工具声明的顺序一致。
 */
public interface TurnListener {

    /** 什么都不做的实现。调用方只想要答案、不在意进度时用它，省掉各处判空。 */
    TurnListener NOOP = new TurnListener() {};

    /**
     * 安全地触发一次回调：任何异常都被吞掉，只留一条 debug 日志。
     *
     * <p><b>这条规则只有这一份实现。</b>进度是旁路，它的失败绝不能影响主链路——
     * 一个断开的浏览器标签页、一个写满的日志队列、一个报错的监听器，
     * 都不该让已经算出来的回答作废。以前它在执行循环里各写一遍，
     * 现在连「看图」这类循环外的耗时步骤也要用，复制第二份迟早会长出不一致的版本：
     * 一处吞异常、另一处不吞，而「进度回调让整轮失败」恰恰是最难复现的那类线上事故。
     *
     * <p>用 {@code static} 而不是默认方法，是为了让调用方写成
     * {@code TurnListener.safely(listener, () -> ...)}——读代码的人一眼看出这是防火墙，
     * 而不是一次普通的回调。
     */
    static void safely(TurnListener listener, Runnable action) {
        if (listener == null || listener == NOOP) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TurnListener.class)
                    .debug("进度回调失败（已忽略，不影响本轮结果）：{}", e.toString());
        }
    }

    /**
     * 进入某个阶段。目前取值：{@code thinking}（模型正在思考/决策）。
     * 阶段是「粒度更粗的进度」，用来覆盖那些没有工具调用的等待时间。
     */
    default void onStage(String stage) {}

    /**
     * 某个工具即将执行。
     *
     * @param toolName 工具名（方法名，如 {@code searchProducts}）
     * @param args     参数摘要（已裁剪，仅供展示，不要用于任何逻辑判断）
     */
    default void onToolStart(String toolName, String args) {}

    /**
     * 某个工具执行结束。
     *
     * @param status    见 {@code ToolRegistry.STATUS_*}：OK / ERROR / DENIED / DUPLICATE / TIMEOUT
     * @param latencyMs 执行耗时（毫秒）；短路未执行时为 0
     */
    default void onToolEnd(String toolName, String status, long latencyMs) {}

    /**
     * 是否要接收<b>逐字增量</b>。默认 false。
     *
     * <p>只有真正能把增量送到用户面前的监听器才该返回 true（SSE 会，写日志的不会）。
     * 返回 true 会让 {@link AgentLoop} 改用流式模型客户端——那是一条不同的调用路径，
     * 没必要为不需要它的消费者付这个代价：多一次连接、多了等待回调的复杂度。
     */
    default boolean consumesTokens() {
        return false;
    }

    /**
     * 模型产出的一个文本增量。可能是几个字，也可能是一句话，取决于服务端分块策略。
     *
     * <p><b>增量未必是最终答案</b>：模型可能先说一句「我帮你查一下」再发起工具调用。
     * 消费者按追加处理即可，{@code done} 携带的完整文本才是权威版本——
     * 输出守卫可能改写过它。
     *
     * @param token 文本增量，非空
     */
    default void onToken(String token) {}

    /**
     * 增量推送被中断，已发出的增量应当作废。
     *
     * <p>目前唯一的触发原因是输出守卫在增量里命中了泄密规则：流式把「先渲染、后定案」
     * 的窗口从整段缩短到一个增量，但窗口依然存在——这里就是通知消费者
     * 「刚才那些字不算数」的那一步。
     *
     * @param reason 中断原因码，如 {@code OUTPUT_GUARD}
     */
    default void onTokensStopped(String reason) {}
}
