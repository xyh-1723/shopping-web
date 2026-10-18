package dev.learning.mall.wallet.api;

import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一条资金流水的对外视图：<b>只读</b>，没有对应的写入口。
 *
 * <p>{@code WalletService} 那边「写流水」只有 {@code append} 一个方向（追加、不改写），
 * 这里把它翻译成页面能读的形状。刻意<b>不</b>提供「按 id 改一条流水」的能力——
 * 流水是审计留痕，记错了就补一条反向记录，改写历史比记错更糟。
 *
 * <p><b>两个金额口径与 {@link WalletBalance} 同一条规矩</b>：
 * {@code deltaCents} / {@code balanceBeforeCents} / {@code balanceAfterCents} 是整数分，
 * 是唯一真相；带 {@code Yuan} 后缀的两个字段只是它们的展示形态，由
 * {@link MoneyUnits} 换算得到，供页面与人读的文案使用，不进任何计算。
 *
 * <p><b>{@code username} 为什么留在视图里</b>：它是这一行的归属，也是唯一能证明
 * 「这条流水确实属于正在看它的这个人」的字段。少了它，页面只能靠「接口没给错人」
 * 这个隐含前提来相信自己看到的账。
 *
 * <p>{@code deltaCents} 的<b>符号是信息的一部分</b>：负数为出账、正数为入账。
 * 页面据此显示带符号的金额——少了符号，一笔支付看起来就像一笔充值。
 */
public record WalletLedgerEntry(long id, String username, long deltaCents, BigDecimal deltaYuan,
                                long balanceBeforeCents, long balanceAfterCents,
                                BigDecimal balanceAfterYuan, WalletReason reason,
                                String actor, String orderId, LocalDateTime createdAt) {

    /** 按「整数分」装配视图，元的展示形态由 {@link MoneyUnits} 换算（全项目唯一的产地）。 */
    public static WalletLedgerEntry of(long id, String username, long deltaCents,
            long balanceBeforeCents, long balanceAfterCents, WalletReason reason,
            String actor, String orderId, LocalDateTime createdAt) {
        return new WalletLedgerEntry(id, username, deltaCents, MoneyUnits.toYuan(deltaCents),
                balanceBeforeCents, balanceAfterCents, MoneyUnits.toYuan(balanceAfterCents),
                reason, actor, orderId, createdAt);
    }
}
