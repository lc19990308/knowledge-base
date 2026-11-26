-- 审核缺口：原型与需求要求、但此前表结构缺失的字段。
ALTER TABLE space ADD COLUMN department VARCHAR(60);

ALTER TABLE document_version ADD COLUMN version_number INT NOT NULL DEFAULT 1
  CHECK (version_number >= 1);

ALTER TABLE app_user ADD COLUMN email VARCHAR(255);

ALTER TABLE global_setting ADD COLUMN context_limit INT NOT NULL DEFAULT 10
  CHECK (context_limit >= 1 AND context_limit <= 100);
ALTER TABLE global_setting ADD COLUMN usage_report_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 同一空间内每次新导入的版本号递增，供界面展示 v1/v2/v3。
WITH ranked AS (
  SELECT id, row_number() OVER (PARTITION BY space_id, normalized_filename ORDER BY created_at, id) AS rn
  FROM document_version
)
UPDATE document_version v SET version_number = ranked.rn FROM ranked WHERE v.id = ranked.id;

CREATE INDEX ix_document_version_name ON document_version(space_id, normalized_filename, version_number DESC);
