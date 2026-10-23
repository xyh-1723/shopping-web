-- HITL 从「支付专用」泛化为「多类写操作共用同一道人类闸门」。
--
-- 为什么泛化而不是新建一张表：HITL 承载的是安全关键逻辑——归属校验（只能本人确认）、
-- 一次性原子迁移（重复确认只执行一次）、TTL（隔天失效）、审计留痕。
-- 复制出第二份实现，就是这些规则分叉的开始（项目决策第 2 条：安全关键逻辑只有一份实现）。

-- 原表为「支付」而生，这两列当时是 NOT NULL；泛化后只有 ORDER_PAYMENT 才需要它们。
-- MySQL 没有 H2 的 `ALTER COLUMN ... SET NULL`，只有 MODIFY，而 MODIFY 必须把类型重复一遍
-- （所以这里写死了 VARCHAR(36) / VARCHAR(20)，与 V5 的原定义一致）。
-- 这是两侧语法唯一的交集，代价是改列类型时要记得同步这一行。
ALTER TABLE assistant_pending_action MODIFY COLUMN order_id VARCHAR(36) NULL;
ALTER TABLE assistant_pending_action MODIFY COLUMN payment_method VARCHAR(20) NULL;

-- ORDER_CREATE 的载荷：服务端生成的幂等键、要下的商品行。
-- 没有加「请求指纹」列：行内容原样落在这里，且幂等键每次随机生成、不可能被重放；
-- 真要校验「内容是否变化」，是订单模块自己的职责（OrderFacade 会算请求哈希并对不上就报错）。
ALTER TABLE assistant_pending_action ADD COLUMN idempotency_key VARCHAR(80);
ALTER TABLE assistant_pending_action ADD COLUMN lines_json VARCHAR(2000);

-- 每一类 kind 各自的必填字段，显式命名（不依赖 H2 自动名，理由同 V12）。
-- 存量行都是 ORDER_PAYMENT 且两列非空，因此加约束不会拒绝历史数据。
ALTER TABLE assistant_pending_action ADD CONSTRAINT ck_pending_kind_fields
  CHECK (
    (kind = 'ORDER_PAYMENT' AND order_id IS NOT NULL AND payment_method IS NOT NULL)
    OR (kind = 'ORDER_CREATE'  AND idempotency_key IS NOT NULL AND lines_json IS NOT NULL)
    OR (kind = 'ORDER_CANCEL'  AND order_id IS NOT NULL)
  );

CREATE INDEX idx_pending_kind ON assistant_pending_action(kind, status);
