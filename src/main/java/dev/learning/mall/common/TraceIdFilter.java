package dev.learning.mall.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求级 traceId 过滤器（D1）：把「同一次请求的所有日志行」串起来。
 *
 * <p><b>为什么需要它：</b>日志格式默认只有「时间 + 线程 + 类 + 消息」。多用户并发时，
 * 一次请求的日志行与另一次请求的日志行交错在一起，没有任何字段能把同一请求的行挑出来。
 * 商家中心这类多表写路径出问题时，排障只能靠「时间相近 + 肉眼猜」。
 *
 * <p><b>工作方式：</b>
 * <ol>
 *   <li>请求进来时读 {@value #HEADER}：外部调用方传了就原样沿用（便于将来接入调用方追踪），
 *       没传就生成 UUID 前 8 位（够短、够排障用——它不是全局唯一键，只是「这次请求」的标签）。</li>
 *   <li>放进 MDC（key={@value #MDC_KEY}），日志 pattern 里的 {@code %X{traceId}} 从这里取值。</li>
 *   <li>回写响应头 {@value #HEADER}：调用方（含前端）拿到它，报障时报出这串号，
 *       我们就能在日志里精确捞出这一次请求的全部记录。</li>
 *   <li>{@code finally} 里 {@code MDC.remove}：Tomcat 线程是池化复用的，
 *       不清掉的话，下一个复用这条线程的请求会带着上一个请求的 traceId 打日志——
 *       那不是「没有 traceId」，而是「有错的 traceId」，比没有更糟。</li>
 * </ol>
 *
 * <p><b>顺序：</b>{@link Ordered#HIGHEST_PRECEDENCE}——必须跑在 Spring Security 过滤器链之前，
 * 否则「未登录被拒」这类由安全过滤器自己打出来的日志就没有 traceId，
 * 排障时恰恰最需要的那几行反而串不起来。
 *
 * <p><b>异步段：</b>Spring MVC 的 {@code Callable} 异步处理会把工作挪到助手线程池，
 * MDC 是 ThreadLocal，不会自己跟过去。那一段由
 * {@link MdcTaskDecorator} 负责把 MDC 拷过去（见 {@code AssistantAsyncConfig}）。
 *
 * <p><b>刻意不处理 ASYNC 二次派发</b>（{@code OncePerRequestFilter} 的默认行为）：
 * 异步结果回到容器写响应时会再走一遍过滤器链，但那时请求头还是客户端原来那一个——
 * 客户端没传号的话，这一遍会生成<b>另一个</b> UUID，同一次请求反而出现两个 traceId。
 * 与其这样，不如让第二遍不参与：写响应这一步本身不产生我们需要串联的业务日志。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    /** 外部调用方与本服务之间约定的请求号头。 */
    public static final String HEADER = "X-Request-Id";

    /** MDC 的 key；与 application.yml 里 {@code logging.pattern.level} 的 {@code %X{traceId}} 对应。 */
    public static final String MDC_KEY = "traceId";

    /**
     * 外部传入的请求号只接受这一小撮字符，最长 64。
     *
     * <p>它会被<b>原样写进响应头和日志行</b>，所以不能无条件信任：
     * 带 CR/LF 的值会让 Tomcat 在 {@code setHeader} 时抛异常（一个畸形头就把请求变成 500），
     * 带换行的值还能伪造日志行。格式不合规就当作「没传」——我们自己生成一个，
     * 不因为一个花值拒绝整个请求。
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || !SAFE_REQUEST_ID.matcher(traceId).matches()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_KEY, traceId);
        // 先写响应头再走过滤器链：一旦下游开始提交响应体，响应头就写不进去了。
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
