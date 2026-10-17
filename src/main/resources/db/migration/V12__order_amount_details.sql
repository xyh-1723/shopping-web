-- 把「金额明细」落到订单上：小计 / 满减 / 运费 / 应付。
--
-- 背景：购物袋页面与下单接口各自算钱，导致用户看到的金额和实际付的金额不是同一个数
-- （满减与运费只进了购物袋，没进订单）。修法是让两条链路走同一个定价器（见 pricing 包），
-- 并把明细落库，使「订单金额 = 购物袋金额」可被查询、可被断言。
--
-- 列语义（全项目统一，见 docs/优化计划.md §B8）：
--   subtotal      = 商品小计 = Σ(单价 × 数量)
--   discount      = 满减优惠（取满足条件的最高一档，不叠加）
--   shipping_fee  = 运费（折后未达包邮门槛时收取）
--   pay_amount    = 应付 = subtotal - discount + shipping_fee
--   total         = 应付（历史列，语义升级；恒等于 pay_amount，仅为兼容保留）

ALTER TABLE mall_order ADD COLUMN subtotal DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE mall_order ADD COLUMN discount DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE mall_order ADD COLUMN shipping_fee DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE mall_order ADD COLUMN pay_amount DECIMAL(12,2) NOT NULL DEFAULT 0;

-- 存量订单当时没有满减/运费概念，total 就是商品小计 → subtotal = total、pay_amount = total。
-- 回填后 total 恒等于 pay_amount 这条不变量对存量行同样成立。
-- 条件用 pay_amount = 0 而不是无条件：V12 会被重复执行（如测试库重建），
-- 无条件 UPDATE 会把已经落过明细的订单抹平成「无优惠」。
UPDATE mall_order SET subtotal = total, discount = 0, shipping_fee = 0, pay_amount = total
 WHERE pay_amount = 0;

-- 显式命名约束：H2 给内联 CHECK 起的是 CONSTRAINT_x 这种自动名，
-- 写进后续迁移脚本跨环境会失配（V10 的教训）。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_amount_nonneg
  CHECK (subtotal >= 0 AND discount >= 0 AND shipping_fee >= 0 AND pay_amount >= 0);
