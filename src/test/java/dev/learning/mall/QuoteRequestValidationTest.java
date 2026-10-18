package dev.learning.mall;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

    /**
     * 本类建过的金额快照号（跨用例累积）：{@link #noQuoteIsLeftBehind()} 用它把自查的范围
     * 收窄到本类自己的行上。
     *
     * <p>为什么不能用用户名收窄：这个类的所有请求都扮演<b>种子用户 {@code student}</b>，
     * 而项目里一大半的测试都在用它。按用户名删/查等于把别的类写的快照也算进来，
     * 于是「跑完是不是 0」取决于之前跑过哪些类——单跑绿、全量红。
     * 按快照自己的 UUID 收窄就没有这个问题。
     */
    private static final List<String> seenQuoteIds = new CopyOnWriteArrayList<>();

    /** {@code @AfterAll} 必须是 static，拿不到实例的 {@code jdbc}；这里由 {@code @AfterEach} 递进来。 */
    private static JdbcClient trailJdbc;

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
        String body = mvc.perform(post("/api/quotes")
                        .with(user("student").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 这条用例是四类里唯一真的落一行 price_quote 的：把它记下来，好让 @AfterEach 清掉它。
        seenQuoteIds.add(JsonPath.read(body, "$.id"));
    }

    /**
     * 金额快照的清理：本类跑完之后不留残迹给下一个测试类。
     *
     * <p>按快照号收窄而不是按用户名：见 {@link #seenQuoteIds} 的说明。
     */
    @AfterEach
    void cleanUpQuotes() {
        trailJdbc = jdbc;
        if (seenQuoteIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM price_quote WHERE id IN (:ids)")
                .param("ids", List.copyOf(seenQuoteIds)).update();
    }

    /**
     * 哨兵：本类跑完之后，自己建过的金额快照一行都不剩。
     *
     * <p>它存在的<b>唯一</b>理由是「清理本身无法被变异验证」：把上面那个
     * {@code @AfterEach} 删掉，全量测试仍然全绿——没有任何用例按全表计数断言
     * {@code price_quote}（本类自己的断言是<b>增量式</b>的，本来就免疫残迹），
     * 清理就这么静悄悄地失效了。有了这条断言，删掉清理就会变红。
     */
    @AfterAll
    static void noQuoteIsLeftBehind() {
        if (trailJdbc == null || seenQuoteIds.isEmpty()) {
            return;
        }
        assertThat(trailJdbc.sql("SELECT COUNT(*) FROM price_quote WHERE id IN (:ids)")
                .param("ids", List.copyOf(seenQuoteIds)).query(Integer.class).single())
                .as("本类建过的金额快照在 price_quote 里必须一行不剩：残迹会一路带到下一个测试类")
                .isZero();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }

    private static String key() {
        return "qrv-" + UUID.randomUUID().toString().replace("-", "");
    }
}
