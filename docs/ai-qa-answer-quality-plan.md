# AI 问答回答质量改造方案

- 日期：2026-11-27
- 状态：已评审，S1 实施中。三项决策已定：允许安装 pgvector、判定失败默认拒答、精排先用网关 listwise
- 范围：只改「检索 → 证据 → 生成 → 引用 → 历史屏蔽」这条回答质量链路
- 依据：本方案针对 2026-11-27 的代码评审发现，问题定位到文件与行号

## 1. 基线与目标

当前实测（真实模型 + 向量召回，2026-11-25 记录）：

| 指标           | 当前                                        | 改造目标                           |
| -------------- | ------------------------------------------- | ---------------------------------- |
| 质量题通过率   | 20/20（20 题）                              | 50 题 ≥ 95%                        |
| Recall@20      | 100%                                        | ≥ 98%（50 题口径）                 |
| Hit@5          | 17/17                                       | ≥ 90%                              |
| 引用正确率     | 未单独统计                                  | ≥ 95%，且 0 条「未标注却出现引用」 |
| 同题重复一致性 | 未测                                        | 3 次一致率 ≥ 90%                   |
| 回答延迟       | 均值 10.99 s，p95 21.93 s（另一轮 32.07 s） | p95 ≤ 15 s，首字 ≤ 3 s             |
| 向量召回覆盖   | 2000 块无序截断                             | 全库覆盖，或显式降级并可见         |
| 无依据正确处理 | 20/20                                       | 100%，且失败回路可配置             |

## 2. 非目标

- 不引入多租户、计费、SSO、OCR、移动端（属于商业化议题，另立方案）。
- 不更换前端框架、不重做交互，只补必要提示。
- 不追求模型能力提升，只修正链路设计（证据选择、输入一致性、降级策略）。

## 3. 总览

| 阶段 | 主题       | 工期    | 依赖           | 产出                                     |
| ---- | ---------- | ------- | -------------- | ---------------------------------------- |
| S1   | 正确性修复 | 5 人日  | 无             | 引用不失真、判定与生成输入一致、降级可控 |
| S2   | 检索质量   | 8 人日  | S1.2 的切块 v2 | 向量可扩展、精排、多轮改写、屏蔽粒度     |
| S3   | 评测与门禁 | 5 人日  | S1、S2         | 50 题题库、CI 回放门禁、延迟达标         |
| 合计 |            | 18 人日 |                |                                          |

三项 P0 必须完成：S1 全部、S2.1、S2.4。S2.2 与 S2.3 可按质量数据决定是否上线。

## 4. S1 正确性修复

### 4.1 引用不得回填（P0，0.5 人日）

**问题**：`GatewayAnswerModel.parseCitations` 在模型一个 `[n]` 都没写时返回全部证据索引，导致所有证据都被落成引用；`AnswerService.paragraphKind` 同样在无引用时回退到全部候选，可能把正常回答误标为 `CONFLICT`。这会让「引用可核对」这一产品承诺静默失效。

**改法**：

1. `backend/src/main/java/dev/aiqa/answer/GatewayAnswerModel.java`：`parseCitations` 解析为空时返回 `List.of()`，不再回填全部。
2. `backend/src/main/java/dev/aiqa/answer/AnswerService.java`：`paragraphKind` 收到空引用时按「无引用」判断（不参与冲突与失效判定），`used` 用空列表即可，段落类型保持 `ANSWER`。
3. `AnswerService` 落库时新增 `aiqa.answer.uncited` 指标计数，并在日志记录 `questionId` 与证据条数，便于排查。
4. 前端 `frontend/src/views/AssistantView.vue`：段落无引用时在段落底部显示「本条答案未标注来源」，不改变既有布局；有引用时行为不变。

**验证**：

- 新增单测 `GatewayAnswerModelTest` 用例：模型回复无 `[n]` → `citedEvidenceIndexes()` 为空。
- 新增集成用例 `AnswerServiceIT`：固定「模型不写引用」的替身 → `citation` 表 0 行，段落类型 `ANSWER`，前端出现未标注提示。
- 回归：现有 20 题中带引用的题目引用数与 marker 不变。

