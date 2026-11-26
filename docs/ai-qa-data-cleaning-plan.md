# 数据清洗与清洗草稿箱｜实施计划

- 制定日期：2026-11-26
- 适用系统：AI 智能问答平台（`backend` Java 17 + Spring Boot / `frontend` Vue 3 + TS）
- 需求来源：知识库缺少数据清洗能力。需要把上传的**文件夹**与**压缩包**里的原始数据清洗成知识条目，进入**清洗草稿箱**并打分，由**管理员审核**，通过后**流转进知识库**。

## 1. 结论摘要

新增一条独立于「文档导入」的清洗管线，但不另造一套知识存储：

```
上传文件夹/压缩包
  → 原始文件落对象存储 + 批次登记（异步）
  → 清洗 worker：解码 → 去噪 → 拆条 → 去重 → 规则打分
  → 模型打分 worker（复用默认模型网关，失败降级不阻塞）
  → 全局清洗草稿箱（按目标空间筛选，显示规则分/模型分/总分）
  → 管理员审核（批量通过/驳回，驳回必填原因）
  → 流转：生成一份 Markdown 文档版本 → 写入目标空间草稿 space_draft
  → 走现有「提交变更审核 → 发布」流程 → 检索/问答可见，引用定位到条目小节
```

关键取舍：**清洗产物最终是「文档版本 + 空间草稿」**，因此快照、发布审核、撤回、检索、引用、统计这些既有链路全部复用，不新增知识条目表、不改检索 SQL。

## 2. 现状核对（本次阅读的代码）

| 链路 | 现有实现 | 对本需求的含义 |
| --- | --- | --- |
| 导入解析 | `ImportService` 落对象存储 → `document_version` → `PARSE_DOCUMENT` 任务 → `DocumentParseWorker` 用 `TextExtractor` 切块、写 `document_chunk`（含 `search_vector`、`embedding`） | 生成文档可以直接复用这套解析，不必自己写切块/索引 |
| Markdown 切块 | `TextExtractor.textSections(markdown=true)` 按 `#`/`##` 标题分节，`source_location` 记小节标题 | 清洗条目用 `## 条目标题` 输出，引用就能定位到具体条目，而不是整篇文档 |
| 草稿 | `space_draft` + `space_draft_document`，强 ETag `draft_revision`，`DraftService.replace` 在 `lockSpace` 后整体替换 | 流转只需「读草稿 → 追加一条 → 按原版本写回」 |
| 发布 | `publish_request` 冻结快照；`searchableReady` 对 `QUEUED/PROCESSING/FAILED` 返回 false，先转 `APPROVED_WAITING_INDEX` 再发布 | 生成文档解析期间提审是安全的，无需等解析完才允许流转 |
| 检索 | `SearchService` 只认「当前发布快照」或「草稿」两个集合 | 清洗数据发布前对普通用户不可见，符合审核语义 |
| 队列 | `job` 表单一队列，`JobRepository.Kind` 枚举 + 容量/并发配置 + 租约 | 新增两个任务类型即可，不需要新中间件 |
| 模型调用 | `ModelRelevanceJudge` 展示了「取默认模型配置 + 协议鉴权 + 一行 JSON 解析 + 失败开放」的现成范式 | 模型打分照抄这个范式，不引新依赖 |
| 权限 | `SpaceRepository.findForUser` 里系统管理员 `can_maintain` 恒为 true | 管理员可把清洗结果流转到任意空间，不用额外授权表 |
| 契约 | `OpenApiContractTest` 双向校验控制器与 `docs/ai-qa-openapi.yaml` | 新接口必须同步写契约，否则测试直接失败 |
| 前端 | 路由 `router.ts` + 导航 `navigation.ts`；知识库页 `KnowledgeView.vue` 已有抽屉/表格/分页范式 | 清洗页按同构方式新建，不改造知识库页 |

现有「草稿」一词专指**空间发布草稿**。本功能在界面上必须叫「数据清洗 / 清洗草稿箱」，与「空间草稿」区分，避免用户误解。

## 3. 范围

**做**

1. 上传原始数据集：多文件夹（保留相对路径）、单个 `.zip` 压缩包；逐文件反馈结果。
2. 清洗：编码与空白归一、HTML/控制字符去噪、按文本段落 / CSV·TSV 行 / JSON 记录 / XLSX 行拆条、批内去重；PDF/DOCX/PPTX 复用现有 `TextExtractor` 抽取后再清洗，避免「能导入却不能清洗」的落差。
3. 打分：规则分（可解释维度）+ 模型分（复用默认模型网关），总分与降级规则。
4. 全局清洗草稿箱：按状态、批次、目标空间、最低分、关键词筛选，服务端分页。
5. 管理员审核：批量通过 / 驳回（驳回必填原因），审核与流转写审计。
6. 流转：生成 Markdown 文档版本 → 追加进目标空间草稿 → 提示去「发布管理」提交变更审核。

**不做（本期明确排除）**

- 外部数据库直连清洗（JDBC 数据源配置、只读账号、表映射）。本轮数据来源是文件夹与压缩包；数据源读取抽象成接口（`RawDatasetReader`），后续接数据库只加实现。
- OCR、图片、音视频清洗。
- 一次上传超过 500 个文件 / 200 MB 的语料（需分批）。
- 清洗结果的语义冲突检测、政策时效自动识别（仍由人工在发布审核时填政策状态与生效区间）。
- 把清洗打分的 Token 计入「问答统计」（现有 `model_call_usage` 强绑 `question_id`，见 §9 待确认）。

