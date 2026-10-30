-- 演示券：平台券两张、店铺券一张。
-- 时间窗开到 2037 年底，只是为了让本地演示永远有券可领；
-- 上限刻意不写 2099：MySQL 的 TIMESTAMP 只到 2038-01-19，写 2099 会在真库上
-- 直接报 "Incorrect datetime value"（H2 不报，所以这条只有真库验收才抓得出来）。
-- 真实运营当然要用后台入口按活动时间创建，而不是靠种子数据。

INSERT INTO coupon (id, code, name, owner_type, merchant_id, discount_type, discount_value,
                    max_discount, min_spend, platform_share_bps, total_quantity, per_user_limit,
                    platform_budget_cents, start_at, end_at)
VALUES ('coupon-platform-10', 'PLAT10', '平台新客满 99 减 10', 'PLATFORM', NULL,
        'AMOUNT', 10.00, NULL, 99.00, 6000, 1000, 1, 1000000,
        '2020-01-01 00:00:00', '2037-12-31 23:59:59');

INSERT INTO coupon (id, code, name, owner_type, merchant_id, discount_type, discount_value,
                    max_discount, min_spend, platform_share_bps, total_quantity, per_user_limit,
                    platform_budget_cents, start_at, end_at)
VALUES ('coupon-platform-9off', 'PLAT9OFF', '平台满 199 打 9 折（最高减 30）', 'PLATFORM', NULL,
        'PERCENT', 10.00, 30.00, 199.00, 6000, 500, 1, 2000000,
        '2020-01-01 00:00:00', '2037-12-31 23:59:59');

INSERT INTO coupon (id, code, name, owner_type, merchant_id, discount_type, discount_value,
                    max_discount, min_spend, platform_share_bps, total_quantity, per_user_limit,
                    platform_budget_cents, start_at, end_at)
VALUES ('coupon-merchant-1', 'SHOP1-5', '1 号店满 50 减 5', 'MERCHANT', 1,
        'AMOUNT', 5.00, NULL, 50.00, 0, 500, 2, 0,
        '2020-01-01 00:00:00', '2037-12-31 23:59:59');