**验收**：构造用例 100% 拦截；20 题引用正确率不下降。

**回滚**：单点改动，`git revert` 即可；无数据迁移。

### 4.2 判定与生成输入对齐 + 切块 v2（P0，3 人日）

**问题**：

- 判定看前 8 条、每条截断 600 字；生成看全部 20 条、每块最多 4000 字。两者输入不一致，既可能误拒（依据在 600 字之后），也可能误放（生成被无关长文稀释）。
- 4000 字/块的粒度与 1024 维向量不匹配，语义被平均掉。
- 最坏情况下 20 × 4000 = 8 万字进上下文，成本与「中间遗忘」都不可控。

**改法**：

1. 切块 v2：`backend/src/main/java/dev/aiqa/ingest/TextExtractor.java`
   - `MAX_CHUNK_LENGTH` 4000 → `aiqa.chunking.max-chars`（默认 800）。
   - `addChunks` 改为滑动窗口，窗口重叠 `aiqa.chunking.overlap`（默认 120 字），重叠区间不改变 `source_location`。
   - 表格行、幻灯片页、PDF 页的既有 `source_location` 规则保持不变。
2. 证据选择统一为一处：`AnswerService` 新增私有方法 `selectEvidence(List<EvidenceCandidate>)`，返回「发给模型的证据列表」（默认前 10 条），判定、生成、`model_call_evidence` 落库全部使用同一份列表。
   - `CANDIDATE_LIMIT` 20 → 24（块变小后需要更多候选覆盖复合问题）。
   - 每条证据进 prompt 前截断到 `aiqa.answer.evidence-max-chars`（默认 1200），总 prompt 证据文本上限 `aiqa.answer.evidence-total-chars`（默认 12000）。
3. `ModelRelevanceJudge` 的 `MAX_CHARS` 600 改为读取同一配置，判定输入与生成输入条数、截断长度一致。
4. `paragraph_evidence` 只记录「发给模型的证据」（10 条），不再记录未入 prompt 的候选，避免历史屏蔽面过宽（配合 4.4）。

**数据迁移**：

- 新增 `V18__chunking_v2.sql`：`document_chunk` 增加 `chunker_version smallint not null default 1` 与中文注释；`question` 增加 `retrieval_text text`（供 5.3 使用，本次一并建列）。
- 重索引：按空间调用 `POST /api/v1/spaces/{spaceId}/reindex`，脚本 `scripts/reindex-all.sh` 串行执行并记录进度。重索引是「先删后插」单事务，期间该空间检索短暂为空，需在维护窗口执行。
- 历史证据不受影响：`model_call_evidence` 存的是冻结文本副本，旧引用仍可预览。

**验证**：

- `TextExtractorTest`：800 字窗口、120 重叠、表格行不切分、位置标注不变。
- `AnswerServiceIT`：判定与生成收到同一批 10 条证据（用替身记录入参）。
- 重索引后 `EMB-01` 断言：全部块有向量、维度 1024。
- 用 20 题新旧对照：Recall@20、Hit@5、引用正确率不降。

**验收**：判定与生成输入完全一致（单测断言）；prompt 证据总量 ≤ 12000 字；20 题通过率不低于 20/20。

**回滚**：`aiqa.chunking.max-chars=4000` + `overlap=0` 恢复旧切块，重新 reindex 一次；代码层保留配置读取，不带版本分支。

### 4.3 判定失败策略（P0，0.5 人日）

**问题**：`ModelRelevanceJudge` 在网关超时、HTTP 非 2xx、解析失败时一律放行（fail-open）。政策场景下，网关抖动的后果是「可能编造的回答被当作有依据」。

**改法**：

