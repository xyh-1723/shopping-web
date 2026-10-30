-- V27：优惠券 + 订单/子订单结算字段。
--
-- 设计要点见 docs/优惠券系统设计方案.md。这里只落结构：
--   * coupon / user_coupon 管「券是什么」与「这张券现在归谁、锁在哪笔单」；
--   * mall_order / order_sub 管「这笔钱谁出的、谁收的」。
--
-- 所有约束都显式命名：异常消息里会带约束名，后续迁移与测试也会按名字引用。

CREATE TABLE coupon (
    id                    VARCHAR(36)    NOT NULL,
    code                  VARCHAR(32)    NOT NULL,
    name                  VARCHAR(100)   NOT NULL,
    owner_type            VARCHAR(16)    NOT NULL,
    merchant_id           BIGINT,
    discount_type         VARCHAR(16)    NOT NULL,
    discount_value        DECIMAL(12, 2) NOT NULL,
    max_discount          DECIMAL(12, 2),
    min_spend             DECIMAL(12, 2) NOT NULL DEFAULT 0,
    platform_share_bps    INT            NOT NULL DEFAULT 0,
    total_quantity        INT            NOT NULL,
    issued_count          INT            NOT NULL DEFAULT 0,
    per_user_limit        INT            NOT NULL DEFAULT 1,
    platform_budget_cents BIGINT         NOT NULL DEFAULT 0,
    platform_spent_cents  BIGINT         NOT NULL DEFAULT 0,
    start_at              TIMESTAMP      NOT NULL,
    end_at                TIMESTAMP      NOT NULL,
    status                VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT fk_coupon_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (id),
    CONSTRAINT ck_coupon_owner CHECK (
        (owner_type = 'PLATFORM' AND merchant_id IS NULL)
        OR (owner_type = 'MERCHANT' AND merchant_id IS NOT NULL)
    ),
    CONSTRAINT ck_coupon_type CHECK (discount_type IN ('AMOUNT', 'PERCENT')),
    CONSTRAINT ck_coupon_value CHECK (
        discount_value > 0
        AND min_spend >= 0
        AND (max_discount IS NULL OR max_discount > 0)
        AND (discount_type = 'AMOUNT' OR discount_value <= 100)
    ),
    CONSTRAINT ck_coupon_share CHECK (
        platform_share_bps BETWEEN 0 AND 10000
        AND (owner_type = 'PLATFORM' OR platform_share_bps = 0)
    ),
    CONSTRAINT ck_coupon_quantity CHECK (
        total_quantity >= 0
        AND issued_count >= 0
        AND issued_count <= total_quantity
        AND per_user_limit >= 1
    ),
    CONSTRAINT ck_coupon_budget CHECK (
        platform_budget_cents >= 0
        AND platform_spent_cents >= 0
        AND platform_spent_cents <= platform_budget_cents
    ),
    CONSTRAINT ck_coupon_window CHECK (end_at > start_at),
    CONSTRAINT ck_coupon_status CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED'))
);

CREATE INDEX idx_coupon_active ON coupon (status, start_at, end_at);
CREATE INDEX idx_coupon_owner ON coupon (owner_type, merchant_id);

