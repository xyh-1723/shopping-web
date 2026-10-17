-- Agent 运行轨迹：把「模型 → 工具 → 观察」的每一步落库。
-- 目的是让「这次回答是怎么产生的」可复盘：为什么停了、调了哪些工具、哪个工具慢、哪一步失败。
-- 没有轨迹的 agent 调试只能靠重掷骰子；有了轨迹，问题才能定位到具体一步。
CREATE TABLE assistant_run (
  id VARCHAR(36) PRIMARY KEY,
  conversation_id VARCHAR(36),
  username VARCHAR(100) NOT NULL,
  model VARCHAR(60),
  stop_reason VARCHAR(20) NOT NULL,   -- DONE / MAX_STEPS / TIMEOUT / STALLED
  steps INT NOT NULL,
  tool_calls INT NOT NULL,
  wall_ms BIGINT NOT NULL,
  answer_excerpt VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_run_conv ON assistant_run(conversation_id, created_at);
CREATE INDEX idx_run_stop ON assistant_run(stop_reason, created_at);

-- 单步轨迹：没有工具调用的最终作答不产生 step，所以步数可能小于 tool_calls。
CREATE TABLE assistant_run_step (
  seq BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id VARCHAR(36) NOT NULL REFERENCES assistant_run(id) ON DELETE CASCADE,
  step_no INT NOT NULL,
  round INT NOT NULL,
  tool_name VARCHAR(80),
  args_excerpt VARCHAR(300),
  status VARCHAR(20) NOT NULL,        -- OK / ERROR / DENIED / DUPLICATE / TIMEOUT
  latency_ms BIGINT NOT NULL,
  result_chars INT NOT NULL,
  truncated BOOLEAN NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_run_step_run ON assistant_run_step(run_id, step_no);
