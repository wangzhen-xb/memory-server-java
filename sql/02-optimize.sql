-- ===================================================================
-- 增量优化脚本（在 01-schema.sql 之后执行，可重复执行）
-- 执行：psql -h 127.0.0.1 -U memory -d memory_db -f 02-optimize.sql
-- 内容：会话删除墓碑表、去除冗余索引、token 摘要说明
-- ===================================================================

-- 1) 会话删除墓碑：逻辑删除对外不可见，需要独立记录删除事实供多端增量同步
CREATE TABLE IF NOT EXISTS t_conversation_tombstone (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES t_user (id) ON DELETE CASCADE,
    conv_id     VARCHAR(64)  NOT NULL,
    deleted_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 同一用户同一会话只保留一条墓碑，重复删除靠 UPSERT 收敛
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_tombstone_user_conv
    ON t_conversation_tombstone (user_id, conv_id);
-- 增量拉取条件为 user_id + deleted_at > since，按此顺序建索引可避免全表扫描
CREATE INDEX IF NOT EXISTS idx_conversation_tombstone_user_deleted
    ON t_conversation_tombstone (user_id, deleted_at);

COMMENT ON TABLE  t_conversation_tombstone IS '会话删除墓碑：记录删除时间，供客户端 since 增量同步删除动作';
COMMENT ON COLUMN t_conversation_tombstone.conv_id IS '客户端生成的会话 ID，与 t_conversation.id 对应（不设外键，允许删除后重建）';

-- 2) 去除冗余索引：idx_conversation_user_updated 已覆盖 (user_id, updated_at DESC)，
--    idx_conversation_user_id 作为其前缀重复，只会增加写入成本
DROP INDEX IF EXISTS idx_conversation_user_id;

-- 3) token 摘要：t_session.token 现存储 SHA-256 摘要，明文只在响应客户端时出现一次
COMMENT ON COLUMN t_session.token IS 'SHA-256 摘要（64 位小写 hex），禁止存储明文 token';

-- 迁移说明：
-- 存量明文 token 行无需手工处理：SessionService 在 hash 未命中时会回退按明文查一次，
-- 命中后原地改写为摘要并回填 Redis，存量会话随 30 天 TTL 自然完成升级，无需停机。
