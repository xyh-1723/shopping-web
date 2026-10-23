package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.learning.mall.assistant.internal.ToolRegistry;
import dev.learning.mall.assistant.security.AssistantActor;
import dev.learning.mall.assistant.security.AssistantActors;
import dev.learning.mall.assistant.security.AssistantAudit;
import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderRefunds;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.pricing.api.MoneyUnits;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * 商家在助手对话里的退款审批工具（T13b）。
 *
 * <p>三条贯穿全类的纪律：
 * <ul>
 *   <li><b>身份来自真实账号解析链路</b>：actor 一律由 {@link AssistantActors#resolve(String)}
 *       从 {@code UserDetailsService} 解析。手造 {@code new AssistantActor("merchant", Set.of("ROLE_MERCHANT"))}
 *       会绕过「SecurityConfig 里 merchant 到底带了什么角色」这一环，而那正是
 *       「商家被误配成店员子类型」这类 bug 的藏身处。</li>
 *   <li><b>断言落在钱和库存上，不落在返回的字符串上</b>：工具类返回 {@code "status":"REFUNDED"}
 *       很容易做到——只要不真的调 {@code refunds.approve}，自己拼一个 JSON 就行。
 *       所以批准那条必须往下查 {@code wallet} 余额与 {@code wallet_ledger} 流水。
 *       只断言「文本里写了 REFUNDED」的测试，是「看起来在防、其实什么也没守」的典型。</li>
 *   <li><b>越权用例要用商家的工具包去执行</b>：如果用顾客自己的 bundle 去调
 *       {@code approveRefund}，第一道闸门（清单里根本没有这个工具）会先拦下，
 *       第二道闸门（方法内的角色硬校验）根本没被执行到——那条断言是<b>空跑</b>的。
 *       所以这里刻意拿<b>商家</b>的 bundle 配<b>非商家</b>的 actor，单独验证第二道闸门。</li>
 *   <li><b>退款是两档权限，不是一档</b>：队列 = 经营侧只读（STAFF + MERCHANT），
 *       审批 = 仅商家。与 HTTP 同一套答案（{@code GET /api/refunds/pending} →
 *       {@code hasAnyRole("STAFF","MERCHANT")}，{@code POST .../approve|reject} →
 *       {@code hasRole("MERCHANT")}）。用例⑤把这两档分开断言，互不掩盖。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantToolsTest {

    private static final String MERCHANT = "merchant";
    private static final String STAFF = "admin";
    private static final String CUSTOMER = "student";

    /** 商品5（机械键盘 ¥199）×2：折后 ¥373，超过 auto-refund-max-amount（¥200）→ 一定进人工队列。 */
    private static final int PRODUCT_ID = 5;
    private static final int QUANTITY = 2;

    /**
     * 审批人必须是「{@link #PRODUCT_ID} 这件货所属那家店」的店主。
     *
     * <p>V24 之后退款审批判归属：挂在别家店的商品，{@code merchant} 这个账号批不动——
     * 那正是 {@link MerchantOwnershipTest} 在守的事。所以这里按商品<b>回查</b>店主账号，
     * 而不是写死 {@code "merchant"}。写死的后果是「换一次商品归属分配公式，
     * 这批用例就因为归属不匹配而全红」，而那与「工具能不能用」毫无关系——
     * 那种红只会骗人去改工具，真正的病因却在数据上。
     *
     * <p>越权的那几条用例（顾客 / 店员拿商家的工具清单）不受影响，
     * 它们要的是「某个商家账号」，用哪个都行，仍用 {@link #MERCHANT}。
     */
    private String shopOwner;

    @Autowired ToolRegistry registry;
    @Autowired AssistantActors actors;
    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired OrderRefunds refunds;
    @Autowired JdbcClient jdbc;

    private String buyer;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("DELETE FROM assistant_audit").update();
        jdbc.sql("UPDATE product SET stock = 10 WHERE id = " + PRODUCT_ID).update();
        shopOwner = jdbc.sql("SELECT m.owner_username FROM merchant m"
                        + " JOIN product p ON p.merchant_id = m.id WHERE p.id = :id")
                .param("id", PRODUCT_ID).query(String.class).single();
    }

    @AfterEach
    void tearDown() {
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("DELETE FROM assistant_audit").update();
        if (buyer != null) {
            WalletProbe.purge(jdbc, buyer);
            buyer = null;
        }
    }

    // ——————————————————— ① 商家看得到队列 ———————————————————

    @Test
    @DisplayName("① 商家账号能看到退款审批工具，并真的查到待审批队列里的那一笔")
    void merchantSeesPendingQueue() {
        AssistantActor merchant = actors.resolve(shopOwner);
        String u = buyer("queue");
        OrderView paid = payWithWallet(u);
        RefundView queued = refunds.apply(paid.id(), u, null, "OTHER", "不想要了");

        ToolRegistry.Bundle bundle = registry.forActor(merchant);
        List<String> visible = bundle.specs().stream().map(ToolSpecification::name).toList();
        assertThat(visible).contains("pendingRefundQueue", "approveRefund", "rejectRefund");

        ToolRegistry.Execution out = exec(bundle, merchant, "pendingRefundQueue", "{}");

        assertThat(out.status()).isEqualTo(ToolRegistry.STATUS_OK);
        assertThat(out.text())
                .as("队列里必须出现刚才那笔：工具若只返回一句「暂无待审批」就等于什么也没查")
                .contains(queued.id())
                .contains(u)
                .contains("373.00");
        assertThat(refundStatus(queued.id())).isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
    }

    // ——————————————————— ② 批准 = 真的放款 ———————————————————

    @Test
    @DisplayName("② 批准真的把钱退回买家钱包（查余额与流水，不只看返回的字符串）")
    void approveDisbursesRealMoney() {
        AssistantActor merchant = actors.resolve(shopOwner);
        String u = buyer("approve");
        OrderView paid = payWithWallet(u);
        RefundView queued = refunds.apply(paid.id(), u, null, "OTHER", "发错货了");
        long expectedCents = MoneyUnits.toCents(paid.payAmount());
        long before = WalletProbe.balanceCents(jdbc, u);
        int stockBefore = stockOf();

        ToolRegistry.Execution out = exec(registry.forActor(merchant), merchant, "approveRefund",
                "{\"refundId\":\"" + queued.id() + "\",\"note\":\"核实后同意\"}");

        assertThat(out.status()).isEqualTo(ToolRegistry.STATUS_OK);
        assertThat(out.text()).contains("\"status\":\"REFUNDED\"").contains("\"decidedBy\":\"" + shopOwner + "\"");

        assertThat(refundStatus(queued.id())).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(decidedBy(queued.id())).isEqualTo(shopOwner);
        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("批准即放款：钱必须真的回到买家钱包，而不是只把状态改成 REFUNDED")
                .isEqualTo(before + expectedCents);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).containsExactly(expectedCents);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        assertThat(stockOf())
                .as("未发货的订单批准退款要归还库存（证明走的是 RefundService 那一份实现，不是工具类自己拼的 JSON）")
                .isEqualTo(stockBefore + QUANTITY);
    }

    // ——————————————————— ③ 驳回 = 不放款、不动库存 ———————————————————

    @Test
    @DisplayName("③ 驳回不放款、不动库存，且审批意见落库")
    void rejectDisbursesNothing() {
        AssistantActor merchant = actors.resolve(shopOwner);
        String u = buyer("reject");
        OrderView paid = payWithWallet(u);
        RefundView queued = refunds.apply(paid.id(), u, null, "OTHER", "拍错了");
        long before = WalletProbe.balanceCents(jdbc, u);
        int stockBefore = stockOf();

        ToolRegistry.Execution out = exec(registry.forActor(merchant), merchant, "rejectRefund",
                "{\"refundId\":\"" + queued.id() + "\",\"note\":\"已发货，不支持无理由\"}");

        assertThat(out.status()).isEqualTo(ToolRegistry.STATUS_OK);
        assertThat(refundStatus(queued.id())).isEqualTo(RefundView.STATUS_REJECTED);
        assertThat(decisionNote(queued.id())).isEqualTo("已发货，不支持无理由");
        assertThat(WalletProbe.balanceCents(jdbc, u)).as("驳回不放款").isEqualTo(before);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
        assertThat(stockOf()).as("驳回不动货").isEqualTo(stockBefore);
    }

    // ——————————————————— ④ 顾客越权 ———————————————————

    @Test
    @DisplayName("④ 普通顾客调商家工具：清单里没有（第一道），硬闯也被拒并留下 DENY 审计（第二道）")
    void customerIsDeniedInsideTheToolMethod() {
        String u = buyer("deny-user");
        OrderView paid = payWithWallet(u);
        RefundView queued = refunds.apply(paid.id(), u, null, "OTHER", "不想要了");
        AssistantActor customer = actors.resolve(CUSTOMER);
        long before = WalletProbe.balanceCents(jdbc, u);

        // ① 第一道：顾客的工具包里根本没有审批工具。
        ToolRegistry.Bundle customerBundle = registry.forActor(customer);
        assertThat(customerBundle.routes()).doesNotContainKeys("pendingRefundQueue", "approveRefund", "rejectRefund");

        // ② 第二道：即便拿着商家的工具包硬闯，方法内的角色校验也必须把它挡回去。
        //    刻意用 merchant 的 bundle —— 否则第一道会先拦下，这条断言根本没跑到第二道。
        ToolRegistry.Execution out = exec(registry.forActor(actors.resolve(MERCHANT)), customer,
                "approveRefund", "{\"refundId\":\"" + queued.id() + "\"}");

        // 先断言审计，再看文本：文本只是给模型看的形状，审计才是「越权真被挡住」的证据。
        assertThat(denyCount(CUSTOMER, "TOOL_FORBIDDEN_approveRefund"))
                .as("越权尝试必须写一条 DENY 审计：规则会漏，漏了必须有痕迹")
                .isEqualTo(1);
        assertThat(out.text()).contains("FORBIDDEN");

        // 队列是经营侧只读，也不对普通顾客开放——放宽给店员不等于放宽给所有人。
        ToolRegistry.Execution queue = exec(registry.forActor(actors.resolve(MERCHANT)), customer,
                "pendingRefundQueue", "{}");
        assertThat(denyCount(CUSTOMER, "TOOL_FORBIDDEN_pendingRefundQueue")).isEqualTo(1);
        assertThat(queue.text()).contains("FORBIDDEN");

        assertThat(refundStatus(queued.id())).isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        assertThat(WalletProbe.balanceCents(jdbc, u)).as("被拒的调用绝不能放款").isEqualTo(before);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
    }

    // ——————————————————— ⑤ 店员：看得见队列，批不了 ———————————————————

    /**
     * 已拍板的口径：<b>队列 = 经营侧只读（STAFF + MERCHANT）；审批 = 仅商家</b>，
     * 与 HTTP 层同一套答案（{@code GET /api/refunds/pending} →
     * {@code hasAnyRole("STAFF","MERCHANT")}，{@code POST .../approve|reject} →
     * {@code hasRole("MERCHANT")}）。
     *
     * <p>三条断言必须分开写：「看得见」只归队列，批准与驳回各一条。
     * 合成一句的话，把决策判据误放宽成 {@code isOperatingSide()} 这种改动会藏在
     * 「队列那条是绿的」后面不被发现。
     */
    @Test
    @DisplayName("⑤ 店员看得见待审批队列；批准与驳回各自被拒、各留一条 DENY 审计")
    void staffSeesQueueButCannotDecide() {
        String u = buyer("staff");
        OrderView paid = payWithWallet(u);
        RefundView queued = refunds.apply(paid.id(), u, null, "OTHER", "不想要了");
        AssistantActor staff = actors.resolve(STAFF);

        assertThat(staff.isStaff()).isTrue();
        assertThat(staff.isMerchant()).as("前提：admin 不带 ROLE_MERCHANT（并列角色）").isFalse();

        // ① 队列：店员自己的工具包里就有，而且真的查得到那一笔。
        ToolRegistry.Bundle staffBundle = registry.forActor(staff);
        assertThat(staffBundle.routes()).containsKey("pendingRefundQueue");
        assertThat(staffBundle.routes())
                .as("审批工具的声明绝不出现在店员的清单里：看得见不等于批得了，声明本身也不该下发")
                .doesNotContainKeys("approveRefund", "rejectRefund");

        ToolRegistry.Execution queue = exec(staffBundle, staff, "pendingRefundQueue", "{}");
        assertThat(queue.status()).isEqualTo(ToolRegistry.STATUS_OK);
        assertThat(queue.text())
                .as("店员应当看得到待审批队列：这是 HTTP hasAnyRole(STAFF,MERCHANT) 的同一口径")
                .contains(queued.id())
                .contains(u);

        // ② 批准：即便拿着商家的工具包硬闯，方法内的判据也必须挡回去。
        ToolRegistry.Execution approve = exec(registry.forActor(actors.resolve(MERCHANT)), staff,
                "approveRefund", "{\"refundId\":\"" + queued.id() + "\"}");
        // 先断言审计：文本断言只是形状，审计条数才是「判据真的执行了」的证据。
        // （反过来若只断言文本：判据被删时也仍然红，但红的是领域层抛错的文本，
        //   看不出是工具层失守——而那正是要盯的那一层。）
        assertThat(denyCount(STAFF, "TOOL_FORBIDDEN_approveRefund")).isEqualTo(1);
        assertThat(approve.text()).contains("FORBIDDEN");

        // ③ 驳回：与批准分开断言。两条判据是分别写死的，漏改其中一条不该被另一条掩盖。
        ToolRegistry.Execution reject = exec(registry.forActor(actors.resolve(MERCHANT)), staff,
                "rejectRefund", "{\"refundId\":\"" + queued.id() + "\"}");
        assertThat(denyCount(STAFF, "TOOL_FORBIDDEN_rejectRefund")).isEqualTo(1);
        assertThat(reject.text()).contains("FORBIDDEN");

        assertThat(refundStatus(queued.id())).isEqualTo(RefundView.STATUS_PENDING_APPROVAL);

        // 纵深防御：绕过工具层直接调领域服务，也必须被 requireMerchant 挡下，
        // 且断言到 code()——只断言 isInstanceOf(BusinessException.class) 太宽，
        // 「订单不存在」之类的业务异常会让它假绿。
        assertThatThrownBy(() -> refunds.approve(queued.id(), STAFF, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code())
                .isEqualTo("REFUND_APPROVAL_FORBIDDEN");
    }

    // ——————————————————— 工具 ———————————————————

    /** 执行一个工具调用：工具包与 actor 分开传，以便把两道闸门拆开验证。 */
    private ToolRegistry.Execution exec(ToolRegistry.Bundle bundle, AssistantActor actor,
                                        String tool, String args) {
        return registry.execute(bundle, ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString()).name(tool).arguments(args).build(), actor);
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(PRODUCT_ID, QUANTITY))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private String buyer(String suffix) {
        buyer = "refund-tool-" + suffix;
        return buyer;
    }

    private int stockOf() {
        return jdbc.sql("SELECT stock FROM product WHERE id = :id").param("id", PRODUCT_ID)
                .query(Integer.class).single();
    }

    private String refundStatus(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private String decisionNote(String refundId) {
        return jdbc.sql("SELECT decision_note FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).optional().orElse(null);
    }

    private String decidedBy(String refundId) {
        return jdbc.sql("SELECT decided_by FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    /** 某个 username 在工具阶段、某条规则上的 DENY 条数。 */
    private long denyCount(String username, String ruleId) {
        return jdbc.sql("SELECT COUNT(*) FROM assistant_audit "
                        + "WHERE username = :u AND stage = :stage AND action = :action AND rule_id = :rule")
                .param("u", username)
                .param("stage", AssistantAudit.STAGE_TOOL)
                .param("action", "DENY")
                .param("rule", ruleId)
                .query(Long.class)
                .single();
    }
}
