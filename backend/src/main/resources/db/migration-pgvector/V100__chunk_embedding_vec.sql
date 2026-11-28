-- pgvector 近邻检索：real[] 无法建 ANN 索引，这里补一列 vector(1024) 与 HNSW 索引。
-- 只在启用 pgvector profile 时加载；扩展未安装的环境仍走应用内余弦，不执行本迁移。
-- 维度必须与嵌入模型一致（当前 qwen3-embed 为 1024 维）；换模型维度需先清空重建。
-- 扩展装在 public（库级一次），隔离测试 schema 通过 search_path 复用同一份类型定义；
-- 装在业务 schema 里会被 DROP SCHEMA CASCADE 连带删除，留下悬空的 pg_extension 记录。
CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

ALTER TABLE document_chunk ADD COLUMN embedding_vec vector(1024);

COMMENT ON COLUMN document_chunk.embedding_vec IS
  '由配置的嵌入模型生成（1024 维），供 HNSW 近邻检索；real[] 列保留一个版本周期供回退。';

-- 历史向量一次性回填，避免启用 ANN 后必须全量重索引才能用上索引。
UPDATE document_chunk SET embedding_vec = embedding::vector(1024) WHERE embedding IS NOT NULL;

CREATE INDEX ix_document_chunk_embedding_vec
  ON document_chunk USING hnsw (embedding_vec vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
