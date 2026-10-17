package dev.learning.mall.assistant.internal;

import dev.learning.mall.assistant.api.ChatMessageView;
import dev.learning.mall.assistant.api.ConversationView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 会话与消息持久化。所有查询都带 username 过滤，避免越权读取他人会话。 */
@Repository
class ConversationRepository {
    private final JdbcClient jdbc;
    ConversationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static final String SUMMARY_SELECT =
            "SELECT c.id, c.title, c.created_at, c.updated_at, " +
            "(SELECT COUNT(*) FROM assistant_message m WHERE m.conversation_id = c.id) AS message_count " +
            "FROM assistant_conversation c ";

    String create(String username, String title) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO assistant_conversation(id,username,title) VALUES(:id,:user,:title)")
                .param("id", id).param("user", username).param("title", title).update();
        return id;
    }

    List<ConversationView> list(String username) {
        return jdbc.sql(SUMMARY_SELECT + "WHERE c.username = :user ORDER BY c.updated_at DESC, c.id LIMIT 50")
                .param("user", username).query(ConversationView.class).list();
    }

    Optional<ConversationView> find(String id, String username) {
        return jdbc.sql(SUMMARY_SELECT + "WHERE c.id = :id AND c.username = :user")
                .param("id", id).param("user", username).query(ConversationView.class).optional();
    }

    int delete(String id, String username) {
        // 消息由外键 ON DELETE CASCADE 自动清除。
        return jdbc.sql("DELETE FROM assistant_conversation WHERE id = :id AND username = :user")
                .param("id", id).param("user", username).update();
    }

    List<ChatMessageView> messages(String id, String username) {
        return jdbc.sql("SELECT m.id, m.role, m.content, m.tool_data, m.created_at " +
                "FROM assistant_message m JOIN assistant_conversation c ON m.conversation_id = c.id " +
                "WHERE c.id = :id AND c.username = :user ORDER BY m.seq")
                .param("id", id).param("user", username).query(ChatMessageView.class).list();
    }

    Optional<ChatMessageView> findMessage(String id, String username) {
        return jdbc.sql("SELECT m.id, m.role, m.content, m.tool_data, m.created_at " +
                "FROM assistant_message m JOIN assistant_conversation c ON m.conversation_id = c.id " +
                "WHERE m.id = :id AND c.username = :user")
                .param("id", id).param("user", username).query(ChatMessageView.class).optional();
    }

    void insertMessage(String id, String conversationId, String role, String content, String toolData) {
        jdbc.sql("INSERT INTO assistant_message(id,conversation_id,role,content,tool_data) " +
                "VALUES(:id,:cid,:role,:content,:tool)")
                .param("id", id).param("cid", conversationId).param("role", role)
                .param("content", content).param("tool", toolData).update();
    }

    void touch(String conversationId) {
        jdbc.sql("UPDATE assistant_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", conversationId).update();
    }

    void renameTitle(String id, String username, String title) {
        jdbc.sql("UPDATE assistant_conversation SET title = :title WHERE id = :id AND username = :user")
                .param("title", title).param("id", id).param("user", username).update();
    }
}
