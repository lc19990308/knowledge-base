# 变更记录：数据清洗后端管线（2026-11-26）

## 背景

按[数据清洗实施计划](./ai-qa-data-cleaning-plan.md)实现 M1–M3 的后端：上传文件夹/压缩包 → 清洗成条目 → 草稿箱打分 → 管理员审核 → 流转成文档版本进空间草稿。前端页面已在同一轮先交付，接口口径以 `frontend/src/api/cleaning.ts` 为准。

## 交付内容

### 迁移

- `V17__cleaning_pipeline.sql`：新增 `cleaning_batch`、`cleaning_source_file`、`cleaning_item`，并给 `job` 加 `cleaning_batch_id`、扩展任务类型与目标约束。
- **编号用 V17 而不是计划里的 V16**：同期另一处改动已占用 `V16__ai_template_generation.sql`。迁移里重建 `job` 的 CHECK 时同时保留 `GENERATE_TEMPLATE` 与 `template_generation_id` 条件，两个功能互不影响。

### 新增包 `dev.aiqa.cleaning`

| 文件 | 职责 |
| --- | --- |
| `CleaningRepository` | 批次/来源文件/条目的读写；列表筛选与总数共用同一份条件 |
| `CleaningService` | 上传受理：数量与体积校验、压缩包安全解包、原件暂存、幂等、入队 |
| `CleaningZip` | 临时文件 + `ZipFile` 解包；拒绝路径穿越、超量条目、超大条目、异常压缩比 |
| `RawDatasetReader` | 按扩展名读成原始记录：TXT/MD、CSV/TSV、JSON/JSONL、HTML、XLSX，PDF/DOCX/PPTX 复用 `TextExtractor` |
| `TextCleaner` / `ItemSplitter` / `RuleScorer` | 去噪、拆条与标题、五维规则分 |
| `CleaningWorker` | `CLEAN_DATASET`：重跑幂等、逐文件容错、条目与批次状态同事务提交 |
| `CleaningModelScorer` / `CleaningScoreWorker` | `SCORE_CLEANING`：调用默认模型网关打质量分，失败回退规则分 |
| `CleaningReviewService` | 批量审核（驳回必填原因）与重新打分 |
| `CleaningPromotionService` | 生成 Markdown 文档版本、入对象存储、追加空间草稿、回写条目、审计 |
| `api/CleaningController` | 8 个操作、7 个路径 |

### 修改的既有文件

| 文件 | 改动 |
| --- | --- |
| `persistence/JobRepository` | 新增 `CLEAN`/`CLEAN_SCORE` 两类任务与容量/并发参数，`Job` 增加 `cleaningBatchId`，新增入队与查询方法；与同期 `GENERATE_TEMPLATE` 改动合并 |
| `draft/DraftService` | 新增 `appendGeneratedDocument`：锁空间后读最新草稿追加并按原版本写回，调用方不需要 ETag |
| `common/ErrorCode` | 新增 `CLEANING_ARCHIVE_REJECTED` |
| `resources/application.yml` | `multipart.max-file-size` 10MB → 64MB；新增 `aiqa.cleaning.*` 与 `worker.capacity/concurrency.clean*` |
| `docs/ai-qa-openapi.yaml` | 新增 7 个路径与 9 个 schema |
| `OpenApiContractTest` | 登记 `CleaningController`；并把同期遗漏的 `AnswerStreamController` 补进清单 |
| `migration/SchemaMigrationTest` | 三张新表加入断言，期望表数 7 → 10 |
| `ingest/IndexRebuildIT` | 补 `document_id`（V15 之后 NOT NULL），修复该用例既有失败 |
| `ingest/ImportLifecycleIT`、`IndexRebuildIT` | 同步 `JobRepository` 构造参数 |

## 实现要点

