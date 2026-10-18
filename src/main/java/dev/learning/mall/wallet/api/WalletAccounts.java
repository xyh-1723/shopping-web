package dev.learning.mall.wallet.api;

import java.util.List;

/**
 * 钱包模块的公开边界：订单、助手与 Web 只依赖此接口，不访问钱包 Repository。
 *
 * <p>本接口只放「账户级」的四个动作：三个会<b>改动账户</b>的（开户 / 查余额 / 演示充值）
 * 与一个<b>纯读</b>的（查流水）。
 * 支付与放款（{@code debit} / {@code credit}）留在 {@code WalletService} 上，
 * 因为它们是<b>支付通道</b>的内部契约，不是页面或订单该直接调用的入口——
 * 钱只能作为一次业务事务的一部分被挪动。
 *
 * <p>为什么「查流水」可以在这里而「扣款」不行：读不产生状态，谁读、读几次都无所谓；
 * 写会。把它放进边界接口换来的好处是页面不必碰 {@code wallet_ledger} 这张表，
 * 于是「页面自己拼一条 SQL 查余额」这条后门不会出现。
 */
public interface WalletAccounts {

    /**
     * 懒开户：账户不存在时按配置授予初始额度并留一条 {@code OPENING} 流水；已存在则什么都不做。
     *
     * <p>幂等是硬要求：它会被支付、放款、查余额三条路径同时调用。
     */
    void ensureAccount(String username);

    /**
     * 查余额。账户不存在时按「首次触达」语义先开户，因此返回值一定有值。
     */
    WalletBalance balanceOf(String username);

    /**
     * 演示充值：金额<b>不可自定义</b>，固定为配置里的档位；累计授予超过上限即拒绝。
     *
     * <p>它没有幂等键——充值不是支付回调，不存在被第三方重放的场景，
     * 给它造一个客户端幂等键是为不存在的问题写协议。它需要的不是幂等，而是上限。
     *
     * @throws dev.learning.mall.common.BusinessException
     *         {@code TOPUP_DISABLED}（总开关关闭）或 {@code TOPUP_LIMIT_EXCEEDED}（累计已达上限）
     */
    void topUp(String username);

    /**
     * 最近若干条流水，<b>新的在前</b>。
     *
     * <p>纯读：不开户、不写任何东西。账户不存在时返回空列表，而不是顺手建一个——
     * 「看一眼流水」不该产生一条 {@code OPENING} 流水，那是 {@link #balanceOf} 的职责。
     *
     * @param limit 返回条数上限，由调用方给；接口不定义「默认条数」，
     *              因为默认值一旦存在就会有两个地方可以改它
     */
    List<WalletLedgerEntry> recentLedger(String username, int limit);
}
