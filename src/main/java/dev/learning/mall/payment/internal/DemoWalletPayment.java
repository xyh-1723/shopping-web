package dev.learning.mall.payment.internal;

import dev.learning.mall.payment.api.PayRequest;
import dev.learning.mall.payment.api.PaymentGateway;
import dev.learning.mall.payment.api.RefundRequest;
import dev.learning.mall.pricing.api.MoneyUnits;
import dev.learning.mall.wallet.internal.WalletService;
import org.springframework.stereotype.Component;

/**
 * 站内钱包通道：这里第一次<b>真的</b>动钱。
 *
 * <p>在此之前它只是一个返回值里碰巧写着 {@code SIM-WALLET-} 的空壳——
 * 不查余额、不扣款、不落任何账。那样的话「退款把钱退回买家钱包」这句话
 * 从头到尾都没有对照物：钱从来没进来过，也就谈不上退回去。
 *
 * <p><b>它自己不算钱，也不换算</b>：金额一律是「元」，元→分交给
 * {@link MoneyUnits}——「超过两位小数即抛异常而不是四舍五入」这条契约，
 * 是这个通道之所以敢说自己记对了账的前提。这里如果自己
 * {@code multiply} 一遍，那条契约就没了。
 *
 * <p><b>它也不碰表</b>：所有写都借 {@link WalletService}，
 * 因为「改余额」与「留痕」必须成对发生，而只有 {@code WalletService} 知道怎么排这个顺序
 * （懒开户 → 加行锁读前值 → 条件扣减 → 留痕）。这里一旦自己 {@code UPDATE wallet}，
 * 钱包对账恒等式就会在某次重构之后静默崩掉。
 *
 * <p><b>幂等的哪一半在这里</b>：
 * <ul>
 *   <li>应用层：{@code OrderTransactions.pay} 的状态机保证同一订单只会走到这里一次；</li>
 *   <li>数据层：{@code wallet_ledger(order_id, reason)} 的唯一索引保证即便被绕过去也会炸。</li>
 * </ul>
 * 两层各一条，缺一层就守不住「换个地方调用」。
 */
@Component
public class DemoWalletPayment implements PaymentGateway {

    /**
     * 支付凭证前缀。
     *
     * <p>刻意沿用空壳时期的 {@code SIM-WALLET-}：它已经是既有订单表里躺着的值，
     * 换了前缀只会给「这单到底是用哪种方式付的」再添一个待解释的差异。
     * 前缀的形状不改变任何断言，真正变的是「这张凭证背后有没有真的动过账」。
     */
    private static final String RECEIPT_PREFIX = "SIM-WALLET-";

    /** 放款凭证前缀：与支付凭证区分开，一张凭证不该既是收款又是放款。 */
    private static final String REFUND_RECEIPT_PREFIX = "SIM-REFUND-";

    private final WalletService wallets;

    public DemoWalletPayment(WalletService wallets) {
        this.wallets = wallets;
    }

    @Override
    public String method() {
        return "DEMO_WALLET";
    }

    /**
     * 收款：从买家钱包里扣掉这笔订单的钱。
     *
     * <p>{@code WalletService.debit} 是 {@code MANDATORY} 传播，因此调用方必须已经在事务里
     * （订单支付的事务）。这是刻意的：不允许存在「扣了钱但订单没支付成功」这种孤立事实。
     *
     * <p>actor 记成买家本人：这是自助支付，动这笔钱的就是他自己。
     *
     * @throws IllegalArgumentException 金额精度失衡（由 {@link MoneyUnits} 抛出，发生在扣款之前）
     * @throws dev.learning.mall.common.BusinessException
     *         {@code INSUFFICIENT_FUNDS}（409）余额不足；整笔订单事务随之回滚
     */
    @Override
    public String pay(PayRequest request) {
        // 换算必须发生在任何写之前：出错时不该已经碰过钱包。
        long cents = MoneyUnits.toCents(request.amount());
        wallets.debit(request.username(), cents, request.username(), request.orderId());
        return RECEIPT_PREFIX + request.orderId();
    }

    /**
     * 放款：把钱打回买家钱包。与 {@link #pay} 只有方向相反，行锁与留痕一步不少。
     *
     * <p><b>actor 是放款责任人，不是收款人</b>：无理由退款由系统判定，那是 {@code SYSTEM}；
     * 需要商家确认的退款，是那位按下「批准」的商家账号。
     * {@link RefundRequest#actor()} 因此在构造时就是必填项（缺失直接报错），
     * 于是流水的 actor 列永远答得出「这笔钱是谁放出来的」。
     *
     * @throws dev.learning.mall.common.BusinessException
     *         同一订单重复放款会被 {@code (order_id, reason)} 唯一索引拒绝
     */
    @Override
    public String refund(RefundRequest request) {
        long cents = MoneyUnits.toCents(request.amount());
        wallets.credit(request.username(), cents, request.actor(), request.orderId());
        return refundReceipt(request);
    }

    /**
     * 放款凭证：<b>把原支付凭证编进去</b>。
     *
     * <p>这是 {@link RefundRequest#originalReceipt()} 今天唯一的消费者，也是它值得存在的理由：
     * 一张退款凭证必须能反查到当初那一笔收款，否则「这笔退款退的是哪次支付」
     * 只能靠 {@code order_id} 去猜（将来放开部分退款、一笔订单多笔放款时就猜不出来了）。
     */
    private static String refundReceipt(RefundRequest request) {
        String prefix = REFUND_RECEIPT_PREFIX + request.orderId();
        String original = request.originalReceipt();
        return original == null || original.isBlank() ? prefix : prefix + "/" + original;
    }
}
