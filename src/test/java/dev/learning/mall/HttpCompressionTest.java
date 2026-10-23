package dev.learning.mall;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 响应压缩的守护（B1）。
 *
 * <p><b>为什么必须走真实端口</b>：压缩是 servlet 容器（这里是 Tomcat）在响应写出时做的，
 * 位置在过滤器链之外。MockMvc 只调用 {@code DispatcherServlet}，根本不经过这一层，
 * 所以在 MockMvc 里断言 {@code Content-Encoding: gzip} 恒为 null——
 * 那种断言不管配置开没开都是「绿」的，是典型的假绿。
 * 因此这个类用 {@code webEnvironment = RANDOM_PORT}，并用 JDK 原生 {@link HttpClient}
 * 直连：它不会替我们解压，能拿到原始的 gzip 字节和响应头。
 *
 * <p>反向验证：把 {@code server.compression.enabled} 改成 false（或直接删掉这段配置），
 * 下面两条断言必须都红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HttpCompressionTest {

    private static final long FIXTURE_ID_BASE = 900_000L;

    /** 填充商品的统一前缀：既是被搜索的关键词，也让插进来的行一眼认得出是谁造的。 */
    private static final String SEED_KEYWORD = "低库存商品";

    /** 造多少件商品：响应体必须稳定超过 1024 字节的压缩门槛，不依赖目录里原有几件。 */
    private static final int SEED_COUNT = 40;

    /**
     * 带筛选与显式 size 的列表 URL。
     *
     * <p>为什么必须带 {@code keyword}：列表接口现在是分页的，默认只回前 24 件，
     * 而基准商品是 {@code id >= 900001} 现插的——不筛就根本不在第一页里，
     * 于是「压缩生效」这条断言会退化成在压一个跟本测试无关的首页。
     */
    private static final String PRODUCTS_URL = "/api/products?keyword=" + SEED_KEYWORD + "&size=100";

    @LocalServerPort int port;

    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM product WHERE id > :base").param("base", FIXTURE_ID_BASE).update();
    }

    @Test
    @DisplayName("真实容器下 GET /api/products 被 gzip 压缩，且解压回来是合法 JSON")
    void productsApiIsGzippedOnRealContainer() throws Exception {
        seed(SEED_COUNT);

        HttpResponse<byte[]> gzipped = send(PRODUCTS_URL, true);

        assertThat(gzipped.statusCode()).isEqualTo(200);
        assertThat(gzipped.headers().firstValue("Content-Encoding").orElse(""))
                .as("几百件商品的商品列表不该裸奔：真实容器必须写出 Content-Encoding: gzip")
                .contains("gzip");

        byte[] raw = gzipped.body();
        // gzip 魔数 0x1f 0x8b：确认拿到的是真 gzip 字节，而不是只是加了个头。
        assertThat(raw).hasSizeGreaterThan(2);
        assertThat(raw[0]).isEqualTo((byte) 0x1f);
        assertThat(raw[1]).isEqualTo((byte) 0x8b);

        String json = gunzip(raw);
        assertThat(json).startsWith("{");
        assertThat(json).contains("低库存商品").contains("\"total\"");
    }

    @Test
    @DisplayName("压缩后确实变小：这才是开 gzip 的理由（瓶颈在字节不在 CPU）")
    void gzippedBodyIsMuchSmallerThanPlain() throws Exception {
        seed(SEED_COUNT);

        int plain = send(PRODUCTS_URL, false).body().length;
        int compressed = send(PRODUCTS_URL, true).body().length;

        assertThat(plain).as("响应体必须先超过 1024 字节的压缩门槛")
                .isGreaterThan(1024);
        assertThat(compressed).as("gzip 对重复的 JSON 字段名压缩率很高，压完必须明显更小")
                .isLessThan(plain / 2);

        // 顺手把量级打出来，方便人眼核对（断言不依赖它）。
        System.out.printf("  /api/products?size=100: 原始 %d 字节 → gzip %d 字节（%.1f%%）%n",
                plain, compressed, 100.0 * compressed / plain);
    }

    @Test
    @DisplayName("客户端不声明 Accept-Encoding 时不压缩：证明是按需压缩而不是一律压缩")
    void noAcceptEncodingMeansNoCompression() throws Exception {
        seed(SEED_COUNT);

        HttpResponse<byte[]> plain = send(PRODUCTS_URL, false);

        assertThat(plain.headers().firstValue("Content-Encoding")).as("未声明 Accept-Encoding 时不应压缩").isEmpty();
        assertThat(new String(plain.body(), StandardCharsets.UTF_8)).startsWith("{");
    }

    private HttpResponse<byte[]> send(String path, boolean gzip) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (gzip) builder.header("Accept-Encoding", "gzip");
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String gunzip(byte[] raw) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void seed(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.sql("INSERT INTO product(id, name, description, category, illustration, price, stock,"
                            + " merchant_id) VALUES(:id,:n,:d,'生活','box',:p,:s,1)")
                    // merchant_id 在 V24 之后是 NOT NULL 且没有默认值：造商品必须回答「谁的货」。
                    // 这些填充商品与归属无关（用例验证的是分页 / 压缩 / 告警上限），
                    // 一律挂 1 号店——写死一个确定的答案，比在这里再发明一套分配规则好。
                    .param("id", FIXTURE_ID_BASE + i + 1)
                    .param("n", SEED_KEYWORD + i)
                    .param("d", "用于把响应体撑过压缩门槛，以便验证 gzip 真的生效。")
                    .param("p", 9.9)
                    .param("s", 50)
                    .update();
        }
    }
}
