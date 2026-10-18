package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.pricing.api.MoneyUnits;
import dev.learning.mall.wallet.api.WalletAccounts;
import dev.learning.mall.wallet.api.WalletBalance;
import dev.learning.mall.wallet.internal.WalletProperties;
import dev.learning.mall.wallet.internal.WalletService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 钱包流水（T17）：<b>每一分钱的进出都要留痕，且留痕必须与余额对得上账</b>。
 *
 * <p>要守的不是「有没有一张流水表」，而是这条恒等式：
 *
 * <pre>
 *   wallet.balance_cents  ==  该用户所有流水 delta_cents 之和
 * </pre>
 *
 * <p>（开户的 {@code OPENING} 本身就是一条 delta，所以不需要像库存那样另带基线。）
 * 它一旦不成立，「钱回到买家手里」这句话就无法验证——那正是退款今天的现状。
 *
 * <p><b>分层纪律</b>：凡是「Java writer 把字段写对了没有」这类断言，
 * 一律走真实的 {@link WalletService} / {@link WalletAccounts} 入口，<b>不用裸 SQL 顶替</b>。
 * 数据库约束层由 {@link V16MigrationTest} 单独守——两层各一条，缺一层就守不住
 * 「换个地方写」。
 *
 * <p>所有用例都用一个<b>全新用户名</b>并在 {@code @AfterEach} 里清干净：
 * H2 内存库在整个 JVM 内共享，假设库干净而写出的断言会在换一个执行顺序后随机变红。
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletLedgerTest {

    private static final String ACTOR = "tester";

    /**
     * 端口与实现都注入，是刻意的：{@link WalletAccounts} 是模块对外边界，
     * 账户级动作一律只经它；{@code debit} / {@code credit} 是支付通道的内部契约，
     * 只能作为业务事务的一部分被调用（{@code MANDATORY}），因此走实现类。
     */
    @Autowired WalletAccounts accounts;
    @Autowired WalletService wallet;
    @Autowired WalletProperties properties;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    private String user;

    @AfterEach
    void cleanUp() {
        if (user != null) {
            WalletProbe.purge(jdbc, user);
            user = null;
        }
    }

    /** 取一个本用例专属的用户名（顺便登记给 {@link #cleanUp()}）。 */
    private String user(String suffix) {
        user = "wallet-" + suffix;
        return user;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    @DisplayName("初始额度来自配置而不是迁移：触达前没有账户行，触达后余额 == 配置的额度")
    void openingGrantComesFromConfigurationAndReconciles() {
        String u = user("opening");

        // 迁移里不 seeding：这一行根本不存在，开户只能发生在「首次触达」那一刻。
        assertThat(WalletProbe.walletRowCount(jdbc, u)).isZero();

        WalletBalance balance = accounts.balanceOf(u);
        assertThat(balance.balanceCents()).isEqualTo(properties.getInitialGrantCents());
        assertThat(balance.balanceYuan())
                .isEqualByComparingTo(MoneyUnits.toYuan(properties.getInitialGrantCents()));
        assertThat(balance.username()).isEqualTo(u);

        assertThat(WalletProbe.deltas(jdbc, u, "OPENING")).hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    @Test
    @DisplayName("懒开户幂等：连续调用两次，余额不变且 OPENING 流水只有一行")
    void ensureAccountIsIdempotent() {
        String u = user("idempotent");

        accounts.ensureAccount(u);
        long first = WalletProbe.balanceCents(jdbc, u);
        accounts.ensureAccount(u);

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(first);
        assertThat(WalletProbe.deltas(jdbc, u, "OPENING"))
                .as("重复开户不能重复授予，否则余额会比流水多出一块").hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    /**
     * 「PAYMENT 记成出账」的 <b>Java writer 层</b>证据。
     *
     * <p>数据库层另有一条 {@code V16MigrationTest#paymentOutflowIsConstraintEnforced}。
     * 两层都要有：只有数据库那条，守不住「将来有人在别处直接 INSERT 一条错的」。
     */
    @Test
    @DisplayName("支付记成出账：走真实 debit，PAYMENT 流水的 delta 必须为负且对得上账")
    void paymentIsRecordedAsOutflow() {
        String u = user("payment");
        accounts.ensureAccount(u);
        long before = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> wallet.debit(u, 1234, ACTOR, "order-payment"));

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(before - 1234);
        List<Long> deltas = WalletProbe.deltas(jdbc, u, "PAYMENT");
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0))
                .as("支付必须是出账：符号写反会让它变成一笔充值，而算术 CHECK 与对账恒等式都发现不了")
                .isNegative();
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();
        // 流水要能归因：带着订单号，才知道这笔钱是哪笔订单扣走的。
        assertThat(jdbc.sql("SELECT order_id FROM wallet_ledger WHERE username = :u AND reason = 'PAYMENT'")
                .param("u", u).query(String.class).single()).isEqualTo("order-payment");
        assertThat(jdbc.sql("SELECT actor FROM wallet_ledger WHERE username = :u AND reason = 'PAYMENT'")
                .param("u", u).query(String.class).single()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("退款放款记成入账：走真实 credit，REFUND 流水的 delta 必须为正")
    void refundIsRecordedAsInflow() {
        String u = user("refund");
        accounts.ensureAccount(u);
        long before = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> wallet.credit(u, 500, ACTOR, "order-refund"));

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(before + 500);
        List<Long> deltas = WalletProbe.deltas(jdbc, u, "REFUND");
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0)).as("退款必须是入账：钱要真的回到买家手里").isPositive();
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();
    }

    /**
     * 哨兵自证：如果这条不红，说明对账 SQL 本身写错了，上面所有对账断言都没有意义。
     *
     * <p>做法是直接改库而不写流水（模拟「运维手改 / 数据导入」这类绕过服务的改动），
     * 对账 SQL 必须把它揪出来。它验证的是<b>尺子</b>，不是被测物。
     */
    @Test
    @DisplayName("哨兵：不写流水直接给余额加 500 元，对账 SQL 必须报 1 条差异")
    void reconciliationDetectsPhantomBalance() {
        String u = user("phantom");
        accounts.ensureAccount(u);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();

        jdbc.sql("UPDATE wallet SET balance_cents = balance_cents + 50000 WHERE username = :u")
                .param("u", u).update();
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u))
                .as("对账 SQL 必须能抓到「钱凭空多出来了却没留痕」，否则它就是一把坏尺子")
                .isEqualTo(1);

        jdbc.sql("UPDATE wallet SET balance_cents = balance_cents - 50000 WHERE username = :u")
                .param("u", u).update();
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    /**
     * 对照组：走真实 {@link WalletService#debit} 必须对得上账。
     *
     * <p>它有两重身份：一是证明上面的哨兵不是在「无论如何都返回 1」地假红；
     * 二是「{@code debit} 忘了写流水」这条 bug 的唯一探测器——
     * 那条 bug 会让余额凭空少一块，而没有任何其它断言会发现。
     */
    @Test
    @DisplayName("对照组：真实扣款路径对得上账（哨兵不是假红，且 debit 不会只扣钱不留痕）")
    void reconciliationSqlIsNotVacuous() {
        String u = user("not-vacuous");
        accounts.ensureAccount(u);
        long before = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> wallet.debit(u, 700, ACTOR, "order-not-vacuous"));

        assertThat(WalletProbe.balanceCents(jdbc, u)).isEqualTo(before - 700);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u))
                .as("扣了钱就必须留痕：只 tryDeduct 不 append 会在这里红").isZero();
    }

    /**
     * 哨兵自证：{@code chainMismatch} 这条窗口查询本身是有效的。
     *
     * <p>否则「并发扣款首尾相接」那条断言就是一条恒绿的空断言——
     * 而它是漏了行锁<b>唯一</b>的探测器。
     */
    @Test
    @DisplayName("哨兵：手工插一条错开一格的流水，首尾相接查询必须报断链")
    void chainQueryIsNotVacuous() {
        String u = user("chain");
        accounts.ensureAccount(u);
        long grant = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> wallet.debit(u, 100, ACTOR, "order-chain"));
        assertThat(WalletProbe.chainMismatch(jdbc, u)).isZero();

        // 手工插一条「前值用开户额度、而不是扣完之后的余额」的流水——
        // 它的算术与非负约束全部成立，只有首尾相接能看出中间漏了一格。
        jdbc.sql("INSERT INTO wallet_ledger"
                        + "(username, delta_cents, balance_before_cents, balance_after_cents,"
                        + " reason, actor, order_id)"
                        + " VALUES (:u, -50, :before, :after, 'PAYMENT', 'tester', NULL)")
                .param("u", u)
                .param("before", grant)
                .param("after", grant - 50)
                .update();

        assertThat(WalletProbe.chainMismatch(jdbc, u))
                .as("首尾相接那条窗口查询必须能看见漏掉的一格").isEqualTo(1);
    }

    /**
     * 充值上限必须是<b>累计</b>的：只看本次金额，就能一次一次把上限刷穿。
     */
    @Test
    @DisplayName("充值上限是累计的：刷到上限后第 N+1 次被 TOPUP_LIMIT_EXCEEDED 拒")
    void topupCeilingIsCumulative() {
        String u = user("ceiling");
        long grant = properties.getInitialGrantCents();
        long amount = properties.getTopupAmountCents();
        long ceiling = properties.getTopupCeilingCents();

        accounts.ensureAccount(u);
        assertThat(WalletProbe.grantedCents(jdbc, u)).isEqualTo(grant);

        long times = (ceiling - grant) / amount;
        assertThat(grant + times * amount)
                .as("用例前提：能充若干次且刚好不超过上限").isLessThanOrEqualTo(ceiling);

        for (int i = 0; i < times; i++) {
            accounts.topUp(u);
        }
        assertThat(WalletProbe.grantedCents(jdbc, u)).isEqualTo(grant + times * amount);

        BusinessException rejected = catchThrowableOfType(() -> accounts.topUp(u), BusinessException.class);
        assertThat(rejected).as("上限是累计的，不是「单次不超过」").isNotNull();
        assertThat(rejected.code()).isEqualTo("TOPUP_LIMIT_EXCEEDED");
        assertThat(rejected.status().value()).isEqualTo(409);

        assertThat(WalletProbe.grantedCents(jdbc, u))
                .as("被拒之后累计授予一分都不能变").isEqualTo(grant + times * amount);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
        // 充值不带订单号，因此不受 (order_id, reason) 唯一索引的约束。
        assertThat(WalletProbe.deltas(jdbc, u, "TOPUP")).hasSize((int) times);
    }

    /**
     * 充值总开关：它必须真的能关掉，否则 {@code mall.wallet.topup-enabled}
     * 就是一个「写了但没用」的配置项。
     *
     * <p>这里直接翻转注入进来的 {@link WalletProperties} Bean（用完还原），
     * 而不是再开一个 Spring 上下文——为一条开关再起一次上下文，代价远大于收益。
     */
    @Test
    @DisplayName("充值总开关关掉后 topUp 被拒：配置项不是摆设")
    void topupSwitchOffIsRejected() {
        String u = user("topup-off");
        accounts.ensureAccount(u);
        long grantedBefore = WalletProbe.grantedCents(jdbc, u);

        boolean original = properties.isTopupEnabled();
        properties.setTopupEnabled(false);
        try {
            BusinessException rejected = catchThrowableOfType(() -> accounts.topUp(u), BusinessException.class);
            assertThat(rejected).as("总开关关掉之后不该还能充值").isNotNull();
            assertThat(rejected.code()).isEqualTo("TOPUP_DISABLED");
        } finally {
            properties.setTopupEnabled(original);
        }

        assertThat(WalletProbe.grantedCents(jdbc, u)).isEqualTo(grantedBefore);
        assertThat(WalletProbe.deltas(jdbc, u, "TOPUP")).isEmpty();
        // 开关开回来之后照常能充：证明上面的红来自开关，而不是账户坏了。
        accounts.topUp(u);
        assertThat(WalletProbe.deltas(jdbc, u, "TOPUP")).hasSize(1);
    }

    /**
     * 「幂等的数据库那一半」走<b>真实 writer</b>：在测试里复制一份 INSERT
     * 只能证明「测试里的 SQL」，那是本项目已经踩过的错配。
     */
    @Test
    @DisplayName("同一笔订单只能扣一次：走真实 debit 连扣两次，第二次被唯一索引拒")
    void sameOrderCannotBeChargedTwice() {
        String u = user("idempotent-order");
        accounts.ensureAccount(u);
        long before = WalletProbe.balanceCents(jdbc, u);

        tx().executeWithoutResult(s -> wallet.debit(u, 100, ACTOR, "order-twice"));

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> wallet.debit(u, 100, ACTOR, "order-twice")))
                .as("同一 (order_id, reason) 只能有一行")
                .hasMessageContaining("UQ_WALLET_LEDGER_ORDER_REASON");

        assertThat(WalletProbe.balanceCents(jdbc, u))
                .as("唯一索引挡下的第二次扣款必须整笔回滚，余额只减少一次").isEqualTo(before - 100);
        assertThat(WalletProbe.deltas(jdbc, u, "PAYMENT")).hasSize(1);
        assertThat(WalletProbe.reconciliationMismatch(jdbc, u)).isZero();
    }

    @Test
    @DisplayName("金额一律是整数分：余额视图里的元形态由 MoneyUnits 换算，不引入第二套口径")
    void balanceViewKeepsCentsAsTheOnlyTruth() {
        String u = user("units");
        accounts.ensureAccount(u);
        tx().executeWithoutResult(s -> wallet.debit(u, 1, ACTOR, "order-units"));

        long cents = WalletProbe.balanceCents(jdbc, u);
        WalletBalance view = accounts.balanceOf(u);
        assertThat(view.balanceCents()).isEqualTo(cents);
        assertThat(view.balanceYuan().scale()).as("展示形态必须是两位小数").isEqualTo(2);
        assertThat(MoneyUnits.toCents(view.balanceYuan()))
                .as("元形态再换回分必须原样回来：展示字段不参与任何计算").isEqualTo(cents);
    }
}
