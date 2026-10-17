package dev.learning.mall.assistant.web;

import dev.learning.mall.assistant.api.*;
import dev.learning.mall.assistant.harness.TurnListener;
import dev.learning.mall.assistant.internal.AssistantProperties;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.common.BusinessException;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService service;
    private final TaskExecutor turnExecutor;
    private final AssistantProperties properties;

    public AssistantController(AssistantService service,
                               @Qualifier("assistantTurnExecutor") TaskExecutor turnExecutor,
                               AssistantProperties properties) {
        this.service = service;
        this.turnExecutor = turnExecutor;
        this.properties = properties;
    }

    @GetMapping("/status")
    public AssistantStatus status(Principal principal) { return service.status(principal.getName()); }

    @PostMapping("/conversations")
    public ConversationView create(Principal principal, @RequestBody(required = false) CreateConversationRequest request) {
        return service.create(principal.getName(), request == null ? null : request.title());
    }

    @GetMapping("/conversations")
    public List<ConversationView> list(Principal principal) { return service.list(principal.getName()); }

    @GetMapping("/conversations/{id}")
    public ConversationDetail get(@PathVariable String id, Principal principal) {
        return service.get(id, principal.getName());
    }

    @DeleteMapping("/conversations/{id}")
    public void delete(@PathVariable String id, Principal principal) {
        service.delete(id, principal.getName());
    }

    /**
     * 发送消息。返回 {@link Callable} 让 Spring MVC 走<b>异步处理</b>：Tomcat 工作线程在
     * 助手「开始思考」的那一刻就被释放回池子，真正耗时的模型等待由助手专用线程池承担。
     * 不同步返回，助手就不具备「一个功能拖垮整个站点」的能力（默认 200 个 Tomcat 线程
     * 会被几十个等待模型的助手请求吃干净）。
     *
     * <p>用户名与消息在请求线程上就取出并捕获进闭包，异步线程里不再依赖 SecurityContext
     * ——少一次跨线程的上下文传播，就少一处可能静默失效的隐式依赖。
     */
    @PostMapping("/conversations/{id}/messages")
    public Callable<SendMessageResponse> send(@PathVariable String id, Principal principal,
                                             @Valid @RequestBody SendMessageRequest request) {
        String username = principal.getName();
        String message = request.message();
        List<ImagePayload> images = request.images();
        // 同步端点没有人在听进度（响应要等整轮结束才发），所以显式传 NOOP 而不是靠重载猜。
        return () -> service.sendDetailed(id, username, message, images, TurnListener.NOOP);
    }

    /**
     * <b>流式</b>发送消息：同一个轮次，但进度实时可见。
     *
     * <p>与 {@link #send} 的区别只在「怎么把结果送回来」，管线本身完全一样——
     * 同一套限流、输入守卫、并发闸门、工具循环、输出守卫。进度是旁路，
     * 不改变任何安全判定（这也是为什么它复用 {@code sendDetailed} 而不是另写一条链路：
     * 另写一条就意味着有一半的安全检查可能被漏掉）。
     *
     * <p>事件序列：{@code open} →（{@code stage} / {@code tool} 若干）→ {@code done}，
     * 出错时以 {@code error} 收尾。之所以连错误也走事件而不是 HTTP 状态码：
     * 一旦返回 {@link SseEmitter}，响应头（含 200）就已经发出去了，
     * 之后的任何失败都只能从流里表达。
     *
     * <p>真正的执行被提交到<b>助手专用线程池</b>，请求线程立刻返回——
     * 与 {@code Callable} 版本同理，绝不让「等模型」占住 Tomcat 工作线程。
     */
    @PostMapping(path = "/conversations/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, Principal principal,
                             @Valid @RequestBody SendMessageRequest request) {
        String username = principal.getName();
        String message = request.message();
        List<ImagePayload> images = request.images();
        // 超时留出余量：harness 自己的墙钟预算到点会给出兜底答复，容器不该抢先掐断。
        SseEmitter emitter = new SseEmitter(properties.getHarness().getTimeoutMs() + 20_000);
        TurnStreamSink sink = new TurnStreamSink(emitter);
        emitter.onCompletion(sink::markClosed);
        emitter.onTimeout(() -> {
            sink.markClosed();
            emitter.complete();
        });
        emitter.onError(error -> sink.markClosed());

        try {
            turnExecutor.execute(() -> {
                try {
                    sink.emit("open", Map.of("conversationId", id));
                    SendMessageResponse response = service.sendDetailed(id, username, message, images, sink);
                    sink.emit("done", response);
                } catch (BusinessException e) {
                    // 闸门拒绝、会话不存在等可预期失败：把原因如实推给前端，而不是留一个断掉的流。
                    sink.emit("error", Map.of("code", e.code(), "message", e.getMessage()));
                } catch (Exception e) {
                    log.warn("助手流式轮次异常 conversation={}", id, e);
                    sink.emit("error", Map.of("code", "ASSISTANT_ERROR",
                            "message", "助手暂时无法回答，请稍后再试。"));
                } finally {
                    sink.close();
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池也满了：这是「全局在途」之外的最后一道保险丝。
            sink.emit("error", Map.of("code", "ASSISTANT_BUSY", "message", "助手当前请求较多，请稍后再试。"));
            sink.close();
        }
        return emitter;
    }

    /**
     * 安全审计：仅 STAFF 角色可读（SecurityConfig 里同步做了 URL 级限制）。
     * 双重限制的原因——注解式或 URL 式单一防线一旦漏配就是数据泄露。
     */
    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "50") int limit) {
        return service.auditTrail(limit);
    }

    /**
     * Agent 运行轨迹：同样仅 STAFF。审计回答「有没有人攻击」，轨迹回答
     * 「这次回答是怎么产生的、为什么没答完」——排查 agent 行为靠的是后者。
     */
    @GetMapping("/runs")
    public List<RunTraceEntry> runs(@RequestParam(defaultValue = "20") int limit) {
        return service.recentRuns(limit);
    }

    @GetMapping("/runs/{runId}/steps")
    public List<RunStepEntry> runSteps(@PathVariable String runId) {
        return service.runSteps(runId);
    }

    /** 某会话下待本人确认的动作（页面刷新后重新渲染确认卡片）。 */
    @GetMapping("/conversations/{id}/pending-actions")
    public List<PendingActionView> pendingActions(@PathVariable String id, Principal principal) {
        return service.listPendingActions(id, principal.getName());
    }

    /**
     * human-in-the-loop 的人类闸门：只有这两个接口能真正触发支付。
     * 归属/过期/一次性在服务端硬校验——模型与提示词注入都没有路径触达这里。
     */
    @PostMapping("/pending-actions/{id}/confirm")
    public PendingActionResult confirmAction(@PathVariable String id, Principal principal) {
        return service.resolvePendingAction(id, principal.getName(), true);
    }

    @PostMapping("/pending-actions/{id}/cancel")
    public PendingActionResult cancelAction(@PathVariable String id, Principal principal) {
        return service.resolvePendingAction(id, principal.getName(), false);
    }

    /**
     * 助手记住了我什么。只有本人能看自己的记忆（查询条件里带 username），
     * 店员也不行——记忆是用户隐私，不是运营数据。
     */
    @GetMapping("/memory")
    public List<MemoryEntryView> memory(Principal principal) {
        return service.memoryOf(principal.getName());
    }

    /** 忘掉某条记忆；key=ALL 清空。同样只作用于本人。 */
    @DeleteMapping("/memory/{key}")
    public void forgetMemory(@PathVariable String key, Principal principal) {
        service.forgetMemory(principal.getName(), key);
    }
}
