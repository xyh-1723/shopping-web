-- 待确认动作（Human-in-the-loop）：模型发起的写操作先落成一条「待确认」记录，
-- 真正的执行（如支付）只在用户本人于页面点击确认后发生。
-- 模型到执行之间隔着一道人类闸门：提示词注入最多骗模型「发起」，骗不了人「确认」。
CREATE TABLE assistant_pending_action (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  id VARCHAR(36) NOT NULL UNIQUE,
  username VARCHAR(100) NOT NULL,       -- 归属用户（服务端登录名，确认时硬校验）
  conversation_id VARCHAR(36),          -- 发起时所在会话（用于页面重新加载后找回卡片）
  kind VARCHAR(32) NOT NULL,            -- ORDER_PAYMENT
  order_id VARCHAR(36) NOT NULL,
  payment_method VARCHAR(20) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,        -- 发起时的金额快照，确认卡片上展示的就是它
  status VARCHAR(16) NOT NULL,          -- PENDING / CONFIRMED / CANCELLED / EXPIRED
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,        -- 超时未确认自动失效，防止「隔天被重放」
  resolved_at TIMESTAMP
);
CREATE INDEX idx_pending_user ON assistant_pending_action(username, status);
CREATE INDEX idx_pending_conv ON assistant_pending_action(conversation_id, status);
