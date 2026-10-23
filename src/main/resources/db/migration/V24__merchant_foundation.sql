-- 阶段 3a：商家地基——把「商家」从「一个全局审批权限」变成「一条数据归属维度」。
--
-- 【为什么必须先做这一步】
-- 扩容前的事实：全系统只有一个 merchant 账号（SecurityConfig），没有商家表，
-- product / mall_order / order_item / refund 上都没有商家列。于是「商家」=「能看全部退款、
-- 能批全部退款」的一顶帽子——在这种地基上造 N 个商家账号，得到的是 N 个都能批别人单子的
-- 账号：那是 N 倍的越权，不是扩容。所以先落归属，再谈数量。
--
-- 【三张表的归属链路】
--   merchant(owner_username) ← 登录账号的唯一绑定点（一个账号只开一家店）
--   product.merchant_id      ← 商品归谁（当下 5000 件 / 200 家 = 每家 25 件）
--   order_item.merchant_id   ← 下单那一刻从商品带下来的历史归属，
--                               商品以后改挂别的店，历史订单不该跟着漂移
-- 退款的隔离就落在 order_item.merchant_id 上：refund → 订单 → 明细 → 商家。
--
-- 【为什么商家数是 200】
-- 与商品量同一个分档思路：常驻档 200 家 × 25 件 = 5000 件（正好是阶段 2 落地的规模）。
-- 压测档由 tools/SeedMerchants 按比例放大（2000 家 × 25 件 = 5 万件），
-- 不进迁移脚本——迁移脚本只承载「每次重建都必须在」的那一档。
--
-- 【方言实测记录（tools/MerchantDdlProbe，H2 2.3.232 与 MySQL 8.0.31 各跑一遍）】
--   ✔ CREATE TABLE ... CHECK (status IN (...))                两边都接受
--   ✔ INSERT ... WITH RECURSIVE ... SELECT（数位笛卡尔积）      两边都接受
--   ✘ CAST(x AS VARCHAR(n))                                   MySQL 报语法错，只认 CHAR
--     → 因此全程用 CONCAT 做转字符串，一次 CAST 都不写
--   ✔ ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT 1        两边都接受
--   ✘ ALTER TABLE ... ALTER COLUMN ... SET NOT NULL            MySQL 不认（要 MODIFY COLUMN）
--   ✔ ALTER TABLE ... ALTER COLUMN ... DROP DEFAULT            两边都接受
--     → 于是「给大表加非空列」的可移植写法是：
--       ADD ... NOT NULL DEFAULT 1 → 回填 → DROP DEFAULT。
--       DROP DEFAULT 这一步不能省：留着它意味着以后忘写 merchant_id 也能插进去，
--       而那个默认值会静默把商品判给 1 号商家——正是本项目最反对的「静默半成功」。
--   ✔ UPDATE t a SET a.c = (SELECT ...)                       两边都接受（关联子查询回填）
--   ✔ ADD CONSTRAINT ... FOREIGN KEY                          两边都接受

CREATE TABLE merchant (
  id BIGINT PRIMARY KEY,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(100) NOT NULL,
  owner_username VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_merchant_code UNIQUE (code),
  CONSTRAINT uq_merchant_owner UNIQUE (owner_username)
);

