CREATE TABLE product (
  id BIGINT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  category VARCHAR(30) NOT NULL,
  illustration VARCHAR(30) NOT NULL,
  price DECIMAL(12,2) NOT NULL CHECK (price >= 0),
  stock INT NOT NULL CHECK (stock >= 0)
);
CREATE TABLE mall_order (
  id VARCHAR(36) PRIMARY KEY,
  username VARCHAR(100) NOT NULL,
  idempotency_key VARCHAR(80) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  total DECIMAL(12,2) NOT NULL CHECK (total >= 0),
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','PAID','CANCELLED')),
  payment_method VARCHAR(30),
  payment_receipt VARCHAR(100),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_order_request UNIQUE (username, idempotency_key)
);
CREATE TABLE order_item (
  order_id VARCHAR(36) NOT NULL REFERENCES mall_order(id),
  product_id BIGINT NOT NULL REFERENCES product(id),
  product_name VARCHAR(100) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (order_id, product_id)
);
CREATE TABLE outbox_event (
  id VARCHAR(36) PRIMARY KEY,
  aggregate_id VARCHAR(36) NOT NULL REFERENCES mall_order(id),
  username VARCHAR(100) NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  content VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED','DEAD')),
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  delivered_at TIMESTAMP
);
CREATE INDEX idx_outbox_poll ON outbox_event(status, next_attempt_at);
CREATE INDEX idx_order_user ON mall_order(username, created_at);
CREATE TABLE notification (
  event_id VARCHAR(36) PRIMARY KEY REFERENCES outbox_event(id),
  username VARCHAR(100) NOT NULL,
  content VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
