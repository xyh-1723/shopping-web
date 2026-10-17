-- 购物助手安全审计：记录每一次输入拦截、输出脱敏、工具越权拒绝与限流。
-- 这是「检测型控制」——防护规则会漏，但漏了必须留痕，便于复盘与调优规则。
CREATE TABLE assistant_audit (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  id VARCHAR(36) NOT NULL UNIQUE,
  username VARCHAR(100) NOT NULL,
  conversation_id VARCHAR(36),
  stage VARCHAR(16) NOT NULL,          -- INPUT / OUTPUT / TOOL / RATE
  action VARCHAR(16) NOT NULL,         -- ALLOW / MASK / BLOCK / REDACT / REPLACE / DENY
  rule_id VARCHAR(40),                 -- 命中的规则编号，如 PI_OVERRIDE
  owasp VARCHAR(8),                    -- 对应 OWASP LLM Top 10 编号，如 LLM01
  excerpt VARCHAR(400),                -- 命中片段（已截断）
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_user ON assistant_audit(username, created_at);
CREATE INDEX idx_audit_action ON assistant_audit(action, created_at);