## 4. 流程与状态机

```
[管理员] 上传文件夹/压缩包（可选目标空间）
        │  multipart + Idempotency-Key
        ▼
cleaning_batch: QUEUED ──CLEAN_DATASET──▶ EXTRACTING
                                            │ 解压/解码/拆条/去重/规则分
                                            ▼
                        ┌──────────── SCORING（有默认模型时）
                        │                   │  SCORE_CLEANING
                        │                   ▼
                        └──────────── READY（全部成功） / PARTIAL（部分文件失败） / FAILED
                                            │
[管理员] 清洗草稿箱列表/详情 ◀──────────────┘
        │ 审核
        ├─ APPROVE → 条目 APPROVED
        └─ REJECT  → 条目 REJECTED（必填原因）
                        │ 流转（仅 APPROVED）
                        ▼
  生成 text/markdown 文档 → document_version(PARSE) → space_draft 追加
  条目 → PROMOTED（记录 promoted 文档版本与空间）
                        │
                        ▼
  现有：发布管理「提交变更审核」→ 管理员批准 → 发布 → 检索/问答可见
```

条目状态：`PENDING_REVIEW → APPROVED → PROMOTED`，或 `PENDING_REVIEW → REJECTED`。
打分状态（独立字段）：`PENDING → SCORED`，模型不可用时 `UNAVAILABLE`（总分回退为规则分，不阻塞审核）。

## 5. 数据模型（Flyway `V16__cleaning_pipeline.sql`）

沿用项目既有风格：PostgreSQL、UUID 主键、显式外键、`CHECK` 白名单、蛇形命名；按全局规范每个字段加中文注释。**新增文件，不改 V1–V15 任何语句。**

> 下面用行内注释表达字段含义；落地 SQL 必须为每个字段输出一条 `COMMENT ON COLUMN <表>.<字段> IS '…'`（项目 V13/V14 已用该写法），表注释写用途与预估量级。

