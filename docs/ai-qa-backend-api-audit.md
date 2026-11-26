# 后端 API 审核报告

## 当前源码核对（2026-11-23）

当前 `/api/v1` 契约有 **49 条路径、62 个操作**，由 12 个控制器和 Spring Security 的登出处理提供。范围包括本地账号和企业 OIDC 入口、模型配置和问答可选模型、首个问题自动建会话、会话归档与软删除、空间用量、引用预览及原件下载。Actuator 和 OIDC 跳转回调由应用单独提供。接口契约见 [OpenAPI](./ai-qa-openapi.yaml)。

| 核对项 | 当前代码事实 |
| --- | --- |
| 登录与管理 | CSRF 令牌接口可匿名访问。写请求携带 `X-CSRF-TOKEN` 及对应 `XSRF-TOKEN` Cookie。本地账号的创建、查询和访问配置更新都返回扁平 `AccessView`。 |
| 草稿与发布 | 草稿编辑、草稿预览、审核快照分别使用独立结构。提交可携带原因，审核详情 ETag 使用空间草稿版本。撤回响应省略已清空的发布指针，空间详情仍显式返回 `null`。 |
| 模型与证据 | 默认模型支持 OpenAI 兼容和 Anthropic，省略提问模型且缺少可用的默认网关配置时可回退假模型。已登录用户可获取精简模型选项并按次指定 `modelId`，V11 持久化所选 ID。V10 冻结回答实际使用的片段和位置，引用预览区分 `CHUNK` 和 `DOCUMENT`。 |
| 已知实现边界 | `contextLimit` 和 `usageReportEnabled` 目前只保存并回显。worker 固定最多选 20 个正文块，成功生成且证据重检通过后按适配器返回记用量。问答生成仅使用当前问题和本次证据。 |

本节依据控制器、服务和迁移静态核对。当前联调结果见 [实施状态](./ai-qa-status.md)。

## 历史审核范围

- 对象为历史审核时的后端、OpenAPI、PRD v0.5、前端和原型。第 0–8 节及第 10 节保留初审记录，第 9 节保留当时的修复和回归证据。
- 初审时实现与契约各有 46 个操作，双方各有一项差集。历史测试数量、缺口、修复及真实响应仅对应当时的版本。

---

## 0. 结论摘要

| 类别 | 数量 | 是否阻塞 |
| --- | --- | --- |
| A. 契约与实现不一致 | 5 | 阻塞客户端契约校验，须修 |
| B. PRD 要求但未真正实现 | 4 | AC02/AC04 无法通过验收 |
| C. 原型/前端需要但后端无数据 | 11 | 部分页面只能显示占位 |
| D. 接口设计问题 | 4 | 影响性能与可维护性 |

---

## 1. 审计方法与证据

```bash
# 1) 接口清单双向比对（控制器注解 vs OpenAPI paths）
python3 - <<'PY'   # 解析 @Get/Post/...Mapping 与 yaml paths 求差集
PY

# 2) 真实响应 vs 契约必填字段
curl -s -H 'X-Dev-Subject: smoke-admin' http://127.0.0.1:8080/api/v1/spaces
curl -s -H 'X-Dev-Subject: smoke-admin' http://127.0.0.1:8080/api/v1/publish-requests
curl -s -H 'X-Dev-Subject: smoke-admin' http://127.0.0.1:8080/api/v1/settings/global

# 3) PRD 规则追代码
grep -rn "insertParagraph" backend/src/main/java/dev/aiqa/answer/AnswerService.java
grep -rn "recordUsage"     backend/src/main/java/dev/aiqa/answer/AnswerService.java
grep -rn "UPDATE assistant" -A4 backend/src/main/java/dev/aiqa/assistant/AssistantRepository.java
```

接口一致性实测：**实现 46 个操作，契约声明 46 个**，但两侧集合不等——差集见 A5、A6。

---

## 2. A. 契约与实现不一致

