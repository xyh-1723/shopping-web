package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.learning.mall.assistant.api.AssistantStatus;
import dev.learning.mall.assistant.internal.AssistantService;
import dev.learning.mall.assistant.internal.ToolRegistry;
import dev.learning.mall.assistant.knowledge.KnowledgeSegment;
import dev.learning.mall.assistant.knowledge.KnowledgeService;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantActors;
import dev.learning.mall.assistant.security.AssistantAudit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 商家角色（{@code ROLE_MERCHANT}）的验收测试。
 *
 * <p>这是 T13a 的地基部分：账号存在、身份能被解析、权限边界正确、内部知识的可见性语义正确。
 * 退款本身属于 V16/T14，本次不涉及——所以这里刻意<b>不</b>断言任何退款工具，
 * 因为那些工具现在根本不存在，断言它们只会得到一条「看起来在防、其实什么也没守」的测试。
 *
 * <p>两条贯穿全类的原则：
 * <ul>
 *   <li><b>身份必须来自真实链路</b>：除「双角色」那条用例（它测的是单纯的呈现逻辑）外，
 *       actor 一律由 {@link AssistantActors#resolve(String)} 从 {@code UserDetailsService} 解析得到。
 *       手造一个只有 {@code ROLE_MERCHANT} 的 actor 会绕过「账号到底怎么配的」这一环，
 *       而那正是「商家被误配成店员子类型」这个 bug 的藏身处。</li>
 *   <li><b>断言要能被注回的 bug 打红</b>：每条断言都对应一个具体的实现错误，
 *       均经过变异验证（注回 → 变红 → 还原）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantRoleTest {

    /** 店员专属工具：商家一条都不该拿到（改价 / 改库存 / 发货与经营查询都在这里面）。 */
    private static final List<String> STAFF_TOOL_NAMES =
            List.of("inventoryAlerts", "salesOverview", "customerOrders");

    @Autowired AssistantActors actors;
    @Autowired UserDetailsService userDetails;
    @Autowired ToolRegistry registry;
    @Autowired KnowledgeService knowledge;
    @Autowired AssistantService service;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM assistant_audit").update();
    }

    // ——————————————————— ① 权限边界 ———————————————————

    @Test
    @DisplayName("① 商家拿不到店员工具：身份走真实账号解析链路，且工具注册表不把 staffTools 下发给商家")
    void merchantCannotSeeStaffWriteTools() {
        // 关键前提：actor 来自 UserDetailsService，而不是 new AssistantActor("merchant", Set.of("ROLE_MERCHANT"))。
        // 手造 actor 会让「SecurityConfig 里 merchant 到底带了什么角色」这条路径完全不被覆盖。
        AssistantActor merchant = actors.resolve("merchant");

        assertThat(merchant.roles())
                .as("merchant 账号必须带 ROLE_MERCHANT，且刻意不带 ROLE_STAFF（并列角色，不是子类型）")
                .contains(AssistantActor.ROLE_MERCHANT)
                .doesNotContain(AssistantActor.ROLE_STAFF);
        assertThat(merchant.isMerchant()).isTrue();
        assertThat(merchant.isStaff()).isFalse();
        assertThat(merchant.isOperatingSide()).as("商家属于经营侧：内部数据可见").isTrue();

        ToolRegistry.Bundle bundle = registry.forActor(merchant);
        List<String> visible = bundle.specs().stream().map(ToolSpecification::name).toList();
        assertThat(visible)
                .as("商家不得看到任何店员工具（含经营查询与运营执行入口）")
                .doesNotContainAnyElementsOf(STAFF_TOOL_NAMES);
        assertThat(bundle.routes()).doesNotContainKeys("inventoryAlerts", "salesOverview", "customerOrders");

        // 即便模型「幻觉」出一个店员工具名，执行层也必须拒绝并留痕，而不是抛异常或放行。
        ToolRegistry.Execution denied = registry.execute(bundle,
                ToolExecutionRequest.builder().id("m1").name("salesOverview").arguments("{}").build(),
                merchant);
        assertThat(denied.ok()).isFalse();
        assertThat(denied.status()).isEqualTo(ToolRegistry.STATUS_DENIED);
    }

    // ——————————————————— ② 角色呈现顺序 ———————————————————

    @Test
    @DisplayName("② 同时带 STAFF 与 MERCHANT 的账号仍报 STAFF（roleName 的判定顺序不能反）")
    void dualRolePrefersStaff() {
        // 既有 admin 就是这个形状（USER + STAFF）；将来若再挂 MERCHANT，
        // 徽标与既有断言都不该漂移，所以这里显式构双角色账号来锁顺序。
        AssistantActor dual = new AssistantActor("dual-role",
                Set.of(AssistantActor.ROLE_USER, AssistantActor.ROLE_STAFF, AssistantActor.ROLE_MERCHANT));

        assertThat(dual.isStaff()).isTrue();
        assertThat(dual.isMerchant()).isTrue();
        assertThat(dual.isOperatingSide()).isTrue();
        assertThat(dual.roleName()).isEqualTo("STAFF");
        assertThat(dual.capabilityHint()).contains("店员").doesNotContain("普通顾客");
    }

    // ——————————————————— ③ 知识隔离语义 ———————————————————

    @Test
    @DisplayName("⑥ 商家看得见内部运营文档，且不被后置租户过滤打回（两处改动点必须一起改）")
    void merchantSeesInternalDocs() {
        AssistantActor merchant = actors.resolve("merchant");

        List<KnowledgeService.KnowledgeHit> hits = knowledge.search("陶瓷供应商的联系方式", merchant, "merchant");

        assertThat(hits).as("商家属于经营侧，内部文档应当可见").isNotEmpty()
                .anySatisfy(h -> {
                    assertThat(h.visibility()).isEqualTo(KnowledgeSegment.STAFF);
                    assertThat(h.title()).contains("供应商");
                    assertThat(h.snippet()).contains("8369"); // 座机保留
                });
        assertThat(ragDenyCount("merchant"))
                .as("先命中再被后置过滤丢掉 = 只改了一处判定点的典型症状，会刷出一堆 RAG_TENANT_FILTER DENY")
                .isZero();

        // 反向控制：顾客仍然看不到内部文档——证明上面不是靠「把过滤整段删掉」变绿的。
        List<KnowledgeService.KnowledgeHit> customerHits =
                knowledge.search("陶瓷供应商的联系方式", actors.resolve("student"), "merchant-customer");
        assertThat(customerHits)
                .allSatisfy(h -> assertThat(h.visibility()).isEqualTo(KnowledgeSegment.PUBLIC));
    }

    // ——————————————————— 哨兵：失败方向必须安全 ———————————————————

    @Test
    @DisplayName("哨兵：解析不到的账号必须降级为最小权限 ROLE_USER，绝不能因为「查不到」而放行")
    void unknownActorDegradesToLeastPrivilege() {
        AssistantActor ghost = actors.resolve("merchant-no-such-user");

        assertThat(ghost.roles()).containsExactly(AssistantActor.ROLE_USER);
        assertThat(ghost.isStaff()).isFalse();
        assertThat(ghost.isMerchant()).isFalse();
        assertThat(ghost.isOperatingSide()).isFalse();
        assertThat(ghost.roleName()).isEqualTo("USER");
        assertThat(registry.forActor(ghost).specs().stream().map(ToolSpecification::name).toList())
                .doesNotContainAnyElementsOf(STAFF_TOOL_NAMES);
    }

    // ——————————————————— 附加：URL 级规则仍然生效 ———————————————————

    @Test
    @DisplayName("附加：商家打 /api/assistant/audit 必须 403（新增账号不等于继承 STAFF）")
    void merchantIsForbiddenOnStaffAuditEndpoint() throws Exception {
        // 用真实账号构造 UserDetails：这样 URL 规则测的就是 SecurityConfig 里那一份配置，
        // 而不是测试里手写的一份「我以为的角色」。
        UserDetails merchant = userDetails.loadUserByUsername("merchant");
        UserDetails admin = userDetails.loadUserByUsername("admin");

        mvc.perform(get("/api/assistant/audit").with(user(merchant)))
                .andExpect(status().isForbidden());
        // 正向对照：端点本身是通的——上面的 403 不是「谁都进不去」造成的假绿。
        mvc.perform(get("/api/assistant/audit").with(user(admin)))
                .andExpect(status().isOk());
    }

    // ——————————————————— 附加：能力清单不说空话 ———————————————————

    @Test
    @DisplayName("附加：状态接口对商家说实话——只列此刻真的做得到的能力")
    void merchantCapabilitiesStayHonest() {
        AssistantStatus status = service.status("merchant");

        assertThat(status.role()).isEqualTo("MERCHANT");
        assertThat(status.capabilities())
                .contains("检索内部运营文档（供应商与经营口径）")
                .as("T13b 后商家真的有退款审批工具，能力清单就得如实列出来")
                .contains("查询待审批的退款队列", "批准退款申请（立即放款给买家）", "驳回退款申请")
                .as("商家拿不到 staffTools，把店员能力列上去就是空承诺")
                .doesNotContain("库存预警与补货建议", "全站经营概览", "按用户名查询订单");
        assertThat(actors.resolve("merchant").capabilityHint())
                .contains("商家")
                .contains("待审批")
                .as("T13b 后退款审批已真的开放：再写「尚未开放」就是一句与事实相反的陈述，"
                        + "会让模型对买家说错话（空承诺与谎报能力是同一个毛病的两面）")
                .doesNotContain("尚未开放");
    }

    // ——————————————————— 附加：店员的清单也要说实话 ———————————————————

    /**
     * 队列工具对店员<b>可用</b>（{@code RefundQueueTools} 判 {@code isOperatingSide()}），
     * 于是店员的能力清单与系统提示<b>都必须写出来</b>，且必须点明只读。
     *
     * <p>两处一起断言的理由与商家那条相同：只改一处会自相矛盾——
     * 清单说能做、提示说不能做（或反过来），模型收到的是两个互相打架的指令。
     */
    @Test
    @DisplayName("附加：店员能力清单写明「能看队列但不能批」——工具可用却不说，等于漏报能力")
    void staffCapabilitiesMentionTheReadOnlyQueue() {
        AssistantActor staff = actors.resolve("admin");

        assertThat(staff.isStaff()).isTrue();
        assertThat(staff.isMerchant()).as("前提：admin 不带 ROLE_MERCHANT（并列角色）").isFalse();
        // 能力清单说的必须是真话，所以先确认工具真的在店员手里。
        assertThat(registry.forActor(staff).routes()).containsKey("pendingRefundQueue");

        assertThat(service.status("admin").capabilities())
                .as("队列工具对店员可用，清单里就必须写出来：否则模型会回答「我没有这个能力」，"
                        + "用户永远走不到那个工具。漏报能力与谎报能力是同一个问题的两面")
                .contains("查看待审批的退款队列（只读，审批归商家）");
        assertThat(staff.capabilityHint())
                .contains("待审批")
                .as("措辞必须点明只读：批准 / 驳回是商家专属")
                .contains("只读")
                .doesNotContain("可以批准", "可以驳回");
        // 反向控制：清单里不许出现审批动作——看得见不等于批得了。
        assertThat(service.status("admin").capabilities())
                .doesNotContain("批准退款申请（立即放款给买家）", "驳回退款申请");
    }

    /** RAG 阶段针对某个 username 的 DENY 条数：用来发现「命中又被过滤」这类自相矛盾的改动。 */
    private long ragDenyCount(String username) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM assistant_audit "
                        + "WHERE stage = :stage AND action = :action AND username = :username")
                .param("stage", AssistantAudit.STAGE_RAG)
                .param("action", "DENY")
                .param("username", username)
                .query(Integer.class)
                .single();
        return n == null ? 0L : n;
    }
}