```sql
-- 清洗批次：一次上传（文件夹集合 + 可选压缩包）对应一行
CREATE TABLE cleaning_batch (
  id                UUID PRIMARY KEY,
  name              VARCHAR(200) NOT NULL,              -- 批次名，默认取压缩包名或「文件夹名-时间」
  target_space_id   UUID REFERENCES space(id),          -- 预选目标空间，可为空，流转时再指定
  created_by        UUID NOT NULL REFERENCES app_user(id),
  status            VARCHAR(24) NOT NULL
    CHECK (status IN ('QUEUED','EXTRACTING','SCORING','READY','PARTIAL','FAILED')),
  model_scoring     BOOLEAN NOT NULL DEFAULT FALSE,     -- 本批次是否执行模型打分
  file_count        INTEGER NOT NULL DEFAULT 0 CHECK (file_count >= 0),
  skipped_count     INTEGER NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),   -- 压缩包内被忽略的条目数
  failed_file_count INTEGER NOT NULL DEFAULT 0 CHECK (failed_file_count >= 0),
  item_count        INTEGER NOT NULL DEFAULT 0 CHECK (item_count >= 0),
  duplicate_count   INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
  error_code        VARCHAR(64),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at       TIMESTAMPTZ
);
COMMENT ON TABLE cleaning_batch IS '数据清洗批次；预估量级：单环境万级以内';
CREATE INDEX ix_cleaning_batch_created ON cleaning_batch(created_at DESC, id);
CREATE INDEX ix_cleaning_batch_target ON cleaning_batch(target_space_id, created_at DESC);

-- 原始来源文件：文件夹里的每个文件、压缩包解出的每个条目
CREATE TABLE cleaning_source_file (
  id            UUID PRIMARY KEY,
  batch_id      UUID NOT NULL REFERENCES cleaning_batch(id) ON DELETE CASCADE,
  ordinal       INTEGER NOT NULL CHECK (ordinal >= 0),
  relative_path VARCHAR(512) NOT NULL,                  -- 保留文件夹层级，用于溯源
  display_name  VARCHAR(255) NOT NULL,
  media_type    VARCHAR(120) NOT NULL,
  size_bytes    BIGINT NOT NULL CHECK (size_bytes >= 0),
  sha256        CHAR(64) NOT NULL,
  object_key    TEXT NOT NULL,                          -- 对象存储里的只读原件
  state         VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','READY','FAILED')),
  record_count  INTEGER NOT NULL DEFAULT 0 CHECK (record_count >= 0),
  error_code    VARCHAR(64),
  UNIQUE (batch_id, ordinal)
);

-- 清洗条目：清洗草稿箱的主体
CREATE TABLE cleaning_item (
  id                          UUID PRIMARY KEY,
  batch_id                    UUID NOT NULL REFERENCES cleaning_batch(id) ON DELETE CASCADE,
  source_file_id              UUID REFERENCES cleaning_source_file(id) ON DELETE SET NULL,
  ordinal                     INTEGER NOT NULL CHECK (ordinal >= 0),
  title                       VARCHAR(240) NOT NULL,
  body                        TEXT NOT NULL,
  body_digest                 CHAR(64) NOT NULL,        -- 归一化正文摘要，批内去重的唯一键
  raw_excerpt                 TEXT,                     -- 清洗前原文前 2000 字，供审核对照
  source_location             VARCHAR(512),             -- 相对路径 + 行号/小节
  rule_score                  SMALLINT NOT NULL CHECK (rule_score BETWEEN 0 AND 100),
  model_score                 SMALLINT CHECK (model_score BETWEEN 0 AND 100),
  total_score                 SMALLINT NOT NULL CHECK (total_score BETWEEN 0 AND 100),
  score_detail                JSONB NOT NULL DEFAULT '{}'::jsonb,  -- 规则各维度与模型理由
  score_state                 VARCHAR(16) NOT NULL CHECK (score_state IN ('PENDING','SCORED','UNAVAILABLE')),
  status                      VARCHAR(16) NOT NULL CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED','PROMOTED')),
  reviewed_by                 UUID REFERENCES app_user(id),
  reviewed_at                 TIMESTAMPTZ,
  review_reason               VARCHAR(2000),
  promoted_space_id           UUID REFERENCES space(id),
  promoted_document_version_id UUID,
  promoted_by                 UUID REFERENCES app_user(id),
  promoted_at                 TIMESTAMPTZ,
  created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (promoted_space_id, promoted_document_version_id)
    REFERENCES document_version(space_id, id),
  CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(review_reason,''))) > 0),
  CHECK (status <> 'PROMOTED' OR (promoted_document_version_id IS NOT NULL AND promoted_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_cleaning_item_batch_digest ON cleaning_item(batch_id, body_digest);
CREATE INDEX ix_cleaning_item_review ON cleaning_item(status, total_score DESC, created_at DESC);
CREATE INDEX ix_cleaning_item_batch ON cleaning_item(batch_id, ordinal);
CREATE INDEX ix_cleaning_item_promoted ON cleaning_item(promoted_document_version_id);

-- 队列：新增清洗与打分两类任务
ALTER TABLE job ADD COLUMN cleaning_batch_id UUID REFERENCES cleaning_batch(id);
-- 约束名先用 information_schema/pg_constraint 确认（默认 job_job_type_check、job_check），
-- 迁移里用 DROP CONSTRAINT IF EXISTS 再重建，避免升级环境命名差异。
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_job_type_check;
ALTER TABLE job ADD CONSTRAINT job_job_type_check CHECK (
  job_type IN ('PARSE_DOCUMENT','INDEX_DOCUMENT','PUBLISH_SNAPSHOT','ANSWER_QUESTION',
               'CLEAN_DATASET','SCORE_CLEANING'));
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_check;
ALTER TABLE job ADD CONSTRAINT job_target_owner_check CHECK (
  ((job_type IN ('PARSE_DOCUMENT','INDEX_DOCUMENT')) AND document_version_id IS NOT NULL)
  OR (job_type = 'PUBLISH_SNAPSHOT' AND publish_request_id IS NOT NULL)
  OR (job_type = 'ANSWER_QUESTION' AND question_id IS NOT NULL)
  OR (job_type IN ('CLEAN_DATASET','SCORE_CLEANING') AND cleaning_batch_id IS NOT NULL));
CREATE UNIQUE INDEX uq_job_cleaning_active ON job(cleaning_batch_id, job_type)
  WHERE job_type IN ('CLEAN_DATASET','SCORE_CLEANING') AND status IN ('QUEUED','RUNNING');

COMMENT ON COLUMN job.cleaning_batch_id IS '清洗类任务的批次；其他任务类型为空';
```

> 实现时省略计划里的 `duplicate_of`：重复条目直接跳过并计入 `duplicate_count`，留一列永远为 NULL 的死字段没有意义。

索引理由：批次按时间倒序列出；条目审核列表按 `status + total_score` 排序；批内去重靠唯一索引兜住并发；`promoted_document_version_id` 支撑「这份文档来自哪些清洗条目」的回查。

## 6. 后端设计

新增包 `dev.aiqa.cleaning`：

| 文件 | 职责 |
| --- | --- |
| `CleaningRepository.java` | 批次/来源文件/条目的 JdbcClient 读写，镜像 `ImportRepository` 的写法 |
| `CleaningService.java` | 上传受理：大小与数量校验、文件夹相对路径、压缩包安全解出、落对象存储、幂等、入队 |
| `SafeZip.java` | `ZipInputStream` 解包：拒绝 `..`/绝对路径/盘符、单条目 10 MB、条目数 ≤ 2000、解压总量 ≤ 200 MB、压缩比异常保护；跳过目录与加密条目 |
| `RawDatasetReader.java` | 按扩展名读成 `RawRecord(text, location)`：`.txt/.md` 分段、`.csv/.tsv` 行、`.json/.jsonl` 记录、`.html/.htm` 去标签、`.xlsx` 用既有 POI 读行、`.pdf/.docx/.pptx` 复用 `TextExtractor`；其余扩展名记为被忽略（只累计计数，不建来源记录） |
| `TextCleaner.java` | NFC 归一、BOM/控制字符/零宽字符清理、空白折叠、HTML 残留与实体解码、重复标点合并；返回清洗后正文与噪声标记 |
| `ItemSplitter.java` | 标题提炼（首行或首句，≤240 字）、正文 20–4000 字（超长按句子边界再拆）、过短合并、批内摘要去重 |
| `RuleScorer.java` | 规则分 0–100：长度 20 / 完整性 20 / 噪声 20 / 信息量 20 / 结构 20，明细写入 `score_detail` |
| `CleaningWorker.java` | `CLEAN_DATASET` 任务：读来源文件 → 拆条 → 去重 → 规则分 → 落条目；更新批次与来源状态；有模型时再入队打分 |
| `CleaningModelScorer.java` | 复用 `ModelRelevanceJudge` 的鉴权与 JSON 解析写法，取 `ModelConfigRepository.findDefaultConfig()`，按协议发一行 JSON 打分请求 |
| `CleaningScoreWorker.java` | `SCORE_CLEANING` 任务：分页取 `score_state = PENDING` 的条目，每批 20 条调用一次；失败置 `UNAVAILABLE`，总分回退规则分 |
| `CleaningPromotionService.java` | 生成 Markdown、写对象存储、插 `document_version`、`enqueueParse`、追加空间草稿、回写条目状态、审计 |
| `api/CleaningController.java` | 全部清洗接口，`/api/v1/cleaning/**` |