### A1. `Space.currentPublicationId` 空值被丢弃（P0）

契约 `PublicationState` 把 `currentPublicationId` 列为 **required**（类型 `[string, 'null']`）；实际未发布空间整个字段消失。

```json
{"spaceId":"e7080466…","name":"冒烟扫描库","epoch":0,"canRead":true,"canMaintain":true,"documentCount":0,"searchableCount":0}
```

后果：前端只能靠 `Boolean(space.currentPublicationId)` 判断，任何强类型客户端会解析失败。

### A2. `PublishRequest.snapshot` 未返回（P0）

契约 `PublishRequest` 要求 `snapshot` 数组（冻结清单），实际响应只有申请元信息：

```json
{"requestId":"ca21f6d3…","spaceId":"6816…","snapshotId":"19057bae…","status":"PUBLISHED",
 "submittedBy":"ab0e…","submittedAt":"2026-11-21T12:53:02Z","submissionReason":"…",
 "decisionReason":"…","decisionRequired":false,"draftRevision":2,"publicationId":"f5723dcf…"}
```

后果：`PublishingView` 的「变更内容」表只能显示申请号，无法列出冻结了哪些文档——这正是原型「文档 / 变更 / 当前用户可见」三列的数据来源。后端已有 `publishes.snapshot(user, requestId)`，只是没拼进响应。

### A3. `GlobalSettings.templateId` 空值被丢弃（P1）

```json
{"modelAlias":"","credentialRef":"","template":"","revision":0}
```

契约要求 `templateId`（`[string,'null']`）。未初始化全局设置时字段消失。

### A4. `Usage.reportedTokenCount` 空值被丢弃（P1）

契约要求该字段恒在（`[integer,'null']`，全部未上报时为 `null`）；实际响应缺少该键。

### 根因与已有先例

`PageView` 与 `SearchView` 已用 `@JsonInclude(ALWAYS)` 解决同类问题，其余 record 未加。**修复方式一致**：给 `SpaceView`、`RequestView`、`SettingsController` 返回体、`UsageService.summary` 补 `@JsonInclude(ALWAYS)`，或在 `application.yml` 统一 `spring.jackson.default-property-inclusion: always`（需评估其他接口的空字段膨胀）。

### A5. `GET /spaces/{spaceId}/publish-requests` 实现但未声明（P1）

该端点存在且可用，但 OpenAPI 里没有；它和全局 `GET /publish-requests` 功能重叠（后者已按角色过滤）。前端 `src/api/knowledge.ts` 里有 `listPublishRequests()` 封装，但**没有任何页面调用它**——属于实现与封装两端的死代码。建议二选一：补声明并接入，或两端一起删除。

### A6. `POST /auth/logout` 由安全过滤器提供，契约测试覆盖不到（P2）

实现方式正确（`SecurityConfig` 的 `.logout(...)`），但它不在任何控制器里，导致现有 `OpenApiContractTest` 的单向校验（实现 ⊆ 声明）无法发现反向缺口。**建议把契约测试改为双向**，否则 A5 这类漂移会持续发生。

---

## 3. B. PRD 要求但未真正实现

### B1. AC04 的四种回答态只实现了两种（P0）

PRD R03 要求：有依据给引用、无依据明说、**失效明确提示**、**待核实标注**、**冲突逐项列出**。
代码里只有两处段落写入：

```java
// AnswerService:136  无证据
questions.insertParagraph(questionId, 0, "…没有能够支持回答的内容。", "NO_EVIDENCE");
// AnswerService:172  正常回答
UUID paragraphId = questions.insertParagraph(questionId, 0, rendered.text(), "ANSWER");
```

`EXPIRED` / `UNCERTAIN` / `CONFLICT` 三个枚举值在库表、DTO、前端都支持，**但没有任何代码产出它们**。`AC04` 的失效/待核实/冲突三个样例必然失败。

