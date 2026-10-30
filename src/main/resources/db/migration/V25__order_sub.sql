-- V25：按店拆子单（阶段 3b）。
--
-- 加商家表（V24）之后，「商家」第一次成了数据维度；但订单仍然是一整块：
-- 一笔跨店订单在库里是<b>一行</b> mall_order，于是——
--   · 履约（发货 / 收货 / 完成）只能整单推进，而现实里三家店是各发各的货；
--   · 退款挂在订单上，涉及的每一家店都能看到并批准它，且批准会退掉<b>整单</b>金额。
-- 这不是漏判，是「一行订单 = 一个卖家」这个结构假设的直接后果。
--
-- 淘宝的真实做法是<b>子订单</b>：买家下一次单、付一次款，结算时按店拆成 N 个子订单，
-- 每个子订单有自己的金额、自己的物流、自己的售后。本文件就是把这一层落到库里：
--
--    mall_order（一次交易：钱的状态、支付时限）
--        └── order_sub（每家店一份：金额 + 履约状态机）
--                └── order_item.merchant_id（子订单的成员，靠 (order_id, merchant_id) 归组）
--                        └── refund.order_sub_id（退款挂在子订单上）
--
-- ── 实测过的方言事实（tools/SubOrderDdlProbe，H2 2.3.232 MODE=MySQL 与 MySQL 8.0.31 都跑过）──
--   1. 回填的 INSERT 用<b>三层 CTE</b>（agg 聚合 → cut 求份额与前缀和 → alloc 落金额），
--      最后一层直接引用上一层的列别名算出 pay_amount，于是金额恒等式 CHECK 可以
--      <b>建在 CREATE TABLE 里</b>而不必等回填——两趟写法（先插 0 再 UPDATE）会让约束
--      在插入那一刻就打回自己的回填（实测 CK_SUB_AMOUNTS 违反）。
--   2. 分摊用<b>关联子查询</b>而不是窗口函数：可移植性更稳，也不依赖 MySQL 8 的窗口函数。
--   3. 「父单是否已支付 / 是不是本人在操作」这类<b>跨表</b>条件，SQL 里没有跨表 CHECK，
--      只能写进 UPDATE 的 WHERE：`EXISTS (SELECT 1 FROM mall_order o WHERE o.id = order_sub.order_id …)`。
--      带别名（UPDATE order_sub s … s.order_id）与不带别名两种写法两边都接受。
--   4. `DROP INDEX n` 裸写法 H2 接受、<b>MySQL 拒绝</b>（42000 语法错误）；
--      `ALTER TABLE t DROP INDEX n` 与 `DROP INDEX n ON t` 两边都通——本文件用前者。
--   5. 生成列 in_flight 改指向：只能 DROP INDEX → DROP COLUMN → ADD COLUMN … GENERATED ALWAYS AS
--      → 重建唯一索引（没有可移植的 MODIFY 表达式写法）。
--
-- ── 金额分摊规则（回填与新建子订单共用同一条）──
--   subtotal_i   = 该店明细的 Σ(单价 × 数量)，两位小数
--   discount_i   = 父单满减 × subtotal_i / 父单 subtotal，向下取整到「分」
--   shipping_i   = 同上（运费同理分摊）
--   最后一家店（sub_seq 最大）吃下全部取整余量 ⇒ Σ 子单 = 父单，<b>分毫不差</b>
--   pay_amount_i = subtotal_i − discount_i + shipping_i
-- 实测：父单小计 200.00 而明细和 199.99 的漂移样本，最后一家店记 39.96，
-- Σ 子单 [200.00/30.03/7.77/177.74] 与父单逐列相等（两种库上都一致）。
-- 代价要写清楚：那 1 分钱落在最后一家店头上，不是它真卖了这么多——
-- 这是「钱不能凭空多出来或少一分」与「每家店金额绝对精确」之间的取舍，前者优先。

