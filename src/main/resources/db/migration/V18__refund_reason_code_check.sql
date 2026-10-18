-- V18：refund.reason_code 的取值约束。
--
-- 依赖：V17（refund 表已存在）。编号必须 > 17 且一次写对：Flyway 默认禁止 out-of-order，
-- V17 已落盘并带校验和，因此这里新开一个文件而不是回头改 V17——改已落盘的迁移会让
-- 既有环境的 checksum 校验失败（上线即崩，而不是上线即生效）。
--
-- 约束写成独立文件而不是塞进 V17 的第二行，还有一个副作用是它可被单独断言：
-- V18MigrationTest 按名字查它、并用真实写入探针证明它真的在拦。

-- ===================================================================
-- reason_code 的取值域
-- ===================================================================
-- 为什么必须由数据库来守：Java 侧 RefundService#normalizeReasonCode 已经拒过一次，
-- 但那条路径只覆盖「走 RefundService 的人」。裸 SQL、数据修数脚本、将来的批量导入
-- 都不经过它，于是 reason_code 会成为「Java 里是枚举、库里是自由文本」的列。
-- 两份真相一旦分家，谁先被发现谁就是 bug。
--
-- ⚠ 耦合点：本约束的取值集合必须与 RefundService.REASON_CODES
--   （List.of("NO_REASON", "DAMAGED", "WRONG_ITEM", "OTHER")）逐字一致。
--   Java 侧枚举新增值时，本约束要同步扩展——否则新值在 Java 里合法、在库里被拒，
--   报错形态是 500 而不是业务异常。
--
-- 约束必须显式命名：H2 给内联 CHECK 起的是自动名（形如 CONSTRAINT_51），
-- 而自动名会在环境之间失配（它是建表顺序的函数），改名等于换库。
-- 名字还出现在异常消息里，测试要断言它。
ALTER TABLE refund ADD CONSTRAINT ck_refund_reason_code
  CHECK (reason_code IN ('NO_REASON', 'DAMAGED', 'WRONG_ITEM', 'OTHER'));

-- 注：H2 2.3.232 判定 CHECK 为 UNKNOWN（NULL）时视为通过——这是 SQL 语义，不是漏洞。
-- 本列在 V17 里是 NOT NULL，所以 NULL 根本进不来，无需额外加 IS NULL 分支。