同时：快照里冻结了 `policy_state` 与生效区间，但 `AnswerService` 生成回答时**没有读取政策有效性**，`FakeAnswerModel` 也未使用。

### B2. 政策有效性不进入检索与文档预览（P0）

PRD R03「可检索不等于有效」。当前：

- `SearchService` 查询只取 `v.id, display_filename, parse_state, snippet`，**不含 policy_state**
- `DocumentPreview` = `documentVersionId, name, status, previewText`，**不含有效性**
- `PublishService.snapshot(...)` 与 `PublishRepository.SnapshotEntry` 里有 `policyState`，但**没有任何控制器调用它**（原先的 `/snapshot` 端点已删），属于死代码；草稿接口 `GET /draft` 本身是返回 `policyState` 的

后果：工作台搜索结果的「现行 / 已失效 / 待核实」徽标无数据来源；本轮前端只能显示「名称匹配/正文匹配」，与原型有可见差距。草稿编辑页能看到有效性，但**用户侧（搜索、文档预览、引用）看不到**。

### B3. Tokens 永远不会被上报（P1）

```java
questions.recordUsage(callId, questionId, question.userId(), null, null);
```

两个 token 参数恒为 `null`，因此 `reportedTokenCount` 永远为 `null`、`unreportedModelCallCount` 永远等于调用数。PRD R05 要求「Tokens 以实际用户用量为准」——目前只有"未上报"这一半是真的。需要答案模型适配器返回 usage。

### B4. 全局模型配置不生效（P1）

`global_setting.model_alias` 只在 `SettingsController` 读写，全项目无其他引用：

```
grep -rn "modelAlias|model_alias" backend/src/main/java/dev/aiqa  → 仅 SettingsController
```

PRD R03「模型从全局配置继承」在数据层成立、在运行时不成立（`FakeAnswerModel` 不读模型别名）。这是预期中的占位实现，但应在文档中标注为**未生效**，避免被当成已完成。

### B5. 失败原因只在导入批次可见（P2）

AC02 要求「可见失败原因」。`ImportFileView.code` 提供了 `PROCESSING_FAILED` 等原因，但：

- `DocumentPreview` 无 `errorCode`
- `document_version.error_code` 列存在，未暴露

后果：离开导入页后（例如知识库草稿清单点「预览」）看不到失败原因，只能看到 `FAILED`。

---

## 4. C. 原型/前端需要但后端无数据

| # | 界面需要 | 现状 | 影响页 |
| --- | --- | --- | --- |
| C1 | 空间所属部门（`hr · 已发布`） | `space` 表无部门字段 | 工作台 |
| C2 | 空间「最近变更」时间 | `space.updated_at` **有**，`SpaceView` 未暴露 | 知识库 |
| C3 | 草稿条目里的文档名、格式、大小、导入时间 | `EntryView` 只有 id/有效性/生效区间；前端只能逐个调 `GET /draft/documents/{id}` 补名称（N+1） | 知识库 |
| C4 | 导入文件的大小与格式 | `document_version.size_bytes`、`media_type` **有**，`ImportFileView` 未暴露 | 导入 |
| C5 | 检索结果的「现行/已失效/待核实」徽标 | 见 B2 | 工作台 |
| C6 | 文档版本号（v1/v2/v3） | `document_version` 无版本号列，前端用 id 前 4 位代替 | 知识库、引用 |
| C7 | 助手启用/停用开关与「已停用」状态 | `assistant.enabled` **有**，但更新 SQL 只写 name/template_id；`AssistantInput` 无 `enabled`，列表只返回启用的 | 助手配置 |
| C8 | 用户邮箱 | `app_user` 无邮箱列 | 系统设置 |
| C9 | 全局「上下文引用上限」 | `global_setting` 无该列 | 系统设置 |
| C10 | 全局「用量上报开关」 | 无该列 | 系统设置 |
| C11 | 统计：用户用量排行、逐条明细 | 只有单用户周期汇总与时间序列 | 问答统计 |

