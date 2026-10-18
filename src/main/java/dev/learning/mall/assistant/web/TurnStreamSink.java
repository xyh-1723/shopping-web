package dev.learning.mall.assistant.web;

import dev.learning.mall.assistant.harness.TurnListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 {@link TurnListener} 的进度回调翻译成 SSE 事件，推给浏览器。
 *
 * <p><b>为什么用 SSE 而不是 WebSocket</b>：进度是<b>单向、短时、随请求生随请求灭</b>的。
 * WebSocket 要额外维护握手、心跳、连接注册表，换来的全双工在这里一次都用不上。
 * SSE 就是一个「不会立即结束的 HTTP 响应」，天然对齐这个场景，也天然继承了
 * 现有的登录态与 CSRF 校验——不需要为它另做一套鉴权。
 *
 * <p><b>为什么用 fetch 读流而不是 EventSource</b>：{@code EventSource} 只能发 GET、
 * 不能带自定义头，于是既传不了 CSRF 令牌，也传不了消息正文。前端因此用
 * {@code fetch} + {@code response.body.getReader()} 手动解析同样格式的 SSE 帧。
 *
 * <p><b>线程安全</b>：一轮里互不依赖的只读工具是并行执行的，事件因此可能从多个
 * worker 线程同时到达。{@code SseEmitter.send} 自身是同步的，本类再加一个「已关闭」
 * 标记——连接断开或轮次结束后，任何迟到的事件都被静默丢弃，而不是抛异常把轮次带崩。
 *
 * <p><b>工具名 → 中文进度文案</b>：映射放在服务端而不是前端。这样新增一个工具时，
 * 只需在这里补一行，前端什么都不用改；不认识的工具名会退化成工具名本身，
 * 不会出现「进度条卡住不显示」。
 */
final class TurnStreamSink implements TurnListener {

    /** 工具名 → 展示用文案。只影响「正在…」的措辞，不参与任何逻辑判断。 */
    private static final Map<String, String> TOOL_LABELS = Map.ofEntries(
            Map.entry("searchProducts", "正在搜索商品"),
            Map.entry("getProduct", "正在查看商品详情"),
            Map.entry("recommendByBudget", "正在按预算挑选"),
            Map.entry("myOrders", "正在查询你的订单"),
            Map.entry("viewCart", "正在结算试算"),
            Map.entry("compareProducts", "正在对比商品"),
            Map.entry("requestClarification", "正在整理提问"),
            Map.entry("addToCart", "正在加入购物袋"),
            Map.entry("setCartQuantity", "正在修改数量"),
            Map.entry("removeFromCart", "正在移出购物袋"),
            Map.entry("clearCart", "正在清空购物袋"),
            Map.entry("rememberPreference", "正在记住偏好"),
            Map.entry("recallPreferences", "正在回忆你的偏好"),
            Map.entry("forgetPreference", "正在更新偏好"),
            Map.entry("requestPayment", "正在准备支付确认"),
            Map.entry("createOrder", "正在准备下单确认"),
            Map.entry("cancelOrder", "正在准备取消确认"),
            Map.entry("requestRefund", "正在准备退款确认"),
            Map.entry("searchKnowledge", "正在查帮助文档"),
            Map.entry("inventoryAlerts", "正在盘点库存"),
            Map.entry("salesOverview", "正在汇总经营数据"),
            Map.entry("customerOrders", "正在查询订单"),
            Map.entry("watchProduct", "正在设置降价提醒"),
            Map.entry("unwatchProduct", "正在取消关注"),
            Map.entry("myWatches", "正在查看你关注的好物"),
            Map.entry("myAlerts", "正在查看你的提醒"),
            Map.entry("recommendRelated", "正在挑选相关好物"),
            Map.entry("pendingRefundQueue", "正在查看待审批退款"),
            Map.entry("approveRefund", "正在批准退款并放款"),
            Map.entry("rejectRefund", "正在驳回退款"));

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean();

    TurnStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onStage(String stage) {
        emit("stage", Map.of("stage", stage));
    }

    @Override
    public void onToolStart(String toolName, String args) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("phase", "start");
        data.put("label", label(toolName));
        emit("tool", data);
    }

    @Override
    public void onToolEnd(String toolName, String status, long latencyMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", toolName);
        data.put("phase", "end");
        data.put("label", label(toolName));
        data.put("status", status);
        data.put("latencyMs", latencyMs);
        emit("tool", data);
    }

    /**
     * 这个 sink 会把增量推给浏览器，所以明确声明「我要逐字」——
     * 执行循环据此切换到流式模型客户端。不给这个信号，模型就还是一次性返回，
     * 前端看到的就只有进度条。
     */
    @Override
    public boolean consumesTokens() {
        return true;
    }

    @Override
    public void onToken(String token) {
        emit("delta", Map.of("text", token));
    }

    /** 增量作废：前端应立即清掉已渲染的正文，等 {@code done} 带来权威文本。 */
    @Override
    public void onTokensStopped(String reason) {
        emit("guard", Map.of("reason", reason));
    }

    /** 推一个事件。连接已断或轮次已结束时静默丢弃——迟到的进度不该制造错误。 */
    void emit(String event, Object data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端断开、响应已提交完毕、序列化失败：都归为「这条通道没了」。
            closed.set(true);
        }
    }

    /** 标记通道已关闭。连接断开/超时/出错时由容器回调触发。 */
    void markClosed() {
        closed.set(true);
    }

    /** 正常收尾：发完最后一条事件后结束响应。幂等，且不会在已关闭的通道上重复 complete。 */
    void close() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    private static String label(String toolName) {
        return TOOL_LABELS.getOrDefault(toolName, toolName);
    }

    /**
     * 某个工具是否有中文进度文案。仅供测试使用——它存在的意义是让
     * 「新增工具忘了补一行文案」在构建期就被发现，而不是等用户在界面上看到
     * 一行裸工具名（{@code watchProduct}）才发现进度没写人话。
     */
    static boolean hasLabel(String toolName) {
        return TOOL_LABELS.containsKey(toolName);
    }
}
