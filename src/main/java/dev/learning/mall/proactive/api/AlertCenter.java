package dev.learning.mall.proactive.api;

import java.util.List;

/**
 * 站内提醒中心：未读数 + 最近的提醒条目。
 *
 * <p>未读数单独给出来，是因为「有没有新提醒」和「提醒都有什么」是两种不同的读取频率：
 * 前者每次进页面都要问一下（便宜），后者只在用户真的展开时才需要（贵）。
 */
public record AlertCenter(int unread, List<Alert> alerts) {

    /** 一条提醒。{@code read} 由 {@code readAt} 推导，前端不需要自己去比时间。 */
    public record Alert(String id, String type, String title, String content, Long productId,
                        String createdAt, boolean read) {}
}