说明：C1、C6、C8、C9、C10、C11 属**原型有、PRD 未明确要求**，建议按"先确认需求再实现"处理；C2、C3、C4、C5、C7 是 PRD 口径内可以补的。

---

## 5. D. 接口设计问题

### D1. 草稿列表 N+1

`KnowledgeView` 为每个条目再请求一次详情：

```ts
const names = await Promise.all(draft.data.entries.map((entry) =>
  getDraftDocument(spaceId, entry.documentVersionId) …))
```

一个 20 条草稿的空间 = 1 + 20 次请求。应把 `name/size/mediaType/parseState` 直接放进 `EntryView`。

### D2. 空值省略策略不统一

同一个 API 里，`PageView`/`SearchView` 保证字段恒在，`SpaceView`/`RequestView`/`Usage`/`GlobalSettings` 会丢字段。客户端无法用统一规则解析（详见 A1–A4）。

### D3. 端点职责重叠

`GET /spaces/{id}/publish-requests` 与 `GET /publish-requests` 重叠；前者既未声明，前端封装也没被任何页面使用（A5）。

### D4. 契约测试单向

`OpenApiContractTest` 只断言"实现 ⊆ 声明"。反向缺口的后果已经出现：A5（多出的端点）从未被发现。

---

## 6. PRD 对照矩阵

| 需求 | 后端支撑 | 结论 |
| --- | --- | --- |
| R01 工作台：空间入口、创建、导入、最近会话、助手入口 | `GET /spaces`、`POST /spaces`、`POST /imports`、`GET /conversations`、`GET /assistants` | ✅ |
| R01 文档数/可检索数按空间统计 | `SpaceView.documentCount/searchableCount`（发布看快照、草稿看草稿） | ✅ |
| R01 搜索三态（未完成/无匹配/不可访问） | `READY/PROCESSING/NO_MATCH` + 404 | ✅ |
| R02 常见格式、同名跳过、无 OCR、逐文件结果、重试 | `POST /imports`、`GET /imports/{id}`、`POST …/retry` | ✅ |
| R02 新旧版本均可检索 | 快照按 `document_version_id` 冻结，搜索走快照 | ✅ |
| R03 助手绑定多空间、只用有权且已发布 | `assistant_space` + 回答前按当前发布过滤 | ✅ |
| R03 助手模板优先于全局模板 | `TemplateResolver` + `answer_template` 实体 | ✅（有单测） |
| R03 引用可定位到文档 | `GET /citations/{id}` | ✅ |
| R03 失效提示 / 待核实 / 冲突逐项 | `PolicyValidity` + 段落类型（B1、B2） | ✅ 已修复 |
| R04 提交、审核、驳回重提、撤回、历史屏蔽 | 发布申请 + 快照 + `paragraphStillAuthorized` | ✅ |
| R05 北京时间自然日/周/月 | `UsageService` | ✅ |
| R05 会话/提问/失败按用户归属 | `UsageService.summary` | ✅ |
| R05 Tokens 以实际用量为准 | 适配器可返回用量，未上报记 null（B3） | ✅ 已修复 |
| AC01 并发同名只收一份 | 唯一索引 + 集成测试 | ✅ |
| AC02 可见失败原因 | 导入批次 + 文档预览 `errorCode`（B5） | ✅ 已修复 |
| AC03 绑定 A/B 但只有 A 权限 | 可见性过滤 + 测试 | ✅ |
| AC04 正常/无依据/失效/待核实/冲突 | 四种段落类型均有产出（B1） | ✅ 已修复 |
| AC05 驳回重提、重审期间不可见 | 快照指针 + 测试 | ✅ |
| AC06 撤回后旧链接失效、历史屏蔽 | 每段证据依赖校验 | ✅ |
| AC07 跨周期对账 | `usage` + `usage/series` | ✅ |

---

## 7. 建议修复顺序

