-- V17：数据清洗管线。原始数据集 → 清洗条目草稿箱 → 审核 → 流转为文档版本。
-- 三张新表从空开始，不改既有数据；job 表只加一列清洗批次引用，
-- 并把任务类型与目标约束扩展到清洗两类（同时保留 V16 的模板生成任务）。

CREATE TABLE cleaning_batch (
  id                UUID PRIMARY KEY,
  name              VARCHAR(200) NOT NULL,
  target_space_id   UUID REFERENCES space(id),
  created_by        UUID NOT NULL REFERENCES app_user(id),
  status            VARCHAR(24) NOT NULL
    CHECK (status IN ('QUEUED', 'EXTRACTING', 'SCORING', 'READY', 'PARTIAL', 'FAILED')),
  model_scoring     BOOLEAN NOT NULL DEFAULT FALSE,
  file_count        INTEGER NOT NULL DEFAULT 0 CHECK (file_count >= 0),
  skipped_count     INTEGER NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
  failed_file_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_file_count >= 0),
  item_count        INTEGER NOT NULL DEFAULT 0 CHECK (item_count >= 0),
  duplicate_count   INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
  error_code        VARCHAR(64),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at       TIMESTAMPTZ
);
COMMENT ON TABLE cleaning_batch IS '数据清洗批次；预估量级：单环境万级以内，按创建时间倒序列出';
COMMENT ON COLUMN cleaning_batch.id IS '批次主键';
COMMENT ON COLUMN cleaning_batch.name IS '批次名，默认取压缩包名或「文件夹名-时间」';
COMMENT ON COLUMN cleaning_batch.target_space_id IS '预选目标空间，可为空，流转时再指定';
COMMENT ON COLUMN cleaning_batch.created_by IS '上传人；统计与审计按此人归属';
COMMENT ON COLUMN cleaning_batch.status IS '状态：QUEUED 排队、EXTRACTING 提取中、SCORING 打分中、READY 完成、PARTIAL 部分文件失败、FAILED 整体失败';
COMMENT ON COLUMN cleaning_batch.model_scoring IS '本批次是否启用模型打分；为否时总分等于规则分';
COMMENT ON COLUMN cleaning_batch.file_count IS '受理的来源文件数（文件夹文件 + 压缩包内受支持条目）';
COMMENT ON COLUMN cleaning_batch.skipped_count IS '被忽略的条目数（不支持的格式、目录项等），不建来源记录';
COMMENT ON COLUMN cleaning_batch.failed_file_count IS '处理失败的来源文件数；大于零时批次最终为 PARTIAL';
COMMENT ON COLUMN cleaning_batch.item_count IS '清洗产出的条目数（已去重）';
COMMENT ON COLUMN cleaning_batch.duplicate_count IS '批内被去重跳过的条目数';
COMMENT ON COLUMN cleaning_batch.error_code IS '整体失败时的错误码；成功或部分成功为空';
COMMENT ON COLUMN cleaning_batch.created_at IS '创建时间';
COMMENT ON COLUMN cleaning_batch.finished_at IS '清洗与打分结束时间；仍在处理时为空';
CREATE INDEX ix_cleaning_batch_created ON cleaning_batch(created_at DESC, id);
CREATE INDEX ix_cleaning_batch_target ON cleaning_batch(target_space_id, created_at DESC);

