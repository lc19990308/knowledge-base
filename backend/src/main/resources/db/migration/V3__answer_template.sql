-- 回答模板从内联正文升级为独立实体：助手与全局配置改为引用同一条模板记录。
CREATE TABLE answer_template (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  body TEXT NOT NULL CHECK (length(btrim(body)) > 0),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (length(btrim(name)) > 0)
);
CREATE INDEX ix_answer_template_name ON answer_template(name, id);

ALTER TABLE assistant ADD COLUMN answer_template_id UUID REFERENCES answer_template(id);
ALTER TABLE global_setting ADD COLUMN template_id UUID REFERENCES answer_template(id);

-- 迁移既有正文：一行旧文本对应一条模板记录，再回填引用。
DO $$
DECLARE
  row_rec RECORD;
  new_id UUID;
BEGIN
  FOR row_rec IN
    SELECT id, name, answer_template FROM assistant
    WHERE answer_template IS NOT NULL AND btrim(answer_template) <> ''
  LOOP
    new_id := gen_random_uuid();
    INSERT INTO answer_template (id, name, body)
    VALUES (new_id, row_rec.name || ' 模板', row_rec.answer_template);
    UPDATE assistant SET answer_template_id = new_id WHERE id = row_rec.id;
  END LOOP;

  FOR row_rec IN
    SELECT singleton_id, template FROM global_setting
    WHERE template IS NOT NULL AND btrim(template) <> ''
  LOOP
    new_id := gen_random_uuid();
    INSERT INTO answer_template (id, name, body)
    VALUES (new_id, '全局默认模板', row_rec.template);
    UPDATE global_setting SET template_id = new_id WHERE singleton_id = row_rec.singleton_id;
  END LOOP;
END $$;

ALTER TABLE assistant DROP COLUMN answer_template;
ALTER TABLE global_setting DROP COLUMN template;
