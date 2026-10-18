package dev.learning.mall.pricing.api;

import java.math.BigDecimal;

/**
 * 元 ↔ 分 的<b>唯一</b>换算点。
 *
 * <p><b>关键契约：它绝不四舍五入。</b>
 * 「元」的小数位超过两位时一律抛异常，而不是悄悄抹掉。
 * 理由：{@link Pricer} 的 {@code Quote} 已经保证四个金额都是两位小数，
 * 出现三位小数说明上游出了 bug——让它在换算这一步炸掉，
 * 比把它抹平成一分钱、然后散落到对账里要便宜得多。
 *
 * <p><b>为什么放 {@code pricing} 而不是 {@code wallet}？</b>
 * 换算虽然不是「算钱」，但它是关于「钱怎么表示」的知识，和 {@link Pricer} 同居一个包，
 * 才能保证全项目关于钱的原语只有一处。依赖方向上 {@code wallet → pricing} 也是对的
 * （{@code order} 早已依赖 {@code pricing}，不会有循环依赖）。
 *
 * <p><b>怎么保证没人绕过它？</b>三道，从强到弱：
 * <ol>
 *   <li><b>类型</b>：钱包相关 Repository / Service 的所有金额参数与返回值都是 {@code long} 分，
 *       钱包内部根本不存在 {@code BigDecimal}，想绕也拿不到入口。</li>
 *   <li><b>契约</b>：{@link #toCents} 在精度失衡时抛异常而不是舍入。
 *       任何「我随手 {@code multiply(BigDecimal.valueOf(100))} 一下」的等价写法都会丢掉这条检查。</li>
 *   <li><b>源码扫描测试</b>：{@code MoneyUnitsTest#noRawCentConversionOutsideMoneyUnits}
 *       扫描 {@code src/main/java}，除本类外不许出现裸的元→分换算写法。</li>
 * </ol>
 */
public final class MoneyUnits {

    private MoneyUnits() {
    }

    /**
     * 元 → 分。<b>超过两位小数直接抛异常，绝不四舍五入。</b>
     *
     * @param yuan 以「元」为单位的金额，小数位不得超过 2
     * @return 以「分」为单位的整数
     * @throws IllegalArgumentException 入参为 {@code null}，或小数位超过 2
     * @throws ArithmeticException      小数位虽未超过 2 但存在无法精确表示的分值（实践中不会发生）
     */
    public static long toCents(BigDecimal yuan) {
        if (yuan == null) throw new IllegalArgumentException("金额不能为空");
        if (yuan.scale() > 2) throw new IllegalArgumentException("金额精度超过「分」：" + yuan);
        // longValueExact 是最后一道：有残余小数就抛，绝不静默截断。
        return yuan.movePointRight(2).longValueExact();
    }

    /**
     * 分 → 元（两位小数）。<b>仅用于展示与错误文案</b>，任何落库路径都不该用它。
     *
     * <p>之所以允许它存在，是因为「¥19.99」这种人类可读形态总要有个产地；
     * 而之所以强调它不进落库路径，是因为一旦有人拿它的返回值再去算钱，
     * 「整数分是唯一真相」这条就破了。
     */
    public static BigDecimal toYuan(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
