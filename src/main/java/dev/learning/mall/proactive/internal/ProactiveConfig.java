package dev.learning.mall.proactive.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 主动服务只多了一个配置类，逻辑都在 Service 里——配置注册显式写出来，比靠扫描更好排查。 */
@Configuration
@EnableConfigurationProperties(ProactiveProperties.class)
class ProactiveConfig {}