修改的既有文件（只加不改语义）：

| 文件 | 改动 |
| --- | --- |
| `persistence/JobRepository.java` | `Kind` 增加 `CLEAN("CLEAN_DATASET",5)`、`CLEAN_SCORE("SCORE_CLEANING",6)`；构造参数加容量/并发；`Job` 记录加 `cleaningBatchId` 与 `RETURNING` 列；新增 `enqueueCleaning`、`enqueueCleaningScore` |
| `draft/DraftService.java` | 新增 `appendGeneratedDocument(userId, spaceId, documentVersionId, policyState, from, until)`：`lockSpace → ensure → load → 追加 → replace(原 revision) → bumpState`，供流转调用 |
| `resources/application.yml` | `spring.servlet.multipart.max-file-size` 10MB → 64MB（压缩包需要；单文件 10MB 仍由业务代码约束）；新增 `aiqa.cleaning.*` |
| `common/ErrorCode.java` | 只加需要前端区分的 `CLEANING_ARCHIVE_REJECTED`；状态类冲突复用 `STATE_CONFLICT` |
| `OpenApiContractTest.java` | `CONTROLLERS` 加入 `CleaningController` |
| `docs/ai-qa-openapi.yaml` | 新增 7 个路径与 4 组 schema |

### 6.1 接口契约

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/cleaning/batches` | 系统管理员 | multipart：`files[]`、`paths[]`（与 files 同序，文件夹上传的相对路径）、`archive`（可选）、`name`、`targetSpaceId`；必带 `Idempotency-Key`；`202 + Location` |
| `GET` | `/api/v1/cleaning/batches` | 系统管理员 | `status`、`limit`、`offset`；返回 `PageView<CleaningBatchView>` |
| `GET` | `/api/v1/cleaning/batches/{batchId}` | 系统管理员 | 批次详情 + 来源文件状态与失败原因 |
| `GET` | `/api/v1/cleaning/items` | 系统管理员 | `status`、`batchId`、`targetSpaceId`、`minScore`、`q`、`limit`、`offset` |
| `GET` | `/api/v1/cleaning/items/{itemId}` | 系统管理员 | 正文、原文摘录、评分明细、来源、审核与流转记录 |
| `POST` | `/api/v1/cleaning/items/review` | 系统管理员 | `{itemIds[], decision: APPROVE\|REJECT, reason}`；驳回必填原因；Idempotency-Key |
| `POST` | `/api/v1/cleaning/promotions` | 系统管理员 | `{itemIds[], spaceId, documentName?, policyState?, effectiveFrom?, effectiveUntil?, reason}`；只接受 `APPROVED`；返回 `documentVersionId`、`documentName`、`versionNumber`、`draftRevision` |
| `POST` | `/api/v1/cleaning/batches/{batchId}/rescore` | 系统管理员 | 对 `score_state = UNAVAILABLE` 的条目重跑模型打分；网关恢复后的补偿入口 |

约定：响应走既有 `ApiSuccessAdvice` 信封；列表用 `PageQuery`/`PageView`；写接口 `limiter.checkWrites`；审核与流转写 `audit_event`（`CLEANING_ITEMS_REVIEWED`、`CLEANING_ITEMS_PROMOTED`）；错误统一 `application/problem+json`。

注意：`OpenApiContractTest.normalize` 只做斜杠归一，**不做占位符归一**，契约里的 `{batchId}`、`{itemId}` 必须与控制器 `@PathVariable` 路径字面完全一致（含大小写与占位符名），否则测试会报「实现存在但契约未声明」。

### 6.2 打分规则

规则分（0–100，明细进 `score_detail`）：

| 维度 | 权重 | 判定 |
| --- | --- | --- |
| 长度 | 20 | 80–1200 字满分；20–79 或 1201–4000 线性降分 |
| 完整性 | 20 | 以句号/问号/分号/冒号结尾，或含「问/答」「步骤/条件」等结构词 |
| 噪声 | 20 | HTML 残留、替换字符 `�`、连续重复标点、空白占比 > 30%、重复行按比例扣分 |
| 信息量 | 20 | 数字、日期、金额、期限、专有名词命中数（0–4 档） |
| 结构 | 20 | 有标题、有序/无序列表、表格行、问答标记 |

模型分：单条一次调用，提示语要求只输出 `{"score":0-100,"reason":"≤40字"}`；返回解析失败、超时、HTTP 错误一律 `UNAVAILABLE` 并记录原因。批次清洗结束时若 `aiqa.cleaning.model-score=off` 或没有可用默认模型，条目直接置 `UNAVAILABLE`（原因「未启用模型打分」），不会长期停在 `PENDING`。

总分：模型分可用时 `round(0.4*规则分 + 0.6*模型分)`，否则等于规则分，界面标注「仅规则分」。权重以语义常量集中定义，不做无意义的配置化。

### 6.3 流转实现要点

1. 校验：条目全部 `APPROVED`、未被流转、`spaceId` 存在且调用者可维护（管理员天然满足）。
2. 生成内容：
   ```markdown
   # 清洗知识 · {批次名}

   ## {条目标题}
   {正文}

   ## {下一条目标题}
   ...
   ```
   `##` 会被 `TextExtractor` 切成独立 chunk，`source_location` 即条目标题，引用可定位到条目级。
