-- 超时关单的分片扫描基础。
--
-- 单实例时「每分钟扫一次到期未支付订单」就够了。多实例时，每个实例都会扫到同一批订单，
-- 于是同一笔订单被 N 个实例同时尝试关闭：带条件的 UPDATE（markClosed 只在 status='PENDING'
-- 时生效）保证不会重复归还库存，但另外 N-1 个实例会在行锁上白等一轮。
-- 分片要解决的<b>不是</b>「重复关闭」——那条已经被状态机挡住了——而是「重复挑中同一批候选」
-- 和随之而来的锁等待。
--
-- 分片键取<b>订单号首字符的十六进制值（0..15）</b>，不用额外的哈希函数，理由是：
--   1. 订单号是 UUID，首字符在 16 个十六进制字符上均匀分布，天然均衡；
--   2. 它是订单号的纯函数，任何实例、任何时刻算出来都一样，
--      因此不需要「谁负责哪一段」的中心化分配表，也就没有分配表本身的一致性问题；
--   3. 落库成列而不是每次现算：现算就没法走索引，分片反而退化成全表扫。
--
-- 代价写在前面：分片数最多 16（首字符只有 16 种取值）。对一个学习项目足够；
-- 真需要更细的粒度时，应该换成「对订单号做一次真正的哈希取模」，而不是在这里加位数。
ALTER TABLE mall_order ADD COLUMN expiry_shard SMALLINT NOT NULL DEFAULT 0;

-- 分片值必须落在 0..15。这不是装饰：越界值进到 MOD() 之后，MySQL 与 H2 对负数取模的
-- 定义不同，同一个订单在不同实例上会被算进不同分片，于是它可能谁都扫不到。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_expiry_shard
  CHECK (expiry_shard BETWEEN 0 AND 15);

-- 存量订单回填。表达式必须与 Java 侧 OrderShard.of() 算出同一个值，
-- 否则新订单走一套分片、老订单走另一套，老的那批就没人扫了。
--
-- GREATEST(0, ...) 是防非十六进制订单号（例如测试里手写的 'order-twice'）：
-- POSITION 查不到时返回 0，减 1 得到 -1，会直接撞上上面的 CHECK 让迁移失败。
-- 夹到 0 与 Java 侧「取不到数字就当 0」一致。
-- POSITION(x IN y) 与 SUBSTRING(s FROM n FOR m) 在 H2 2.3 与 MySQL 8.0 上结果一致（实测）。
UPDATE mall_order
   SET expiry_shard = GREATEST(0, POSITION(SUBSTRING(id FROM 1 FOR 1) IN '0123456789abcdef') - 1);

-- 扫描条件是 (status, 分片, 到期时刻) 三元组，索引按这个顺序建：
-- 先把表按状态与分片切成 N 份，再在份内按到期时刻顺序取，而不是先扫时间再过滤分片。
CREATE INDEX idx_order_expire_shard ON mall_order(status, expiry_shard, expires_at);
