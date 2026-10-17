package dev.learning.mall.assistant.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 助手安全策略可调项。默认值偏向「教学环境下安全优先」：
 * 输入上限 1000 字（正常提问远用不到）、输出上限 2000 字、每分钟 20 条。
 */
@ConfigurationProperties("mall.assistant.security")
public class AssistantSecurityProperties {
    /** 单条输入最大字符数，超过直接拦截（LLM10）。 */
    private int maxInputLength = 1000;
    /** 单条回复最大字符数，超过截断（LLM05）。 */
    private int maxOutputLength = 2000;
    /** 每用户每分钟最多发送条数（LLM10）。 */
    private int rateLimitPerMinute = 20;
    /** 是否在送模型前脱敏手机号/证件号/邮箱（LLM02）。 */
    private boolean maskPii = true;
    /** 是否把正常的放行请求也写入审计（默认 false，只记安全事件）。 */
    private boolean auditAllowed = false;
    /** 待确认支付的有效期（分钟）：超时未确认自动失效，防止隔天被重放。 */
    private int paymentConfirmTtlMinutes = 10;

    public int getMaxInputLength() { return maxInputLength; }
    public void setMaxInputLength(int maxInputLength) { this.maxInputLength = maxInputLength; }
    public int getMaxOutputLength() { return maxOutputLength; }
    public void setMaxOutputLength(int maxOutputLength) { this.maxOutputLength = maxOutputLength; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public boolean isMaskPii() { return maskPii; }
    public void setMaskPii(boolean maskPii) { this.maskPii = maskPii; }
    public boolean isAuditAllowed() { return auditAllowed; }
    public void setAuditAllowed(boolean auditAllowed) { this.auditAllowed = auditAllowed; }
    public int getPaymentConfirmTtlMinutes() { return paymentConfirmTtlMinutes; }
    public void setPaymentConfirmTtlMinutes(int paymentConfirmTtlMinutes) { this.paymentConfirmTtlMinutes = paymentConfirmTtlMinutes; }
}
