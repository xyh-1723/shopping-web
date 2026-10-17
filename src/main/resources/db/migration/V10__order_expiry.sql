-- 订单超时自动关闭的模型基础。
--
-- 要解决的不是「少一个定时任务」，而是：下单即预占库存，用户不付款就走，
-- 库存就一直扣着。在只有几件商品的库里，一个弃单就能让某件商品对所有人显示「卖完了」。
-- 释放库存的动作早就有了（取消订单），缺的是一个<b>时间驱动的触发源</b>，
-- 所以这里只补「什么时候该关」的事实，「怎么关」仍复用同一份关单实现。

ALTER TABLE mall_order ADD COLUMN expires_at TIMESTAMP;
ALTER TABLE mall_order ADD COLUMN close_reason VARCHAR(20);
ALTER TABLE mall_order ADD COLUMN closed_at TIMESTAMP;

-- 关闭原因必须显式命名约束。V1 里 status 的内联 CHECK 被 H2 起成了 CONSTRAINT_51 这种
-- 自动名，写进脚本换个环境就会失配；自己起名才跨环境可靠。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_close_reason
  CHECK (close_reason IS NULL OR close_reason IN ('USER', 'TIMEOUT'));

-- 存量 PENDING 订单必须回填，否则它们永远进不了扫描窗口——迁移里最容易漏的一步。
-- 用 created_at + 默认 15 分钟回推：这些订单的下单时刻是事实，按当时承诺的时限补上。
UPDATE mall_order SET expires_at = DATEADD('MINUTE', 15, created_at)
 WHERE status = 'PENDING' AND expires_at IS NULL;

-- 扫描按「状态 + 截止时间」过滤。没有这个索引，定时任务就是每分钟一次全表扫。
CREATE INDEX idx_order_expire ON mall_order(status, expires_at);
