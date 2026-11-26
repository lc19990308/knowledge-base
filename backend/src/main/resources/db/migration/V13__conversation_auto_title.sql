ALTER TABLE conversation
  ADD COLUMN title_auto BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN conversation.title_auto IS
  '标题由首个问题与首轮回答自动提炼；为 true 时允许后台按对话内容更新，用户显式命名后置为 false。';

-- 旧链路先建空会话、标题为空，这些会话在首次提问后也应得到自动标题。
UPDATE conversation SET title_auto = true WHERE title IS NULL;
