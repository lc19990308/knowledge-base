-- V16：AI 辅助生成回答模板。
-- 一次生成请求一行；requested_by_name / model_alias / reference_template_* / prompt_text 为写后不可变的
-- 快照字段（冗余理由与一致性维护方式见字段注释与 docs/ai-qa-ai-template-generation-plan.md）。
-- 结果只落生成记录，用户确认后走既有模板保存流程，不直接改 answer_template。

CREATE TABLE IF NOT EXISTS answer_template_generation (
  id UUID PRIMARY KEY,
  request_key VARCHAR(255) NOT NULL,
  requested_by UUID NOT NULL REFERENCES app_user(id),
  requested_by_name VARCHAR(200) NOT NULL,
  mode VARCHAR(16) NOT NULL CHECK (mode IN ('STRUCTURED', 'ADVANCED')),
  scenario VARCHAR(240) NOT NULL DEFAULT '',
  requirements TEXT NOT NULL
    CHECK (length(btrim(requirements)) > 0 AND length(requirements) <= 2000),
  reference_template_id UUID REFERENCES answer_template(id),
  reference_template_name VARCHAR(120) NOT NULL DEFAULT '',
  reference_template_revision BIGINT
    CHECK (reference_template_revision IS NULL OR reference_template_revision >= 0),
  model_config_id UUID NOT NULL REFERENCES model_config(id),
  model_alias VARCHAR(120) NOT NULL,
  prompt_text TEXT NOT NULL CHECK (length(prompt_text) <= 20000),
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
    CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  failure_code VARCHAR(64),
  result_body TEXT
    CHECK (result_body IS NULL OR (length(btrim(result_body)) > 0 AND length(result_body) <= 20000)),
  input_tokens BIGINT CHECK (input_tokens IS NULL OR input_tokens >= 0),
  output_tokens BIGINT CHECK (output_tokens IS NULL OR output_tokens >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (requested_by, request_key),
  CHECK ((reference_template_id IS NOT NULL AND reference_template_revision IS NOT NULL)
      OR (reference_template_id IS NULL AND reference_template_name = ''
          AND reference_template_revision IS NULL)),
  CHECK ((status = 'SUCCEEDED' AND result_body IS NOT NULL AND finished_at IS NOT NULL)
      OR (status = 'FAILED' AND failure_code IS NOT NULL AND finished_at IS NOT NULL)
      OR (status IN ('QUEUED', 'RUNNING') AND result_body IS NULL
          AND failure_code IS NULL AND finished_at IS NULL))
);
CREATE INDEX IF NOT EXISTS ix_template_generation_user_recent
  ON answer_template_generation(requested_by, created_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_template_generation_status
  ON answer_template_generation(status, created_at, id);

COMMENT ON TABLE answer_template_generation IS
  'AI 辅助生成回答模板任务，预估年增 <5 万行；快照字段写入后不再更新';
COMMENT ON COLUMN answer_template_generation.request_key IS
  '调用方幂等键，与请求人组成唯一约束，重复提交返回同一条记录';
COMMENT ON COLUMN answer_template_generation.requested_by_name IS
  '冗余：请求人显示名快照，列表/审计消 join；写入后不随用户改名更新';
COMMENT ON COLUMN answer_template_generation.mode IS
  '目标编辑格式 STRUCTURED-结构化 ADVANCED-高级文本';
COMMENT ON COLUMN answer_template_generation.scenario IS '使用场景简述，参与提示词';
COMMENT ON COLUMN answer_template_generation.requirements IS '用户填写的模板要求，10-2000 字，参与提示词';
COMMENT ON COLUMN answer_template_generation.reference_template_id IS
  '可选：基于既有模板改写；为 NULL 时名称与版本快照必须同时为空';
COMMENT ON COLUMN answer_template_generation.reference_template_name IS
  '冗余：被参考模板名称快照；无参考时为空串';
COMMENT ON COLUMN answer_template_generation.reference_template_revision IS
  '冗余：被参考模板版本号快照；无参考时为 NULL';
COMMENT ON COLUMN answer_template_generation.model_config_id IS '本次使用的模型配置';
COMMENT ON COLUMN answer_template_generation.model_alias IS
  '冗余：模型配置别名快照，配置改名/删除后仍可还原当时模型；写入后不更新';
COMMENT ON COLUMN answer_template_generation.prompt_text IS
  '创建时冻结的完整提示词（系统提示 + 用户输入），用于复现与审计';
COMMENT ON COLUMN answer_template_generation.status IS
  'QUEUED-已入队 RUNNING-生成中 SUCCEEDED-成功 FAILED-失败';
COMMENT ON COLUMN answer_template_generation.failure_code IS '失败码；成功时为 NULL';
COMMENT ON COLUMN answer_template_generation.result_body IS '生成的模板正文，仅成功时有值，最长 20000 字符';
COMMENT ON COLUMN answer_template_generation.input_tokens IS
  '模型上报的输入 token；NULL 表示未上报，不记 0';
COMMENT ON COLUMN answer_template_generation.output_tokens IS
  '模型上报的输出 token；NULL 表示未上报，不记 0';

-- 任务队列新增 GENERATE_TEMPLATE 类型：先删再建有枚举的 CHECK，新增指向生成记录的列与活跃任务唯一索引。
ALTER TABLE job ADD COLUMN IF NOT EXISTS template_generation_id UUID
  REFERENCES answer_template_generation(id);
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_job_type_check;
ALTER TABLE job ADD CONSTRAINT job_job_type_check
  CHECK (job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT', 'PUBLISH_SNAPSHOT',
                      'ANSWER_QUESTION', 'GENERATE_TEMPLATE'));
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_check;
ALTER TABLE job ADD CONSTRAINT job_check CHECK (
  ((job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT')) AND document_version_id IS NOT NULL)
  OR (job_type = 'PUBLISH_SNAPSHOT' AND publish_request_id IS NOT NULL)
  OR (job_type = 'ANSWER_QUESTION' AND question_id IS NOT NULL)
  OR (job_type = 'GENERATE_TEMPLATE' AND template_generation_id IS NOT NULL)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_job_generation_active
  ON job(template_generation_id)
  WHERE job_type = 'GENERATE_TEMPLATE' AND status IN ('QUEUED', 'RUNNING');
