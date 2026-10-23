-- 阶段 2：目录从 506 件扩到 5,000 件（新增 4,494 件，id 507..5000）。
--
-- 【为什么用生成式 SQL 而不是硬编码 INSERT】
-- V20 的 500 件是 82KB 的纯 INSERT；照同样写法造 4,494 件会得到一个 700KB 的迁移文件，
-- 它既不可读也不可改（改一个商品名要在 700KB 里找）。这里改成「一条 INSERT ... SELECT
-- + 递归 CTE 造序号 + 词表拼名字」，文件小、可复现、改词表只动一处。
--
-- 【为什么不用「递归到 4494」而是「数位笛卡尔积」】
-- MySQL 的 cte_max_recursion_depth 默认只有 1000：递归 4494 层会直接报错
-- "Recursive query aborted after 1001 iterations"。这里只递归到 10（生成 0..9 四个数位），
-- 再用笛卡尔积拼出 0..1997，递归深度恒定，与要造多少件无关。
--
-- 【名字为什么不重复】
-- 每个分类内取序号 k ∈ [0,1498)，名字 = 修饰词(k mod 20) + 品类词(k mod 7) + 规格(k mod 11)。
-- 20/7/11 两两互质，最小公倍数 1540 > 1498，所以 k 与三元组一一对应——
-- 同一个分类内不会出现重名（跨分类重名是允许的，真实商城也这样）。
--
-- 【为什么必须两种数据库都跑得通】
-- 主库是 MySQL 8，测试跑 H2(MODE=MySQL)。下面的写法是实测过的交集：
--   * WITH 必须放在 INSERT 之后（写成 "WITH ... INSERT" 两边都会报语法错）；
--   * 用 MOD 而不是 %；
--   * 全程整数运算，不引入 FLOOR/CAST 造成的 DECIMAL 与 INT 隐式转换差异。
INSERT INTO product (id, name, description, category, illustration, price, stock)
WITH RECURSIVE digit(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM digit WHERE n < 9
),
seq(k) AS (
    SELECT d1.n + d2.n * 10 + d3.n * 100 + d4.n * 1000 AS k
      FROM digit d1, digit d2, digit d3, digit d4
     WHERE d4.n < 2
),
limited(k) AS (
    SELECT k FROM seq WHERE k < 1498
),
cats(c, label) AS (
    SELECT 0, '生活'
    UNION ALL SELECT 1, '文具'
    UNION ALL SELECT 2, '数码'
)
SELECT
    507 + c * 1498 + k,
    -- 名称：修饰词 × 品类词 × 规格
    CONCAT(
        CASE MOD(k, 20)
            WHEN  0 THEN '晨雾' WHEN  1 THEN '海盐' WHEN  2 THEN '藤紫' WHEN  3 THEN '墨蓝'
            WHEN  4 THEN '曜石' WHEN  5 THEN '晴山' WHEN  6 THEN '晚枫' WHEN  7 THEN '雪松'
            WHEN  8 THEN '青柠' WHEN  9 THEN '暖砂' WHEN 10 THEN '远帆' WHEN 11 THEN '疏影'
            WHEN 12 THEN '初霁' WHEN 13 THEN '南山' WHEN 14 THEN '北屿' WHEN 15 THEN '微光'
            WHEN 16 THEN '静水' WHEN 17 THEN '长夏' WHEN 18 THEN '秋分' ELSE '白露'
        END,
        CASE c * 7 + MOD(k, 7)
            WHEN  0 THEN '陶瓷马克杯' WHEN  1 THEN '玻璃随行杯' WHEN  2 THEN '保温咖啡杯'
            WHEN  3 THEN '双层茶水杯' WHEN  4 THEN '便携冷水杯' WHEN  5 THEN '手冲分享壶'
            WHEN  6 THEN '棉麻收纳袋'
            WHEN  7 THEN '点阵笔记本' WHEN  8 THEN '横线随记本' WHEN  9 THEN '活页计划本'
            WHEN 10 THEN '方格草稿本' WHEN 11 THEN '软抄记事本' WHEN 12 THEN '线圈速写本'
            WHEN 13 THEN '皮质手账本'
            WHEN 14 THEN '夹式阅读灯' WHEN 15 THEN '护眼台灯'   WHEN 16 THEN '机械键盘'
            WHEN 17 THEN '静音薄膜键盘' WHEN 18 THEN '桌面计时器' WHEN 19 THEN '无线充电板'
            ELSE '折叠手机支架'
        END,
        CASE MOD(k, 11)
            WHEN  0 THEN '标准款' WHEN  1 THEN '轻量版' WHEN  2 THEN '旅行装'
            WHEN  3 THEN '桌面版' WHEN  4 THEN '随身款' WHEN  5 THEN '礼盒装'
            WHEN  6 THEN '基础款' WHEN  7 THEN '加大版' WHEN  8 THEN '联名款'
            WHEN  9 THEN '限定款' ELSE '经典款'
        END),
    -- 描述：修饰词 + 品类词 + 规格 + 一句按品类的卖点
    CONCAT(
        CASE MOD(k, 20)
            WHEN  0 THEN '晨雾' WHEN  1 THEN '海盐' WHEN  2 THEN '藤紫' WHEN  3 THEN '墨蓝'
            WHEN  4 THEN '曜石' WHEN  5 THEN '晴山' WHEN  6 THEN '晚枫' WHEN  7 THEN '雪松'
            WHEN  8 THEN '青柠' WHEN  9 THEN '暖砂' WHEN 10 THEN '远帆' WHEN 11 THEN '疏影'
            WHEN 12 THEN '初霁' WHEN 13 THEN '南山' WHEN 14 THEN '北屿' WHEN 15 THEN '微光'
            WHEN 16 THEN '静水' WHEN 17 THEN '长夏' WHEN 18 THEN '秋分' ELSE '白露'
        END,
        '系列，',
        CASE c * 7 + MOD(k, 7)
            WHEN  0 THEN '陶瓷马克杯' WHEN  1 THEN '玻璃随行杯' WHEN  2 THEN '保温咖啡杯'
            WHEN  3 THEN '双层茶水杯' WHEN  4 THEN '便携冷水杯' WHEN  5 THEN '手冲分享壶'
            WHEN  6 THEN '棉麻收纳袋'
            WHEN  7 THEN '点阵笔记本' WHEN  8 THEN '横线随记本' WHEN  9 THEN '活页计划本'
            WHEN 10 THEN '方格草稿本' WHEN 11 THEN '软抄记事本' WHEN 12 THEN '线圈速写本'
            WHEN 13 THEN '皮质手账本'
            WHEN 14 THEN '夹式阅读灯' WHEN 15 THEN '护眼台灯'   WHEN 16 THEN '机械键盘'
            WHEN 17 THEN '静音薄膜键盘' WHEN 18 THEN '桌面计时器' WHEN 19 THEN '无线充电板'
            ELSE '折叠手机支架'
        END,
        '，',
        CASE MOD(k, 11)
            WHEN  0 THEN '标准款' WHEN  1 THEN '轻量版' WHEN  2 THEN '旅行装'
            WHEN  3 THEN '桌面版' WHEN  4 THEN '随身款' WHEN  5 THEN '礼盒装'
            WHEN  6 THEN '基础款' WHEN  7 THEN '加大版' WHEN  8 THEN '联名款'
            WHEN  9 THEN '限定款' ELSE '经典款'
        END,
        '。',
        CASE c
            WHEN 0 THEN '低饱和釉色配顺手握感，冷热饮都能从容盛放。'
            WHEN 1 THEN '纸张厚实不透墨，书写顺滑，日常记录与规划都合适。'
            ELSE '做工扎实、手感稳定，放在桌面上既好看又好用。'
        END),
    label,
    -- 插图：每个分类两款，与前端 /art 下的六张图一一对应（不能凭空造新的图形名）
    CASE c
        WHEN 0 THEN CASE WHEN MOD(k, 2) = 0 THEN 'cup'      ELSE 'bag'      END
        WHEN 1 THEN CASE WHEN MOD(k, 2) = 0 THEN 'notebook' ELSE 'timer'    END
        ELSE        CASE WHEN MOD(k, 2) = 0 THEN 'lamp'     ELSE 'keyboard' END
    END,
    -- 价格 10..199 元（ck_product_price_positive 要求严格为正）
    CAST(10 + MOD(k * 7, 190) AS DECIMAL(10, 2)),
    -- 库存 0..119：刻意保留少量 0 库存（k=0 时必为 0），
    -- 否则前端的「暂时售罄」与店员的库存告警在扩容后就成了永远触发不到的死代码。
    MOD(k * 13, 120)
FROM limited, cats;

-- 顺带把统计信息刷新一遍：MySQL 的优化器依赖它选索引，
-- 行数从 506 变成 5000 之后不刷，执行计划可能还停留在旧分布上。
ANALYZE TABLE product;
