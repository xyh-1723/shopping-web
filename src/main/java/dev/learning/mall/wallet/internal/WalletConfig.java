package dev.learning.mall.wallet.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册钱包的配置属性。
 *
 * <p>额度与充值档位要能随演示场景调整，所以走 {@code @ConfigurationProperties} 外部化；
 * 而外部化的代价是必须有人把它注册成 Bean——Spring Boot 不会自动扫描
 * 带 {@code @ConfigurationProperties} 的普通类（除非用 {@code @ConfigurationPropertiesScan}）。
 *
 * <p>与 {@code pricing.internal.PricingConfig} 同一条理由：谁拥有这个 Bean，谁负责交给容器。
 */
@Configuration
@EnableConfigurationProperties(WalletProperties.class)
public class WalletConfig {
}
