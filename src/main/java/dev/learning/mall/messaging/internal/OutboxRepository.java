package dev.learning.mall.messaging.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcClient jdbc;
    public OutboxRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public void insert(String id, String orderId, String user, String type, String content) {
        jdbc.sql("INSERT INTO outbox_event(id,aggregate_id,username,event_type,content) VALUES(:id,:oid,:user,:type,:content)")
                .param("id", id).param("oid", orderId).param("user", user).param("type", type).param("content", content).update();
    }
    public List<String> readyIds() {
        return jdbc.sql("SELECT id FROM outbox_event WHERE status='PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY created_at,id LIMIT 20")
                .query(String.class).list();
    }
    public Optional<EventRow> lock(String id) {
        return jdbc.sql("SELECT * FROM outbox_event WHERE id=:id FOR UPDATE").param("id", id).query(EventRow.class).optional();
    }
    public void delivered(String id) {
        jdbc.sql("UPDATE outbox_event SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=:id")
                .param("id", id).update();
    }
    public void failed(String id, int attempts, boolean dead, LocalDateTime next, String error) {
        jdbc.sql("UPDATE outbox_event SET attempts=:attempts,status=:status,next_attempt_at=:next,last_error=:error WHERE id=:id")
                .param("attempts", attempts).param("status", dead ? "DEAD" : "PENDING").param("next", next)
                .param("error", error).param("id", id).update();
    }
    public int retryDead(String id, String user) {
        return jdbc.sql("UPDATE outbox_event SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=:id AND username=:user AND status='DEAD'")
                .param("id", id).param("user", user).update();
    }
    public List<EventRow> list(String user) {
        return jdbc.sql("SELECT * FROM outbox_event WHERE username=:user ORDER BY created_at DESC,id LIMIT 50")
                .param("user", user).query(EventRow.class).list();
    }
}
