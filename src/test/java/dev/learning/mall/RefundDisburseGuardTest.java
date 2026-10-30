package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.order.api.CreateOrderRequest;
import dev.learning.mall.order.api.OrderPayments;
import dev.learning.mall.order.api.OrderView;
import dev.learning.mall.order.api.RefundView;
import dev.learning.mall.order.internal.FulfillmentService;
import dev.learning.mall.order.internal.OrderFacade;
import dev.learning.mall.order.internal.RefundService;
import dev.learning.mall.payment.internal.DemoWalletPayment;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 放款第二段（{@code APPROVED → REFUNDED}）没拿到行时，<b>必须响亮地失败</b>。
 *
 * <p>T14 收尾时这里写的是 {@code if (markRefunded(...) != 1) return;}。
 * 静默 return 会让整笔退款停在 {@code APPROVED}——<b>既没放款、也没发站内信，还不报错</b>。
 * 这是「不红不报错」里最坏的一种：对账的人看到 APPROVED 会以为「正在处理」，
 * 线上会一直挂着，而唯一知道它发生过的人只有恰好翻到那条日志的人。
 * 本轮把它改成抛 {@code ALREADY_RESOLVED}，与紧邻的上一处（{@code markApproved} 的
 * 0 行分支）保持同一个形状：同一个事务里的两道闸门，失败方式也必须一致。
 *
 * <h3>诚实边界：这段路径在业务上不可达，所以它是被「造」出来的</h3>
 *
 * <p>同一个事务里 {@code markApproved} 刚影响 1 行，紧接着 {@code markRefunded}
 * 必然命中——没有任何并发能插进这两条语句之间（行锁在事务提交前不释放）。
 * 正因为不可达，本用例<b>不假装它能自然发生</b>，而是把
 * 「{@code UPDATE refund SET status='REFUNDED'}」这一次写调用替换成返回 0 行，
 * 让那个分支真的被走到一次。这样断言到的东西只有一件：
 * <b>一旦这段分支被走到，代码是响亮的而不是沉默的</b>。
 *
 * <p>它<b>不</b>证明「生产上真会走到」。那正是「不可达才更不能静默」的含义：
 * 一旦它响了，就是并发或状态机被破坏的强信号，而这是收到那个信号的唯一方式。
 *
 * <h3>断言为什么落在「回滚后停在 PENDING_APPROVAL」上</h3>
 *
 * <p>换成静默 return 后，抛异常与不抛异常<b>都不会动钱</b>（放款在那个 return 之后），
 * 所以「余额没变」「放款没被调用」这两条在两种实现下都绿——它们是必要的，
 * 但不是区分点。<b>区分点是事务的结局</b>：抛出会让整笔回滚，退款回到
 * {@code PENDING_APPROVAL}；静默 return 会让事务<b>提交</b>，留一条永远停在
 * {@code APPROVED} 的记录。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundDisburseGuardTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 18, 15, 0, 0);

    private static final String MERCHANT = "merchant";

    /** 商品1（¥39）×1 → ¥39 + 运费 8 = ¥47.00。 */
    private static final long ORDER_CENTS = 4_700L;

    @Autowired OrderFacade facade;
    @Autowired OrderPayments payments;
    @Autowired FulfillmentService fulfillment;
    @Autowired RefundService refunds;
    @MockitoBean Clock clock;
    @MockitoSpyBean DemoWalletPayment walletGateway;
    /**
     * {@code JdbcClient} 的 spy：仓库层的每一次 SQL 都经过它，
     * 于是「只让某一条 UPDATE 返回 0 行」不必把包级可见的 {@code RefundRepository}
     * 为了测试暴露成 public（那会把模块内部通路变成对外端口）。
     *
     * <p>它同时也是本用例自己查库用的那个 JdbcClient：桩之外的调用全部落到真实实现。
     */
    @MockitoSpyBean JdbcClient jdbc;

    private String user;

    @BeforeEach
    void setUp() {
        for (String table : List.of("notification", "outbox_event", "order_item", "mall_order")) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("DELETE FROM refund").update();
        jdbc.sql("UPDATE product SET stock = 30 WHERE id = 1").update();
        at(BASE);
        clearInvocations(walletGateway);
    }

    @AfterEach
    void tearDown() {
        // 关键：spy 上的桩是跨用例累积的，不清掉会污染同一个上下文里的后续用例，
        // 而且一旦泄漏出去，别的测试类拿到被桩过的 JdbcClient 会表现得像数据库坏了。
        Mockito.reset(jdbc);
        jdbc.sql("DELETE FROM refund").update();
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
    }

    // ——————————————————— 放款第二段落空 ———————————————————

    @Test
    @DisplayName("放款第二段没拿到行：抛 ALREADY_RESOLVED 并整笔回滚，绝不停在 APPROVED 上什么也不说")
    void stalledRefundLegIsLoudAndRolledBack() {
        String u = user("stalled");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-STALL-1");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "WRONG_ITEM", "发错款式");
        assertThat(queued.status()).as("用例前提：这笔在等商家").isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        long balanceBefore = WalletProbe.balanceCents(jdbc, u);
        int stockBefore = stockOf();

        // 只把「APPROVED → REFUNDED」这一次 UPDATE 换成 0 行；其余 SQL 全部走真实实现。
        mockUpdateReturningZeroRows("UPDATE refund SET status='REFUNDED'");

        BusinessException thrown = catchThrowableOfType(
                () -> refunds.approve(queued.id(), MERCHANT, "核实后同意"), BusinessException.class);

        assertThat(thrown).as("第二段落空必须报错：静默 return 会留一条停在 APPROVED 的死记录").isNotNull();
        assertThat(thrown.code()).isEqualTo("ALREADY_RESOLVED");
        assertThat(thrown.status().value()).isEqualTo(409);

        // 区分点：整笔回滚 → 退款回到「还在等」，而不是提交一条 APPROVED。
        assertThat(statusOf(queued.id()))
                .as("回滚后必须回到 PENDING_APPROVAL：停在 APPROVED 就是「不红不报错」的那个形状")
                .isEqualTo(RefundView.STATUS_PENDING_APPROVAL);
        assertThat(decidedByOf(queued.id())).as("审批人也要跟着回滚：不能留下「某某批过」的痕迹").isNull();

        // 必要的辅助断言（它们在两种实现下都成立，守住的是「失败没有夹带副作用」）。
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(balanceBefore);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
        assertThat(stockOf()).isEqualTo(stockBefore);
        verify(walletGateway, never()).refund(any());
    }

    /**
     * AUTO 路由走的是同一个 {@code disburse}，所以它也要响。
     *
     * <p>这两条路径共用的正是「放款唯一入口」——只修商家那条会把自助退款漏在后面。
     */
    @Test
    @DisplayName("AUTO 路由的放款第二段同样响亮：抛 ALREADY_RESOLVED，且申请那一行整个回滚掉")
    void stalledAutoRefundLegIsLoudAndRolledBack() {
        String u = user("stalled-auto");
        OrderView paid = payWithWallet(u);
        long balanceBefore = WalletProbe.balanceCents(jdbc, u);

        mockUpdateReturningZeroRows("UPDATE refund SET status='REFUNDED'");

        BusinessException thrown = catchThrowableOfType(
                () -> refunds.apply(paid.id(), SubOrders.only(paid), u, null, null, null), BusinessException.class);

        assertThat(thrown).as("自助退款的放款落空同样必须报错").isNotNull();
        assertThat(thrown.code()).isEqualTo("ALREADY_RESOLVED");
        assertThat(countRefunds(paid.id()))
                .as("建单本身也要回滚：留一条 PENDING_APPROVAL 会让下一次申请直接撞上在途约束")
                .isZero();
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(balanceBefore);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).isEmpty();
        verify(walletGateway, never()).refund(any());
    }

    // ——————————————————— 反向控制 ———————————————————

    /**
     * 桩必须是<b>精确</b>的：没有这条反向控制，「抛了异常」也可能来自
     * 「我把 {@code JdbcClient} 整个打坏了，于是每条 SQL 都返回 0 行」。
     */
    @Test
    @DisplayName("反向控制：不装这个桩时，同一笔审批正常走完（桩只命中那一条 UPDATE）")
    void approvalStillWorksWithoutTheStub() {
        String u = user("unstubbed");
        OrderView paid = payWithWallet(u);
        fulfillment.ship(SubOrders.only(paid), "admin", "SF-STALL-2");
        RefundView queued = refunds.apply(paid.id(), SubOrders.only(paid), u, null, "WRONG_ITEM", "发错款式");
        long before = WalletProbe.balanceCents(jdbc, u);

        RefundView refunded = refunds.approve(queued.id(), MERCHANT, "核实后同意");

        assertThat(refunded.status()).isEqualTo(RefundView.STATUS_REFUNDED);
        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(before + ORDER_CENTS);
        assertThat(WalletProbe.deltas(jdbc, u, "REFUND")).containsExactly(ORDER_CENTS);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    // ——————————————————— 工具 ———————————————————

    /** 让某一句 UPDATE 返回 0 行：模拟「条件 UPDATE 没命中」。 */
    private void mockUpdateReturningZeroRows(String sqlFragment) {
        JdbcClient.StatementSpec zeroRows = Mockito.mock(JdbcClient.StatementSpec.class);
        Mockito.when(zeroRows.param(Mockito.anyString(), Mockito.any())).thenReturn(zeroRows);
        Mockito.when(zeroRows.update()).thenReturn(0);
        Mockito.doReturn(zeroRows).when(jdbc).sql(Mockito.contains(sqlFragment));
    }

    private void at(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        when(clock.instant()).thenReturn(now.atZone(zone).toInstant());
        when(clock.getZone()).thenReturn(zone);
        when(clock.withZone(Mockito.any())).thenReturn(clock);
    }

    private OrderView payWithWallet(String username) {
        OrderView order = facade.create(username, UUID.randomUUID().toString(),
                new CreateOrderRequest(List.of(new CreateOrderRequest.Line(1, 1))));
        return payments.pay(order.id(), username, "DEMO_WALLET");
    }

    private int stockOf() {
        return jdbc.sql("SELECT stock FROM product WHERE id = 1").query(Integer.class).single();
    }

    private int countRefunds(String orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM refund WHERE order_id = :o")
                .param("o", orderId).query(Integer.class).single();
    }

    private String statusOf(String refundId) {
        return jdbc.sql("SELECT status FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).single();
    }

    private String decidedByOf(String refundId) {
        return jdbc.sql("SELECT decided_by FROM refund WHERE id = :id").param("id", refundId)
                .query(String.class).optional().orElse(null);
    }

    private String user(String suffix) {
        user = "refund-disburse-" + suffix;
        return user;
    }
}