1. 新增配置 `aiqa.answer.relevance-failure`（`closed` 默认 / `open`）与 `aiqa.answer.relevance-failure-text`（默认「暂时无法确认回答依据，请稍后重试」）。
2. `ModelRelevanceJudge.judge` 的 `Verdict.unavailable` 分支按配置返回：`closed` → `sufficient=false`，`AnswerService` 走 `NO_EVIDENCE` 并带失败文案；`open` → 维持现状。
3. 指标 `aiqa.relevance.unavailable` 已存在，补告警阈值：5 分钟内 > 5 次即告警（运维接入）。
4. 前端区分两种空回答：「资料中没有相关内容」与「暂时无法确认依据」，由 `ParagraphView.kind` 或新增 `reason` 字段表达。

**验证**：`ModelRelevanceJudgeTest` 增加「网关不可达 + closed → 不放行」「网关不可达 + open → 放行」两例；`AnswerServiceIT` 断言 `NO_EVIDENCE` 与文案。

**验收**：关闭态下网关故障不产生引用；两种空回答在界面上文案可区分。

**回滚**：`RELEVANCE_FAILURE=open` 即恢复旧行为。

## 5. S2 检索质量

### 5.1 向量召回规模（P0，已实施 2026-11-27）

**问题**：`AnswerService.vectorCandidates` 用 `ORDER BY c.id LIMIT 2000` 取块，是 UUID 顺序截断，不是相关度。超过 2000 块后向量召回退化为随机抽样，且无任何日志与指标。按 4000 字/块估算，几十份制度文档就会越过。

**改法（推荐 pgvector）**：

1. 独立迁移目录 `backend/src/main/resources/db/migration-pgvector/`，通过 `pgvector` profile 在 `spring.flyway.locations` 追加，避免扩展未安装时阻塞启动。
2. `V1__embedding_vector.sql`：`CREATE EXTENSION IF NOT EXISTS vector;`，新增列 `document_chunk.embedding_vec vector(1024)` 与 HNSW 索引（`vector_cosine_ops`, `m=16`, `ef_construction=64`）。
3. 写入侧双写：`DocumentRepository` 的插入语句同时写 `embedding` 与 `embedding_vec`；旧列保留一个版本周期。
4. 检索侧：扩展可用时走 SQL `ORDER BY embedding_vec <=> CAST(:query AS vector) LIMIT 60`；不可用时回退现有内存余弦，并在启动日志与 `/actuator/info` 标记 `vector.mode=memory`。
5. 指标：`aiqa.retrieval.vector.mode`（ann/memory）、`aiqa.retrieval.vector.scanned`、`aiqa.retrieval.vector.coverage`（scanned / 库内块数）。覆盖率 < 100% 时打 WARN。

**降级路径（无扩展权限时）**：保持内存余弦，但把 `VECTOR_SCAN_LIMIT` 显式化并写进文档与启动日志；覆盖率指标触发告警；超过上限时检索日志标记 `vector_degraded`。

**验证**：构造 5000 块的隔离库，断言 `scanned == 库内块数`、覆盖率 100%；20 题 Recall@20 不降；检索段 p95 ≤ 300 ms。

**验收**：5000 块规模下向量召回结果与暴力全量余弦一致（抽样 100 题比对）；扩展缺失时启动不失败、降级可见。

**回滚**：关闭 `pgvector` profile 即回内存余弦，旧列仍在。

### 5.2 判定与精排合并（P1，2 人日）

**问题**：当前没有 cross-encoder 精排，候选顺序只靠词法衰减 + 集合覆盖，进入模型的 10 条里仍有低相关片段；判定与生成又是两次独立模型调用。

**改法**：

1. 把 `ModelRelevanceJudge` 扩展为「判定 + 排序」一次调用：输入问题与 10 条候选（各 600 字），输出 `{"sufficient": true|false, "reason": "...", "order": [3,1,5,...]}`。
2. 解析失败时降级：`sufficient` 按 4.3 的失败策略，`order` 用原顺序，指标 `aiqa.rerank.outcome=parse-failed`。
3. `order` 只影响证据顺序与前 10 条的选择，不影响权限与有效性判定。
4. 可选增强（不进本期必做）：部署本地 `bge-reranker-v2-m3` 走 cross-encoder，减少一次网关往返；由部署资源决定。