-- 200 家店。名字 = 地区词(k mod 10) + 业态词(k mod 7) + 尾缀(k mod 3)：
-- 10 / 7 / 3 两两互质，最小公倍数 210 > 200，所以 k 与三元组一一对应——200 个店名互不重复
-- （与 V23 给商品起名时用的是同一个把戏，目的也一样：不去重、不查重，靠算术保证唯一）。
--
-- owner_username 是「账号 → 店」的唯一绑定点。k=0 那家刻意沿用既有的 merchant 账号：
-- 它已经在 SecurityConfig 与一批测试里作为「那个商家」存在，保留它等于不动既有契约；
-- 其余 199 家按 m0002..m0200 编下去。
INSERT INTO merchant (id, code, name, owner_username)
WITH RECURSIVE digit(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM digit WHERE n < 9
),
seq(k) AS (
    SELECT d1.n + d2.n * 10 + d3.n * 100
      FROM digit d1, digit d2, digit d3
     WHERE d3.n < 2
),
limited(k) AS (
    SELECT k FROM seq WHERE k < 200
)
SELECT
    1 + k,
    CONCAT('M', LPAD(CONCAT('', 1 + k), 4, '0')),
    CONCAT(
        CASE MOD(k, 10)
            WHEN 0 THEN '南山' WHEN 1 THEN '北屿' WHEN 2 THEN '晨雾' WHEN 3 THEN '海盐'
            WHEN 4 THEN '藤紫' WHEN 5 THEN '墨蓝' WHEN 6 THEN '晴山' WHEN 7 THEN '晚枫'
            WHEN 8 THEN '雪松' ELSE '青柠'
        END,
        CASE MOD(k, 7)
            WHEN 0 THEN '生活馆' WHEN 1 THEN '文具社' WHEN 2 THEN '数码铺' WHEN 3 THEN '家居坊'
            WHEN 4 THEN '手作屋' WHEN 5 THEN '选物集' ELSE '杂货店'
        END,
        CASE MOD(k, 3)
            WHEN 0 THEN '旗舰店' WHEN 1 THEN '专营店' ELSE '工作室'
        END),
    CASE WHEN k = 0 THEN 'merchant'
         ELSE CONCAT('m', LPAD(CONCAT('', 1 + k), 4, '0')) END
FROM limited;

-- ——————————————————— 商品：归属 ———————————————————

ALTER TABLE product ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1;

-- 分店只用 MOD、不用除法：MySQL 的 / 是小数除法，H2 在 MySQL 模式下又是另一套隐式转换，
-- 而 MOD 在两边都是整数运算。乘 37（与 200 互质）而不是直接 MOD(id, 200)：
-- 后者会让 1 号店独吞「每 200 件的第一件」，且同一家店的 id 连成一段——
-- 于是前 8 家店只卖生活类、后 8 家只卖数码类，那不叫分店，叫按品类切块。
-- 乘同余之后连续的 id 被打散到全部 200 家，每家恰好 25 件，且三家品类都有。
UPDATE product SET merchant_id = 1 + MOD((id - 1) * 37, 200);

-- 去掉上面那个 DEFAULT：加列时用它兜住 5000 行存量，用完即撤。
ALTER TABLE product ALTER COLUMN merchant_id DROP DEFAULT;
ALTER TABLE product ADD CONSTRAINT fk_product_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(id);
CREATE INDEX idx_product_merchant ON product(merchant_id);

-- ——————————————————— 订单明细：下单那一刻的历史归属 ———————————————————

ALTER TABLE order_item ADD COLUMN merchant_id BIGINT NOT NULL DEFAULT 1;

-- 存量明细按今天的商品归属回填。这一步是「历史数据怎么办」的显式选择：
-- 下单时并没有记录商家，只能拿当下的归属补上，属于最合理的近似，
-- 但它是近似——所以上面的注释里写的是「新单在下单那一刻落库」，而不是「明细永远跟着商品走」。
UPDATE order_item oi SET oi.merchant_id =
    (SELECT p.merchant_id FROM product p WHERE p.id = oi.product_id);

ALTER TABLE order_item ALTER COLUMN merchant_id DROP DEFAULT;
ALTER TABLE order_item ADD CONSTRAINT fk_item_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(id);
-- 退款队列的隔离查询走 order_item.merchant_id，索引必须建在它身上：
-- 建在 product 上帮不上「这笔退款涉及哪些店」这个方向的查询。
CREATE INDEX idx_order_item_merchant ON order_item(merchant_id);

ANALYZE TABLE merchant;
ANALYZE TABLE product;
ANALYZE TABLE order_item;
