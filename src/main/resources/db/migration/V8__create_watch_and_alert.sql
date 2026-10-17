-- 主动服务：关注（降价 / 到货提醒）+ 站内提醒。
--
-- 为什么不做成第二个「发件箱」：
-- 现有 outbox_event 的 aggregate_id 外键指向 mall_order(id)，因为它的语义是
-- 「某一笔订单上发生的领域事件要可靠地送出去」。而关注提醒的聚合根是「某个用户关注的
-- 某个商品」，且由定时扫描批量产生——没有订单。为了复用而把外键改成可空，
-- 会让发件箱的模型变得含糊：一张表里混着两种聚合、两种投递理由，之后的每一次改动
-- 都要先问「这行到底是哪一类」。所以主动提醒有自己的表，但沿用同样的两个要点：
--   * 幂等（同一件事不重复提醒）——靠 mall_product_watch 上的基线价格/库存状态；
--   * 可追溯（提醒发过什么、用户读没读）——靠 mall_alert。
--
-- 另一点：提醒不是「越准越好」，而是「越不烦人越好」。关注行的 baseline_price 在每次
-- 提醒后会被重置为当时的现价，所以「一次降价 = 一条提醒」，而不是每次扫描都提醒一次。

CREATE TABLE mall_product_watch (
  username VARCHAR(100) NOT NULL,
  product_id BIGINT NOT NULL REFERENCES product(id) ON DELETE CASCADE,
  -- 关注时的价格。当前价低于它才算降价；提醒后会被重置为当时现价（重新武装）。
  baseline_price DECIMAL(10,2) NOT NULL,
  -- 关注那一刻是否有货。当时没货、现在有货 = 到货提醒。
  in_stock_at_watch BOOLEAN NOT NULL DEFAULT TRUE,
  last_notified_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (username, product_id)
);
CREATE INDEX idx_watch_product ON mall_product_watch(product_id);

CREATE TABLE mall_alert (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  product_id BIGINT REFERENCES product(id) ON DELETE CASCADE,
  alert_type VARCHAR(20) NOT NULL CHECK (alert_type IN ('PRICE_DROP','BACK_IN_STOCK','RELATED')),
  title VARCHAR(120) NOT NULL,
  content VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at TIMESTAMP
);
CREATE INDEX idx_alert_user ON mall_alert(username, read_at, created_at);
