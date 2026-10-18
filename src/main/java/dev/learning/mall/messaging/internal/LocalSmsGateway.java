package dev.learning.mall.messaging.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 短信通道的<b>本地实现</b>：不连任何服务商、不发真实请求。
 *
 * <p>它存在的意义是让「通道」这件事在本地就<b>跑得通、看得见</b>：
 * <ul>
 *   <li>配齐密钥 → {@link #available()} 为 {@code true}，投递走 SMS，{@code notification_delivery}
 *       留一行 {@code channel='SMS'} 的痕（「发过」可断言，靠留痕与这里的日志，不靠真的短信）。</li>
 *   <li>密钥留空 → {@link #available()} 为 {@code false}，{@link NotificationSink} 降级到站内信。
 *       <b>不抛异常</b>——这正是「本地版本不需要密钥也能用」。</li>
 * </ul>
 *
 * <p>注意 {@link #available()} 与 {@link #send(String, String)} 的分工：
 * 「能不能发」是配置问题，「发了没有」是副作用。前者必须可查询（供降级判断），
 * 后者必须可计数（供回滚场景下断言调用次数）。
 */
@Component
public class LocalSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalSmsGateway.class);

    private final MessagingProperties properties;

    public LocalSmsGateway(MessagingProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean available() {
        MessagingProperties.Sms sms = properties.sms();
        // 三个条件缺一不可：总开关关掉时不走短信；只有 api-key 没有 secret 也不算配齐
        // （国内厂商普遍是 key + secret 双签，只填一半的后果是「每次调用都被签名校验拒」，
        //  那比直接降级更难排查）。
        return sms.enabled() && StringUtils.hasText(sms.apiKey()) && StringUtils.hasText(sms.secret());
    }

    @Override
    public void send(String username, String content) {
        // 本地实现：不真实外发。"发过"这件事由 notification_delivery 的留痕证明，
        // 这里留一条 info 是为了让「留痕被回滚但调用已发生」不至于彻底无迹可寻。
        log.info("本地短信通道：不真实外发，仅留痕（username={}，content={}）", username, content);
    }
}
