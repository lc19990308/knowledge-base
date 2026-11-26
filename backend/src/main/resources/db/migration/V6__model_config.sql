-- V6：全局模型配置支持多条（2026-11-22 用户需求）。
-- 模型配置独立成实体；global_setting 只保留「默认模型」指针，旧的单条 alias/credential_ref 迁移为一条记录。
CREATE TABLE model_config (
  id UUID PRIMARY KEY,
  alias VARCHAR(120) NOT NULL UNIQUE,
  credential_ref VARCHAR(255) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE global_setting ADD COLUMN default_model_id UUID REFERENCES model_config(id);

-- 把既有单条配置迁为一条模型记录并回填默认指针（global_setting 无行时全部跳过）。
INSERT INTO model_config (id, alias, credential_ref)
SELECT gen_random_uuid(),
       COALESCE(NULLIF(btrim(g.model_alias), ''), '默认模型'),
       COALESCE(NULLIF(btrim(g.credential_ref), ''), '')
FROM global_setting g
WHERE g.singleton_id = 1;

UPDATE global_setting
SET default_model_id = (
  SELECT m.id FROM model_config m
  WHERE m.alias = COALESCE(NULLIF(btrim(global_setting.model_alias), ''), '默认模型')
)
WHERE singleton_id = 1;

ALTER TABLE global_setting DROP COLUMN model_alias;
ALTER TABLE global_setting DROP COLUMN credential_ref;