1. **流转生成的文档接入既有链路**：写成 `text/markdown` 的 `document_version`，投递 `PARSE_DOCUMENT`，由 `DocumentParseWorker` 切块、生成向量并进入空间草稿；发布仍走原审核流程。
2. **`scan_state` 必须用 `PENDING`**：`DocumentParseWorker` 对 `CLEAN`/`REJECTED` 直接跳过解析（现网导入链路只写 `PENDING`，该分支此前不会命中）。生成文档若写 `CLEAN` 会永远没有正文块，因此按导入链路口径写 `PENDING`，并在代码里注明原因。
3. **压缩包用 `ZipFile` 而不是 `ZipInputStream`**：流式解包在读取前拿不到可靠的原始/压缩后大小，压缩比保护形同虚设；改为落临时文件后读中心目录，读完删除。加密包读不出来，统一按 `ARCHIVE_UNREADABLE` 拒绝而不是静默跳过。
4. **重跑幂等**：worker 领取任务后锁批次、清空上一轮条目与来源状态再重算；若上一轮已提交过条目（`itemCount > 0`）则直接标记任务成功，避免崩溃重放把结果重复写入。
5. **审核整体成功或整体失败**：批量审核只更新仍为「待审核」的条目，实际更新数与请求数不一致就回滚并返回 409，避免出现「部分通过」。
6. **模型打分失败开放**：网关不可用或解析失败时单条回退规则分并标 `UNAVAILABLE`；未配置模型时整批只出规则分，不阻塞审核。总分 = 0.4×规则 + 0.6×模型。
7. **流转的文档名与草稿**：默认 `清洗知识-{批次}-{yyyyMMddHHmm}.md`，同空间重名自动加序号；标题去重后作为二级标题，切块后引用可定位到条目。

## 验证

```bash
cd backend
mvn -o -DskipTests compile
mvn -o test -Dtest='CleaningRulesTest,CleaningZipTest,CleaningModelScorerTest'
IT_DB_URL=jdbc:postgresql://127.0.0.1:5432/ai_qa_it IT_DB_USER=liuchao IT_DB_PASSWORD= \
  mvn -o test
```

结果：

- 单元测试 18 项通过：清洗/拆条/标题/评分边界 7 项；压缩包路径穿越、条目上限、压缩比、路径归一 5 项；模型响应解析与降级 6 项。
- `CleaningFlowIT`（真实 PostgreSQL + MockMvc 全链路）通过：multipart 上传返回 202 且 `skippedCount=1`；worker 后批次 `READY`、`itemCount=1`；条目 `scoreState=UNAVAILABLE` 且理由为「未启用模型打分」；驳回缺原因返回 422；审核通过 200；流转返回 201 且 `promoted=1`；解析后 `document_version.media_type=text/markdown`、`document_chunk` 有切块、`space_draft_document` 有该版本、条目变 `PROMOTED`；重复流转返回 409。
- 全量 125 项：124 通过、1 失败。失败是 `PublishingAndWithdrawalIT:312`：移除管理员成员关系后仍能读取引用详情（期望 404）。该行为来自 `QuestionRepository.paragraphStillAuthorized` 对系统管理员的显式放行（文件时间 2026-11-25 22:11，早于本轮改动），与本变更无关，未擅自改他人用例。
- 为让全量套件可判定，顺带修了两处**既有**红灯：`OpenApiContractTest` 控制器清单遗漏 `AnswerStreamController`；`IndexRebuildIT` 插入 `document_version` 未补 V15 的 `document_id`。

## 与计划的偏差

| 计划 | 实际 | 原因 |
| --- | --- | --- |
| `cleaning_item.duplicate_of` | 未建该列，重复条目直接跳过并计入 `duplicate_count` | 草稿箱不展示重复项，留着是永远为 NULL 的死列 |
| 跳过加密压缩包条目 | 整个压缩包按不可读拒绝 | `ZipFile` 无法在不读内容的情况下可靠识别加密条目，拒绝比静默跳过更安全 |
| 生成文档 `scan_state=CLEAN` | 改为 `PENDING` | 解析 worker 会跳过 CLEAN，正文永远为空 |
| V16 迁移编号 | V17 | 与同期模板生成改动冲突 |

## 遗留问题

- 未用真实模型网关跑打分：当前本地没有可用的默认模型配置，测试里关闭了模型打分；接入正式网关后需复跑一次并记录 Token 与分数分布。
- 前端尚未与后端联调：需要重启后端加载新代码后，用 `/cleaning` 页跑一遍上传 → 审核 → 流转 → 发布。
- 正式 E2E 用例（`frontend/e2e/cleaning.mjs`）仍未入库，本轮用临时脚本验证前端。
- 打分消耗的 Tokens 未计入问答统计（`model_call_usage` 强绑 `question_id`），要计量需另建表。
- 上传与审核目前仅系统管理员可用；空间维护者上传需要额外过滤，尚未实现。
- `PublishingAndWithdrawalIT` 的引用访问期望与管理员读取策略不一致，待该功能负责人确认后更新。
