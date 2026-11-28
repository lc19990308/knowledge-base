# 变更记录：向量召回改用 pgvector 近邻检索（S2.1）

- 日期：2026-11-27
- 方案：[ai-qa-answer-quality-plan.md](./ai-qa-answer-quality-plan.md) 的 S2.1
- 决策依据：2026-11-27 确认「允许安装 pgvector」

## 背景

原实现 `ORDER BY c.id LIMIT 2000` 取向量候选：按 UUID 顺序截断，不是相关度。文本块超过 2000 个后，向量召回退化为随机抽样，且没有日志或指标。按 4000 字/块估算，几十份制度文档就会越过；改成 800 字/块后更容易触顶。

## 实现过程

| 文件                                                                             | 改动                                                                                                                                                |
| -------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `backend/src/main/resources/db/migration-pgvector/V100__chunk_embedding_vec.sql` | 新增独立迁移目录：`CREATE EXTENSION vector`、`document_chunk.embedding_vec vector(1024)`、历史 `real[]` 回填、HNSW 索引（m=16, ef_construction=64） |
| `backend/src/main/resources/application.yml`                                     | 新增 `pgvector` profile，仅在启用时把 `db/migration-pgvector` 追加进 Flyway locations                                                               |
| `backend/src/main/java/dev/aiqa/search/VectorIndex.java`                         | 启动时检测扩展与索引列；可用则为 `ann`，否则 `memory`；写启动日志与 `aiqa.retrieval.vector.mode` 指标                                               |
| `backend/src/main/java/dev/aiqa/search/EmbeddingClient.java`                     | 新增 `vectorLiteral`：把 `{...}` 数组字面量转成 pgvector 的 `[...]`，避免两套格式化逻辑                                                             |
| `backend/src/main/java/dev/aiqa/document/DocumentRepository.java`                | 写入时按模式双写 `embedding_vec`；`real[]` 保留一个版本周期供回退                                                                                   |
| `backend/src/main/java/dev/aiqa/answer/AnswerService.java`                       | 向量候选查询按模式切换：`ann` 走 `embedding_vec <=> CAST(:queryVec AS vector)` + `LIMIT 60`；`memory` 保持原逻辑并新增触顶指标                      |
| `backend/README.md`                                                              | 新增「向量近邻检索（可选）」部署说明与约束                                                                                                          |

指标：

- `aiqa.retrieval.vector.mode`：1=ann，0=memory（启动时确定）
- `aiqa.retrieval.vector.scanned`：每次检索实际取回的候选数
- `aiqa.retrieval.vector.truncated`：memory 模式触顶次数，应接入告警

## 验证

### 全量回归（降级路径）

```sh
export IT_DB_URL="jdbc:postgresql://127.0.0.1:5432/ai_qa_it"
cd backend && mvn -o test
```

结果：**132 项通过，0 失败 0 跳过**。默认不启用 profile，测试库走 `memory` 模式，行为与改造前一致。

### pgvector 端到端（本机 PostgreSQL 17.12）

pgvector 0.8.7 由源码针对 `postgresql@17` 编译安装（Homebrew bottle 只含 @18/@19 的扩展文件）。

```sh
createdb ai_qa_pgv && psql ai_qa_pgv -c "create extension vector"
SPRING_PROFILES_ACTIVE=pgvector DB_URL=jdbc:postgresql://127.0.0.1:5432/ai_qa_pgv \
  SERVER_PORT=18080 WORKER_ENABLED=false PROCESS_ROLE=api mvn -o spring-boot:run
```

观察结果：

- Flyway：`Migrating schema "public" to version "100 - chunk embedding vec"`，`Successfully applied 19 migrations ... now at version v100`
- 启动日志：`向量检索模式：ann（pgvector HNSW 近邻）`
- `GET /actuator/health` 返回 `200`，`{"groups":["liveness","readiness"],"status":"UP"}`
- 索引确认：`ix_document_chunk_embedding_vec ON public.document_chunk USING hnsw (embedding_vec vector_cosine_ops) WITH (m='16', ef_construction='64')`
- 列类型：`embedding` 为 `real[]`，`embedding_vec` 为 `vector`

## 遗留项处理（2026-11-28）

1. **CI 覆盖 ANN**：`.github/workflows/backend.yml` 的 Postgres service 由 `postgres:16-alpine` 换成
   `pgvector/pgvector:pg16`；新增 `VectorAnnIT`，在隔离 schema 里安装扩展、写入 1000 个随机向量块，
   逐条比对数据库 `<=>` 召回与应用内暴力余弦的 top-20，5 个查询重合均 ≥ 19/20。
2. **真实表上的 ANN 一致性已验证**：测试走真实 `document_chunk` 表与 HNSW 索引，不是独立临时表。
   仍是 1000 块规模，5000 块的吞吐与召回留待压测。
3. **测试库搜索路径调整**：`TestDatabase` 的 `currentSchema` 追加 `,public`，让隔离 schema 能解析装在
   库级 public 的扩展类型；`V100` 的 `CREATE EXTENSION` 显式 `SCHEMA public`，避免扩展对象被
   `DROP SCHEMA CASCADE` 连带删除后留下悬空的 `pg_extension` 记录。
4. **开发库已启用**：`~/.config/ai-qa/dev.env` 增加 `SPRING_PROFILES_ACTIVE=pgvector`
   （备份 `dev.env.bak-20261127-202500`）。实测启动时 V18 + V100 迁移成功、`向量检索模式：ann`、
   `/actuator/health` 返回 200；`document_chunk` 67 块全部回填 `embedding_vec`，HNSW 索引存在。
5. **临时库清理**：验证用的 `ai_qa_pgv` 已删除。
6. **开发库重索引已执行**：16 个 READY 文档全部重索引，`document_chunk` 67 块、最大 154 字
   （语料本身较短，未触及 800 字窗口），`embedding` 与 `embedding_vec` 均为 67/67 非空。

### 多实例升级注意（实测踩到）

重索引期间发现：与开发库共用同一数据库的旧实例（改造前启动、未加载新代码）会抢先领取
`INDEX_DOCUMENT` 任务，按旧 SQL 写入，只写 `embedding` 不写 `embedding_vec`，导致 24 个新块缺列，
ANN 检索会漏掉这些块。

- 已用 `UPDATE document_chunk SET embedding_vec = embedding::vector(1024)
WHERE embedding IS NOT NULL AND embedding_vec IS NULL` 回填，恢复 67/67。
- **部署要求**：升级到本版本后，必须先停掉所有旧实例（或先停 worker、跑完迁移与回填），
  再重新启用 worker，否则旧实例会持续写入缺列数据。
- 建议把「`embedding` 非空但 `embedding_vec` 为空」的计数纳入运维巡检与告警。

## 遗留问题

1. **规模**：5000 块的吞吐与召回未压测；`VectorAnnIT` 只覆盖 1000 块。
2. **维度硬编码**：`embedding_vec vector(1024)` 与当前 qwen3-embed 绑定；换模型需清空重建该列。
3. **生产环境重索引**：开发库已执行重索引；其他已部署环境仍需在维护窗口按空间重索引，
   且必须先停掉旧实例，避免旧 worker 写入缺 `embedding_vec` 的块。
4. **memory 模式 2000 条上限仍在**：有意保留的降级路径，靠 `aiqa.retrieval.vector.truncated` 暴露。
