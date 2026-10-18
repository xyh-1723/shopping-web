package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learning.mall.wallet.internal.WalletProperties;
import dev.learning.mall.wallet.internal.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 钱包端点（T19）的验收测试：余额与流水第一次对用户可见，可见性的另一半是「看不见别人的」。
 *
 * <p>四条必须同时成立，缺一条这条链路就还是断的：
 * <pre>
 *   匿名         → 401（不是 500，也不是「看得见但金额是 0」）
 *   已登录       → 200 且余额结构完整（分 + 元 + 流水）
 *   充值         → 余额真的增加，且最新一条流水就是这次充值
 *   超出上限     → 409 TOPUP_LIMIT_EXCEEDED，余额一分不动
 * </pre>
 *
 * <p><b>为什么走 MockMvc 而不是直接调 {@link WalletAccounts}</b>：
 * 本次要守的是<b>端点</b>——401 是 {@code SecurityConfig} 给的，不是服务给的，
 * 直接调服务根本碰不到它。服务层的金额语义由 {@code WalletLedgerTest} /
 * {@code WalletPaymentTest} 守，这里不重复。
 *
 * <p><b>用户名每次都换一个新的</b>：H2 内存库在整个 JVM 内共享，
 * 假设库里没有这个人而写出的断言会在换一个执行顺序后随机变红（{@code WalletLedgerTest} 同一条纪律）。
 *
 * <p><b>反向验证</b>：每条断言都做过变异验证（注回 bug → 变红 → 还原），逐条见提交信息。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired WalletService wallet;
    @Autowired WalletProperties properties;
    @Autowired PlatformTransactionManager txManager;

    private String user;

    @AfterEach
    void cleanUp() {
        properties.setTopupEnabled(true);
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
    }

    /** 取一个本用例专属的用户名（顺便登记给 {@link #cleanUp()}）。 */
    private String newUser(String suffix) {
        user = "wallet-ui-" + suffix;
        return user;
    }

    /** 读出钱包视图：既要看 HTTP 状态，也要看返回的结构。 */
    private JsonNode wallet(String username) throws Exception {
        String body = mvc.perform(get("/api/wallet").with(user(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    @DisplayName("① 匿名访问钱包是 401：未登录不能查任何人的余额（不是 500，更不是 200 + 空余额）")
    void anonymousCannotReadOrTopUpWallet() throws Exception {
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/wallet/top-up").with(csrf())).andExpect(status().isUnauthorized());

        // 补一条：连「账户不存在」这件事都不能从匿名请求里被探测出来——
        // 库里没有这个人的任何一行，说明上面两次请求没有触发懒开户。
        assertThat(WalletProbe.walletRowCount(jdbc, "wallet-ui-anon")).isZero();
    }

    @Test
    @DisplayName("② 已登录能查到余额结构：分是唯一真相、元是展示形态、开户流水跟着回来")
    void loggedInUserSeesBalanceAndOpeningEntry() throws Exception {
        String u = newUser("read");

        JsonNode view = wallet(u);

        assertThat(view.get("balance").get("username").asText()).isEqualTo(u);
        assertThat(view.get("balance").get("balanceCents").asLong())
                .isEqualTo(properties.getInitialGrantCents());
        // 元与分必须同源：一个 ¥500.00 对应 50000 分，页面拿这个字段显示，
        // 它和分对不上就说明「元」这条口径又从后门进来了。
        assertThat(view.get("balance").get("balanceYuan").decimalValue())
                .isEqualByComparingTo("500.00");
        assertThat(view.get("recent").isArray()).isTrue();
        assertThat(view.get("recent").size()).isEqualTo(1);
        assertThat(view.get("recent").get(0).get("reason").asText()).isEqualTo("OPENING");
        assertThat(view.get("recent").get(0).get("deltaCents").asLong())
                .isEqualTo(properties.getInitialGrantCents());
        assertThat(view.get("recent").get(0).get("orderId").isNull()).isTrue();
    }

    @Test
    @DisplayName("③ 充值后余额真的增加，且最新一条流水就是这次充值（不是按时间碰巧排对）")
    void topUpRaisesBalanceAndHeadsTheLedger() throws Exception {
        String u = newUser("topup");
        long before = wallet(u).get("balance").get("balanceCents").asLong();

        String body = mvc.perform(post("/api/wallet/top-up").with(user(u)).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(body);

        assertThat(view.get("balance").get("balanceCents").asLong())
                .isEqualTo(before + properties.getTopupAmountCents());
        // 新的在前：第一条必须是这次 TOPUP，OPENING 被挤到后面。
        // 这条同时守住了「按 id 倒序」——按 created_at（精度到秒）排会在这里随机失败。
        assertThat(view.get("recent").get(0).get("reason").asText()).isEqualTo("TOPUP");
        assertThat(view.get("recent").get(0).get("deltaCents").asLong())
                .isEqualTo(properties.getTopupAmountCents());
        assertThat(view.get("recent").get(1).get("reason").asText()).isEqualTo("OPENING");

        // 端点返回的余额必须与真实余额一致（页面显示的不是一份「看起来对」的缓存）。
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .isEqualTo(before + properties.getTopupAmountCents());
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    @Test
    @DisplayName("④ 累计充值超过上限：409 TOPUP_LIMIT_EXCEEDED，且余额与流水都停在最后一次成功的状态")
    void topUpBeyondCeilingIsRejected() throws Exception {
        String u = newUser("ceiling");
        long before = wallet(u).get("balance").get("balanceCents").asLong();
        long amount = properties.getTopupAmountCents();
        long ceiling = properties.getTopupCeilingCents();
        long granted = WalletProbe.grantedCents(jdbc, u);

        int affordable = (int) ((ceiling - granted) / amount);
        assertThat(affordable).isPositive(); // 配置若改成「一上来就超上限」，这里先炸，而不是给出一条假绿的断言

        for (int i = 0; i < affordable; i++) {
            mvc.perform(post("/api/wallet/top-up").with(user(u)).with(csrf()))
                    .andExpect(status().isOk());
        }
        long afterAllowed = WalletProbe.balanceCents(jdbc, u);
        assertThat(afterAllowed).isEqualTo(before + affordable * amount);

        mvc.perform(post("/api/wallet/top-up").with(user(u)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPUP_LIMIT_EXCEEDED"));

        // 拒绝必须是一次彻底的拒绝：没写流水、余额一分未动。
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(afterAllowed);
        assertThat(WalletProbe.deltas(jdbc, u, "TOPUP")).hasSize(affordable);
    }

    @Test
    @DisplayName("⑤ 总开关关闭时充值：409 TOPUP_DISABLED，而不是静默成功")
    void topUpDisabledIsRejected() throws Exception {
        String u = newUser("disabled");
        long before = wallet(u).get("balance").get("balanceCents").asLong();
        properties.setTopupEnabled(false);

        mvc.perform(post("/api/wallet/top-up").with(user(u)).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOPUP_DISABLED"));

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(before);
    }

    @Test
    @DisplayName("⑥ 流水条数有上限：页面一次最多拿 20 条，不会因为流水越积越多把整页拖垮")
    void ledgerIsCapped() throws Exception {
        String u = newUser("cap");
        // 造 25 条流水走的是真实入账入口（不是裸 SQL）：端点上限是「返回多少」的上限，
        // 被测行为是那一条 LIMIT，制造数据的手段不该是它的替身。
        // 用 credit 而不是 topUp：充值有累计上限，连续 25 次会在第 6 次就被拒。
        var tx = new TransactionTemplate(txManager);
        for (int i = 0; i < 25; i++) {
            // 每笔一个独立订单号：uq_wallet_ledger_order_reason 钉着「同一订单同一原因只留一条」。
            String orderId = UUID.randomUUID().toString();
            tx.executeWithoutResult(status -> wallet.credit(u, 1L, "tester", orderId));
        }
        assertThat(WalletProbe.ledgerRowCount(jdbc, u)).isEqualTo(26L); // 25 笔入账 + 1 条开户

        JsonNode recent = wallet(u).get("recent");
        assertThat(recent.size()).isEqualTo(20);
        // 上限之外还得是对的那一批：最新的在前，所以第一条必须是最后写进去的那笔。
        assertThat(recent.get(0).get("reason").asText()).isEqualTo("REFUND");
    }
}
