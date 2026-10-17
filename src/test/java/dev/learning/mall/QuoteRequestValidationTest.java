package dev.learning.mall;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求体 Bean Validation 的守护测试：守的是「HTTP 入口 → 参数绑定 → {@code @Valid} 级联」这一层。
 *
 * <p>为什么必须单独立一条：<b>编译能过的“最省事修法”是摘掉 {@code @Valid}</b>
 * ——javac 不允许把 {@code @NotNull @Valid} 这类 TYPE_USE 注解加在
 * {@code CreateOrderRequest.Line} 这种限定名上，于是有人提议把注解删掉绕开编译错误。
 * 一旦摘掉，校验就不再级联进 {@code Line}，{@code @Positive}（商品编号为正）与
 * {@code @Min(1)/@Max(99)}（数量）<b>全部失效</b>，
 * {@code {"productId":0,"quantity":0}} 会被判为合法请求——这正是 P1-5 要拦的脏数据。
 * 为了过编译而静默删掉校验，等于“编译绿了、防线没了”，而且 {@code mvn -o test} 照样全绿。
 *
 * <p>分层要求（不许写假绿）：
 * <ul>
 *   <li>必须走真实 MockMvc 全链路 {@code post("/api/quotes")}，经过消息转换、参数绑定与
 *       {@code @Valid} 级联——<b>不能</b>退成「new 一个 request 对象再手动调校验器」，
 *       那样绕过了级联，摘掉 {@code @Valid} 也照样绿。</li>
 *   <li>断言是 HTTP 状态码 400（本项目统一形态：{@code code=INVALID_REQUEST}），
 *       不是「抛了异常」。</li>
 * </ul>
 *
 * <p>反向验证：把 {@code QuoteController.QuoteRequest} 的 {@code @Valid} 摘掉，
 * 本类前三条必须变红；{@link #validQuoteIsStillAccepted()} 保持绿色（它是阳性对照，
 * 证明 400 来自校验而不是鉴权/CSRF/URL 写错）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuoteRequestValidationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("商品编号与数量同时非法：POST /api/quotes 返回 400，且不落任何金额快照")
    void zeroProductIdAndZeroQuantityAreRejected() throws Exception {
        int quotesBefore = count("price_quote");

        mvc.perform(post("/api/quotes")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":0,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 增量断言：不假设库是空的，只证明这条请求没有多写出任何一行。
        assertThat(count("price_quote")).isEqualTo(quotesBefore);
    }

    @Test
    @DisplayName("数量越界（100 > 99）：POST /api/quotes 返回 400，且不落任何金额快照")
    void quantityAboveLimitIsRejected() throws Exception {
        int quotesBefore = count("price_quote");

        mvc.perform(post("/api/quotes")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":100}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(count("price_quote")).isEqualTo(quotesBefore);
    }

    @Test
    @DisplayName("商品编号为 0：POST /api/orders 返回 400，且不落任何订单")
    void orderPathRejectsNonPositiveProductId() throws Exception {
        int ordersBefore = count("mall_order");

        mvc.perform(post("/api/orders")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":0,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(count("mall_order")).isEqualTo(ordersBefore);
    }

    /** 阳性对照：合法请求仍应 200，证明上面的 400 来自校验而不是鉴权/CSRF/路径写错。 */
    @Test
    @DisplayName("阳性对照：合法商品行仍然 200，400 不是由鉴权或 CSRF 造成的")
    void validQuoteIsStillAccepted() throws Exception {
        mvc.perform(post("/api/quotes")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isOk());
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static String key() {
        return "qrv-" + UUID.randomUUID().toString().replace("-", "");
    }
}
