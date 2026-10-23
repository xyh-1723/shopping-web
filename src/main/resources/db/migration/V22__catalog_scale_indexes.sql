-- 商品目录扩容前的索引与约束准备。
--
-- 为什么是新迁移而不是回头改 V1：V1 已经在存量库上应用过了，改它会让所有存量库的
-- Flyway 校验和失配（Flyway 不允许修改已应用的迁移）。新增永远是安全的。
--
-- 背景：product 表除主键外<b>零索引</b>。506 件时这件事无所谓——实测全表扫一遍
-- catalog.list() 是 0.346ms 量级，加索引也省不出什么。但商品量要扩到 5 万 / 50 万，
-- 到那时「按分类挑」「按价格排」「筛低库存」三条路径会全部退化成全表扫描 + 排序。
-- 所以这几个索引是扩容的<b>前置条件</b>，不是性能优化的锦上添花。

-- (category, price)：服务「按分类浏览并按价格排序」，以及关联推荐的
-- `WHERE category IN (...) ORDER BY price`。分类放在前面，因为过滤先发生、排序后发生。
CREATE INDEX idx_product_category_price ON product(category, price);

-- (stock)：低库存告警要 `WHERE stock < ? ORDER BY stock`，没有它每轮都全表扫一遍商品表。
CREATE INDEX idx_product_stock ON product(stock);

-- (price)：按预算推荐是<b>跨分类</b>的 `WHERE price <= ? ORDER BY price`，
-- 上面那个 (category, price) 帮不上——复合索引只有最左列能被单独当索引用。
CREATE INDEX idx_product_price ON product(price);

-- 价格必须严格为正。
--
-- V1 只有 `price >= 0`：HTTP 改价接口有 @DecimalMin("0.01") 挡着，但<b>直接写库</b>没有——
-- 批量导入、数据修复脚本、测试夹具都绕过了那一层校验。接下来要批量生成几万件商品，
-- 一条 0 元商品混进去不会报错，只会静默地出现在货架上，被当成真商品卖。
-- 这条约束把「价格为正」从「接口约定」升级为「数据不变量」。
ALTER TABLE product ADD CONSTRAINT ck_product_price_positive CHECK (price > 0);