**验证**：`ModelRelevanceJudgeTest` 覆盖合法/非法/越界 order；质量题 Hit@5 对比提升；延迟不高于改造前。

**验收**：Hit@5 ≥ 90%；判定 + 精排合计只增加一次模型调用；解析失败率 < 1%。

**回滚**：`aiqa.answer.rerank=false` 关闭排序，仅保留判定。

### 5.3 多轮问题改写（P1，1.5 人日）

**问题**：每次只把「当前问题 + 本次证据」交给模型，「那住宿呢？」这类追问检索必然偏。

**改法**：

1. 会话存在上一轮成功问答时，先做一次改写：输入「上一轮问题 + 上一轮回答前 200 字 + 当前问题」，输出独立问题（`max_tokens` 80，超时 8 s）。
2. 改写失败或超时回退原问题；改写结果写入 `question.retrieval_text`，原始问题保持展示与审计。
3. 只有首轮之后才触发，首轮零额外成本。

**验证**：新增 5 道追问题（住宿→「那住宿呢？」、差旅→「那国际的呢？」等）；断言检索用改写后文本、展示仍是原文。

**验收**：追问题通过率 ≥ 4/5；首轮 p95 不变。

**回滚**：`aiqa.answer.rewrite=false`。

### 5.4 历史屏蔽粒度（P0，1 人日）

**问题**：`QuestionRepository.paragraphStillAuthorized` 用 `paragraph_evidence` 的全部证据判断，任一来源失权即整段隐藏。一段回答通常只有 2–3 条被真正引用，其余是候选陪跑，导致「无关文档被撤回，整段回答消失」。

**改法**：

1. 子查询加入 `citation`：只检查被引用的证据（`paragraph_evidence` join `citation` 的 `call_id + evidence_ordinal`）。
2. 段落没有 `citation` 时回退为全部证据（保守），覆盖 4.1 修复前的历史数据。
3. 前端不变，`MASKED` 语义不变。

**验证**：新增集成用例——撤回一条未被引用的文档，段落仍 `VISIBLE`；撤回被引用的文档，段落 `MASKED`；旧数据（无 citation）保持保守屏蔽。

**验收**：两个用例均通过；撤回场景的既有 8 项测试不回归。

**回滚**：单条 SQL 改动，可 revert；无数据迁移。

## 6. S3 评测与门禁

### 6.1 题库扩到 50 题（2 人日）

按题型分布扩题，保持 `frontend/e2e/quality.mjs` 的 `requiredFacts` / `forbiddenFacts` / `requiredEvidence` 结构：

| 题型      | 题数 | 覆盖点                   |
| --------- | ---- | ------------------------ |
| 精确条款  | 10   | 数字、时限、审批人       |
| 口语改写  | 8    | 同义替换、指代           |
| 表格      | 8    | 行级证据、表头语义、多列 |
| 复合问题  | 6    | 两方面证据同时命中       |
| 时效/失效 | 6    | 失效、待核实、新旧版并存 |
| 冲突      | 4    | 同名多版本、跨文档多值   |
| 无答案    | 5    | 无相关文档、仅否定语境   |
| 越权      | 3    | 未授权空间、撤回后       |

判分升级：

- `requiredFacts` 支持「数值容忍」与「同义组」（如 `15 个工作日` 与 `十五个工作日内`）。
- 新增重复性检查：每题跑 3 次，断言结果一致。
- 每轮人工抽检 10 题，记录在变更记录中。

### 6.2 CI 回放门禁（0.5 人日）

**问题**：质量题不在 CI，`FakeAnswerModel` 不拒答，质量退化无人发现。

**改法**：

1. 新增 `ReplayAnswerModel`（`MODEL_PROVIDER=replay`）：从 `backend/src/test/resources/replay/<题号>.json` 读固定模型响应，模拟真实输出。
2. 新增集成测试 `AnswerQualityReplayIT`：固定语料 + replay 模型，断言 50 题的 `NO_EVIDENCE`、引用数、段落类型、屏蔽行为。
3. 接入 `.github/workflows/backend.yml`（已有 Postgres service），作为 `mvn verify` 的一部分。
4. E2E 仍保留真实模型运行，作为发布前人工验收，不进 CI。

