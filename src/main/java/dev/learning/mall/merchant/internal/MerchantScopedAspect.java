package dev.learning.mall.merchant.internal;

import dev.learning.mall.merchant.api.MerchantContext;
import dev.learning.mall.merchant.api.MerchantDirectory;
import dev.learning.mall.merchant.api.MerchantScoped;
import dev.learning.mall.merchant.api.MerchantView;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 解析「当前登录账号属于哪家店」并写入 {@link MerchantContext}。
 *
 * <p>解析规则（只此一处，所有 {@code @MerchantScoped} 方法共用）：
 * <ol>
 *   <li>从 {@link SecurityContextHolder} 拿当前认证；拿不到（未登录 / 异步线程）→ 空。</li>
 *   <li>用账号名查 {@code merchant.owner_username}；有店 → 存店铺，没店 → 空。</li>
 * </ol>
 *
 * <p><b>店员和管理员也是「空」</b>：他们没有店铺行，存空值而不是抛异常。
 * 「空值怎么处理」由具体方法决定——发货方法把空值当「店员，可发任何人的」，
 * 退款审批方法把空值当「不是商家，拒绝」。切面不做业务判断，只负责把事实摆出来。
 *
 * <p><b>为什么用 AOP 而不是 HandlerInterceptor</b>：拦截器只能在 HTTP 入口切，
 * 而 {@code @MerchantScoped} 会出现在被定时任务、被助手工具直接调用的 Service 方法上——
 * 那些调用没有 HTTP 请求。AOP 按方法切，不管调用源是谁。
 */
@Aspect
@Component
public class MerchantScopedAspect {

    private final MerchantDirectory merchants;

    public MerchantScopedAspect(MerchantDirectory merchants) {
        this.merchants = merchants;
    }

    @Around("@annotation(merchantScoped)")
    public Object around(ProceedingJoinPoint pjp, MerchantScoped merchantScoped) throws Throwable {
        MerchantView shop = resolveShop();
        MerchantContext.setShop(shop);
        try {
            return pjp.proceed();
        } finally {
            // 线程池复用：上一个请求的店铺必须清掉，否则下一个请求会继承它。
            MerchantContext.clear();
        }
    }

    private MerchantView resolveShop() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return merchants.ofOwner(auth.getName()).orElse(null);
    }
}
