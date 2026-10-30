package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.learning.mall.common.TraceIdFilter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D1 请求级 traceId 的守护测试。
 *
 * <p>要守的三件事，各对应一条用例：
 * <ol>
 *   <li><b>入口</b>：外部传了 {@code X-Request-Id} 就原样沿用（将来接入调用方追踪），
 *       没传就自动生成——两条都回写响应头，调用方报障时报出这串号即可捞日志。</li>
 *   <li><b>出口</b>：请求结束后 MDC 必须清掉。Tomcat 线程池化复用，不清的话下一个请求
 *       会带着上一个请求的 traceId 打日志——错的 traceId 比没有更糟。</li>
 *   <li><b>异步段</b>：Spring MVC 的 {@code Callable} 异步处理把「等模型」挪到助手线程池，
 *       MDC 是 ThreadLocal，不拷贝就会在这里断成两截。这条用例直接用生产配置的那个
 *       线程池 bean 验证「装饰器真的挂上了」，而不是另起一个手工装饰的池子自欺欺人。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TraceIdFilterTest {

    @Autowired MockMvc mvc;
    /** 生产配置里的助手线程池（AssistantAsyncConfig）。按名注入：项目里还有 messaging 的池。 */
    @Autowired @Qualifier("assistantTurnExecutor") ThreadPoolTaskExecutor assistantExecutor;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // ——————————————————— 入口：生成与沿用 ———————————————————

    @Test
    @DisplayName("外部传入的 X-Request-Id 原样沿用并回写响应头")
    void externalRequestIdIsKept() throws Exception {
        // GET /api/products 是放行路径：这条用例测的是过滤器，不是登录。
        mvc.perform(get("/api/products").header(TraceIdFilter.HEADER, "ext-abc123"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, "ext-abc123"));
    }

    @Test
    @DisplayName("没带 X-Request-Id 的请求自动生成一段短号并回写")
    void requestIdIsGeneratedWhenAbsent() throws Exception {
        String traceId = mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId)
                .as("没传就要生成：UUID 前 8 位，够短、够排障用")
                .matches("[0-9a-f]{8}");
    }

    @Test
    @DisplayName("格式不合规的 X-Request-Id 不沿用，按缺省生成——不让畸形头污染响应头和日志")
    void unsafeRequestIdIsReplaced() throws Exception {
        // 含空格与斜杠：真实攻击面是带 CR/LF 的值（Tomcat 会在 setHeader 时抛异常）。
        // 这里用 MockMvc 能安全表达的近似值，验证「不合规就不沿用」这条规则。
        String traceId = mvc.perform(get("/api/products").header(TraceIdFilter.HEADER, "bad id/path"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{8}");
    }

    @Test
    @DisplayName("未登录被拒的响应也带 traceId：过滤器确实跑在安全链之前")
    void unauthorizedResponseCarriesTraceId() throws Exception {
        // /merchant 需要登录。SecurityConfig 的默认入口直接给 401 + JSON（不跳登录页），
        // 若 TraceIdFilter 排在其后，这个响应头就不会存在——这条用例守的就是那个顺序。
        mvc.perform(get("/merchant"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(TraceIdFilter.HEADER));
    }

    // ——————————————————— 出口：MDC 可见且事后清除 ———————————————————

    @Test
    @DisplayName("请求内 MDC 可见（与响应头同值），请求结束后 MDC 清空")
    void mdcIsVisibleInsideAndClearedAfter() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInside.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(seenInside.get())
                .as("过滤器链内部必须能读到 traceId，且与回写响应头的是同一个值")
                .isNotNull()
                .isEqualTo(response.getHeader(TraceIdFilter.HEADER));
        assertThat(MDC.get(TraceIdFilter.MDC_KEY))
                .as("请求结束后必须清掉：线程是池化复用的，残留的 traceId 会串到下一个请求")
                .isNull();
    }

    // ——————————————————— 异步段：线程池携带 MDC ———————————————————

    @Test
    @DisplayName("助手线程池携带提交方的 MDC，且任务结束后不残留在池线程上")
    void assistantPoolCarriesMdc() throws Exception {
        MDC.put(TraceIdFilter.MDC_KEY, "async-trace-1");
        // Callable 是 Spring MVC 异步处理实际提交的形状——装饰器若只包 Runnable，
        // 这条用例就会红（那正是它要守的洞）。
        String seen = assistantExecutor.submit(() -> MDC.get(TraceIdFilter.MDC_KEY)).get(5, TimeUnit.SECONDS);
        assertThat(seen)
                .as("异步段必须看到提交方的 traceId，否则一轮回答的日志断成两截")
                .isEqualTo("async-trace-1");

        // 反向：本线程清掉 MDC 后再提交一个任务，池线程上不该残留上一个任务的值。
        MDC.clear();
        String leaked = assistantExecutor.submit(() -> MDC.get(TraceIdFilter.MDC_KEY)).get(5, TimeUnit.SECONDS);
        assertThat(leaked)
                .as("装饰器必须精确还原：上一个任务的 traceId 不能漏给下一个任务")
                .isNull();
    }
}
