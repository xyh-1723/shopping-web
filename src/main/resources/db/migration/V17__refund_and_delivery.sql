-- V17：退款（N2）+ HITL 退款闸门字段 + 外发投递留痕（Q5）。
--
-- 依赖：V14（stock_ledger 已存在）、V15（fulfillment_status 已存在）。Flyway 顺序即依赖保证。
-- 编号说明：本文件在方案正文里一度写成 V16；钱包落地后占了 V16，退款顺延到 V17。
-- 本文件<b>不引用</b> V16 的 wallet / wallet_ledger，两者物理上互不依赖（方案 §13.3）。
--
-- 边界：本文件只建「退款」这个业务实体与它的状态机；真实放款由 payment → wallet 完成，
-- 资金本身不在这里出现（方案 §13.1）。

-- ===================================================================
-- 一、stock_ledger 认得退款归还
-- ===================================================================
-- 退款归还库存要能在流水里被认出来：关单归还叫 RELEASE（订单没成交，货压根没出去），
-- 退款归还叫 REFUND（钱收过了，货要回来）。共用同一个 reason 会把这两件事混在一起，
-- 于是「这笔订单到底还过货没有」这条幂等判据没法写（见方案 §5）。
-- 能可靠地 DROP 掉 V14 那条，正因为它是命名约束（自动名会跨环境失配）。
ALTER TABLE stock_ledger DROP CONSTRAINT ck_ledger_reason;
ALTER TABLE stock_ledger ADD CONSTRAINT ck_ledger_reason_v2
  CHECK (reason IN ('RESERVE', 'RELEASE', 'RESTOCK', 'REFUND'));

-- ===================================================================
-- 二、refund：退款业务实体与它自己的状态机
-- ===================================================================
CREATE TABLE refund (
  id VARCHAR(36) PRIMARY KEY,
  -- 刻意不加 REFERENCES mall_order(id)：订单表在每个测试类的 @AfterEach 里被 DELETE，
  -- 加外键会把「删订单」变成「先删退款」，逼着 10+ 个测试类改清理顺序（V14 教训第 3 条）。
  order_id VARCHAR(36) NOT NULL,
  username VARCHAR(100) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  reason_code VARCHAR(30) NOT NULL,        -- NO_REASON / DAMAGED / WRONG_ITEM / OTHER
  reason_text VARCHAR(200),
  route VARCHAR(20) NOT NULL,              -- AUTO / MERCHANT_APPROVAL：分流结果落库，可事后复盘
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL',
  requested_by VARCHAR(100) NOT NULL,      -- 买家自己，或助手代发起（写请求者标识）
  decided_by VARCHAR(100),                 -- MERCHANT 审批人；AUTO 路由固定为 'SYSTEM'
  decided_at TIMESTAMP,
  decision_note VARCHAR(200),
  -- 库存是否已归还。这是幂等的第一道实物标记（见方案 §5）。
  stock_returned INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP,

  -- ── 「一笔订单只能有一笔在途退款」的数据库保证 ──
  -- H2 2.3.232 不支持条件唯一索引（CREATE UNIQUE INDEX … WHERE …，实测 Syntax error），
  -- 于是用「生成列 + 唯一索引」表达：在途态映射到 order_id，终态映射到 NULL；
  -- H2 的唯一索引允许多个 NULL，所以终态可以留任意多条历史，在途只允许一条。
  --
  -- 迁移与代码的三条必须遵守的注意点（都是实测结论，不是纸上推演）：
  --   1. GENERATED ALWAYS AS 的列不可手工赋值。INSERT/UPDATE 写它会报
  --      `Generated column "PUBLIC.REFUND.IN_FLIGHT" cannot be assigned`，
  --      因此所有 INSERT 的列清单里都不要出现 in_flight，种子数据与测试夹具同理。
  --   2. 生成列在 UPDATE 时会重算（实测 PENDING_APPROVAL→APPROVED 仍为 order_id、
  --      APPROVED→REFUNDED 后变 NULL）。这条是本方案成立的前提：
  --      若不重算，约束就从「防重复」退化成「这笔订单永远不能再退款」。
  --   3. 表达式必须是确定性的。H2 2.3.232 **不会**拦你（实测 CURRENT_TIMESTAMP / RAND()
  --      / RANDOM_UUID() 都能建表），所以这是自律而非数据库保证：非确定性表达式在每次
  --      UPDATE 时重算出新值，会让唯一索引的键漂移，约束形同虚设。
  --
  -- ⚠ 耦合点：这里的在途态集合 ('PENDING_APPROVAL','APPROVED') 与下面的 ck_refund_status
  --   是两处独立的枚举。将来若给 refund.status 增加第三个「在途」值，必须同步改这里，
  --   否则新状态的退款不受约束（静默失效，不报错）。反向验证见 V17MigrationTest。
  in_flight VARCHAR(36) GENERATED ALWAYS AS
    (CASE WHEN status IN ('PENDING_APPROVAL','APPROVED') THEN order_id END)
);
CREATE INDEX idx_refund_order ON refund(order_id, created_at);
CREATE INDEX idx_refund_queue ON refund(status, created_at);
-- 命名沿用项目既有惯例（V1 uq_order_request / V7 uq_memory_user_key / V11 uq_alert_source 都是 uq_ 前缀）。
-- 命名而不是让 H2 自动起名，因为约束名会出现在异常消息里，测试要断言它。
CREATE UNIQUE INDEX uq_refund_inflight ON refund(in_flight);

