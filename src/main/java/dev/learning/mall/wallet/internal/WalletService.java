package dev.learning.mall.wallet.internal;

import dev.learning.mall.common.BusinessException;
import dev.learning.mall.pricing.api.MoneyUnits;
import dev.learning.mall.wallet.api.WalletAccounts;
import dev.learning.mall.wallet.api.WalletBalance;
import dev.learning.mall.wallet.api.WalletLedgerEntry;
import dev.learning.mall.wallet.api.WalletReason;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 改钱的<b>唯一</b>入口。
 *
 * <p>四条纪律，每一条都对应一个「不看住就会静默出错」的事故：
 * <ol>
 *   <li><b>余额与流水同事务</b>：先加行锁读前值、再改余额、最后留痕，三步在同一个事务里。
 *       于是「扣了钱没流水」与「有流水没扣成」都不可能存在。</li>
 *   <li><b>条件 UPDATE 拿转换权</b>：{@code UPDATE ... WHERE balance_cents >= :cents}，
 *       影响 1 行才算这一笔真的发生了；0 行就是一次明确的业务拒绝。</li>
 *   <li><b>行锁不能省</b>：见 {@link WalletRepository#balanceForUpdate} 的推导。
 *       少了它，余额、对账恒等式、算术 CHECK 全都仍然正确，只有流水的
 *       {@code balance_before} 会记成别人的中间态——而那是唯一能发现它的地方。</li>
 *   <li><b>整数分</b>：所有金额都是 {@code long} 分，元→分只允许经过 {@link MoneyUnits}。</li>
 * </ol>
 *
 * <p>本类不依赖 {@code order}：依赖方向是 {@code payment → wallet → pricing}，单向无环。
 *
 * <p><b>边界</b>：钱包是站内虚拟余额，不连银行、不做清算、不出对账文件。
 * 它新增的只是「总额的减法被记下来了」，不是「我们开始处理钱了」。
 */
@Service
public class WalletService implements WalletAccounts {

    /** 开户由系统授予，没有具体操作者；流水 actor 列是 NOT NULL，用它占位。 */
    private static final String ACTOR_SYSTEM = "SYSTEM";

    /** 拿不到操作者时的占位：宁可写 unknown，也不要让 NOT NULL 把一笔已扣成的账连带回滚。 */
    private static final String ACTOR_UNKNOWN = "unknown";

    private final WalletRepository accounts;
    private final WalletLedgerRepository ledger;
    private final WalletProperties properties;

    public WalletService(WalletRepository accounts, WalletLedgerRepository ledger,
            WalletProperties properties) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * 懒开户：账户不存在时按配置授予初始额度，并补一条 {@code OPENING} 流水。
     *
     * <p>幂等：第二次调用 {@code ensureRow} 影响 0 行，于是既不重复授予也不重复留痕。
     * 它被支付、放款、充值和查余额四条路径同时调用，幂等是硬要求。
     *
     * <p>{@code REQUIRED} 而不是 {@code MANDATORY}：开户允许是「一次触达」的全部内容
     * （比如页面只是想看一眼余额），不必寄生在别人的事务里。
     *
     * <p>{@link #debit} / {@link #credit} 里对它的调用是<b>自调用</b>（不经 Spring 代理），
     * 这是刻意的：开户必须落在扣款已有的那个事务里，否则「开户成功但扣款失败」
     * 会留下一笔幽灵额度。外部调用方（页面、订单）走代理时则会开一个自己的事务。
     */
    @Override
    @Transactional
    public void ensureAccount(String username) {
        long grant = properties.getInitialGrantCents();
        // 开户与 OPENING 流水在同一个事务里写，于是「余额 == Σdelta」从开户那一刻就成立。
        // 这正是 V16 不需要像 V14 那样在迁移里 seeding 的原因（V14 的历史库存无从追溯，钱包有配置）。
        if (accounts.ensureRow(username, grant) == 1 && grant > 0) {
            // grant = 0 时不写：delta = 0 的行会被 ck_wallet_ledger_delta_nonzero 拒，
            // 在这里挡掉比让数据库抛异常更诚实（那不是错误，只是没发生）。
            ledger.append(username, grant, 0, grant, WalletReason.OPENING, ACTOR_SYSTEM, null);
        }
    }

    @Override
    @Transactional
    public WalletBalance balanceOf(String username) {
        ensureAccount(username);
        long cents = accounts.balance(username);
        return WalletBalance.of(username, cents);
    }

    /**
     * 演示充值：固定档位，累计授予（含开户额度）超过上限即拒绝。
     *
     * <p>刻意没有 {@code amount} 入参：它能被自定义，就等于把上限当成倍率而不是天花板。
     *
     * <p>顺序是「锁 → 读前值 → 算累计 → 入账 → 留痕」：
     * 累计的 SUM 必须在行锁内，否则并发充值各自读到「还没超」，一起放行就把上限捅穿。
     */
    @Override
    @Transactional
    public void topUp(String username) {
        if (!properties.isTopupEnabled()) {
            throw BusinessException.conflict("TOPUP_DISABLED", "演示充值已关闭");
        }
        ensureAccount(username);
        long before = accounts.balanceForUpdate(username);
        long amount = properties.getTopupAmountCents();
        long ceiling = properties.getTopupCeilingCents();
        long granted = ledger.grantedCents(username);
        if (granted + amount > ceiling) {
            throw BusinessException.conflict("TOPUP_LIMIT_EXCEEDED",
                    "演示充值已达累计上限：已授予 ¥" + MoneyUnits.toYuan(granted)
                            + "，上限 ¥" + MoneyUnits.toYuan(ceiling));
        }
        accounts.add(username, amount);
        ledger.append(username, amount, before, before + amount,
                WalletReason.TOPUP, actor(username), null);
    }

    /**
     * 最近若干条流水：一次只读查询，<b>不做懒开户</b>。
     *
     * <p>为什么不开户：开户会写一条 {@code OPENING} 流水，于是「用户只是想看一眼」
     * 这件没有副作用的事会凭空产生一条流水。开户由 {@link #balanceOf} 负责——
     * 而页面查钱包时两个方法一起调，账户那时已经存在。
     *
     * <p>{@code readOnly = true}：它不写任何东西，明确告诉数据库与读者这一点，
     * 比留给「将来也许有人在这里加一句 append」要好。
     */
    @Override
    @Transactional(readOnly = true)
    public List<WalletLedgerEntry> recentLedger(String username, int limit) {
        return ledger.recent(username, limit);
    }

    /**
     * 扣款（下单支付）。
     *
     * <p>四步与 {@code ProductService.reserve} 完全同一形状：
     * 懒开户 → 行锁读前值 → 条件扣减 → 留痕。
     *
     * <p>{@code MANDATORY}：钱只能作为一次业务事务的一部分被挪动。
     * 单独调用它会立刻报错，而不是悄悄开一个只有扣款、没有订单的新事务——
     * 「扣了钱但订单没支付成功」是最难查的一种账。
     *
     * @throws BusinessException {@code INSUFFICIENT_FUNDS}（409）余额不足。
     *         有了行锁，并发竞争<b>退化</b>成一次货真价实的余额不足，
     *         不存在「请重试」这种第三种结果，因此不设并发专用错误码。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(String username, long cents, String actor, String orderId) {
        requirePositive(cents);
        ensureAccount(username);
        long before = accounts.balanceForUpdate(username);
        // 条件 UPDATE 拿「扣款权」：影响 1 行才算这一笔真的发生了。
        if (accounts.tryDeduct(username, cents) != 1) {
            throw BusinessException.conflict("INSUFFICIENT_FUNDS",
                    "钱包余额不足：当前 ¥" + MoneyUnits.toYuan(before)
                            + "，本次需要 ¥" + MoneyUnits.toYuan(cents));
        }
        // 拿到扣款权才写流水；三者同事务，于是「扣了钱没流水」与「有流水没扣成」都不可能存在。
        ledger.append(username, -cents, before, before - cents,
                WalletReason.PAYMENT, actor(actor), orderId);
    }

    /**
     * 放款（退款）。与 {@link #debit} 严格对称：只有方向相反，锁与留痕一步不少。
     *
     * <p>入账不需要条件 UPDATE（入账不可能超扣），但<b>仍然需要行锁</b>：
     * {@code balance_before} 只有在「读与写中间没有别人插队」时才成立。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(String username, long cents, String actor, String orderId) {
        requirePositive(cents);
        ensureAccount(username);
        long before = accounts.balanceForUpdate(username);
        accounts.add(username, cents);
        ledger.append(username, cents, before, before + cents,
                WalletReason.REFUND, actor(actor), orderId);
    }

    private static void requirePositive(long cents) {
        if (cents <= 0) throw BusinessException.badRequest("INVALID_AMOUNT", "金额必须大于 0");
    }

    /**
     * 归一化操作者：流水要能回答「谁动的」，拿不到时宁可写 {@code unknown}，
     * 也不要让 NOT NULL 约束把一笔已经扣成功的账连带回滚。
     */
    private static String actor(String actor) {
        return actor == null || actor.isBlank() ? ACTOR_UNKNOWN : actor;
    }
}