CREATE TABLE cleaning_source_file (
  id            UUID PRIMARY KEY,
  batch_id      UUID NOT NULL REFERENCES cleaning_batch(id) ON DELETE CASCADE,
  ordinal       INTEGER NOT NULL CHECK (ordinal >= 0),
  relative_path VARCHAR(512) NOT NULL,
  display_name  VARCHAR(255) NOT NULL,
  media_type    VARCHAR(120) NOT NULL,
  size_bytes    BIGINT NOT NULL CHECK (size_bytes >= 0),
  sha256        CHAR(64) NOT NULL,
  object_key    TEXT NOT NULL,
  state         VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'READY', 'FAILED')),
  record_count  INTEGER NOT NULL DEFAULT 0 CHECK (record_count >= 0),
  error_code    VARCHAR(64),
  UNIQUE (batch_id, ordinal)
);
COMMENT ON TABLE cleaning_source_file IS '清洗批次的来源文件：文件夹里的每个受支持文件、压缩包解出的每个受支持条目';
COMMENT ON COLUMN cleaning_source_file.id IS '来源文件主键';
COMMENT ON COLUMN cleaning_source_file.batch_id IS '所属批次';
COMMENT ON COLUMN cleaning_source_file.ordinal IS '批次内序号，从 0 开始，用于稳定排序';
COMMENT ON COLUMN cleaning_source_file.relative_path IS '原始相对路径（保留文件夹层级），用于溯源与去重';
COMMENT ON COLUMN cleaning_source_file.display_name IS '文件显示名（不含目录）';
COMMENT ON COLUMN cleaning_source_file.media_type IS '按扩展名推断的 MIME 类型';
COMMENT ON COLUMN cleaning_source_file.size_bytes IS '文件体积（字节）';
COMMENT ON COLUMN cleaning_source_file.sha256 IS '内容摘要，用于排查重复上传与档案核对';
COMMENT ON COLUMN cleaning_source_file.object_key IS '对象存储里的只读原件键；清洗失败后仍可重跑';
COMMENT ON COLUMN cleaning_source_file.state IS '状态：PENDING 等待处理、READY 已处理、FAILED 处理失败';
COMMENT ON COLUMN cleaning_source_file.record_count IS '本文件产出的条目数（含后续被去重跳过的记录数）';
COMMENT ON COLUMN cleaning_source_file.error_code IS '失败原因码，例如 ENCODING_UNSUPPORTED、EXTRACT_FAILED';

CREATE TABLE cleaning_item (
  id                           UUID PRIMARY KEY,
  batch_id                     UUID NOT NULL REFERENCES cleaning_batch(id) ON DELETE CASCADE,
  source_file_id               UUID REFERENCES cleaning_source_file(id) ON DELETE SET NULL,
  ordinal                      INTEGER NOT NULL CHECK (ordinal >= 0),
  title                        VARCHAR(240) NOT NULL,
  body                         TEXT NOT NULL,
  body_digest                  CHAR(64) NOT NULL,
  raw_excerpt                  TEXT,
  source_location              VARCHAR(512),
  rule_score                   SMALLINT NOT NULL CHECK (rule_score BETWEEN 0 AND 100),
  model_score                  SMALLINT CHECK (model_score BETWEEN 0 AND 100),
  total_score                  SMALLINT NOT NULL CHECK (total_score BETWEEN 0 AND 100),
  score_detail                 JSONB NOT NULL DEFAULT '{}'::jsonb,
  score_state                  VARCHAR(16) NOT NULL CHECK (score_state IN ('PENDING', 'SCORED', 'UNAVAILABLE')),
  status                       VARCHAR(16) NOT NULL CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'PROMOTED')),
  reviewed_by                  UUID REFERENCES app_user(id),
  reviewed_at                  TIMESTAMPTZ,
  review_reason                VARCHAR(2000),
  promoted_space_id            UUID REFERENCES space(id),
  promoted_document_version_id UUID,
  promoted_by                  UUID REFERENCES app_user(id),
  promoted_at                  TIMESTAMPTZ,
  created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (promoted_space_id, promoted_document_version_id)
    REFERENCES document_version(space_id, id),
  CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(review_reason, ''))) > 0),
  CHECK (status <> 'PROMOTED' OR (promoted_document_version_id IS NOT NULL AND promoted_at IS NOT NULL))
);
COMMENT ON TABLE cleaning_item IS '清洗条目：全局清洗草稿箱的主体，审核通过后可流转为知识库文档版本';
COMMENT ON COLUMN cleaning_item.id IS '条目主键';
COMMENT ON COLUMN cleaning_item.batch_id IS '所属清洗批次';
COMMENT ON COLUMN cleaning_item.source_file_id IS '来源文件；来源被删除时置空，不影响条目本身';
COMMENT ON COLUMN cleaning_item.ordinal IS '批次内条目序号，从 0 开始';
COMMENT ON COLUMN cleaning_item.title IS '条目标题，取首行或首句，最长 240 字';
COMMENT ON COLUMN cleaning_item.body IS '清洗后的正文，20–4000 字';
COMMENT ON COLUMN cleaning_item.body_digest IS '归一化正文摘要；批内唯一，用于去重';
COMMENT ON COLUMN cleaning_item.raw_excerpt IS '清洗前原文摘录（前 2000 字），供审核对照';
COMMENT ON COLUMN cleaning_item.source_location IS '来源位置：相对路径 + 行号或小节';
COMMENT ON COLUMN cleaning_item.rule_score IS '规则分 0–100，五个维度各 20 分';
COMMENT ON COLUMN cleaning_item.model_score IS '模型分 0–100；未启用或调用失败时为空';
COMMENT ON COLUMN cleaning_item.total_score IS '总分：模型分可用时按 0.4 规则 + 0.6 模型加权，否则等于规则分';
COMMENT ON COLUMN cleaning_item.score_detail IS '规则分各维度得分与模型理由（JSON）';
COMMENT ON COLUMN cleaning_item.score_state IS '打分状态：PENDING 待打分、SCORED 已打分、UNAVAILABLE 仅规则分';
COMMENT ON COLUMN cleaning_item.status IS '审核状态：PENDING_REVIEW 待审核、APPROVED 已通过、REJECTED 已驳回、PROMOTED 已流转';
COMMENT ON COLUMN cleaning_item.reviewed_by IS '审核人；未审核为空';
COMMENT ON COLUMN cleaning_item.reviewed_at IS '审核时间；未审核为空';
COMMENT ON COLUMN cleaning_item.review_reason IS '审核意见；驳回时必填';
COMMENT ON COLUMN cleaning_item.promoted_space_id IS '流转到的知识空间；未流转为空';
COMMENT ON COLUMN cleaning_item.promoted_document_version_id IS '流转生成的文档版本；未流转为空';
COMMENT ON COLUMN cleaning_item.promoted_by IS '执行流转的管理员；未流转为空';
COMMENT ON COLUMN cleaning_item.promoted_at IS '流转时间；未流转为空';
COMMENT ON COLUMN cleaning_item.created_at IS '创建时间（清洗完成时间）';
CREATE UNIQUE INDEX uq_cleaning_item_batch_digest ON cleaning_item(batch_id, body_digest);
CREATE INDEX ix_cleaning_item_review ON cleaning_item(status, total_score DESC, created_at DESC);
CREATE INDEX ix_cleaning_item_batch ON cleaning_item(batch_id, ordinal);
CREATE INDEX ix_cleaning_item_promoted ON cleaning_item(promoted_document_version_id);

