-- V15：成交之后的履约状态机（N1）。
--
-- 为什么不扩 status 的值：V1 的 status CHECK 是内联的，H2 起了自动名 CONSTRAINT_51，
-- 写进后续脚本跨环境会失配（V10 的教训）。这里改用「交易态 + 正交履约子状态」，
-- 与 V10 的 close_reason 同一套写法，且所有新约束一律显式命名。
--
-- 语句顺序（加列 → 回填 → 加约束 → 加索引）不是排版习惯，而是被约束逼出来的，
-- 理由写在每一节的小标题下；改这个文件前请先读 §2.5。

-- ===================================================================
-- 一、新列（先加列，才能回填，也才能加约束）
-- ===================================================================
-- paid_at：退款窗口要按「支付完成多久」判定，没有它就只能用 created_at 凑，
-- 而 created_at 是下单时刻——用户拖到最后一秒付款，凭空多出 15 分钟。
ALTER TABLE mall_order ADD COLUMN paid_at TIMESTAMP;
ALTER TABLE mall_order ADD COLUMN fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE mall_order ADD COLUMN shipped_at TIMESTAMP;
ALTER TABLE mall_order ADD COLUMN shipped_by VARCHAR(100);
ALTER TABLE mall_order ADD COLUMN shipping_no VARCHAR(40);
ALTER TABLE mall_order ADD COLUMN received_at TIMESTAMP;
ALTER TABLE mall_order ADD COLUMN completed_at TIMESTAMP;
-- 自动收货时刻：在发货这一刻算好并落库（与 expires_at 同一个道理——
-- 之后运营把 10 天改成 7 天，不该让在路上这批订单的对账一起跳变）。
ALTER TABLE mall_order ADD COLUMN auto_receive_at TIMESTAMP;

-- ===================================================================
-- 二、存量回填（必须排在约束之前：先加约束会被存量数据自己拒绝）
-- ===================================================================
-- 为什么必须在加约束之前：此刻库里可能已经有 status='PAID' 的行（V15 之前没有 paid_at
-- 这一列，它们天然是 NULL）。先建 ck_order_paid_needs_moment 再回填，这条 UPDATE 还没
-- 执行，约束就已经在拒绝这些行了——迁移会当场失败：
--   Check constraint violation: "CK_ORDER_PAID_NEEDS_MOMENT"
-- 所以顺序只能是「加列 → 回填 → 加约束」。这条顺序由 V15MigrationTest
-- #scriptAddsConstraintsAfterBackfill 守着：空库灌一笔 PAID 后完整重放本文件，必须成功。
--
-- 历史 PAID 订单没有支付时刻这个事实，唯一可得的下界是 created_at，用它兜底。
-- 代价要写清楚：这批订单的「无理由退款窗口」会比真实支付时刻早几分钟启动。
-- 条件 IS NULL 而不是无条件：这条语句会被重复执行（建库脚本回放 / 多环境重跑），
-- 无条件 UPDATE 会把已经正确的行改坏。
UPDATE mall_order SET paid_at = COALESCE(paid_at, created_at)
 WHERE status = 'PAID' AND paid_at IS NULL;

-- ===================================================================
-- 三、命名约束（回填完成后才建，此时存量行已经合法）
-- ===================================================================
-- 显式命名：H2 给内联 CHECK 起的是 CONSTRAINT_x 这类自动名，换个库实例就是另一个名字，
-- 报错信息里也认不出来（V10 的教训）。下面四条一律写着名字。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_fulfillment_status
  CHECK (fulfillment_status IN ('NONE','SHIPPED','RECEIVED','COMPLETED'));

-- 时间戳与状态必须同步：这条是整套履约状态机的地基——
-- 「SHIPPED 却没有发货时间」这种半吊子行由数据库拒绝，不靠调用方记得写全。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_fulfillment_fields
  CHECK (
    (fulfillment_status = 'NONE'      AND shipped_at IS NULL     AND received_at IS NULL     AND completed_at IS NULL)
 OR (fulfillment_status = 'SHIPPED'   AND shipped_at IS NOT NULL AND received_at IS NULL     AND completed_at IS NULL)
 OR (fulfillment_status = 'RECEIVED'  AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND completed_at IS NULL)
 OR (fulfillment_status = 'COMPLETED' AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND completed_at IS NOT NULL)
  );

-- 付过钱必须有支付时刻：这条约束会强制 OrderRepository.markPaid 同步改写（见 §8 T11）。
-- 留着「约束已建、markPaid 未改」这种中间状态是故意的——让约束把配套改动逼出来，
-- 代价是这两处必须在同一次提交里落地，不能拆成两个 PR。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_paid_needs_moment
  CHECK ((status <> 'PAID' AND paid_at IS NULL) OR (status = 'PAID' AND paid_at IS NOT NULL));

-- 没付钱的订单不允许进入履约：「钱到账才配货」这条底线写在这里，而不是写在某个 Controller 里。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_fulfillment_needs_paid
  CHECK (fulfillment_status = 'NONE' OR status = 'PAID');

-- ===================================================================
-- 四、索引（放在最后：索引只服务查询，不影响任何一条约束能否建立）
-- ===================================================================
-- 待发货 / 在途列表按履约状态查；自动收货扫描按「SHIPPED + 到期时刻」扫。
CREATE INDEX idx_order_fulfill     ON mall_order(fulfillment_status, shipped_at);
CREATE INDEX idx_order_autoreceive ON mall_order(fulfillment_status, auto_receive_at);
