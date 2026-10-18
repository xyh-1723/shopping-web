package dev.learning.mall.messaging.internal;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 消息（outbox → 通知）相关的配置。
 *
 * <p>{@code defaultChannel} 与 {@code sms} 是 T15 为「外发通道」加的两组位：
 * 前者决定<b>默认想走哪条通道</b>，后者是<b>真实外发的密钥预留位</b>。
 * 密钥现在为空 —— 空不等于错，等于「这条通道暂不可用，自动降级到站内信」。
 */
@Validated
@ConfigurationProperties(prefix = "mall.messaging")
public record MessagingProperties(boolean enabled, @Min(1) int corePoolSize, @Min(1) int maxPoolSize,
                                  @Min(1) int queueCapacity, @Min(1) int maxAttempts,
                                  @Min(1) int retryDelaySeconds, boolean simulateFailure,
                                  String defaultChannel, Sms sms) {

    /**
     * 构造函数绑定在「yml 里压根没有这一项」时传进来的是 {@code null}，
     * 所以默认值必须在这里兜住，而不是散落到每个读取点（散落的结果一定是某处漏判 NPE）。
     */
    public MessagingProperties {
        if (defaultChannel == null || defaultChannel.isBlank()) {
            defaultChannel = NotificationChannel.IN_APP.name();
        }
        if (sms == null) {
            sms = new Sms(true, "", "");
        }
    }

    /**
     * 短信通道的配置位。
     *
     * <p><b>为什么现在就要有它：</b>用户明确要求「配置结构从一开始就要能容纳 api-key / secret，
     * 方便后续接真正的外发通道」。只留 {@code sms.enabled} 一个布尔，将来就得改一次配置结构；
     * 而改配置结构在本项目里意味着连带改文档与迁移说明，成本远高于现在多留两个空串。
     *
     * <p><b>为什么默认 {@code enabled=true} 而不是 {@code false}：</b>真正决定「能不能用」的
     * 是密钥是否非空（见 {@link LocalSmsGateway#available()}）。把总开关默认打开，
     * 才能让「配了 default-channel=SMS 但没填密钥 → 降级留痕」这条路径在本地<b>可达</b>；
     * 若总开关默认关闭，这条路径永远不会被执行到，测试也就无从证明它没坏。
     */
    public record Sms(boolean enabled, String apiKey, String secret) {

        public Sms {
            // yml 里写了 sms.api-key 但没写 secret 时，未绑定的那一项是 null；
            // 统一折成空串，让下游只判断 hasText，不必到处判 null。
            if (apiKey == null) {
                apiKey = "";
            }
            if (secret == null) {
                secret = "";
            }
        }
    }
}
