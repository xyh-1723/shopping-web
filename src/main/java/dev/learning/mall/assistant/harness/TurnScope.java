package dev.learning.mall.assistant.harness;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次「对话轮次」的线程上下文——多用户并发下的隔离基座。
 *
 * <p><b>为什么需要它？</b>工具层需要在「处理某条用户消息的过程中」知道三件事：
 * 这条消息属于哪个会话、哪个用户、本轮产生了什么。这些信息<b>不能由模型提供</b>
 * （模型自报的参数可被提示词注入篡改），只能由服务端在自己的执行路径上传递。
 *
 * <p>最直观的做法是 {@code ThreadLocal}，但裸的 {@code ThreadLocal} 在并发下有三个坑：
 *
 * <ol>
 *   <li><b>线程池复用会串味</b>：Tomcat 与工具 worker 都是池化的。上一个请求在某线程上
 *       留下的值，会被下一个请求（甚至是<b>另一个用户</b>的请求）在同一线程上读到。
 *       这不是报错，是<b>静默的跨用户数据串味</b>——A 的支付确认卡片挂到 B 的会话里。</li>
 *   <li><b>忘记清理</b>：靠人工保证每处都 {@code remove()}，漏一条路径就泄漏到下一个请求。</li>
 *   <li><b>跨线程丢失</b>：把工具丢到 worker 线程并行执行，上下文就没了（读到的往往是别人的残留）。</li>
 * </ol>
 *
 * <p>本类用三个机制把上述问题一次性解决：
 *
 * <ul>
 *   <li>{@link #begin(String, String)} 返回 {@link Scope}（{@code AutoCloseable}），
 *       用 try-with-resources 表达「本轮开始/结束」，<b>清理自动发生</b>，不依赖调用者的记性。</li>
 *   <li>{@code close()} <b>精确还原</b>到进入前的值（而不是清空）。这很关键：worker 线程
 *       进入时可能已经残留着别人的上下文，清空同样会造成串味——必须还原成「进入时的样子」，
 *       这样即使池化复用，也永远不会有本轮的上下文泄漏出去。</li>
 *   <li>{@link #wrap(Callable)} / {@link #wrap(Runnable)} 做<b>跨线程传播</b>：提交并行任务前
 *       捕获快照，任务在自己的线程上装载快照、执行、再还原——并行执行因此是「隔离安全的」。</li>
 * </ul>
 *
 * <p><b>传播是单向的</b>：{@code wrap} 传下去的是快照副本，所以 worker 线程里对属性的写入
 * 不会回流到调用线程。这是有意设计——并行执行的只读工具本来就不该改写轮次状态；
 * 需要产生副作用的工具（{@link Effectful}）留在调用线程执行，它的写入才能被本轮看到。
 *
 * <p>非线程安全的使用方式（在同一线程上嵌套 begin 而不 close）会互相覆盖，但嵌套
 * begin/close 成对出现时，{@code close()} 逐层还原，行为是确定的。
 */
public final class TurnScope {

    private TurnScope() {}

    /** 轮次上下文的只读快照，用于跨线程传播。属性表是浅拷贝：值本身应是不可变对象。 */
    public record Snapshot(String conversationId, String username, Map<String, Object> attributes) {}

    /** 线程内的轮次上下文实体。属性表用并发 Map，因为轮次内允许多个 worker 读。 */
    private static final class Context {
        private final String conversationId;
        private final String username;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        Context(String conversationId, String username) {
            this.conversationId = conversationId;
            this.username = username;
        }
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    /**
     * 一次轮次的句柄。{@code close()} 幂等，并把线程上下文还原成进入前的值。
     */
    public static final class Scope implements AutoCloseable {
        private final Context previous;
        private final Context installed;
        private boolean closed;

        private Scope(Context previous, Context installed) {
            this.previous = previous;
            this.installed = installed;
        }

        public String conversationId() { return installed.conversationId; }
        public String username() { return installed.username; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            // 还原而不是清空：本线程进入前可能已被池化的上一个任务用过。
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** 开始一轮对话：装载本轮的会话与用户身份。必须 close（try-with-resources）。 */
    public static Scope begin(String conversationId, String username) {
        Context installed = new Context(conversationId, username);
        Scope scope = new Scope(CURRENT.get(), installed);
        CURRENT.set(installed);
        return scope;
    }

    /**
     * 在「当前线程」装载一份快照执行。这是 {@link #wrap(Callable)} 的底层动作，
     * 单独暴露出来是为了让需要手写线程池提交的场景也能拿到同一套隔离保证。
     */
    public static Scope install(Snapshot snapshot) {
        Context installed = new Context(snapshot.conversationId(), snapshot.username());
        installed.attributes.putAll(snapshot.attributes());
        Scope scope = new Scope(CURRENT.get(), installed);
        CURRENT.set(installed);
        return scope;
    }

    /** 捕获当前线程的轮次快照；不在轮次内时返回 null。 */
    public static Snapshot capture() {
        Context ctx = CURRENT.get();
        return ctx == null ? null
                : new Snapshot(ctx.conversationId, ctx.username, Map.copyOf(ctx.attributes));
    }

    /**
     * 把一段有返回值的逻辑包装成「在目标线程上依然看得到本轮上下文」的任务。
     * 若调用时不在轮次内，则原样返回任务（不装载、不还原），避免误创建上下文。
     */
    public static <T> Callable<T> wrap(Callable<T> body) {
        Snapshot snapshot = capture();
        if (snapshot == null) return body;
        return () -> {
            try (Scope ignored = install(snapshot)) {
                return body.call();
            }
        };
    }

    /**
     * 无返回值版本。
     *
     * <p><b>为什么叫 {@code wrapRunnable} 而不是重载 {@code wrap}</b>：在 Java 里
     * {@code () -> list.set(0, x)} 这种「有返回值的表达式语句」同时满足 {@code Runnable}
     * 与 {@code Callable}，重载解析会挑错目标类型并给出难以理解的编译错误
     * （「不存在类型变量 T 使 Callable&lt;T&gt; 与 Runnable 一致」）。
     * 名字分开，调用点就不需要靠强制转换来消歧义。
     */
    public static Runnable wrapRunnable(Runnable body) {
        Snapshot snapshot = capture();
        if (snapshot == null) return body;
        return () -> {
            try (Scope ignored = install(snapshot)) {
                body.run();
            }
        };
    }

    /** 当前线程所属会话；不在轮次内时为 null（调用方必须容忍这种可能）。 */
    public static String conversationId() {
        Context ctx = CURRENT.get();
        return ctx == null ? null : ctx.conversationId;
    }

    /** 当前线程所属登录用户；不在轮次内时为 null。 */
    public static String username() {
        Context ctx = CURRENT.get();
        return ctx == null ? null : ctx.username;
    }

    /** 当前线程是否处在某个轮次内。 */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** 读本轮属性；不在轮次内时返回 null。 */
    public static Object attribute(String key) {
        Context ctx = CURRENT.get();
        return ctx == null ? null : ctx.attributes.get(key);
    }

    /** 写本轮属性；不在轮次内时静默忽略（工具被单独调用时不应报错）。 */
    public static void attribute(String key, Object value) {
        Context ctx = CURRENT.get();
        if (ctx != null) ctx.attributes.put(key, value);
    }

    /** 移除本轮属性（例如「已消费」的一次性产物）。 */
    public static void removeAttribute(String key) {
        Context ctx = CURRENT.get();
        if (ctx != null) ctx.attributes.remove(key);
    }
}