ALTER TABLE refund ADD CONSTRAINT ck_refund_amount CHECK (amount > 0);
ALTER TABLE refund ADD CONSTRAINT ck_refund_route
  CHECK (route IN ('AUTO', 'MERCHANT_APPROVAL'));
ALTER TABLE refund ADD CONSTRAINT ck_refund_status
  CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'REFUNDED', 'CANCELLED'));
ALTER TABLE refund ADD CONSTRAINT ck_refund_stock_flag CHECK (stock_returned IN (0, 1));
-- 审批字段要么全空（还在等 / 买家撤回），要么全满（已有决定）。半吊子行不存在。
ALTER TABLE refund ADD CONSTRAINT ck_refund_decision_fields
  CHECK (
    (status IN ('PENDING_APPROVAL','CANCELLED') AND decided_by IS NULL AND decided_at IS NULL)
    OR (status IN ('APPROVED','REJECTED','REFUNDED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
  );
-- AUTO 全程没有人类审批人，decided_by 只能是 SYSTEM——防止把自助退款伪造成「某某批过」。
-- ⚠ 这里必须允许 decided_by IS NULL：退款申请一律先落在 PENDING_APPROVAL（尚未决定），
--   之后才在同一事务内推进到终态。若写成 `route <> 'AUTO' OR decided_by = 'SYSTEM'`，
--   NULL = 'SYSTEM' 判 NULL 不判 TRUE，AUTO 路径连建单都会被自己的约束拒绝（实测）。
--   「还没定」与「系统定的」都合法，「某个人定的」对 AUTO 永远不合法（伪造审批仍然被挡）。
ALTER TABLE refund ADD CONSTRAINT ck_refund_auto_decided_by_system
  CHECK (route <> 'AUTO' OR decided_by IS NULL OR decided_by = 'SYSTEM');
-- 库存只能跟着「已放款」走，不能「还没批就先退货」。
-- 现实里「批准」与「钱到账」常常不是同一时刻，所以规则必须写死成只有 REFUNDED 才动手，
-- 否则「批了但转账失败」会变成「库存回来了、钱没退」。
ALTER TABLE refund ADD CONSTRAINT ck_refund_stock_only_when_refunded
  CHECK (stock_returned = 0 OR status = 'REFUNDED');

-- ===================================================================
-- 三、HITL：助手发起退款走已泛化的那道闸门，不新建第二张「待确认」表
-- ===================================================================
ALTER TABLE assistant_pending_action ADD COLUMN refund_reason_code VARCHAR(30);
ALTER TABLE assistant_pending_action ADD COLUMN refund_reason_text VARCHAR(200);

-- V13 的 ck_pending_kind_fields 是穷举式 OR：新增 ORDER_REFUND 不改它，
-- 插入会直接被数据库拒绝（实测见方案 §11）。这里能可靠 DROP，正因为 V13 当初用了命名约束。
ALTER TABLE assistant_pending_action DROP CONSTRAINT ck_pending_kind_fields;
ALTER TABLE assistant_pending_action ADD CONSTRAINT ck_pending_kind_fields_v2
  CHECK (
    (kind = 'ORDER_PAYMENT' AND order_id IS NOT NULL AND payment_method IS NOT NULL)
    OR (kind = 'ORDER_CREATE'  AND idempotency_key IS NOT NULL AND lines_json IS NOT NULL)
    OR (kind = 'ORDER_CANCEL'  AND order_id IS NOT NULL)
    OR (kind = 'ORDER_REFUND'  AND order_id IS NOT NULL AND amount IS NOT NULL
        AND refund_reason_code IS NOT NULL)
  );

-- ===================================================================
-- 四、notification_delivery：外发投递留痕（本地实现也写，降级也写）
-- ===================================================================
-- 本阶段只建这张表，写入它的「通道抽象」属于 T15（方案 §6），
-- 因此它在本次交付里暂时没有 Java writer——这是有意为之的接缝，不是死表：
-- 表结构与 null 语义先把「降级要留痕」这条契约定下来，接线时不许再改它。
CREATE TABLE notification_delivery (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  channel VARCHAR(20) NOT NULL,          -- 实际投递走的通道
  preferred_channel VARCHAR(20) NOT NULL,-- 原本想走的通道（降级后两者不同）
  degraded BOOLEAN NOT NULL DEFAULT FALSE,
  event_type VARCHAR(30) NOT NULL,       -- ORDER_SHIPPED / ORDER_RECEIVED / ORDER_REFUNDED …
  order_id VARCHAR(36),                  -- 同样不加外键，理由同 refund
  content VARCHAR(400) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_delivery_user ON notification_delivery(username, created_at);
ALTER TABLE notification_delivery ADD CONSTRAINT ck_delivery_channel
  CHECK (channel IN ('IN_APP', 'SMS', 'EMAIL'));
ALTER TABLE notification_delivery ADD CONSTRAINT ck_delivery_preferred
  CHECK (preferred_channel IN ('IN_APP', 'SMS', 'EMAIL'));
