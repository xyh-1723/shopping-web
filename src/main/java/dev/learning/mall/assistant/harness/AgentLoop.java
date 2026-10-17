package dev.learning.mall.assistant.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.ToolRegistry;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.assistant.security.OutputGuard;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 执行循环——整个 harness 的核心。
 *
 * <p>Anthropic 对 agent 的定义很朴素：「语言模型在循环里调用工具，根据环境反馈决定下一步」。
 * 模型只负责「每一步的推理」，其余全是常规软件工程：这一步能看到什么上下文、允许调哪些工具、
 * 工具失败怎么办、什么时候算做完了、以及怎么把这一切记下来。这些东西就是 harness，
 * 也是可靠性真正被赢下来的地方。
 *
 * <p>本类负责把原先内联在业务 Service 里的工具循环抽出来，显式具备 harness 该有的五件事：
 *
 * <ol>
 *   <li><b>有界循环</b>：步数预算 + 墙钟超时，到点就停并给诚实的兜底答复，绝不空转
 *       （research 里反复强调：demo 级 agent 烧钱、死循环，根因就是没有停止条件）。</li>
 *   <li><b>进度判定（停滞检测）</b>：同一工具 + 同一参数重复出现时，不再重复执行
 *       （结果不会变），先注入一条纠偏提示；再犯就停下来。</li>
 *   <li><b>并行工具调用</b>：同一轮内互不依赖的只读工具 fan-out 执行，降低多工具回合的延迟；
 *       效果型工具（{@link Effectful}）留在调用线程，见该注解的说明。</li>
 *   <li><b>上下文预算</b>：工具结果进模型前裁剪。单个工具吐出几万字符的日志会吃掉整个窗口，
 *       而且几乎不贡献信息——这是上下文预算最常见的破口。</li>
 *   <li><b>轨迹</b>：每一步的（工具、参数摘要、状态、耗时、结果大小）都返回给调用方落库，
 *       让「这次回答是怎么产生的」可复盘，而不是只能重掷骰子。</li>
 * </ol>
 *
 * <p>退出原因（{@link Outcome#stopReason()}）永远显式返回。调用方据此决定文案与是否需要告警，
 * 而不是靠猜「模型是不是答完了」。
 */
@Component
public class AgentLoop {

    /** 正常结束：模型给出了最终文本。 */
    public static final String STOP_DONE = "DONE";
    /** 步数预算耗尽。 */
    public static final String STOP_MAX_STEPS = "MAX_STEPS";
    /** 墙钟预算耗尽。 */
    public static final String STOP_TIMEOUT = "TIMEOUT";
    /** 同一调用原地打转，已提示仍然重复，主动停下。 */
    public static final String STOP_STALLED = "STALLED";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_DUPLICATE = "DUPLICATE";
    public static final String STATUS_TIMEOUT = "TIMEOUT";

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    /** 停滞纠偏提示：只在「同一调用重复出现」时注入一次。 */
    private static final String STALL_HINT = """
            系统提醒（不是用户消息）：你正在用完全相同的参数反复调用同一个工具，结果不会改变。
            请立刻基于已经拿到的结果作答；如果信息确实不足，就如实告诉用户你还缺什么，
            不要再用相同参数调用同一个工具。""";

    /**
     * 一次运行的预算。全部可配置，不写死在代码里——预算是运营参数，会随模型、成本和场景调整。
     *
     * @param maxSteps            最多几轮「模型 → 工具」
     * @param timeoutMs           整轮墙钟上限
     * @param maxToolResultChars  单个工具结果进模型前的字符上限
     * @param maxSameToolRepeat   同一调用允许重复出现的次数，超过即视为停滞
     */
    public record Budget(int maxSteps, long timeoutMs, int maxToolResultChars, int maxSameToolRepeat) {}

    /** 轨迹里的一步。没有工具调用的最终作答不产生 step。 */
    public record Step(int round, String toolName, String args, String status,
                       long latencyMs, int resultChars, boolean truncated) {}

    /**
     * 一次运行的结果。
     *
     * @param text       模型产出的最终文本；为 null 表示预算耗尽、没有正常作答
     * @param fallback   预算耗尽时的诚实兜底文案（harness 生成的可信文本，非模型产出）
     * @param stopReason 见 STOP_* 常量
     * @param steps      工具调用轨迹
     * @param toolOutputs 工具原始产出（未裁剪），供上层提取商品卡片等结构化数据
     * @param toolsUsed  本次用到过的工具名（去重、保序）
     * @param wallMs     总耗时
     */
    public record Outcome(String text, String fallback, String stopReason, List<Step> steps,
                          List<String> toolOutputs, List<String> toolsUsed, long wallMs) {

        /** 是否存在模型产出（决定要不要过输出守卫：harness 自己的文案是可信内容）。 */
        public boolean hasModelText() {
            return text != null;
        }

        /** 对外答复：优先模型产出，其次诚实兜底。 */
        public String answer() {
            return text != null ? text : fallback;
        }

        public int toolCalls() {
            return steps.size();
        }
    }

    /** 单次工具调用的内部结果。 */
    private record ToolRun(String raw, String forModel, String status, long latencyMs, boolean truncated) {
        static ToolRun verbatim(String text, String status) {
            return new ToolRun(text, text, status, 0L, false);
        }
    }

    private final ToolRegistry toolRegistry;
    private final ObjectMapper json;
    private final AssistantAudit audit;
    /**
     * 流式模型客户端。仅在监听者明确要逐字增量时（{@link TurnListener#consumesTokens()}）
     * 才会被使用；为空则退化为同步调用——同步端点、定时任务、演示模式都不受影响。
     */
    private final Optional<StreamingChatModel> streamingModel;
    /** 用于对<b>增量</b>做泄露检查，见 {@link TokenStreamer}。 */
    private final OutputGuard outputGuard;
    private final ExecutorService workers;
    /**
     * <b>全局</b>并行预算（不是每请求的）。多个用户的轮次会同时争抢这几个名额，
     * 拿不到就退化成顺序执行——见 {@link #invokeAll} 的说明。
     */
    private final Semaphore parallelPermits;

    public AgentLoop(ToolRegistry toolRegistry, ObjectMapper json, AssistantAudit audit,
                     AssistantProperties properties, OutputGuard outputGuard,
                     Optional<StreamingChatModel> streamingModel) {
        this.toolRegistry = toolRegistry;
        this.json = json;
        this.audit = audit;
        this.outputGuard = outputGuard;
        this.streamingModel = streamingModel == null ? Optional.empty() : streamingModel;
        int permits = Math.max(1, properties.getHarness().getMaxParallelTools());
        this.parallelPermits = new Semaphore(permits, true);
        AtomicInteger n = new AtomicInteger();
        // 平台线程池而非虚拟线程：工具里有 JDBC 与 KnowledgeService 的 synchronized 初始化，
        // 在 synchronized 块里阻塞会钉住载体线程（pinning），固定小池更可预期。
        // 池大小 = 许可数：先拿到许可才会提交任务，所以提交时必有空闲线程，池不会排队。
        this.workers = Executors.newFixedThreadPool(permits, r -> {
            Thread t = new Thread(r, "agent-tool-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    /**
     * 跑一轮 agent 循环。会就地修改传入的 {@code context}（追加模型消息与工具结果），
     * 调用方保留这个列表即可拿到完整轨迹。
     */
    public Outcome run(ChatModel model, List<ChatMessage> context, ToolRegistry.Bundle bundle,
                       AssistantActor actor, Budget budget) {
        return run(model, context, bundle, actor, budget, TurnListener.NOOP);
    }

    /**
     * 带进度回调的版本。{@code listener} 会在「模型思考」与「每个工具开始/结束」时被调用，
     * 供上层把进度实时推给用户（见 {@link TurnListener}）。
     *
     * <p>回调是<b>尽力而为</b>的：进度推不出去永远不该影响这次回答本身，
     * 所以监听器的异常在 {@link #notifySafely} 里被吞掉——一个断开的 SSE 连接
     * 不应该让用户的这一轮对话失败。
     */
    public Outcome run(ChatModel model, List<ChatMessage> context, ToolRegistry.Bundle bundle,
                       AssistantActor actor, Budget budget, TurnListener listener) {
        TurnListener progress = listener == null ? TurnListener.NOOP : listener;
        List<Step> steps = new ArrayList<>();
        List<String> toolOutputs = new ArrayList<>();
        LinkedHashSet<String> toolsUsed = new LinkedHashSet<>();
        Map<String, Integer> signatures = new HashMap<>();
        boolean warnedStall = false;
        String stopReason = STOP_MAX_STEPS;
        String text = null;
        long start = System.nanoTime();
        int maxSteps = Math.max(1, budget.maxSteps());

        for (int round = 0; round < maxSteps; round++) {
            if (elapsedMs(start) > budget.timeoutMs()) {
                stopReason = STOP_TIMEOUT;
                break;
            }

            // 进度：模型思考本身没有任何工具事件，用户在这段时间只能干等，
            // 因此每一轮开始前先推一条「思考中」。这是覆盖「长静默」的唯一一段进度。
            notifySafely(progress, () -> progress.onStage("thinking"));

            ChatResponse response = callModel(model, context, bundle, budget, progress, start);
            AiMessage aiMessage = response.aiMessage();
            context.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                text = aiMessage.text() == null ? "" : aiMessage.text();
                stopReason = STOP_DONE;
                break;
            }

            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            // —— 进度判定：这批调用里有没有「和之前一模一样」的？ ——
            boolean[] duplicate = new boolean[requests.size()];
            boolean repeated = false;
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest req = requests.get(i);
                String sig = req.name() + "|" + normalize(req.arguments());
                int seen = signatures.merge(sig, 1, Integer::sum);
                duplicate[i] = seen > 1;
                repeated |= duplicate[i];
            }
            if (repeated && warnedStall) {
                // 提示过一次还是原地打转：再循环下去只会烧预算，主动停下并如实说明。
                stopReason = STOP_STALLED;
                break;
            }

            List<ToolRun> runs = invokeAll(bundle, requests, actor, budget, duplicate, progress);
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest req = requests.get(i);
                ToolRun run = runs.get(i);
                // 注意：必须为每个 tool_call 都补上结果消息，否则下一次模型调用会被
                // 接口判为「工具调用没有对应结果」而直接报错——所以重复调用也要回一条结果，
                // 只是那条结果是「不再执行」的说明。
                context.add(ToolExecutionResultMessage.from(req, run.forModel()));
                toolOutputs.add(run.raw());
                toolsUsed.add(req.name());
                steps.add(new Step(round + 1, req.name(), clip(req.arguments(), 200), run.status(),
                        run.latencyMs(), run.raw().length(), run.truncated()));
            }
            if (repeated) {
                warnedStall = true;
                context.add(SystemMessage.from(STALL_HINT));
            }
        }

        long wall = elapsedMs(start);
        if (stopReason.equals(STOP_DONE)) {
            log.info("agent 循环结束 stop={} rounds={} tools={} wallMs={}", stopReason, steps.size(), toolsUsed, wall);
        } else {
            // 非正常结束是要盯的信号：模型没答完，要么预算太小，要么工具/提示有问题。
            log.warn("agent 循环未正常结束 stop={} steps={} tools={} wallMs={}", stopReason, steps.size(), toolsUsed, wall);
            audit.record(actor.username(), null, AssistantAudit.STAGE_TOOL, "DENY",
                    "AGENT_" + stopReason, "LLM06",
                    "执行循环未正常结束：" + stopReason + "，已调用工具 " + toolsUsed);
        }
        return new Outcome(text, fallbackFor(stopReason, steps, bundle), stopReason,
                List.copyOf(steps), List.copyOf(toolOutputs), List.copyOf(toolsUsed), wall);
    }

    /**
     * 调一次模型。默认同步；当监听者要逐字增量且流式客户端可用时走<b>流式</b>。
     *
     * <p>两条路径返回的东西完全一致（同一个 {@link ChatResponse}），所以后面的
     * 工具执行、停滞判定、落库一行都不用区分——流式只是「怎么把文本送出去」，
     * 不是另一条业务链路。这一点和 SSE 端点复用 {@code sendDetailed} 是同一个道理。
     */
    private ChatResponse callModel(ChatModel model, List<ChatMessage> context, ToolRegistry.Bundle bundle,
                                   Budget budget, TurnListener progress, long startNanos) {
        ChatRequest request = ChatRequest.builder()
                .messages(context)
                .toolSpecifications(bundle.specs())
                .build();
        boolean streamTokens = progress.consumesTokens() && streamingModel.isPresent();
        if (!streamTokens) {
            return model.chat(request);
        }

        // 流式客户端是回调式的：chat() 立刻返回，结果在另一个线程上给。
        // 这里用闩把它拉回同步——执行循环本身就是顺序的（拿不到这一步的决定就无法决定下一步），
        // 强改成异步只会让循环的状态机复杂一倍，换不来任何好处。
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        streamingModel.get().chat(request,
                new TokenStreamer(progress, outputGuard, latch, completed, failure));

        long remaining = Math.max(1_000L, budget.timeoutMs() - elapsedMs(startNanos));
        try {
            if (!latch.await(remaining, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("流式响应超过 " + remaining + "ms 未完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式响应被中断", e);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("流式响应失败：" + failure.get().getMessage(), failure.get());
        }
        ChatResponse response = completed.get();
        if (response == null) {
            throw new IllegalStateException("流式响应既未完成也未报错");
        }
        return response;
    }

    /**
     * 把流式模型的增量转成 {@link TurnListener} 的回调，并顺手做<b>增量级</b>泄露检查。
     *
     * <p>为什么要在增量上过守卫：流式把「先渲染、后定案」的窗口从整段缩短到一个增量，
     * 但窗口依然存在——用户会先看到内容，守卫随后才对完整文本定案。
     * 整段扫描的成本是 O(n²)（n 是本轮已收到的字符数，上限即模型最大输出），
     * 在 2000 字量级上可以忽略；换来的是「泄密内容一到就停」，而不是等整段说完。
     *
     * <p>命中后<b>不中断这次请求</b>：让响应正常走完，由上层对完整文本过守卫并整段替换
     * （那是唯一权威的定案）。这里只负责立刻通知前端「刚才那些字不算数」。
     */
    private static final class TokenStreamer implements StreamingChatResponseHandler {

        private final TurnListener progress;
        private final OutputGuard guard;
        private final CountDownLatch latch;
        private final AtomicReference<ChatResponse> completed;
        private final AtomicReference<Throwable> failure;
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean stopped;

        TokenStreamer(TurnListener progress, OutputGuard guard, CountDownLatch latch,
                      AtomicReference<ChatResponse> completed, AtomicReference<Throwable> failure) {
            this.progress = progress;
            this.guard = guard;
            this.latch = latch;
            this.completed = completed;
            this.failure = failure;
        }

        @Override
        public void onPartialResponse(String token) {
            if (token == null || token.isEmpty()) return;
            if (stopped) return; // 已经判定不安全：后续增量一个都不再往外送
            buffer.append(token);
            if (guard != null && guard.wouldFlag(buffer.toString())) {
                stopped = true;
                log.warn("流式输出命中泄密规则，已停止推送增量（完整文本仍由上层守卫定案）");
                notifySafely(progress, () -> progress.onTokensStopped("OUTPUT_GUARD"));
                return;
            }
            String chunk = token;
            notifySafely(progress, () -> progress.onToken(chunk));
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            completed.set(response);
            latch.countDown();
        }

        @Override
        public void onError(Throwable error) {
            failure.set(error);
            latch.countDown();
        }
    }

    /**
     * 执行一批工具调用。独立只读工具并行，效果型工具留在调用线程顺序执行。
     * 返回结果与传入请求<b>按下标一一对应</b>，顺序对模型理解结果很重要。
     *
     * <p><b>并发下的降级策略</b>：并行预算是<b>全局</b>的（{@link #parallelPermits}），
     * 因为 worker 线程池被所有用户的轮次共享。当预算已被别的轮次占满时，这里的做法是
     * <b>就地顺序执行</b>，而不是排队等许可。区别很大：排队会让本轮的工具集体堵到
     * {@code allOf().get(timeout)} 超时，于是高并发下<b>所有用户一起</b>拿到「查询超时」；
     * 而顺序执行只是变慢（每次调用本来就只有毫秒级），每个请求依然能完整答完。
     * 宁可牺牲并发度，也不要牺牲可用性——这是有界资源系统的通例。
     *
     * <p>并行任务用 {@link TurnScope#wrapRunnable} 包裹：worker 是池化复用的，进去时装载
     * 本轮快照、出来时精确还原，本轮与上一个使用者残留的上下文都不会互相串味。
     */
    private List<ToolRun> invokeAll(ToolRegistry.Bundle bundle, List<ToolExecutionRequest> requests,
                                    AssistantActor actor, Budget budget, boolean[] duplicate,
                                    TurnListener listener) {
        List<ToolRun> results = new ArrayList<>(Collections.nCopies(requests.size(), null));
        List<CompletableFuture<Void>> pending = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            if (duplicate[i]) {
                // 短路掉也要报一次进度：用户看到「正在查…」却永远等不到「查完了」才是更糟的体验。
                notifySafely(listener, () -> listener.onToolStart(req.name(), clip(req.arguments(), 160)));
                results.set(i, ToolRun.verbatim(duplicateNotice(req.name()), STATUS_DUPLICATE));
                notifySafely(listener, () -> listener.onToolEnd(req.name(), STATUS_DUPLICATE, 0L));
            } else if (bundle.isEffectful(req.name()) || !parallelPermits.tryAcquire()) {
                // 效果型工具：留在调用线程，保住轮次上下文，且不与其他调用并发。
                // 只读工具但预算已满：同样在调用线程执行（降级，见方法注释）。
                results.set(i, invoke(bundle, req, actor, budget, listener));
            } else {
                int idx = i;
                try {
                    pending.add(CompletableFuture
                            .runAsync(TurnScope.wrapRunnable(
                                            () -> results.set(idx, invoke(bundle, req, actor, budget, listener))),
                                    workers)
                            .whenComplete((ignored, error) -> parallelPermits.release()));
                } catch (RuntimeException e) {
                    // 提交失败（线程池拒绝）：必须把许可还回去，否则会被永久泄漏、整个池慢慢饿死。
                    parallelPermits.release();
                    log.warn("并行工具提交失败，降级为顺序执行：{}", e.getClass().getSimpleName());
                    results.set(idx, invoke(bundle, req, actor, budget, listener));
                }
            }
        }

        if (!pending.isEmpty()) {
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .get(budget.timeoutMs(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // 超时/中断都不向上抛：能拿到的结果照常给模型，拿不到的按超时如实标注。
                log.warn("并行工具调用未全部完成：{}", e.getClass().getSimpleName());
            }
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i) == null) {
                    results.set(i, ToolRun.verbatim(
                            timeoutNotice(requests.get(i).name(), budget.timeoutMs()), STATUS_TIMEOUT));
                }
            }
        }
        return results;
    }

    /** 单次工具调用：把异常、裁剪都收在这一层，保证永远返回一条模型能读懂的结果。 */
    private ToolRun invoke(ToolRegistry.Bundle bundle, ToolExecutionRequest req,
                           AssistantActor actor, Budget budget, TurnListener listener) {
        notifySafely(listener, () -> listener.onToolStart(req.name(), clip(req.arguments(), 160)));
        long t0 = System.nanoTime();
        ToolRegistry.Execution execution;
        try {
            execution = toolRegistry.execute(bundle, req, actor);
        } catch (Exception e) {
            // 双保险：ToolRegistry 内部已经把异常转成结构化错误，走到这里说明路由层本身出了问题，
            // 那就更不能让整轮对话崩掉。
            log.error("工具执行异常 tool={}", req.name(), e);
            execution = new ToolRegistry.Execution(
                    errorJson("TOOL_FAILED", "工具调用失败", "不要用相同参数重试；请换一种方式或直接回答。"),
                    STATUS_ERROR);
        }
        long latency = (System.nanoTime() - t0) / 1_000_000L;
        // 取成 final 局部量再进 lambda：execution 在 try/catch 两处赋值，本身不是「实际上的最终变量」。
        String status = execution.status();
        notifySafely(listener, () -> listener.onToolEnd(req.name(), status, latency));
        String forModel = truncate(execution.text(), budget.maxToolResultChars());
        return new ToolRun(execution.text(), forModel, execution.status(), latency,
                forModel.length() != execution.text().length());
    }

    /**
     * 进度回调是「尽力而为」的：监听器抛异常（SSE 连接断了、序列化失败）不能影响这次回答。
     * 一个断开的浏览器标签页导致用户的对话失败，是最没有道理的一类故障。
     *
     * <p>实现统一放在 {@link TurnListener#safely}——循环外的耗时步骤（如「看图」）也要用同一条规则。
     */
    private static void notifySafely(TurnListener listener, Runnable action) {
        TurnListener.safely(listener, action);
    }

    /** 工具结果裁剪：头保留 + 明确告知被截断，避免模型把截断当成「就这么多」。 */
    private String truncate(String text, int max) {
        if (text == null) return "{}";
        if (max <= 0 || text.length() <= max) return text;
        return text.substring(0, max)
                + "\n…[工具结果过长，系统已截断：原始 " + text.length() + " 字符。"
                + "如需完整内容，请缩小查询范围或用更精确的参数重新查询]";
    }

    private String fallbackFor(String stopReason, List<Step> steps, ToolRegistry.Bundle bundle) {
        return switch (stopReason) {
            case STOP_TIMEOUT -> "这次查询花的时间有点久，我先停下来。请稍后再试，或者把问题问得更具体一些。";
            case STOP_STALLED -> "我好像卡在同一个查询上了，先停下来避免继续空转。请换个说法或换个问题，我再试一次。";
            default -> "这个请求需要的信息比较多，一次没查完。可以拆开问我，比如先问商品、再问订单。";
        };
    }

    private String duplicateNotice(String toolName) {
        ObjectNode node = json.createObjectNode();
        node.put("note", "DUPLICATE_CALL");
        node.put("tool", toolName);
        node.put("message", "这次调用与上一轮完全相同，结果不会改变，系统没有重复执行。"
                + "请不要再用相同参数调用它：已有信息够用就直接回答，或换一个不同的查询。");
        return node.toString();
    }

    private String timeoutNotice(String toolName, long timeoutMs) {
        ObjectNode node = json.createObjectNode();
        node.put("error", "TOOL_TIMEOUT");
        node.put("tool", toolName);
        node.put("message", "该工具超过 " + timeoutMs + "ms 未返回，已按超时处理。"
                + "请告知用户稍后再试，或用其他方式获取信息。");
        return node.toString();
    }

    private String errorJson(String code, String message, String hint) {
        ObjectNode node = json.createObjectNode();
        node.put("error", code);
        node.put("message", message);
        node.put("hint", hint);
        return node.toString();
    }

    /** 参数归一化只用于「判断是否同一个调用」，不用于执行，所以去掉空白即可。 */
    private static String normalize(String args) {
        return args == null ? "" : args.replaceAll("\\s+", "");
    }

    private static String clip(String text, int max) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
