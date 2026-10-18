package dev.learning.mall.messaging.internal;

import java.util.Locale;

/**
 * 外发通知的通道。
 *
 * <p>三个常量刻意与 V17 里 {@code ck_delivery_channel} / {@code ck_delivery_preferred} 的
 * CHECK 集合<b>一模一样</b>：表先把「通道只有这三种」这个契约定下来了，枚举多一个或少一个
 * 都会让写进去的行被数据库拒掉，而那会把「投递一次通知」升级成「抛异常 → 重试 → 死信」。
 * 可选能力不该有这种破坏力（视觉模型那次的教训）。
 *
 * <p><b>{@code EMAIL} 在枚举里，但没有对应的 sender：</b>它是「可能被要求的通道」，
 * 只会出现在 {@code notification_delivery.preferred_channel} 这一列上——谁把
 * {@code default-channel} 配成 {@code EMAIL}，投递就会降级到 {@link #IN_APP}
 * 并留痕 {@code degraded=TRUE}。留着这个常量是为了让「你想走邮件、我没实现」这件事
 * <b>可断言</b>；把它从枚举里删掉，配了 EMAIL 的人只会得到一行没人看得见的 WARN 日志，
 * 而 {@code preferred_channel} 上撒的谎（写成 IN_APP 却标 degraded）反而更难查。
 */
public enum NotificationChannel {

    /** 站内信：落到既有的 {@code notification} 表，不需要任何密钥。 */
    IN_APP,

    /** 短信：走 {@link SmsGateway}，密钥齐备时才算可用，否则降级到 {@link #IN_APP}。 */
    SMS,

    /** 邮件：<b>本阶段不实现</b>，选中即降级到 {@link #IN_APP}（见类注释）。 */
    EMAIL;

    /**
     * 把配置里的通道名解析成枚举。
     *
     * @param raw 配置值，允许 {@code null}、空白与任意大小写
     * @return 对应的枚举；认不出来返回 {@code null}，由调用方兜到 {@link #IN_APP}
     *         （而不是抛异常——配错一个可选项不该让应用起不来）
     */
    public static NotificationChannel parse(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (NotificationChannel channel : values()) {
            if (channel.name().equals(name)) {
                return channel;
            }
        }
        return null;
    }
}
