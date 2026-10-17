-- 导购闭环：服务端购物车 + 助手记忆 + 会话摘要。
--
-- 购物车从浏览器 localStorage 搬到服务端，理由不是「技术上更正确」，而是功能上的硬需求：
-- 助手要能替用户把商品放进购物袋。如果购物袋只活在某个浏览器的 localStorage 里，
-- 助手加购完用户根本看不到——前后端两份状态，必然分裂。跨端同步也是同一个道理。

CREATE TABLE mall_cart_item (
  username VARCHAR(100) NOT NULL,
  product_id BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  quantity INT NOT NULL CHECK (quantity > 0 AND quantity <= 99),
  added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (username, product_id)
);
CREATE INDEX idx_cart_user ON mall_cart_item(username, updated_at);

-- 助手记忆（语义记忆）：跨会话记住用户偏好。
-- 三道约束写在结构里，不靠模型自觉：
--   1. username + memory_key 唯一 —— 同一偏好只保留最新一条，不会被反复追加成脏数据；
--   2. memory_key 长度 40 —— 键只能是白名单里的短标识，模型写不进任意结构；
--   3. expires_at —— 偏好有时效（「这次预算 200」不该一年后还在生效）。
CREATE TABLE assistant_user_memory (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  memory_key VARCHAR(40) NOT NULL,
  memory_value VARCHAR(200) NOT NULL,
  source VARCHAR(20) NOT NULL DEFAULT 'MODEL',
  confidence DECIMAL(3,2) NOT NULL DEFAULT 0.60,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP,
  CONSTRAINT uq_memory_user_key UNIQUE (username, memory_key)
);
CREATE INDEX idx_memory_user ON assistant_user_memory(username, updated_at);

-- 会话摘要（工作记忆）：长对话里，窗口之外的早期历史压缩成一段摘要。
-- upto_seq 记录「已经摘要到第几条」，下次只对新消息做增量压缩，
-- 不用每次都把整段历史重算一遍。
CREATE TABLE assistant_conversation_summary (
  conversation_id VARCHAR(36) PRIMARY KEY REFERENCES assistant_conversation(id) ON DELETE CASCADE,
  username VARCHAR(100) NOT NULL,
  summary TEXT NOT NULL,
  upto_seq BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
