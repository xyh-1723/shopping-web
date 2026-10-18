package dev.learning.mall.messaging.internal;

/**
 * 短信通道的接缝。
 *
 * <p>现在只有 {@link LocalSmsGateway} 一个实现：<b>本地实现，不真实外发</b>。
 * 将来要接真正的服务商（阿里云 / 腾讯云 …）时新增一个实现类、把
 * {@link MessagingProperties.Sms} 里的密钥位喂给它即可，{@link NotificationSink} 一行都不用改。
 * 这就是用户要的「预留一个密钥位，方便后续用它做真正的外发通道」。
 *
 * <p><b>为什么值得单独抽一个接口，而不是把几行逻辑直接写进 sink：</b>{@code send} 是
 * <b>不可回滚的外部调用</b>。事务回滚能抹掉 {@code notification_delivery} 的留痕，
 * 抹不掉「短信已经发出去了」这个事实——只查表会得出「什么都没发生」的错误结论。
 * 有独立接缝，才能在测试里断言<b>调用了几遍</b>，把「发出去了但没留痕」这种状态抓出来。
 */
public interface SmsGateway {

    /**
     * 这条通道当前能不能用。
     *
     * <p>约定：缺密钥时返回 {@code false}，<b>绝不抛异常</b>。调用方据此降级，
     * 于是「没配密钥」的表现是「走站内信 + 留一条降级痕」，而不是「通知功能死掉」。
     */
    boolean available();

    /**
     * 发一条短信。
     *
     * @param username 收件人（本阶段只有用户名；真实服务商需要的「用户名 → 手机号」映射不在本阶段范围内）
     * @param content  已经拼好的通知正文
     */
    void send(String username, String content);
}