-- ===================================================================
-- 一、order_sub：子订单
-- ===================================================================
-- 刻意<b>不</b>加 REFERENCES mall_order(id)：订单表在每个测试类的 @AfterEach 里被 DELETE，
-- 加外键会把「删订单」变成「先删子订单」，逼着一堆测试改清理顺序（V14 教训第 3 条）。
CREATE TABLE order_sub (
  id VARCHAR(64) PRIMARY KEY,
  -- id = 父单号 + '-S' + 序号：它是 (order_id, sub_seq) 的纯函数，
  -- 于是落子订单不需要「取回自增主键」那一趟往返，也不存在两套 id 对不上的可能。
  order_id VARCHAR(36) NOT NULL,
  sub_seq INT NOT NULL,                  -- 1..N，展示顺序 = 分店序号
  merchant_id BIGINT NOT NULL,           -- 这家店；与 order_item.merchant_id 同源
  subtotal DECIMAL(12,2) NOT NULL,
  discount DECIMAL(12,2) NOT NULL,
  shipping_fee DECIMAL(12,2) NOT NULL,
  pay_amount DECIMAL(12,2) NOT NULL,     -- = subtotal − discount + shipping_fee
  -- ── 履约状态机下移到子订单（原 V15 建在 mall_order 上，本节末下线）──
  fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'NONE',
  shipped_at TIMESTAMP,
  shipped_by VARCHAR(100),
  shipping_no VARCHAR(40),
  received_at TIMESTAMP,
  completed_at TIMESTAMP,
  -- 自动收货时刻：在发货那一刻算好并落库，道理与 mall_order.expires_at 相同。
  auto_receive_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 一家店在一笔订单里只能有一个子订单：这条唯一约束就是「按店拆」的数学定义。
  -- 少了它，同一家店可能被拆出两个子订单，于是「这笔退款是这家店的」又有两种答案。
  CONSTRAINT uq_sub_merchant UNIQUE (order_id, merchant_id),
  CONSTRAINT uq_sub_seq UNIQUE (order_id, sub_seq),
  CONSTRAINT fk_sub_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
  CONSTRAINT ck_sub_fulfillment_status
    CHECK (fulfillment_status IN ('NONE','SHIPPED','RECEIVED','COMPLETED')),
  -- 时刻与状态必须同步，与 V15 的 ck_order_fulfillment_fields 同一条规则，只是换了主人。
  CONSTRAINT ck_sub_fulfillment_fields CHECK (
    (fulfillment_status = 'NONE'      AND shipped_at IS NULL     AND received_at IS NULL     AND completed_at IS NULL)
 OR (fulfillment_status = 'SHIPPED'   AND shipped_at IS NOT NULL AND received_at IS NULL     AND completed_at IS NULL)
 OR (fulfillment_status = 'RECEIVED'  AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND completed_at IS NULL)
 OR (fulfillment_status = 'COMPLETED' AND shipped_at IS NOT NULL AND received_at IS NOT NULL AND completed_at IS NOT NULL)),
  -- 子订单自己的金额恒等式：与父单同一条规则（pay = 小计 − 优惠 + 运费），
  -- 四个金额都非负。它和「Σ 子单 = 父单」一起，让「钱既不会凭空多出来也不会少一分」
  -- 成为一条能被 SQL 直接断言的不变量。
  CONSTRAINT ck_sub_amounts
    CHECK (pay_amount = subtotal - discount + shipping_fee
           AND subtotal >= 0 AND discount >= 0 AND shipping_fee >= 0 AND pay_amount >= 0)
);
CREATE INDEX idx_sub_order            ON order_sub(order_id);
CREATE INDEX idx_sub_merchant_status  ON order_sub(merchant_id, fulfillment_status);
CREATE INDEX idx_sub_autoreceive      ON order_sub(fulfillment_status, auto_receive_at);