CREATE TABLE user_coupon (
    id                VARCHAR(64)    NOT NULL,
    coupon_id         VARCHAR(36)    NOT NULL,
    username          VARCHAR(100)   NOT NULL,
    claim_seq         INT            NOT NULL,
    status            VARCHAR(16)    NOT NULL DEFAULT 'UNUSED',
    claimed_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP      NOT NULL,
    locked_order_id   VARCHAR(36),
    locked_at         TIMESTAMP,
    used_order_id     VARCHAR(36),
    used_at           TIMESTAMP,
    coupon_discount   DECIMAL(12, 2),
    merchant_discount DECIMAL(12, 2),
    platform_subsidy  DECIMAL(12, 2),
    PRIMARY KEY (id),
    CONSTRAINT uq_user_coupon_seq UNIQUE (coupon_id, username, claim_seq),
    -- 一笔订单最多挂一张券；NULL 在 MySQL/H2 里都不参与唯一性，所以未锁定的券不受影响。
    CONSTRAINT uq_user_coupon_locked_order UNIQUE (locked_order_id),
    CONSTRAINT fk_user_coupon_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id),
    CONSTRAINT ck_user_coupon_status CHECK (status IN ('UNUSED', 'LOCKED', 'USED', 'EXPIRED')),
    -- 状态与落点字段必须一一对应：LOCKED 一定挂在某笔订单上，USED 一定有核销时刻。
    -- 这条约束是「券不会被两笔订单同时占用」之外的第二个保证——它防的是代码写错状态。
    CONSTRAINT ck_user_coupon_lifecycle CHECK (
        (status = 'UNUSED'
            AND locked_order_id IS NULL AND used_order_id IS NULL AND used_at IS NULL
            AND coupon_discount IS NULL)
        OR (status = 'LOCKED'
            AND locked_order_id IS NOT NULL AND used_order_id IS NULL AND used_at IS NULL
            AND coupon_discount IS NOT NULL)
        OR (status = 'USED'
            AND used_order_id IS NOT NULL AND used_at IS NOT NULL
            AND coupon_discount IS NOT NULL)
        OR (status = 'EXPIRED'
            AND locked_order_id IS NULL AND used_order_id IS NULL AND used_at IS NULL
            AND coupon_discount IS NULL)
    )
);

CREATE INDEX idx_user_coupon_user ON user_coupon (username, status);
CREATE INDEX idx_user_coupon_order ON user_coupon (locked_order_id);
CREATE INDEX idx_user_coupon_used_order ON user_coupon (used_order_id);

-- ——————————————————— 父单：券的总额 ———————————————————

ALTER TABLE mall_order ADD COLUMN coupon_discount DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE mall_order ADD COLUMN coupon_id VARCHAR(36);
ALTER TABLE mall_order ADD COLUMN coupon_code VARCHAR(32);

ALTER TABLE mall_order ADD CONSTRAINT ck_order_coupon_le_discount
    CHECK (coupon_discount >= 0 AND coupon_discount <= discount);
-- 有减免必须有券号；无减免必须没有券号。避免「减了钱但查不到是哪张券」。
ALTER TABLE mall_order ADD CONSTRAINT ck_order_coupon_fields CHECK (
    (coupon_discount = 0 AND coupon_id IS NULL AND coupon_code IS NULL)
    OR (coupon_discount > 0 AND coupon_id IS NOT NULL AND coupon_code IS NOT NULL)
);

-- ——————————————————— 子单：每店的分摊与结算 ———————————————————

ALTER TABLE order_sub ADD COLUMN coupon_discount DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE order_sub ADD COLUMN merchant_discount DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE order_sub ADD COLUMN platform_subsidy DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE order_sub ADD COLUMN platform_fee DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE order_sub ADD COLUMN merchant_income DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE order_sub ADD COLUMN platform_income DECIMAL(12, 2) NOT NULL DEFAULT 0;

-- 存量订单没有券、也没有平台佣金概念：商家拿走全部实付，平台收入为 0。
-- 回填后下面的约束对存量行同样成立。
UPDATE order_sub SET merchant_income = pay_amount WHERE merchant_income = 0;

ALTER TABLE order_sub ADD CONSTRAINT ck_sub_coupon_split
    CHECK (coupon_discount >= 0 AND coupon_discount = merchant_discount + platform_subsidy);
ALTER TABLE order_sub ADD CONSTRAINT ck_sub_coupon_le_discount
    CHECK (coupon_discount <= discount);
ALTER TABLE order_sub ADD CONSTRAINT ck_sub_platform_income
    CHECK (platform_fee >= 0 AND platform_subsidy >= 0 AND platform_income >= 0
           AND platform_income = platform_fee - platform_subsidy);
ALTER TABLE order_sub ADD CONSTRAINT ck_sub_merchant_income
    CHECK (merchant_income >= 0
           AND merchant_income = pay_amount + platform_subsidy - platform_fee);
