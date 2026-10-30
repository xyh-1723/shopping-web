package dev.learning.mall.merchant.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记此方法需要「商家归属」上下文。
 *
 * <p>切面会在方法执行前把当前登录账号的店铺号写入 {@link MerchantContext}，
 * 方法内通过 {@link MerchantContext#requireMerchantId()} 或 {@link MerchantContext#merchantId()}
 * 取用，执行后清理。
 *
 * <p><b>放在什么粒度</b>：放在 Service 的公开方法上（如 {@code RefundService.approve}、
 * {@code FulfillmentService.ship}），不要放在 Controller——Controller 调 Service 时
 * 线程上下文已经在了。只标入口方法，避免嵌套切面重复解析。
 *
 * <p><b>和 SecurityConfig 的关系</b>：URL 级 {@code hasRole("MERCHANT")} 是第一道墙，
 * 这个注解 + 上下文是第二道。少了第一道，匿名请求会打到业务代码；少了第二道，
 * 商家 A 能碰到商家 B 的数据。两者缺一不可。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MerchantScoped {}
