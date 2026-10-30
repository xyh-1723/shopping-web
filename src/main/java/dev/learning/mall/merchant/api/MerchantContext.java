package dev.learning.mall.merchant.api;

import dev.learning.mall.common.BusinessException;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * 当前请求的「商家归属」上下文。
 *
 * <p>这是把「你是哪家店」从散落的 {@code merchants.ofOwner(username)} 调用收到一处的钥匙：
 * 切面在 {@code @MerchantScoped} 方法入口解析一次，存入 ThreadLocal；
 * 方法内通过 {@link #requireShop()} 或 {@link #requireMerchantId()} 取，
 * 不用再传 username、不用再查库。
 *
 * <p><b>为什么存 {@code Optional} 而不是 {@code long}</b>：店员（STAFF）没有店，
 * 但他可能调用同一个 {@code @MerchantScoped} 方法（比如发货——店员能发任何人的）。
 * 存空值表示「这个调用者不是以商家身份来的」，由方法自己决定空值是放行还是拒绝。
 *
 * <p><b>ThreadLocal 必须清理</b>：线程池复用下，上一个请求的店铺会污染下一个。
 * 切面在 {@code finally} 里 {@link #clear()}，绝不依赖请求线程结束。
 */
public final class MerchantContext {

    private static final ThreadLocal<Optional<MerchantView>> SHOP =
            ThreadLocal.withInitial(Optional::empty);

    private MerchantContext() {}

    /** 切面写入；业务代码不要调。 */
    public static void setShop(MerchantView shop) {
        SHOP.set(Optional.ofNullable(shop));
    }

    /** 切面在 finally 里调用。 */
    public static void clear() {
        SHOP.remove();
    }

    /**
     * 当前调用者的店铺。店员/管理员返回 empty——他们不属于任何一家店，
     * 但可能被授权操作全量数据（例如店员发货）。
     */
    public static Optional<MerchantView> shop() {
        return SHOP.get();
    }

    /** 当前调用者的店铺号，空表示非商家身份。 */
    public static Optional<Long> merchantId() {
        return SHOP.get().map(MerchantView::id);
    }

    /**
     * 要求当前调用者必须是商家身份并返回其店铺。
     *
     * <p>用于「只有商家能做」的操作（审批退款、看本店库存）。店员走到这里会被拒，
     * 错误码为 {@code MERCHANT_SHOP_MISSING}——对外表现是「你没有店」，
     * 不区分「真没店」和「你是店员」，免得泄露账号角色信息。
     */
    public static MerchantView requireShop() {
        return SHOP.get().orElseThrow(MerchantContext::merchantMissing);
    }

    /** {@link #requireShop()} 的便捷形式：只取店铺号。 */
    public static long requireMerchantId() {
        return requireShop().id();
    }

    /** 统一的「无店」异常，供各处复用同一口径。 */
    public static BusinessException merchantMissing() {
        return new BusinessException(HttpStatus.FORBIDDEN, "MERCHANT_SHOP_MISSING",
                "账号没有关联的店铺，无法执行此操作（归属缺失按无权处理）");
    }
}
