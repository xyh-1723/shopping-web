package dev.learning.mall.order.internal;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单模块的装配：配置属性 + 时间源。
 *
 * <p><b>为什么要把时间做成 Bean。</b>「下单后 15 分钟未支付就关闭」这类规则，如果代码里
 * 直接写 {@code LocalDateTime.now()}，测试就只能靠 sleep 或直接改库里的 expires_at 来验证——
 * 前者慢且不稳，后者测的是 SQL 而不是规则本身。把时间变成一个可注入的依赖之后，
 * 「还剩 1 秒没关、过了 1 秒就关」这条规则可以被直接断言，不需要真的等 15 分钟。
 *
 * <p>它注册在订单模块，是因为订单超时是本项目第一个真正依赖时间驱动的行为；
 * 别的模块需要时按类型注入即可，不需要各自定义一个。
 */
@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class OrderConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