-- 队列：新增清洗与模型打分两类任务。
ALTER TABLE job ADD COLUMN IF NOT EXISTS cleaning_batch_id UUID REFERENCES cleaning_batch(id);
COMMENT ON COLUMN job.cleaning_batch_id IS '清洗类任务的批次；其他任务类型为空';

-- V1 与 V16 都用默认名重建过 job 的 CHECK；先按定义找到引用 job_type 的约束并删除，
-- 再显式重建包含模板生成与清洗的完整版本，避免依赖某个版本的默认命名。
DO $$
DECLARE
  constraint_name TEXT;
BEGIN
  FOR constraint_name IN
    SELECT conname
    FROM pg_constraint
    WHERE conrelid = 'job'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%job_type%'
  LOOP
    EXECUTE format('ALTER TABLE job DROP CONSTRAINT %I', constraint_name);
  END LOOP;
END $$;

ALTER TABLE job ADD CONSTRAINT job_job_type_check CHECK (
  job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT', 'PUBLISH_SNAPSHOT', 'ANSWER_QUESTION',
               'GENERATE_TEMPLATE', 'CLEAN_DATASET', 'SCORE_CLEANING'));
ALTER TABLE job ADD CONSTRAINT job_check CHECK (
  ((job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT')) AND document_version_id IS NOT NULL)
  OR (job_type = 'PUBLISH_SNAPSHOT' AND publish_request_id IS NOT NULL)
  OR (job_type = 'ANSWER_QUESTION' AND question_id IS NOT NULL)
  OR (job_type = 'GENERATE_TEMPLATE' AND template_generation_id IS NOT NULL)
  OR (job_type IN ('CLEAN_DATASET', 'SCORE_CLEANING') AND cleaning_batch_id IS NOT NULL));
CREATE UNIQUE INDEX IF NOT EXISTS uq_job_cleaning_active ON job(cleaning_batch_id, job_type)
  WHERE job_type IN ('CLEAN_DATASET', 'SCORE_CLEANING') AND status IN ('QUEUED', 'RUNNING');
