-- 购物助手：多轮会话持久化。会话按用户隔离，删除会话时消息级联清除。
CREATE TABLE assistant_conversation (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  title VARCHAR(100) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_conversation_user ON assistant_conversation(username, updated_at);
CREATE TABLE assistant_message (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  id VARCHAR(36) NOT NULL UNIQUE,
  conversation_id VARCHAR(36) NOT NULL REFERENCES assistant_conversation(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  tool_data VARCHAR(4000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_message_conv ON assistant_message(conversation_id, seq);
