package dev.learning.mall.wallet.api;

import dev.learning.mall.pricing.api.MoneyUnits;
import java.math.BigDecimal;

/**
 * 钱包余额的对外视图。
 *
 * <p>两个金额字段是刻意的：{@code balanceCents} 是<b>唯一真相</b>（整数分，用于一切计算与对账），
 * {@code balanceYuan} 只是它的展示形态（两位小数，用于页面与人读的文案）。
 * 任何落库、比较、求和都只能拿 {@code balanceCents}——
 * 拿 {@code balanceYuan} 去算钱就等于把「元」这条口径重新请回系统。
 */
public record WalletBalance(String username, long balanceCents, BigDecimal balanceYuan) {

    /**
     * 按「整数分」装配视图，元的展示形态由 {@link MoneyUnits} 换算——
     * 这是全项目唯一允许产出「元」的地方。
     */
    public static WalletBalance of(String username, long balanceCents) {
        return new WalletBalance(username, balanceCents, MoneyUnits.toYuan(balanceCents));
    }
}