-- ===================================================================
-- 二、存量回填：把每一笔已有订单按店拆开
-- ===================================================================
-- 条件式：这条语句会被重复执行（建库脚本回放 / 多环境重跑），
-- 无条件 INSERT 会因为 uq_sub_merchant 直接撞唯一约束。
INSERT INTO order_sub (id, order_id, sub_seq, merchant_id, subtotal, discount, shipping_fee, pay_amount)
WITH agg AS (
  SELECT i.order_id AS order_id, i.merchant_id AS merchant_id,
         SUM(i.unit_price * i.quantity) AS raw_subtotal
    FROM order_item i
   GROUP BY i.order_id, i.merchant_id
), cut AS (
  SELECT a.order_id AS order_id, a.merchant_id AS merchant_id,
         a.raw_subtotal AS raw_subtotal,
         o.subtotal AS o_subtotal, o.discount AS o_discount, o.shipping_fee AS o_ship,
         (SELECT COUNT(*) FROM agg b
           WHERE b.order_id = a.order_id AND b.merchant_id <= a.merchant_id) AS seq,
         (SELECT COUNT(*) FROM agg b WHERE b.order_id = a.order_id) AS cnt,
         (SELECT COALESCE(SUM(ROUND(b.raw_subtotal, 2)), 0) FROM agg b
           WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_sub,
         (SELECT COALESCE(SUM(ROUND(o.discount * b.raw_subtotal / NULLIF(o.subtotal, 0), 2)), 0)
            FROM agg b WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_dis,
         (SELECT COALESCE(SUM(ROUND(o.shipping_fee * b.raw_subtotal / NULLIF(o.subtotal, 0), 2)), 0)
            FROM agg b WHERE b.order_id = a.order_id AND b.merchant_id < a.merchant_id) AS prev_ship
    FROM agg a JOIN mall_order o ON o.id = a.order_id
), alloc AS (
  -- 最后一层：把上面两套 CASE 落成为列，pay_amount 由这三个列别名算出。
  -- 抄两份表达式 = 两处真相，CTE 的别名引用正是为了避免这件事。
  SELECT c.order_id AS order_id, c.seq AS seq, c.merchant_id AS merchant_id,
         CASE WHEN c.seq = c.cnt THEN c.o_subtotal - c.prev_sub ELSE ROUND(c.raw_subtotal, 2) END AS subtotal,
         CASE WHEN c.seq = c.cnt THEN c.o_discount - c.prev_dis
              ELSE ROUND(c.o_discount * c.raw_subtotal / NULLIF(c.o_subtotal, 0), 2) END AS discount,
         CASE WHEN c.seq = c.cnt THEN c.o_ship - c.prev_ship
              ELSE ROUND(c.o_ship * c.raw_subtotal / NULLIF(c.o_subtotal, 0), 2) END AS shipping_fee
    FROM cut c
)
SELECT CONCAT(a.order_id, '-S', a.seq), a.order_id, a.seq, a.merchant_id,
       a.subtotal, a.discount, a.shipping_fee,
       a.subtotal - a.discount + a.shipping_fee
  FROM alloc a
 WHERE NOT EXISTS (SELECT 1 FROM order_sub s WHERE s.order_id = a.order_id);

-- （金额恒等式已建在 CREATE TABLE 里，不需要后置的 UPDATE / ADD CONSTRAINT。）

-- ===================================================================
-- 三、refund：退款挂到子订单上
-- ===================================================================
-- 3a 留下的已知边界在这里被堵上：以前「这笔订单涉及哪些店」只有明细知道，
-- 于是跨店订单的退款每一家店都能批、且退的是整单金额。挂上 order_sub_id 之后，
-- 一笔退款在结构上只可能属于一家店，「A 批了 B 的钱」这句话第一次无法被表达。
ALTER TABLE refund ADD COLUMN order_sub_id VARCHAR(64);

-- 回填：V25 之前的退款没有「哪家店」这个事实可查（当时根本没有子订单），
-- 这里挂到 sub_seq 最小那一家，是<b>回填的近似，不是真实归属</b>——
-- 它唯一的用途是让 NOT NULL 立得住。真实主库在落本迁移时 refund 表为空（实测 0 行），
-- 因此这条语句在生产上不产生任何一条「被编出来的归属」。
UPDATE refund r SET r.order_sub_id =
  (SELECT s.id FROM order_sub s WHERE s.order_id = r.order_id ORDER BY s.sub_seq LIMIT 1)
 WHERE r.order_sub_id IS NULL;

-- 「一个子订单只能有一笔在途退款」：把生成列从 order_id 改指到 order_sub_id。
-- 生成列的表达式没有可移植的 MODIFY 写法，只能整列重建（实测第 5 条）。
ALTER TABLE refund DROP INDEX uq_refund_inflight;
ALTER TABLE refund DROP COLUMN in_flight;
ALTER TABLE refund ADD COLUMN in_flight VARCHAR(64) GENERATED ALWAYS AS
  (CASE WHEN status IN ('PENDING_APPROVAL','APPROVED') THEN order_sub_id END);
CREATE UNIQUE INDEX uq_refund_inflight ON refund(in_flight);
CREATE INDEX idx_refund_sub ON refund(order_sub_id);

-- 收成 NOT NULL：以后任何一笔退款都必须说清自己属于哪个子订单。
-- 若某个环境里存在「订单没有任何明细」的孤儿退款，这条会<b>响亮失败</b>而不是静默挂 NULL——
-- 静默 NULL 会长出「整单退款」这个第二个语义，那正是本次要消灭的东西。
ALTER TABLE refund MODIFY COLUMN order_sub_id VARCHAR(64) NOT NULL;

-- ===================================================================
-- 四、HITL 待确认动作：记住要退的是哪个子订单
-- ===================================================================
-- 助手代发起退款时，跨店订单必须说清退哪一家；不传就由服务端按「只有一家店」推导，
-- 多于一家则明确拒绝——绝不能替用户猜一家（猜错了就是把钱退给错的店）。
ALTER TABLE assistant_pending_action ADD COLUMN order_sub_id VARCHAR(64);

-- ===================================================================
-- 五、mall_order：履约字段下线
-- ===================================================================
-- 顺序：先删索引（它们建在被删的列上）→ 再删约束（引用被删的列）→ 最后删列。
-- 「钱到账才配货」（ck_order_fulfillment_needs_paid）没有被放弃，它换了个地方：
-- 现在由每一条子订单 UPDATE 的 WHERE 里的
--   EXISTS (SELECT 1 FROM mall_order o WHERE o.id = order_sub.order_id AND o.status = 'PAID')
-- 承担——跨表条件只能写进语句，数据库里没有跨表 CHECK（实测第 3 条）。
ALTER TABLE mall_order DROP INDEX idx_order_fulfill;
ALTER TABLE mall_order DROP INDEX idx_order_autoreceive;
ALTER TABLE mall_order DROP CONSTRAINT ck_order_fulfillment_status;
ALTER TABLE mall_order DROP CONSTRAINT ck_order_fulfillment_fields;
ALTER TABLE mall_order DROP CONSTRAINT ck_order_fulfillment_needs_paid;
ALTER TABLE mall_order DROP COLUMN fulfillment_status;
ALTER TABLE mall_order DROP COLUMN shipped_at;
ALTER TABLE mall_order DROP COLUMN shipped_by;
ALTER TABLE mall_order DROP COLUMN shipping_no;
ALTER TABLE mall_order DROP COLUMN received_at;
ALTER TABLE mall_order DROP COLUMN completed_at;
ALTER TABLE mall_order DROP COLUMN auto_receive_at;

ANALYZE TABLE order_sub;
ANALYZE TABLE refund;
