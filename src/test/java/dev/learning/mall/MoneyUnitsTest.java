package dev.learning.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.learning.mall.pricing.api.MoneyUnits;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 元 ↔ 分换算（钱包方案 §5.1）。
 *
 * <p>两条断言的分工：
 * <ul>
 *   <li>{@link #fractionalYuanIsRejectedNotRounded} 守<b>契约</b>：超过「分」的精度必须炸，
 *       不能悄悄抹平成一分钱。它是所有金额断言的前置——舍入一旦发生，
 *       差异不会报错，只会在某次偶然的对账里以「差 1 分」的形态出现。</li>
 *   <li>{@link #noRawCentConversionOutsideMoneyUnits} 守<b>唯一性</b>：
 *       全项目只有 {@link MoneyUnits} 能做元→分。这一条无法被运行时测试替代
 *       （类型层面钱包只收 {@code long} 分，但别处仍可能自己换算一遍再传进来），
 *       所以它是本仓库第一条读源码的静态守卫。</li>
 * </ul>
 *
 * <p>静态守卫有一个天生的脆弱点：<b>它可能在看不见源码时静默通过</b>。
 * 会 skip 的守卫等于会消失的守卫，因此 {@link #guardTestSeesTheSourceTree}
 * 专门钉住「它真的看见了源码」，定位不到目录时 {@link #sourceRoot()} 直接 fail。
 */
class MoneyUnitsTest {

    /** 源码扫描守卫至少应当看到这么多个 .java 文件（当前 src/main/java 有 140+ 个）。 */
    private static final int MIN_SOURCE_FILES = 80;

    /**
     * 禁止在 {@code MoneyUnits} 之外出现的裸换算写法。
     *
     * <p>为什么<b>不含</b> {@code doubleValue()}：钱包方案 §5.1 列过它，
     * 但 {@code assistant/internal/CatalogTools.java} 里有两处
     * {@code p.price().doubleValue() <= budget} 是拿它做价格比较、
     * 与「元→分」无关。把它列进来会让这条守卫在<b>没有 bug</b> 的情况下变红，
     * 而一条会误报的守卫最后一定会被人删掉。下面四个才是换算的专属写法。
     */
    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "movePointRight",
            "movePointLeft",
            "multiply(BigDecimal.valueOf(100))",
            "multiply(new BigDecimal(\"100\"))",
            "scaleByPowerOfTen");

    @Test
    @DisplayName("超过「分」的精度直接抛异常，绝不四舍五入")
    void fractionalYuanIsRejectedNotRounded() {
        // 19.999 元：四舍五入会变成 20.00（2000 分），截断会变成 19.99（1999 分），
        // 两个「看起来都合理」的答案都是错的——上游出了 bug，就该在这一步炸掉。
        for (String value : List.of("19.999", "0.001", "100.005", "1.2345")) {
            BigDecimal yuan = new BigDecimal(value);
            assertThatThrownBy(() -> MoneyUnits.toCents(yuan))
                    .as("金额精度超过「分」必须抛异常：" + value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("精度");
        }

        // 反过来确认上面的红不是因为「这个方法根本不能用」：两位小数照常换算。
        assertThat(MoneyUnits.toCents(new BigDecimal("19.99"))).isEqualTo(1999L);
        assertThat(MoneyUnits.toCents(new BigDecimal("20"))).isEqualTo(2000L);
        assertThat(MoneyUnits.toCents(new BigDecimal("0.01"))).isEqualTo(1L);
        assertThat(MoneyUnits.toCents(new BigDecimal("0"))).isZero();
    }

    @Test
    @DisplayName("空金额直接抛异常，不返回 0 分")
    void nullYuanIsRejected() {
        assertThatThrownBy(() -> MoneyUnits.toCents(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("分 → 元 → 分 往返精确：展示形态不丢精度")
    void yuanRoundTripIsExact() {
        for (long cents : new long[] {0L, 1L, 99L, 1999L, 50_000L, 1_234_567L}) {
            BigDecimal yuan = MoneyUnits.toYuan(cents);
            assertThat(yuan.scale()).as("展示形态必须是两位小数").isEqualTo(2);
            assertThat(MoneyUnits.toCents(yuan)).isEqualTo(cents);
        }
        assertThat(MoneyUnits.toYuan(1999L)).isEqualByComparingTo(new BigDecimal("19.99"));
    }

    /**
     * 源码扫描守卫：除 {@link MoneyUnits} 自身外，不许出现裸的元→分换算。
     *
     * <p>它守的是「换算点唯一」这条无法被运行时断言证明的约定。
     */
    @Test
    @DisplayName("源码里没有绕过 MoneyUnits 的裸换算（找不到源码目录就 fail，不许 skip）")
    void noRawCentConversionOutsideMoneyUnits() throws IOException {
        Path root = sourceRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (MoneyUnits.class.getSimpleName().concat(".java").equals(file.getFileName().toString())) {
                    continue;   // 换算点自己当然要出现这些写法
                }
                // 先把全限定名归一化，再按短名匹配：
                // 否则 yuan.multiply(java.math.BigDecimal.valueOf(100)) 这种写法会从守卫底下溜过去。
                String body = Files.readString(file).replace("java.math.BigDecimal", "BigDecimal");
                for (String pattern : FORBIDDEN_PATTERNS) {
                    if (body.contains(pattern)) {
                        offenders.add(root.relativize(file) + " → " + pattern);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("元→分只能经过 pricing.api.MoneyUnits；任何自己换算一遍的写法都会丢掉「精度失衡即抛异常」这条契约")
                .isEmpty();
    }

    /**
     * 哨兵自证：证明上面那条扫描守卫真的看见了源码树。
     *
     * <p>如果有一天 {@link #sourceRoot()} 指到了一个空目录，
     * {@code offenders} 会是空的、守卫也就永远通过——那正是一条空守卫。
     */
    @Test
    @DisplayName("哨兵：扫描守卫真的看见了源码树（文件数有下限）")
    void guardTestSeesTheSourceTree() throws IOException {
        long count;
        try (Stream<Path> files = Files.walk(sourceRoot())) {
            count = files.filter(p -> p.toString().endsWith(".java")).count();
        }
        assertThat(count)
                .as("源码扫描必须真的读到 src/main/java，否则它就是一条永远通过的空守卫")
                .isGreaterThan(MIN_SOURCE_FILES);
    }

    /**
     * 定位 {@code src/main/java}：从工作目录向上找，找不到就抛。
     *
     * <p>刻意<b>不做</b>「找不到就跳过」的降级——会 skip 的守卫等于会消失的守卫。
     */
    private static Path sourceRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("找不到 src/main/java（从 " + Path.of("").toAbsolutePath()
                + " 一路向上都没找到）。源码扫描守卫不允许 skip。");
    }
}
