-- ===================================================================
-- PostgreSQL 建表脚本（对应原 SQLite / Prisma 8 契约的 4 张表）
-- 执行：psql -h 127.0.0.1 -U memory -d memory_db -f 01-schema.sql
-- 规范：字段小写下划线、必带 created_at/updated_at、逻辑删除、索引对齐查询
-- ===================================================================

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(255) NOT NULL,
    nick_name   VARCHAR(64)  NOT NULL DEFAULT '',
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 原 SQLite 仅有普通索引，注册查重语义要求唯一，此处补齐 UNIQUE
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_username ON t_user (username) WHERE deleted = 0;
COMMENT ON COLUMN t_user.password IS 'scrypt 哈希，格式 salt:hash（存量）或 {scrypt}salt:hash（新增），禁止明文';

-- 登录会话审计表：会话真源在 Redis（TTL 自动回收），本表仅留痕 + Redis 丢失时回落
-- token 一律存 SHA-256 摘要（见 02-optimize.sql），禁止明文
CREATE TABLE IF NOT EXISTS t_session (
    token       VARCHAR(64)  PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_session_user_id ON t_session (user_id);
-- 定期清理过期流水依赖此索引，避免全表扫描
CREATE INDEX IF NOT EXISTS idx_session_expires_at ON t_session (expires_at);

-- 跨会话长期记忆
CREATE TABLE IF NOT EXISTS t_memory (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    content     VARCHAR(200) NOT NULL,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 对齐 MemoryService 的 where(user_id) orderBy(created_at asc)
CREATE INDEX IF NOT EXISTS idx_memory_user_created ON t_memory (user_id, created_at);

-- 聊天明细：整条会话以 JSON 字符串存 payload（含图片 base64，可达数 MB）
CREATE TABLE IF NOT EXISTS t_conversation (
    id          VARCHAR(64)  PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    title       VARCHAR(80)  NOT NULL DEFAULT '新对话',
    payload     TEXT         NOT NULL DEFAULT '',
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL
);
-- 对齐 where(user_id) orderBy(updated_at desc)
CREATE INDEX IF NOT EXISTS idx_conversation_user_updated ON t_conversation (user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_user_id ON t_conversation (user_id);
