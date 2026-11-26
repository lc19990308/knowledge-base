# 变更记录：AI 辅助生成回答模板 + 示例按钮图标（2026-11-26）

## 背景

回答模板页已提供格式示例与复制/下载；本次按 [AI 辅助生成方案](./ai-qa-ai-template-generation-plan.md) 落地首期能力：管理员描述场景与要求，异步生成一份可直接使用的模板正文；同时给「复制内容」「下载 .txt」补上图标。

## 实现过程

### 1. 数据库（`backend/src/main/resources/db/migration/V16__ai_template_generation.sql`）

- 新增 `answer_template_generation`：状态 `QUEUED/RUNNING/SUCCEEDED/FAILED`、结果正文、用量（NULL 表示未上报）、失败码；`(requested_by, request_key)` 唯一约束承担幂等；状态与结果/失败码/完成时间用 CHECK 约束成互斥组合。
- 反范式快照字段（写后不可变，理由与一致性维护写入字段注释）：`requested_by_name`、`model_alias`、`reference_template_name`、`reference_template_revision`、`prompt_text`，加 `result_body` 只成功时写一次。
- `job` 新增 `template_generation_id` 列，重建 `job_job_type_check` / `job_check` 加入 `GENERATE_TEMPLATE`，并建活跃任务唯一索引 `uq_job_generation_active`。
- 所有字段带中文 `COMMENT ON`；约束名按 PostgreSQL 默认命名，变更前已用 `IF EXISTS` 容错。

### 2. 后端

| 文件                                         | 实现                                                                                                                                  |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `template/TemplatePromptBuilder.java`        | 固定系统提示 + 目标格式 + 需求分隔符（声明为数据非指令）+ 参考模板快照；变量白名单说明                                                |
| `template/TemplateOutputValidator.java`      | 剥 markdown 围栏、去空白、长度 1–20000、变量只允许 `{question}/{references}/{validity}`，不合规抛不可重试失败                         |
| `template/TemplateGenerationModel.java`      | 独立非流式调用，支持 `openai_compatible` 与 `anthropic`；超时/不可达/429 归一为可重试失败码，用量未上报保持 NULL                      |
| `template/TemplateGenerationRepository.java` | 建记录、并发/每日计数、`markRunning`/`requeue`/`succeed`/`fail` 全部 CAS，快照字段不参与更新                                          |
| `template/TemplateGenerationService.java`    | 校验（mode、场景 ≤240、需求 10–2000）、默认模型解析、配额（并发 3 / 24h 20 次）、Idempotency-Key、审计、事务内写记录 + 入队           |
| `worker/TemplateGenerationWorker.java`       | 领取 `GENERATE_TEMPLATE`，CAS 标记 RUNNING，调用模型 + 校验后写结果；可重试失败退回队列（最多 2 次），不可重试直接 FAILED；审计与指标 |
| `persistence/JobRepository.java`             | 新增 `Kind.GENERATE_TEMPLATE`、容量/并发参数、`enqueueGeneration`、Job 记录新增 `templateGenerationId`                                |
| `api/AnswerTemplateController.java`          | `POST /answer-templates/generations`（202 + Location）、`GET /answer-templates/generations/{id}`                                      |
| `docs/ai-qa-openapi.yaml`                    | 补齐两个路由与 `AnswerTemplateGenerationInput` / `AnswerTemplateGeneration` schema                                                    |

### 3. 前端

- `api/knowledge.ts`：新增 `TemplateGeneration` 类型、`createTemplateGeneration`（自动 Idempotency-Key）、`getTemplateGeneration`。
- 新增 `components/TemplateGenerationDialog.vue`：**独立于新建/编辑模板弹窗**的辅助工具——页面级「AI 生成模板」打开，填目标格式 radio、使用场景、需求描述（计数 0/2000）、模型下拉（`/model-configs/options`，默认模型优先）；提交后轮询 1.5s（上限 120s），关闭/卸载即中止；失败码映射中文提示；成功后展示 `pre-wrap` 预览与用量，提供「复制内容」（2 秒反馈、失败提示、`role=status` 播报）与「重新生成」。不提供「应用到编辑器」，也不读取当前模板作为参考。
- 生成结果不落任何模板：用户复制后自行粘贴到自己的「模板正文」，保存仍走原有新建/编辑与 ETag 流程。
- `AnswerTemplatesView.vue`：页面头部新增「AI 生成模板」入口（与「格式示例」「新建模板」并列）；示例「复制内容 / 下载 .txt」补 `@lucide/vue` 的 `Copy` / `Download` 图标（14px，`aria-hidden`）。

## 验证命令与结果

- 后端：`mvn -q -Dtest='TemplatePromptBuilderTest,TemplateOutputValidatorTest,TemplateGenerationModelTest' test` 通过（含 OpenAI 兼容/Anthropic 响应解析、429 可重试、围栏清理、变量白名单）。
- 后端编译：`mvn -q -DskipTests compile` 通过。
- 前端：`npm run typecheck`、`npx eslint`（改动的 3 个文件）、`prettier --check` 通过；整仓 `npm run lint` 通过。
- 浏览器实测（Chrome / Playwright，页面真实登录，生成接口用 `page.route` 桩数据，模型选项用桩或真实配置）：
  - 入口：页面头部「AI 生成模板」，打开独立弹窗；弹窗内无「基于当前模板改写」开关，说明文案明确「复制后粘贴到模板正文」。
  - 成功链路：提交后状态依次「已排队…→ 模型正在生成…→ 生成完成」，展示 280/160 tokens 与正文预览；**不存在「应用到编辑器」按钮**；点「复制内容」后按钮变「已复制」，读回剪贴板与预览完全一致。
  - 失败链路（上一轮桩验证）：`FAILED / INVALID_MODEL_OUTPUT` 时徽标「生成失败」并提示「模型返回内容不符合模板格式，请重新生成。」。
  - 窄屏 390×844：弹窗左右各 16px、完整落在视口内（整页 406px 为既有顶栏溢出）。
  - 图标：示例卡「复制内容 / 下载 .txt」各 1 个 svg 图标，样式正常。
  - 截图：`docs/e2e-results/ai-template-generation-form-2026-11-26.png`、`-result-2026-11-26.png`、`-copied-2026-11-26.png`、`-narrow-2026-11-26.png`、`docs/e2e-results/answer-template-examples-icons-2026-11-26.png`。

## 未验证 / 遗留

- 生成结果只支持复制，不提供下载（与「格式示例」不同，模板正文通常直接粘进编辑器）。
- **未接真实模型网关验证**：本机后端进程没有配置 `aiqa.model.credentials.*`，生成接口在没有任何可用模型配置时按设计返回 409；真实协议解析只由 `TemplateGenerationModelTest` 的本地 HTTP 替身覆盖。
- **V16 迁移未在真实 PostgreSQL 执行**：仅通过编译与 SQL 审阅，未跑 Flyway；上线前需在测试库执行并核对 `job` 约束名与回滚语句。
- **OpenApiContractTest 当前失败**来自另一会话新增的 `POST /spaces/{spaceId}/documents/{documentVersionId}/versions` 尚未写入 OpenAPI（`DocumentController`），与本次两条新路由无关；等对方补齐后整仓契约测试即可恢复。
- 生成历史列表（`GET /generations`）首期未做；失败后只能重新生成。
- 后端生成接口仍保留 `referenceTemplateId`（以既有模板改写）能力，本期 UI 未使用，后续若要「基于现有模板润色」可直接接上。
