package dev.learning.mall.assistant.internal;

import dev.learning.mall.assistant.api.ChatMessageView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 助手轮次的数据库写入边界——<b>多用户并发的第二个关键修复点</b>。
 *
 * <p>原先 {@code AssistantService.sendDetailed} 上挂着 {@code @Transactional}，
 * 而方法里包含多次大模型调用（可能 10–30 秒）。这意味着一件事：
 * <b>一整轮对话从头到尾独占一个数据库连接</b>。Hikari 池大小是 12，
 * 于是第 13 个并发提问就会卡在等连接上，而且——更糟——商城的下单、查商品、登录
 * 用的是同一个连接池，会被助手的慢请求一起拖死。助手不该有能力影响整个站点。
 *
 * <p>正确做法是<b>把事务收窄到只有真正碰数据库的那一瞬间</b>：写用户消息（毫秒级）、
 * 写助手消息（毫秒级）。模型调用期间不持有任何连接，连接池的占用时长从「秒级」降回
 * 「毫秒级」，同一个 12 连接的小池就能服务几百个并发轮次。
 *
 * <p>这里用 {@link TransactionTemplate} 显式表达边界，而不是靠注解——因为边界本身
 * 就是这个类的全部意义，写在代码里比藏在注解里更难被后人误删。
 */
@Component
class AssistantTurnStore {

    /** 单段写入的事务超时（秒）。本地库毫秒级完成；给个上限避免极端情况长期持锁。 */
    private static final int TX_TIMEOUT_SECONDS = 10;

    private final TransactionTemplate tx;
    private final ConversationRepository conversations;

    AssistantTurnStore(PlatformTransactionManager transactionManager, ConversationRepository conversations) {
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(TX_TIMEOUT_SECONDS);
        this.conversations = conversations;
    }

    /**
     * 事务 A：落下用户消息；若是首条消息，顺带用内容自动命名会话。
     *
     * <p>为什么自动命名要和插入同事务：先插消息再改名，两步之间若失败会留下
     * 「有消息但标题还是『新会话』」的半成品状态，而同事务保证两者同生共死。
     */
    void appendUserMessage(String conversationId, String user, String text, String messageId,
                           boolean deriveTitle) {
        tx.executeWithoutResult(status -> {
            conversations.insertMessage(messageId, conversationId, "USER", text, null);
            if (deriveTitle) {
                String derived = text.strip().replaceAll("\\s+", " ");
                if (derived.length() > 20) derived = derived.substring(0, 20) + "…";
                if (!derived.isBlank()) conversations.renameTitle(conversationId, user, derived);
            }
        });
    }

    /**
     * 事务 B：落下助手回复（含商品卡片数据）并刷新会话更新时间。
     * 返回落库后的消息视图，供接口直接回给前端。
     */
    ChatMessageView appendAssistantMessage(String conversationId, String user, String content,
                                           String toolData, String messageId) {
        ChatMessageView saved = tx.execute(status -> {
            conversations.insertMessage(messageId, conversationId, "ASSISTANT", content, toolData);
            conversations.touch(conversationId);
            return conversations.findMessage(messageId, user).orElse(null);
        });
        if (saved == null) {
            // 理论上不可达（刚插入就查不到）；真出现说明写入被回滚，必须暴露而不是返回 null。
            throw new IllegalStateException("助手回复落库失败：conversationId=" + conversationId);
        }
        return saved;
    }
}
