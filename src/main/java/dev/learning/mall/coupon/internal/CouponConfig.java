package dev.learning.mall.coupon.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CouponProperties.class)
public class CouponConfig {}
