-- 多轮追问的检索文本：把「那住宿呢？」这类依赖上文的提问改写成独立问题后再检索，
-- 原问题仍保存在 question.text 用于展示与审计，这里只放检索用文本。
ALTER TABLE question ADD COLUMN retrieval_text TEXT;

COMMENT ON COLUMN question.retrieval_text IS
  '多轮追问改写后的检索文本；为空表示直接用原提问检索，原提问仍存于 text 列。';