**验收**：故意把 4.1 的回填逻辑改回旧行为，CI 必须变红。

### 6.3 延迟与并行（1.5 人日）

- 检索并行：`lexicalCandidates` 与 `embeddings.embed` 用 `CompletableFuture` 并行，检索段 p95 目标 ≤ 500 ms。
- embedding 缓存：进程内 `ConcurrentHashMap`（上限 1000，TTL 10 分钟），key 为问题文本哈希。
- 合并判定与精排（5.2）后，单次问答的模型调用从 2 次降到 2 次（判定 + 生成），但判定耗时不再叠加两次往返。
- 压测脚本 `scripts/load-qa.mjs`：5 并发、100 次提问，输出 p50/p95/错误率。

**验收**：真实模型下 p95 ≤ 15 s；检索段 p95 ≤ 500 ms；压测错误率 0。

## 7. 数据迁移与兼容

| 迁移                       | 内容                                                                  | 风险                           |
| -------------------------- | --------------------------------------------------------------------- | ------------------------------ |
| V18（必做）                | `document_chunk.chunker_version`、`question.retrieval_text`、相关注释 | 低，加列为空                   |
| V19（可选，独立 location） | pgvector 扩展 + `embedding_vec` + HNSW 索引                           | 扩展缺失时不得阻塞启动         |
| 重索引                     | 按空间串行调用 reindex                                                | 期间该空间检索为空，需维护窗口 |

兼容原则：

- `model_call_evidence` 冻结旧文本，历史引用与预览不受切块变化影响。
- 旧 `citation` 缺失的段落按保守策略屏蔽，不因 5.4 放宽。
- `embedding` 旧列保留一个版本周期，双写期间两者并存。

## 8. 验收标准

| 项         | 标准                                           | 验证方式                 |
| ---------- | ---------------------------------------------- | ------------------------ |
| 引用真实性 | 未标注回答 0 引用                              | `AnswerServiceIT` + 单测 |
| 判定一致性 | 判定与生成同批证据                             | 单测断言入参             |
| 降级可控   | 关闭态网关故障不产生引用，两种空回答文案可区分 | 集成测试                 |
| 检索规模   | 5000 块覆盖率 100%，与暴力余弦一致             | 隔离库抽样比对           |
| 屏蔽粒度   | 未引用文档撤回不屏蔽段落                       | 集成测试                 |
| 质量       | 50 题 ≥ 95%，无答案 100%，一致率 ≥ 90%         | `quality.mjs` + 人工抽检 |
| 回归门禁   | 回填旧行为时 CI 变红                           | 故意回归验证             |
| 延迟       | p95 ≤ 15 s，检索 ≤ 500 ms                      | 压测脚本                 |

## 9. 排期

| 周      | 内容                         |
| ------- | ---------------------------- |
| 第 1 周 | S1.1、S1.2（含重索引）、S1.3 |
| 第 2 周 | S2.1、S2.4                   |
| 第 3 周 | S2.2、S2.3、S3.1             |
| 第 4 周 | S3.2、S3.3、回归与验收       |

## 10. 决策与风险

**2026-11-27 已拍板**：

1. **允许安装 pgvector** → S2.1 走 ANN 方案；保留内存余弦作为扩展缺失时的降级路径，降级必须可见。
2. **判定失败默认拒答** → `aiqa.answer.relevance-failure` 默认 `closed`；网关故障时集中返回「暂时无法确认依据」，需配套告警。
3. **精排先用网关 listwise** → S2.2 先做合并调用，本地 cross-encoder 作为后续优化。

**其他风险**：

- 切块变更会改变检索结果，需用第二套语料空间做新旧对照，不能在同一空间直接比。
- 重索引窗口内检索为空，需与业务确认维护时间。
- 判定 + 精排合并后单次调用更复杂，解析失败率需监控；失败已设计为降级而不是中断。
- 评测判分仍是字面匹配，需持续用人工抽检校准，避免题卡与实现互相过拟合。