3. 写对象存储并插 `document_version`：`media_type = text/markdown`、`scan_state = 'PENDING'`（自产内容不过文件扫描；解析 worker 会跳过 CLEAN，写入后正文会一直为空）、`parse_state = 'QUEUED'`、`version_number = 1`、新 `document_id`；默认文件名 `清洗知识-{批次短号}-{yyyyMMddHHmm}.md`，重名时追加序号。
4. `jobs.requireCapacity(PARSE, 1)` + `enqueueParse`。
5. `DraftService.appendGeneratedDocument(...)` 追加进空间草稿，`policyState` 默认 `EFFECTIVE`，生效区间可为空。
6. 回写条目 `PROMOTED` + `promoted_*`，写审计，返回 `draftRevision` 供前端提示「已进入该空间草稿」。

失败回退：对象已写但事务回滚时删除对象（沿用 `ImportService` 的 `freshKeys` 清理思路）；解析失败的文档仍进草稿，发布时由现有「阻塞文档」提示，管理员可重试索引或驳回。

### 6.4 新增配置项（`application.yml`）

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 64MB        # 压缩包需要；普通文档 10MB 仍由业务代码校验
aiqa:
  cleaning:
    # 模型打分开关：on=有默认模型时调用；off=只出规则分；fake=固定分（隔离 E2E）
    model-score: ${CLEANING_MODEL_SCORE:on}
    model-timeout: ${CLEANING_MODEL_TIMEOUT:PT20S}
    max-files: ${CLEANING_MAX_FILES:500}
    max-file-bytes: ${CLEANING_MAX_FILE_BYTES:10485760}
    max-batch-bytes: ${CLEANING_MAX_BATCH_BYTES:209715200}
    max-archive-bytes: ${CLEANING_MAX_ARCHIVE_BYTES:67108864}
    max-archive-entries: ${CLEANING_MAX_ARCHIVE_ENTRIES:2000}
  worker:
    capacity:
      clean: ${WORKER_CLEAN_CAPACITY:200}
      clean-score: ${WORKER_CLEAN_SCORE_CAPACITY:200}
    concurrency:
      clean: ${WORKER_CLEAN_CONCURRENCY:1}
      clean-score: ${WORKER_CLEAN_SCORE_CONCURRENCY:1}
```

## 7. 前端设计

### 7.1 设计基线：沿用现有页面语言，不新建视觉体系

- **页面骨架**：`PageHeader` + 筛选条 + `bk-table-wrap` 表格 + `PaginationBar`，与知识库、发布管理同构。
- **浮层**：详情用 `DrawerPanel`（右侧抽屉），上传/审核/流转用 `ModalShell`，统一包 `OverlayTransition` 拿进退场动画。
- **按钮语义**：严格按[操作按钮语义分主题](./ai-qa-change-2026-11-26-button-variants.md)——查看 `info`、通过 `success`、驳回 `danger`、上传与流转 `primary`、刷新 `neutral`（页头）/`ghost`（图标）、清除选择 `ghost`。
- **配色**：只用 `theme.css` 既有 token；不新增全局变量，不写裸色值。
- **不做的**：不引图表库（分数用 CSS 条，不用 ECharts）、不做拖拽上传（与现有导入弹窗一致）、不改造知识库页、不重构 `ImportDialog` 的局部上传样式。

### 7.2 信息架构：两个页签，一次只面对一张表

上传与审核是两件不同的事，堆在一页会变成两张表抢注意力。默认停在「草稿箱」，因为管理员的日常动作是审核。

```
数据清洗                                        [刷新] [上传原始数据]

[ 清洗草稿箱 (12) ] [ 清洗批次 ]                       ← .bk-tabs

关键词[________] 状态[全部▾] 批次[全部▾] 空间[全部▾] 分数[不限▾]
                                              共 12 条（已筛选）

┌──┬─────────────────────────┬──────┬────────┬──────────┬─────────────┐
│☐ │ 条目                    │ 总分 │ 状态   │ 目标空间 │ 操作        │
├──┼─────────────────────────┼──────┼────────┼──────────┼─────────────┤
│☐ │ 差旅住宿标准            │ 82   │ 待审核 │ 未指定   │ 查看 通过 驳回│
│  │ 差旅/住宿.csv · a1b2c3  │ ▓▓▓▓ │        │          │             │
│  │ 规则 72 · 模型 86       │      │        │          │             │
└──┴─────────────────────────┴──────┴────────┴──────────┴─────────────┘
 已选 2 条   [通过] [驳回] [流转到知识库] [清除选择]   ← 选中后才出现

                                            第 1 / 2 页 [10 条/页 ▾]