| 优先级 | 事项 | 涉及 |
| --- | --- | --- |
| 1 | 统一空值输出策略（A1–A4），并补 `PublishRequest.snapshot`（A2） | 4 个 DTO + 1 个组装逻辑 |
| 2 | 让政策有效性进入检索与文档预览，产出 EXPIRED/UNCERTAIN/CONFLICT（B1、B2、C5） | `SearchService`、`AnswerService`、`DocumentPreview` |
| 3 | 草稿条目内联文档元信息（C3、D1） | `DraftController` + 前端去掉 N+1 |
| 4 | 暴露 `space.updatedAt`、`document.size/mediaType`、`document.errorCode`（C2、C4、B5） | 3 个 DTO |
| 5 | 助手启用/停用接口（C7） | `AssistantRepository` + `QaController` + 契约 |
| 6 | 契约测试改双向，消掉未声明端点（A5、A6、D3） | `OpenApiContractTest` |
| 7 | 需要产品确认后再做：部门、版本号、用户邮箱、上下文上限、用量上报开关、统计排行（C1、C6、C8–C11） | 需先补 PRD |

---

## 8. 未验证的部分

- 未做并发压测：同名并发导入只在单测里用唯一索引验证，未做真实并发请求
- 未验证 OIDC 真实 IdP（仅用进程内模拟提供方）
- 未在真实 S3 桶上验证对象存储
- 未评估多实例部署下的限流与任务租约（当前限流为单进程内存）
- 未核对 `docs/ai-qa-backend-design.md` 与实现的逐条差异（本次只以 PRD + 原型 + 前端为准）

---

## 9. 修复记录（全部完成）

### 9.1 A 类：契约与实现

| 项 | 处理 | 验证 |
| --- | --- | --- |
| A1 `Space.currentPublicationId` 空值丢失 | `SpaceView` 加 `@JsonInclude(ALWAYS)` | 实测未发布空间返回 `"currentPublicationId": null` |
| A2 `PublishRequest.snapshot` 缺失 | `RequestView` 补 `snapshot` 数组，由 `publishes.snapshotEntries` 组装 | 契约端点测试断言 `$.snapshot[*].documentVersionId` |
| A3 `GlobalSettings.templateId` 空值丢失 | 设置接口改为强类型 `SettingsView` + `ALWAYS` | 实测返回 `"templateId": null` |
| A4 `Usage.reportedTokenCount` 空值丢失 | `UsageService` 改用 `UsageView` record + `ALWAYS` | 实测返回 `"reportedTokenCount": null` |
| A5 未声明端点 | 契约补 `GET /spaces/{spaceId}/publish-requests` | 双向契约测试通过 |
| A6 契约测试单向 | 改为 **(方法, 路径) 双向**校验，并纳入 `AnswerTemplateController` | 该测试当场抓出 A5，修复后才通过 |

同时修正了两处此前未被发现的漂移：
- 审核预览 `ReviewerDocument` 的状态字段名为 `parseState`，而契约按 `DocumentPreview` 要求 `status` → 已改名
- `PUT/POST /assistants` 返回精简视图，而契约要求完整配置视图 → 改为返回 `AssistantAdminView`

### 9.2 B 类：PRD 要求

| 项 | 处理 |
| --- | --- |
| B1 只实现 2 种回答态 | 新增 `PolicyValidity`（快照状态 + 生效区间 + 北京时间 → EFFECTIVE/EXPIRED/UNCERTAIN），`AnswerService` 按证据有效性分布产出 `EXPIRED` / `UNCERTAIN` / `CONFLICT`（同一文件名多版本并存且均生效） |
| B2 有效性不进用户侧 | `SearchService` 与 `SearchHit` 增加 `validity`、`versionNumber`；文档预览与审核预览带元信息 |
| B3 Tokens 永远未上报 | `AnswerModel.Rendered` 增加 `inputTokens/outputTokens`；`FakeAnswerModel` 按字符估算；`recordUsage` 写入真实值；`QuestionView` 增加 `tokenTotal`；未上报时一律 `null` 而非 0 |
| B4 模型别名不生效 | `AnswerService` 读取 `global_setting.model_alias` 并接入生成路径（当前 fake 适配器仅记录，真实适配器可直接使用） |
| B5 失败原因只在导入批次可见 | `DocumentPreview` / `ReviewerDocument` 增加 `errorCode` |

