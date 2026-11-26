-- 向量召回：文本块的语义向量，可空（未启用嵌入或嵌入失败时保持 NULL，检索回退词法）。
-- 向量以 real[] 存放并在这里计算余弦相似度；语料规模较小时够用，量大再迁 pgvector。
ALTER TABLE document_chunk ADD COLUMN embedding real[];
COMMENT ON COLUMN document_chunk.embedding IS
  '由配置的嵌入模型生成；为空表示该块只参与词法检索';
