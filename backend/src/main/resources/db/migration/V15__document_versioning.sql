-- 同一份文档支持多版本更新：新增逻辑文档标识，解除「同空间同文件名只能有一行」的限制。
ALTER TABLE document_version ADD COLUMN document_id uuid;

COMMENT ON COLUMN document_version.document_id IS '逻辑文档标识：同一份文档的多个版本共享此值，等于首版的 id';

UPDATE document_version SET document_id = id WHERE document_id IS NULL;

ALTER TABLE document_version ALTER COLUMN document_id SET NOT NULL;

ALTER TABLE document_version DROP CONSTRAINT document_version_space_id_normalized_filename_key;

ALTER TABLE document_version
  ADD CONSTRAINT document_version_document_id_version_number_key
  UNIQUE (document_id, version_number);

CREATE INDEX ix_document_version_document
  ON document_version (document_id, version_number DESC);