### 9.3 C 类：原型所需数据

| 项 | 处理 |
| --- | --- |
| C1 部门 | `V4` 迁移加 `space.department`；创建/读取/界面均支持 |
| C2 最近变更 | `SpaceView.updatedAt` 暴露 `space.updated_at` |
| C3 草稿条目元信息 | `EntryView` 内联 `name/parseState/sizeBytes/mediaType/versionNumber`，前端删除 N+1 |
| C4 导入文件大小/格式 | `ImportFileView` 增加 `sizeBytes/mediaType/versionNumber`（JOIN 文档版本） |
| C5 检索有效性徽标 | 同 B2 |
| C6 版本号 | `V4` 加 `document_version.version_number`，按空间+文件名回填；导入时递增 |
| C7 助手启停 | `assistant.enabled` 可写；管理员列表含停用助手；`AssistantAdmin.enabled` 返回 |
| C8 用户邮箱 | `V4` 加 `app_user.email`；`AccessView.email` 返回 |
| C9 上下文引用上限 | `V4` 加 `global_setting.context_limit`；设置读写与界面均支持 |
| C10 用量上报开关 | `V4` 加 `global_setting.usage_report_enabled` |
| C11 统计排行与明细 | 新增 `GET /usage/ranking`（管理员）与 `GET /usage/questions`（本人或管理员） |

### 9.4 D 类：接口设计

| 项 | 处理 |
| --- | --- |
| D1 草稿 N+1 | 由 C3 解决，前端不再逐条回查 |
| D2 空值策略不统一 | 统一用 `@JsonInclude(ALWAYS)` 标注在契约要求可空但必在的 DTO 上，未全局改 `non_null`（避免污染 `ProblemDetail` 等未声明可空的字段） |
| D3 端点重叠 | 保留并声明，前端封装保留供按空间查询 |
| D4 契约单向 | 已改双向，见 A6 |

### 9.5 验证结果

- 后端 `mvn test`：**18 个测试全部通过**，新增 `PolicyValidityTest`（4 例）与 `PolicyValidityAnswerIT`（过期/待核实端到端）
- 契约：**39 路径 / 48 操作**，双向一致
- 前端 lint / typecheck / build：全部通过
- 浏览器实测（0 控制台错误）：
  - 检索结果 `正文匹配 现行有效 v1`
  - 知识库空间行 `未设置部门 · 最近变更 2026年11月21日 12:08`，草稿表 `v1`
  - 统计页排行 1 行、明细 4 行，token 显示「未上报」
  - 发布抽屉「变更内容」3 行冻结清单，政策状态「现行」
  - 设置页上下文上限 `10`，开关可切换

## 10. 附：本次审核实际执行的检查

| 检查 | 结果 |
| --- | --- |
| 控制器注解 ↔ OpenAPI paths 双向差集 | 实现 46 / 声明 46；差集各 1（A5、A6） |
| 真实响应 ↔ 契约必填字段 | 4 处缺失（A1–A4） |
| PRD R01–R05 追代码 | R03 两项不成立（B1、B2），R05 部分（B3） |
| 前端实际调用 ↔ 后端实现 | 去重后 35 个路径全部有对应实现（其中 1 个封装无页面调用） |
| 修复后回归 | 后端 18 测试通过；契约 39 路径/48 操作双向一致；浏览器 10 页 0 错误 |
| `mvn test` | 13 个测试全部通过（当前测试未覆盖 A1–A5、B1、B2） |
