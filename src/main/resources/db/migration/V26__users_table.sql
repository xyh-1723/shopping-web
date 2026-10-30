-- =============================================================================
-- V26：用户与权限入库
-- =============================================================================
-- 把账号从内存（InMemoryUserDetailsManager）迁到数据库，目标只有三个：
--   1. 改密码不用重启（直接 UPDATE users.password）
--   2. 热增删账号（INSERT/DELETE users）
--   3. 多实例部署时账号一致（共享同一张表）
--
-- 表结构对齐 Spring Security 内置 JdbcUserDetailsManager 的默认查询，
-- 这样不用自定义 SQL，少一行配置少一处出错。
-- =============================================================================

-- users：一行一个账号。enabled 留着给将来「禁用账号」用，现在全是 true。
CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(100) NOT NULL,
    password VARCHAR(200) NOT NULL,
    enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (username)
);

-- authorities：一个账号可以有多个角色（admin 同时是 USER + STAFF）。
CREATE TABLE IF NOT EXISTS authorities (
    username  VARCHAR(100) NOT NULL,
    authority VARCHAR(50)  NOT NULL,
    PRIMARY KEY (username, authority),
    CONSTRAINT fk_authorities_users FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

-- 商家账号与 merchant.owner_username 一一对应，建个索引方便查「哪些商家还没建账号」。
CREATE INDEX idx_authorities_username ON authorities(username);
