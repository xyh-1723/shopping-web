package dev.learning.mall.messaging.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mall.messaging")
public record MessagingProperties(boolean enabled, @Min(1) int corePoolSize, @Min(1) int maxPoolSize,
                                  @Min(1) int queueCapacity, @Min(1) int maxAttempts,
                                  @Min(1) int retryDelaySeconds, boolean simulateFailure) {}