```

「清洗批次」页签：

```
ℹ 每批最多 500 个文件、单文件 10 MB、整批 200 MB；压缩包会解出其中的文本与表格文件。

┌────────────────┬────────┬──────────┬────────────┬────────┬────────────┐
│ 批次           │ 状态   │ 文件/失败│ 条目/去重  │ 平均分 │ 操作       │
├────────────────┼────────┼──────────┼────────────┼────────┼────────────┤
│ 客服语料 11-26 │ 已完成 │ 128 / 2  │ 964 / 37   │ 78     │ 查看       │
│ 1a2b3c4d       │        │          │            │        │            │
└────────────────┴────────┴──────────┴────────────┴────────┴────────────┘
```

### 7.3 弹窗与抽屉

**上传弹窗（`ModalShell` 560px）**

```
┌ 上传原始数据 ──────────────────────────── × ┐
│ ┌─────────────────────────────────────────┐ │
│ │  选择要清洗的原始数据                    │ │
│ │  支持 TXT/MD/CSV/TSV/JSON/JSONL/HTML/   │ │
│ │  XLSX/PDF/DOCX/PPTX；其余文件自动忽略    │ │
│ │  [选择文件夹] [选择压缩包]               │ │
│ └─────────────────────────────────────────┘ │
│ 已选 128 个文件 · 42.6 MB                    │
│ 客服语料/txt/faq-01.txt          3.2 KB  ×   │
│ 客服语料/csv/refund.csv         18.4 KB  ×   │
│ 批次名 [客服语料-1126        ]               │
│ 目标空间 [可留空，流转时再选 ▾]              │
│ 上传中 43% ▓▓▓▓▓░░░░░░░░░░░                 │
│                          [取消] [开始清洗]   │
└──────────────────────────────────────────────┘
```

两种入口各自保留来源信息：文件夹上传把 `File.webkitRelativePath` 作为 `paths[]` 一并提交，后端据此保留层级；压缩包只接受 `.zip`，解包在后端完成，前端不读压缩内容。

**条目抽屉（`DrawerPanel` 880px）**

```
┌ 「差旅住宿标准」· 条目详情 ─────────────────── × ┐
│ 批次 客服语料-1126 · a1b2c3 · 来源 差旅/住宿.csv:12 │
│ ┌ 总分 82 ┐ ┌ 规则 72 ┐ ┌ 模型 86 ┐               │
│ │ 建议通过│ │ 5 项明细 │ │ 理由：… │               │
│ ├───────────────────────┴───────────┴───────────┤
│ │ 清洗前原文摘录        │ 清洗后正文              │
│ │ 住宿标准：一线城市…   │ 住宿标准：一线城市…     │
│ ├────────────────────────────────────────────────┤
│ │ 评分明细：长度 18 · 完整性 20 · 噪声 14 …      │
│ │ 审核记录：未审核 · 流转记录：未流转             │
│ ├────────────────────────────────────────────────┤
│ │                 [驳回] [通过] [流转到知识库]   │
└──────────────────────────────────────────────────┘
```

**审核弹窗**：通过为轻确认（原因可填），驳回必填原因（`FormTextarea` + `FORM_LIMITS.reviewReason`，空值前端拦截并提示）。危险弹窗把「取消」放首位并用 `initial-focus="first-action"`，焦点默认落在取消上。

**流转弹窗（`ModalShell` 620px）**：已选条数 + 目标空间（`SelectMenu`）+ 文档名（`FormInput`，默认「清洗知识-{批次短号}-{yyyyMMdd}」，后缀 `.md` 只读展示）+ 政策状态（`FormRadioGroup`：现行/已失效/待核实）+ 生效区间（两个日期输入，沿用 `validCalendarDate` 校验起止）+ 说明（可选）。提交成功后关闭弹窗、刷新列表，并给一条带 `router.push('/publishing')` 的提示。

### 7.4 组件清单

复用（不改动）：`PageHeader`、`DataTable`、`ModalShell`、`DrawerPanel`、`OverlayTransition`、`FormInput`、`FormTextarea`、`FormRadioGroup`、`SelectMenu`、`PaginationBar`、`usePagedList`、`pollUntil`、`describeError`/`formatBytes`/`formatDateTime`。

新组件：

| 文件 | 类型 | 职责 |
| --- | --- | --- |
| `views/CleaningView.vue` | 页面 | 页签、筛选、两张表、批量选择与动作、审核弹窗、成功提示 |
| `components/CleaningUploadDialog.vue` | 业务型 | 文件夹/压缩包选择、已选清单、批次名与目标空间、上传进度 |
| `components/CleaningItemDrawer.vue` | 业务型 | 清洗前后对照、评分明细、审核与流转记录、底部动作 |
| `components/CleaningPromotionDialog.vue` | 业务型 | 目标空间、文档名、政策状态与区间、提交 |
| `components/ScoreMeter.vue` | 展示型（不碰 store） | 分数数字 + 配色 + 细条，表格与抽屉共用，避免三处各写一套 |

改动：

| 文件 | 改动 |
| --- | --- |
| `components/FormFileInput.vue` | 增加 `directory?: boolean`（写入 `webkitdirectory`）与 `change` 事件附带 `paths`；现有调用不变 |
| `api/cleaning.ts`（新） | 8 个接口封装 + `CleaningBatch`/`CleaningItem` 类型 |
| `api/display.ts` | 增加 `cleanStatusLabel`/`scoreTone`/`scoreSourceLabel` |
| `navigation.ts` / `router.ts` | 工作区「数据清洗」→ `/cleaning`，`adminOnly` 收录 |

### 7.5 样式：只加页面级局部类，不外扩全局

| 类 | 用途 | 来源 |
| --- | --- | --- |
| `.filter-bar` / `.empty-cell` / `.row-actions` / `.nowrap` | 筛选条、空单元格、行内按钮并排 | 与知识库页同名同义（项目既有做法是页面级重复定义） |
| `.selection-bar` | 选中后的操作条：`--color-surface-selected` 底、`--color-border` 描边、`--radius-control` | 新增局部 |
| `.score-cell` / `.score-value` / `.score-sub` / `.score-bar` | 分数数字、规则/模型小字、进度条；条宽用内联 `width`，颜色取 `--color-success/warning/danger` | 新增局部 |
| `.compare-grid` / `.excerpt` / `.cleaned-body` | 抽屉内左右对照，`pre-wrap + anywhere + max-height: 320px` 内滚动 | 新增局部 |
| `.dropzone` / `.picked-list` | 上传弹窗内的选择区与已选清单 | 新增局部（不抽公共类，第三处上传区出现时再提取） |

不新增全局 token：分数三档直接复用 `--color-success` / `--color-warning` / `--color-danger`，底色用 `color-mix` 与 `--color-surface` 混合，与 `.bk-notice-*` 的既有做法一致。

### 7.6 交互、状态与边界

| 场景 | 处理 |
| --- | --- |
| 列表加载 / 翻页 / 筛选 | `usePagedList`，筛选变化 `reset()`，翻页自动取消上一请求 |
| 空态 | `DataTable` 的 `rows` + `empty-text/hint`，并提供「上传原始数据」空态操作 |
| 批次处理中 | 对 `QUEUED/EXTRACTING/SCORING` 的批次 `pollUntil`（2s 间隔、60s 预算），只更新那一行并显示「正在刷新状态…」；组件卸载即 abort |
| 多选 | 本页全选（表头 checkbox 支持 `indeterminate`）；翻页/筛选后清空选择，避免跨页操作看不见的条目 |
| 批量动作 | 提交中禁用按钮并显示「处理中…」；成功后关闭弹窗、清空选择、`reload()` |
| 驳回未填原因 | 前端先拦（提示 + 聚焦输入框），不请求接口 |
| 流转含未通过条目 | 按钮禁用并提示「只有已通过的条目可以流转」 |
| 版本/状态冲突 | `needsVersionRefresh` 或 409 时提示刷新后重试，不静默重放写请求 |
| 上传超限 | 提交前按 500 个/10 MB/200 MB 校验，超限直接提示，不发请求 |
| 刷新页面 | 服务端是唯一真相，选择与展开状态不持久化 |

### 7.7 无障碍与响应式

- 表头全选与每行 checkbox 都有可读 `aria-label`（含条目标题）；选择条用 `role="status"` 播报「已选 N 条」。
- 分数不只靠颜色表达，文本写成「总分 82 分（规则 72，模型 86）」；状态用中文徽章。
- 抽屉与弹窗沿用公共组件的焦点管理与 Esc 关闭；危险操作默认焦点在「取消」。
- ≤1100px：筛选条自动换行，表格横向滚动（`min-width: 920px`）。
- ≤900px：抽屉内左右对照改单列；≤420px：页头按钮与选择条各占一行，按钮保持 `--control-height` 触控高度。
- 深色主题逐项检查分数条、选中行与徽章对比度，不新增亮色残留。

### 7.8 开发顺序

1. `api/cleaning.ts` + `display` 帮手 + 路由/导航（可先用 mock 渲染）。
2. 上传弹窗 + 批次页签 + 轮询。
3. 草稿箱表格 + 筛选 + 分页 + 多选。
4. 条目抽屉 + 审核弹窗。
5. 流转弹窗 + 成功跳转。
6. 联调、暗色/窄屏截图、`lint` + `typecheck`。

每步完成即以真实接口联调一次，不做「全部写完再联调」。

## 8. 实施阶段与验收

### M1 后端管线骨架（不含模型分）

- 交付（**2026-11-26 已完成**，迁移实际为 V17，见变更记录）：上传/批次接口；`SafeZip`、`RawDatasetReader`、`TextCleaner`、`ItemSplitter`、`RuleScorer`；`CLEAN_DATASET` worker；契约与错误码。
- 验证：`cd backend && mvn -o -DskipTests compile`；`mvn -o test`（`IT_DB_*` 指向隔离库）中新增
  `CleaningRulesTest`（清洗/拆分/去重/打分边界）、`SafeZipTest`（路径穿越、条目数超限、压缩比异常、加密条目）；
  `SchemaMigrationTest` 把三张新表加入 `information_schema` 断言清单，并同步期望值 `7 → 10`；
  `OpenApiContractTest` 通过。
- 通过标准：上传一个混合数据集后，批次转 `READY`，条目数、去重数、失败文件数与实际一致；恶意压缩包被整体拒绝且不落任何对象。

### M2 模型打分

- 交付（**2026-11-26 已完成**）：`CleaningModelScorer`、`SCORE_CLEANING` worker、`aiqa.cleaning.model-score=on|off|fake`、`POST /cleaning/batches/{batchId}/rescore`。
- 验证：`CleaningModelScorerTest` 覆盖一行 JSON 解析、越界分数钳制、超时/HTTP 失败降级；本地用真实网关跑一条，记录 Tokens 与实际耗时。
- 通过标准：网关不可用时批次仍 `READY`，条目 `score_state = UNAVAILABLE`，总分等于规则分；网关恢复后重打分成功。

### M3 审核与流转

- 交付（**2026-11-26 已完成**）：审核与流转接口、`DraftService.appendGeneratedDocument`、生成文档与 `document_version` 写入、审计。
- 验证：`CleaningFlowIT`（真实 PostgreSQL）：上传 → 清洗 → 审核 → 流转 → 断言 `space_draft_document` 新增条目、`document_version` 为 `text/markdown`、条目状态 `PROMOTED`；再走 `POST /publish-requests` + 批准 + 发布后，`GET /search` 能命中该条目小节。
- 通过标准：驳回不带原因返回 422；重复流转返回 409；流转失败（如对象存储异常）不留下半条草稿记录。

### M4 前端页面

- 交付：`CleaningView.vue`、上传弹窗、详情抽屉、流转弹窗、导航与路由、API 层。
  **2026-11-26 已完成**：见[前端变更记录](./ai-qa-change-2026-11-26-cleaning-frontend.md)。
- 验证：`cd frontend && npm run lint && npm run typecheck`；浏览器实测上传 → 列表 → 审核 → 流转 → 跳发布管理；深色主题与 390 px 窄屏检查截图。
- 通过标准：筛选/分页与接口入参一致；批量操作按钮禁用态正确；刷新页面后状态可恢复（服务端为唯一真相）。

### M5 端到端验收与文档

- 交付：`frontend/e2e/cleaning.mjs` 并在 `e2e/run.mjs` 注册；`docs/ai-qa-change-2026-11-26-cleaning-pipeline.md`；更新 `ai-qa-status.md`、`ai-qa-openapi.yaml`；本计划文档状态改为「已实施」。
- 验证命令：
  ```bash
  cd backend && mvn -o test                     # 需 IT_DB_URL/IT_DB_USER/IT_DB_PASSWORD
  cd frontend && npm run lint && npm run typecheck && npm run build
  cd frontend && npm run test:e2e               # 或按 ai-qa-e2e-plan.md 起本地栈
  ```
- 证据要求（按全局规范第 13 条）：实际接口响应（上传 202、批次详情、审核 200、流转 201）、页面截图、
  `space_draft_document` 与 `document_chunk` 的行数变化、发布后 `/search` 命中片段。

预计工作量：后端 4–6 人日，前端 2–3 人日，验收与文档 1 人日。

## 9. 风险与待确认

| # | 事项 | 现状与建议 | 影响 |
| --- | --- | --- | --- |
| 1 | 谁能上传原始数据 | 计划先按「仅系统管理员」实现；若空间维护者也要能上传，需要放开路由并在后端按 `created_by`/目标空间过滤 | 前端路由与后端过滤各一处，约 0.5 人日 |
| 2 | 打分 Tokens 是否计量 | `model_call_usage` 强绑 `question_id`，清洗打分无法复用；本期记为「不入统计」，若要计量需新增 `model_call_usage_batch` 类表 | 影响统计口径，不影响主流程 |
| 3 | 压缩包大小 | `spring.servlet.multipart.max-file-size` 需 10MB → 64MB，否则稍大的包直接 413；业务层仍限普通文档 10MB | 配置变更，需在部署说明同步 |
| 4 | 非 UTF-8 文本 | 不引字符集探测依赖，非 UTF-8 文件标记 `ENCODING_UNSUPPORTED` 并提示另存为 UTF-8 | 可能有一部分源数据需人工转码 |
| 5 | 生成文档的粒度 | 一次流转生成一份文档，条目为 `##` 小节；条目很多时文档会很大（受 4000 字/块切分保护） | 如需「一条一文档」要改为多次生成+多次入草稿 |
| 6 | 前端单元测试 | 仓库无 vitest 等单测框架，现验收方式为 lint/typecheck/E2E；补单测需新增依赖 | 需你确认是否加依赖，否则不写单测 |
| 7 | 术语混淆 | 「清洗草稿箱」与空间「草稿」是两件事，界面文案必须区分 | 只影响文案，不影响实现 |
| 8 | 已有数据 | 新表从空开始，无需回填；`job` 新增列对历史任务无影响 | 无迁移风险 |

## 10. 相关文档

- [实施状态](./ai-qa-status.md)
- [首期技术方案](./ai-qa-technical-design.md)
- [后端详细设计](./ai-qa-backend-design.md)
- [OpenAPI 契约](./ai-qa-openapi.yaml)
- [端到端计划](./ai-qa-e2e-plan.md)
