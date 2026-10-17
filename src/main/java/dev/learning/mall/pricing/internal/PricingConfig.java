package dev.learning.mall.pricing.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册促销规则的配置属性。
 *
 * <p>促销规则要能随运营调整，所以走 {@code @ConfigurationProperties} 外部化；
 * 而外部化的代价是必须有人把它注册成 Bean——Spring Boot 不会自动扫描
 * 带 {@code @ConfigurationProperties} 的普通类（除非用 {@code @ConfigurationPropertiesScan}）。
 *
 * <p>原来这个职责在 {@code cart.internal.CartConfig}。规则搬进定价包后，
 * 注册者也应该跟过来——谁拥有这个 Bean，谁负责把它交给容器。
 */
@Configuration
@EnableConfigurationProperties(ShoppingProperties.class)
public class PricingConfig {
}
