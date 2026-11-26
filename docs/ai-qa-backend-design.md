# AI 智能问答平台｜后端详细设计 v1.1

- 基线：[PRD v0.5（含 2026-11-22 修订）](./ai-qa-prd.md) · [首期技术方案（含修订记录）](./ai-qa-technical-design.md) · [OpenAPI 契约](./ai-qa-openapi.yaml)。
- 本文保留首期设计目标。以下各节的「当前实现」与开头的实现边界按当前源码核对，接口调用以 [OpenAPI](./ai-qa-openapi.yaml) 为准，验证结果见[实施状态](./ai-qa-status.md)。
- 当前工程使用 JDK 17、Maven 3.6.3+、Spring Boot 4.1.0、Spring Security、Spring Session JDBC、PostgreSQL（开发基线 17）和 Flyway V1–V13。同一产物可运行 `api`、`worker` 或 `all`（默认）。对象存储支持本地目录和 S3 兼容实现。正文检索使用 Lucene SmartChineseAnalyzer 与 PostgreSQL 全文索引。
- §1 中的全局锁序、最终授权点和并发保证是设计目标。当前控制器与服务存在多步查询，问答 `answer` 方法带事务且包含模型调用。涉及并发授权和失败处理的上线保证需按对应测试逐项验证。

## 1. 发布快照：审核绑定内容，撤回作废旧申请

**搜索和问答只认 PostgreSQL 中的当前发布指针，不认草稿、审核通过标记、空间版本号或索引里的「已发布」标记。** 任何可见文档必须实际属于当前发布快照。

| 对象 | 状态 / 约束 |
| --- | --- |
| 草稿 `space_draft` | 可编辑，`draft_revision` 单调增加；导入接受的唯一文档可加入草稿，已提交快照不随之改变。保存按草稿强 ETag 做 CAS；用户提交审核时也必须绑定读取过的草稿版本。 |
| 快照 `space_snapshot` | 冻结文档版本 ID、政策状态、生效和失效边界、替代关系、审核用清单摘要。不复制文件内容。相同草稿若驳回后重提，仍生成新申请及新快照；快照和条目不可就地修改。 |
| 审核申请 `publish_request` | `PENDING_REVIEW → APPROVED_WAITING_INDEX → PUBLISHED`。待审或等待索引时，管理员可给出原因转为 `REJECTED`；撤回发布时，两种未终结状态均转为 `CANCELLED`。待审期间编辑草稿不会更新原申请，原申请终结后才能新提交，旧 ID 不可复用。 |
| 已发布 `space_publication` | 发布 ID 唯一，记录所属快照、申请和发布代次。每次发布分配新的 `publication_epoch`。撤回清空当前指针、递增代次并更新 `last_withdrawal_epoch`。历史证据须晚于最近一次撤回，且文档仍在当前快照中并可读。 |

### 1.1 提交、审核与自动生效

1. `POST /spaces/{spaceId}/publish-requests` 验证维护权限及草稿 ETag；锁空间后复制草稿形成快照。导入失败、尚未上传完成或未经安全扫描的文档不能提交。已完成安全扫描、尚在建立索引的文档可以提审；纯扫描件只能作为不可检索存档进入快照。一次最多一个未终结申请。
2. 系统管理员审核具体申请 ID 和强 ETag。驳回必须填写原因；通过意见可留空。通过后若索引尚未就绪，记为 `APPROVED_WAITING_INDEX`，并同一事务创建发布任务。等候期间仍用上一有效快照；首次发布则不可见。
3. 发布事务按统一锁序取得锁，再验证 `requestId`、状态、提交时的 `base_publication_id`（支持空值）和 `base_epoch` 均匹配，且快照各可检索文档的索引完整。插入发布记录、切换空间指针、登记 `PUBLISHED`、写审计一次提交；无事务外改指针。各快照按文档 ID 成员判断，因此「空间发布号升到 v4」不会自动开放未审批过的文档 v4。
4. 同名旧版不能被新版覆盖；正常提交草稿默认保留新旧文档，二者发布后都可查。搜索的政策有效性根据快照元数据和当前时间计算，不以文件名称判断。因发布任务故障或索引未就绪，旧发布指针保留：短暂错误自动重试；索引最终失败时展示阻塞文档，维护者重试索引或由管理员对等待中的申请执行带原因的 `REJECT`，再修改草稿重提。禁止直接回拨旧指针；需要回退内容时，从目标快照派生草稿重新审批。

### 1.2 撤回与竞态

- 撤回请求带 `expectedPublicationId` 和原因。事务锁定空间，校验当前指针，将指针设空、递增代次、将该空间的 `PENDING_REVIEW` 和 `APPROVED_WAITING_INDEX` 置为 `CANCELLED`、记录审计。撤回与切版若竞争：先获取锁的一方提交；若发布先赢，旧指针的撤回返回 `409 PUBLICATION_CHANGED`，管理员刷新后再次确认当前发布；若撤回先赢，旧申请和延迟任务均不得复活空间。
- 普通读取使用主库授权判断。搜索在检索前限定授权和快照成员，返回前再校验；旧索引或缓存未清理不影响撤回。事务隔离默认 `READ COMMITTED`。关键授权检查的锁序为 **用户行按 UUID 排序 → 助手行按 UUID 排序（如涉及） → 空间行按 UUID 排序 → 申请/问答任务行**；同时涉及操作者和目标用户时，先确定读写模式并按同一顺序取锁，不做锁升级。检查者对这些实体取 `FOR SHARE`；角色、启停、助手绑定、成员授权、发布和撤回的写入者，对所修改范围的同一父实体取得 `FOR UPDATE` 后再改子表。获得锁后用下一条 SQL 读成员、绑定、当前发布指针，不能用加锁之前的查询结果。
- 锁只用于短事务，不跨模型请求或网络文件传输。问答提交结果、搜索返回及文档下载的最后授权点与撤回事务可排序；**最后授权在撤回提交之后的请求必须拒绝**。不宣称能收回此前已合法交付的字节。预览/问答接口不使用公共 CDN 缓存，文档内容由 API 鉴权后传输，禁止把长期直链或预签名地址发给读者；已经合法开始的下载无法收回，服务端可尽力中止在途传输。授权或主库不可用时拒绝输出，不降级为缓存放行。

## 2. 段落证据与模型调用

### 2.1 当前实现

- 解析器按 PDF 页、DOCX 标题与表格行、XLSX 工作表与行、PPTX 幻灯片与表格行、TXT/Markdown 段落或标题切分，能定位时写入 `document_chunk.source_location`。以实际可提取文字为输入，不执行 OCR。重建索引会重新提取分块。
- `AnswerService` 在助手绑定、读权限、当前发布快照与用户显式范围的交集中对 `READY` 正文块进行关键词匹配并按相关度取最多 20 块。显式选中空间在受理时先检查可读和已发布，worker 再检查交集。无候选内容时直接写 `NO_EVIDENCE`，不调用模型。
- 登录用户可从 `GET /model-configs/options` 读取可选模型。提问可携带 `modelId`，服务端首次受理时检查可选性并在任务中保存选定 ID。worker 生成前再查，显式所选模型在执行时已不可用则标记 `MODEL_UNAVAILABLE`。省略 `modelId` 时，worker 读取全局默认模型，缺少有效配置可回退假模型。
- 每题当前至多调用一次模型，使用当前问题、解析后的模板与本次命中证据。助手启用模板优先，其次全局启用模板，最后回退内置模板。网关适配器解析 `[编号]` 标注，有效标注去重，无有效标注时返回所有候选引用。生成成功且本次证据仍匹配原发布指针、代次和权限时，写入一条回答段落及实际投喂的所有证据依赖。调用失败为 `MODEL_UNAVAILABLE`，证据范围变化为 `CONTEXT_CHANGED`。
- Flyway V10 为证据保存冻结的 `evidence_excerpt` 和 `source_location`，同时把能回填的旧分块引用冻结后清空 `chunk_id`，以便索引重建不影响已保存的引用。历史预览按所有段落依赖重新鉴权，`CHUNK` 返回冻结原文，`DOCUMENT` 用于无冻结片段的旧证据并读取同文档版本正文。`sourceLocation` 缺失表示没有可靠定位。原件下载使用同一证据权限边界。
- 分组投喂、经授权历史参与续问、模型失败调用独立计账是下节的目标设计。当前问答不会把历史消息发给模型，且仅在模型成功返回并通过 `stillCurrent` 后记录用量。

### 2.2 设计目标与验收示例

**段落是最小输出与屏蔽单位。** 服务端为生成调用保存证据包，记录每个证据的 `space_id`、投喂时 `publication_id` 和 `publication_epoch`、`document_version_id`、可选 `chunk_id`。段落证据是实际投喂的全部资料与继承来源，不只是最终展示的引文。

1. 取 `助手允许绑定的空间 ∩ 当前用户读权限 ∩ 当前已发布空间`。客户端指定范围时，每个空间必须在交集中；不允许输入里混入无权空间再静默扩大范围，统一返回 `404 RESOURCE_NOT_FOUND`。留空则使用交集。空交集或无结果时返回不带来源的「无依据」，不调用模型编写确定结论。
2. 检索仅来自当前快照。生成时按证据包分组，每组只给本组资料和已经过授权过滤的历史；模型返回结构化段落和引文标识，服务端只接受证据包中存在的标识。若同一调用看到 A 与 B，则其所有段落默认都依赖 A 与 B，即便模型只引用 A。要做到只屏蔽受影响段，必须分开投喂；不能靠模型自报或从生成文本反推。经过授权的历史段落若进入上下文，其全部证据也继承给新段落。首期不把未经同样过滤的历史摘要、缓存或原始模型输出作为上下文。
3. 服务端在发送模型请求前和落库前重检。若生成期间授权、绑定、发布指针或代次发生变化，放弃本次未提交候选，不自动重放可能跨边界的内容；问题结果终态 `FAILED`、`failureCode=CONTEXT_CHANGED`，允许用户发起新问题。模型已报告的真实用量仍按用户记账。运行期间只返回任务状态，不推送模型原文。回答段落通过 `kind` 区分 `ANSWER / NO_EVIDENCE / UNCERTAIN / EXPIRED / CONFLICT`；后四种结论不是 HTTP 或任务失败。用于比较冲突的来源需要各自能核对，不能用失效或待核实来源冒充现行依据。
4. 历史读取与追问使用同一个过滤器：检查当前用户已登录、仍为会话所有人、空间当前可访问、依赖代次等于空间当前代次，且依赖文档现在仍属于获准的发布快照。任一不满足，整段输出 `MASKED`，只带段落 ID、状态和通用原因 `SOURCE_UNAVAILABLE`；不能返回文本、来源名称、引用 ID、摘要或已撤回段落作为模型输入。正常发布变更且代次和文档成员未变时，已合法历史可以保留。重新发布后，只允许新生成的问答使用新代次，不恢复旧引文。
5. 直接打开历史引用按 `citationId` 找到其段落，再核验该段的**所有依赖**和被引用文档当前权限；不可用统一为 `404`。不得用当前可读的普通文档路径重定向旧引用。导出不在首期范围；逐字显示由 SSE 增量流提供，且只是生成中的预览，不改变上面这些校验与终态口径。

例：段落 P1 的实际输入为 A、B（界面仅展示 A 的引用）；P2 只看 C。撤回 B 后，P1 整段和 A 引用都屏蔽，P2 保留；系统也不把 P1 交给下一轮模型。B 重新发布并获得新的空间代次后，P1 仍屏蔽，新问题可引用重新发布且有权限的 B。历史接口只返回以下占位结构：

```json
{
  "id": "435f64e9-cc2f-4cc4-b8bf-6296903510ab",
  "status": "MASKED",
  "code": "SOURCE_UNAVAILABLE"
}
```

## 3. API 与错误契约

[OpenAPI 契约](./ai-qa-openapi.yaml)是路径、JSON 字段、枚举及传输格式的依据。本节补充状态和事务语义。统一前缀 `/api/v1`，JSON 字段用 camelCase，UUID 作为字符串，时间为 ISO 8601。列表响应使用 `items` / `nextCursor` / `total`：分页参数为 `limit`（1–200，省略返回全部）与 `offset`（默认 0），`total` 是筛选后的总数，`nextCursor` 仍为 `null`（不消费游标）。知识空间与发布申请的状态、名称筛选在服务端完成。助手列表返回 `items` 与 `total`；登录方式列表仍只返回 `items`。禁止前端用缓存推断权限。

| 接口组 | 调用约定 |
| --- | --- |
| 会话身份 | `/auth/me` 查询身份。`/auth/csrf`、`/auth/providers` 可匿名访问；`/auth/login` 和 `/auth/logout` 写请求携带 CSRF 令牌与对应 Cookie。需要登录的 API 未登录时返回 `401` JSON。 |
| 知识空间 | `/spaces`、`/spaces/{spaceId}`；读取已发布文档和空间内名称/正文搜索，搜索按 `READY / PROCESSING / NO_MATCH` 区分，空间不可访问统一 `404`，不返回空结果误导用户。名称可返回可预览但无正文的文档，正文搜索只覆盖已索引文档。 |
| 维护与导入 | `GET/PUT /spaces/{spaceId}/draft`；维护预览走 `/spaces/{spaceId}/draft/documents/{documentVersionId}`，仅限草稿清单中的文档。`POST /spaces/{spaceId}/imports` 传 `files`，`202 + Location` 返回批次，`GET /imports/{batchId}` 轮询逐文件状态；技术性失败可按文档版本 `retry`，同名结果为 `SKIPPED_DUPLICATE`，不把整批标为失败。 |
| 审核与撤回 | 提交冻结草稿，管理员按具体申请决策；`GET /publish-requests` 对管理员列范围内申请，非管理员只看自己提交的申请。撤回后返回新代次，响应省略已清空的 `currentPublicationId`；空间详情显式返回 `currentPublicationId: null`。 |
| 助手、对话与用量 | `POST /conversations/{conversationId}/questions` 返回 `202 + Location`；客户端可订阅 `GET /questions/{questionId}/stream`（SSE：`delta`/`done`/`error`/`timeout`）逐字显示，结束后仍读 `/questions/{questionId}` 取段落与引用，流不可用时回退轮询。运营统计按人，自然日/周/月，员工只看本人，管理员可查看全员或指定用户。 |

- `Idempotency-Key` 仅用于契约明确列出的操作。台账按 `(user_id, operation, key)` 去重，键是 3–255 字符的不透明字符串，保持超时重试的原始值。记录有效期 48 小时，清理任务删除过期记录。重放行为依操作而异，部分更新先执行资源版本校验。重建索引与文档重试仅要求非空键并按活跃任务去重。创建空间、本地登录、建号和重置密码无需该头。
- `If-Match` 原样传回响应的数字版本 ETag。缺该头的响应是 `428 IF_MATCH_REQUIRED`，格式非法是 `412 ETAG_MISMATCH`。保存草稿、模板与模型配置的过期版本使用 412。审核提交、决策、助手更新、全局配置更新的相应版本冲突分支使用 `409 ETAG_MISMATCH`，其中审核详情和决策按空间草稿版本。控制器错误使用 `application/problem+json` 和 `requestId`，安全过滤器可直接返回较简的 401/403，缺少正文 `requestId` 时可参考响应的 `X-Request-Id`。
- 统计口径：自然周为北京时间周一至周日。按区间内被受理的逻辑提问归属用户；会话为该区间有至少一次受理提问的不同会话 ID。最终失败计入提问和失败，内部重试/网络重发不另计。已记账的调用按适配器返回的 Tokens 计并以调用 ID 区分，未上报为 `null`，单独汇报。统计一条逻辑提问时，以其受理时间作为会话、问题、失败和相应用量归期基准，未完成请求不先算失败。

## 4. PostgreSQL 表、约束与队列

UUID 主键，时间 `timestamptz`，计数与发布代次 `bigint` 且非负。下表按主要实体归纳设计和当前结构。实际列、约束和迁移顺序以 [Flyway SQL](../backend/src/main/resources/db/migration/) 为准。列表主键与复合外键中的 `space_id` 不得由客户端自由推断；接口先鉴权。跨空间关系通过复合外键约束；跨行事务不变量用锁内校验及集成测试，不把 PostgreSQL `CHECK` 当成跨表证明。

| 表 | 最小字段和强约束 |
| --- | --- |
| `app_user`, `user_role` | `app_user(id, issuer, subject, display_name, email NULL, enabled, can_create_space, auth_version)`；`UNIQUE(issuer, subject)`，`can_create_space` 默认 false。角色关联 `PK(user_id, role)`，角色仅 `USER / SYSTEM_ADMIN`。创建空间仅限管理员或获授 `canCreateSpace` 的用户。角色、功能授权或启用状态变更均更新 `auth_version`；管理员变更加全局事务保护，不得撤掉最后一个启用的管理员。Spring Session 使用框架 JDBC 表。 |
| `space`, `space_member` | `space(id, owner_id, name, state_version, publication_epoch, current_publication_id NULL, department NULL, updated_at)`；`space_member PK(space_id,user_id)`，布尔 `can_read/can_maintain`，后二者均 false 时删除授权行。空间创建者同一事务生成维护和读权限；维护权不隐含读取权。`(id,current_publication_id)` 复合 FK 到发布记录 `(space_id,id)`，空值允许。 |
| `document_version`, `document_chunk` | 文档 `id,space_id,normalized_filename,display_filename,object_key,sha256,media_type,size_bytes,version_number,scan_state,parse_state,try_count,error_code`；`version_number` 按 `(space_id, normalized_filename)` 递增，供界面显示 `vN`，重试不新增版本；`UNIQUE(space_id, normalized_filename)`、`UNIQUE(space_id,id)`、`UNIQUE(object_key)`。同名按 Unicode NFC、去首尾空白、`Locale.ROOT` 转小写比较；文件名不包含路径。正文块含 `space_id,document_version_id,ordinal,body,search_vector,source_location`，复合 FK 到原文档，`UNIQUE(document_version_id,ordinal)`，`GIN(search_vector)`。文档字节和发布内容标识不可覆盖。 |
| `space_draft`, `space_draft_document` | 主草稿 `PK(space_id)`、`draft_revision`；明细 `PK(space_id,document_version_id)`，复合 FK，记录人工政策状态、时间窗和替代文档 ID。`effective_from < effective_until`（均非空时）；不可自替代、替代关系必须在同空间且事务内检查无循环。API 的 `effectiveFrom` / `effectiveUntil` 为北京时间日期，后者为包含的最后一日；入库使用 PostgreSQL `DATE`，计算时按北京时间日期比较，结束日含当日。 |
| `space_snapshot`, `space_snapshot_document` | 头 `id,space_id,source_draft_revision,content_digest,created_at`，`UNIQUE(space_id,id)`；明细 `PK(snapshot_id,document_version_id)`，用 `(space_id,snapshot_id)`、`(space_id,document_version_id)` 双复合 FK 防串空间，并冻结草稿明细的政策数据。应用角色不更新已提交快照。 |
| `publish_request`, `space_publication` | 申请 `id,space_id,snapshot_id,base_publication_id,base_epoch,revision,status,submitted_by,reviewed_by,reason,timestamps`，申请和快照为一对一。`UNIQUE(space_id) WHERE status IN ('PENDING_REVIEW','APPROVED_WAITING_INDEX')` 用部分唯一索引实现。驳回原因不得为空白。发布 `id,space_id,snapshot_id,request_id,epoch,published_at`，`UNIQUE(request_id)` 与 `UNIQUE(space_id,id)`；关联快照、申请、基础发布均验证同空间。 |
| `answer_template`, `assistant`, `assistant_space`, `global_setting` | `answer_template(id, name, body, enabled, revision)` 为独立实体，正文只有一处；`assistant.answer_template_id` 与 `global_setting.template_id` 引用它，停用即回退。`assistant_space PK(assistant_id,space_id)`；model_config 表（V6/V7）按别名唯一保存多条模型配置：`protocol`（`openai_compatible` 或 `anthropic`）、`base_url`、`model_name`、服务端登记的 `credential_ref`、启用开关与 revision；不存 API 密钥。`openai_compatible` 适配 OpenAI/DeepSeek/Kimi/智谱/通义/豆包/百度 v2/混元/Gemini 兼容层等；`anthropic` 适配 Anthropic 原生 Messages（顶层 system、x-api-key、usage.input_tokens/output_tokens）。`global_setting` 单一行仅持有默认模型指针（`default_model_id`，指向其中一条）、全局模板引用、上下文引用上限和用量上报开关。后两个字段当前只保存并回显，worker 取证上限固定为 20，按适配器返回记录用量。删除或停用默认指向的配置返回 409；`model_alias` 由默认指针解析，仅供展示。凭据引用在服务端通过 Spring 环境属性注入：`aiqa.model.credentials.<归一化引用>`，引用中的非字母数字字符转为下划线并转小写（例如引用 `vault:glm53` 对应 `aiqa.model.credentials.vault_glm53`）；禁止把密钥原文放入前端或数据库。解析顺序为助手模板 → 全局模板 → 内置默认。保存助手绑定、权限、模板和全局配置均限制管理员角色；被引用或被设为全局的模板不可删除。 |
| `conversation`, `question`, `answer_paragraph` | 会话持久化所有者和助手；`title_auto` 标记标题由系统生成，用户显式命名后不再自动覆盖。首个问题可在同一事务内自动建会话，标题取自问题主题（问题过短时用首轮回答第一句补全），超过 30 个字符按标点回退截断并加省略号。问题 `id, conversation_id, user_id, status, accepted_at, error_code, run_revision`；问题所有者用 `(conversation_id,user_id)` 复合 FK 锁定，终态不可回退。段落 `id, question_id, ordinal, text, answer_kind`，`UNIQUE(question_id,ordinal)`；只在通过最终鉴权的成功事务写可用段落。 |
| `model_call_evidence`, `paragraph_evidence`, `citation` | 模型调用成功且证据核对通过后，写入 `call_id`、序号、空间、发布 ID、代次、文档版本、冻结片段 `evidence_excerpt` 与可选位置 `source_location`。段落绑定本次调用的全部证据。复合 FK 确认发布代次及文档同空间；写入事务检查文档确在该发布快照，若有正文块则块属于该文档。`citation.id` 为 UUID，外键指向同段的证据关系；展示引文是段落证据的子集。按 `(space_id,epoch)` 建索引。API 返回前按依赖授权屏蔽段落正文与引用。 |
| `import_batch`, `import_item` | 批次 `id,space_id,created_by,created_at`；项目 `PK(batch_id,ordinal)`，保存文件名、逐项结果、可空 `document_version_id` 和稳定错误码。成功项复合 FK 保证关联同空间，这里记录跳过而不创建第二个文档。 |
| `job`, `request_idempotency` | 任务含类型、空间/目标 ID、状态、计划时间、租约、尝试次数、`lease_token`；目标类型对应用 `CHECK` 与真实 FK 约束。活跃解析/发布/问答任务按目标作部分唯一索引。幂等记录 `PK(user_id,operation,key)`，保存摘要、过期和已提交资源 ID。 |
| `model_call_usage`, `audit_event` | 每次调用唯一 `call_id`，关联问题与用户；`input_tokens/output_tokens` 实际已知或 `NULL`，非负，区分是否未上报。审计追加写入，记录操作者、时间、动作、资源 ID、新旧发布指针及请求 ID，不写密钥、文档全文或模型原始上下文。 |

- 导入：先临时隔离文件、计算摘要、检查大小/类型和恶意内容，再由事务登记批次、文档和任务；对象写入先于元数据，失败的临时对象由清理任务回收。采用私有不可变对象键，不用原文件名作存储路径。并发同名由唯一约束保障；失去竞争的对象不得覆盖已入库文件。最后重试只重跑失败任务，不能重复已成功文档。
- 搜索、统计、状态查询按用户分别计量。`RATE_LIMIT_READS_ENFORCE` 默认 `false`，记录超出配置阈值的请求，开启后返回 `429` 和 `Retry-After`。多实例统一读取配额需要网关或共享计数。
- 任务准入和领取按任务类型限制容量和并发，配置前缀为 `WORKER_*_CAPACITY` 和 `WORKER_*_CONCURRENCY`。容量耗尽返回 `503 DEPENDENCY_UNAVAILABLE` 和 `Retry-After`。问答幂等回放复用既有问题，不占用新增问答配额或队列容量。
- 队列：按任务类型筛选，再按 `available_at, id` 选择可用项，使用 `FOR UPDATE SKIP LOCKED` 获得短租约，事务内生成 `lease_token` 随后释放事务。执行和心跳在事务外；完成和重试时按任务 ID + 当前租约令牌比对，过期 worker 不得回写。解析和建立索引可按文档版本幂等重试（首期 3 次，指数退避）；模型调用不因未知是否已计费而盲目自动重放，进入明确失败并允许用户重问。解析进程仅处理 PDF、DOCX、XLSX、PPTX、TXT、Markdown，采用成熟解析库，禁用宏执行、远程资源与 OCR；异常归一为稳定错误码。
- 中文检索：应用使用 Lucene SmartChineseAnalyzer 对文本与查询采用固定版本及一致的分词配置，词元送 PostgreSQL `simple` 配置的 `tsvector/tsquery` 做正文倒排；名称按规范化名称匹配。SQL先限定空间授权及当前快照成员，再排序返回；不接语义检索服务。解析器、分词器或策略升级通过可重建索引实现，不覆盖已发布文档或决定内容可见性。

### 4.1 可空字段的输出约定

契约里标为「必填但可为 null」的字段，服务端必须在 JSON 中显式输出 `null`，不能因全局 `default-property-inclusion: non_null` 而省略。做法是在对应 DTO 上加 `@com.fasterxml.jackson.annotation.JsonInclude(ALWAYS)`，而不是把全局策略改成 `always`——后者会让 `ProblemDetail` 等未声明可空的字段也多出 `null`。当前适用：`SpaceView.currentPublicationId`、`RequestView.snapshot`、`SettingsView.templateId`、`UsageView.reportedTokenCount`、`QuestionView.tokenTotal`、`UsageService.UserUsageRow.reportedTokenCount`、`DocumentPreview` 与 `ReviewerDocument` 的元信息字段。

### 4.2 政策有效性与回答段落类型

`PolicyValidity` 以快照冻结的 `policy_state` 与 `effective_from/effective_until` 结合北京时间计算当前结论：过了末日为 `EXPIRED`，未到生效日或状态为 `UNKNOWN` 为 `UNCERTAIN`，其余按已审核状态取 `EFFECTIVE`。回答优先根据模型引用的证据分类，无有效引用时使用候选证据。依次检查同名文件存在不同且均生效的版本（`CONFLICT`）、证据全失效（`EXPIRED`）、任一待核实（`UNCERTAIN`）、任一失效（`EXPIRED`），否则为 `ANSWER`。检索结果的 `validity` 和回答分类使用同一有效性计算函数。

### 4.3 Token 用量口径

`AnswerModel.Rendered` 携带 `inputTokens/outputTokens`，可为 `null`。未上报时用量行写 `NULL`，统计返回 `reportedTokenCount: null` 与 `unreportedModelCallCount`，**不记为零**；`tokenTotal`、`reportedTokenCount` 在同类记录全部未上报时同样为 `null`。`FakeAnswerModel` 按字符估算。真实适配器返回 OpenAI 兼容或 Anthropic 网关提供的用量。

## 5. 身份认证与环境部署

| 项目 | 决策 |
| --- | --- |
| 登录 | 交付使用本地账号密码登录。`issuer='local'`，`subject` 为用户名，密码以 BCrypt 哈希保存。`POST /api/v1/auth/login` 建立会话，凭据错误返回 401，账号停用返回 403，按用户名限流超限时返回 429。首次启动可引导创建管理员和普通用户。管理员可建号及重置本地账号密码。企业 SSO 已移出交付范围，现有应用代码中仍有 OIDC 实现，交付部署保持关闭。 |
| 浏览器会话 | 前端与 API 同源；后端服务器持有登录会话，Spring Session JDBC 支持多实例。Cookie `QA_SESSION` 在 HTTPS 部署时显式设置 `COOKIE_SECURE=true`，并使用 `HttpOnly`、`SameSite=Lax`、不设置跨域 `Domain`。会话有效期已实现（2026-11-22）：空闲 30 分钟（`server.servlet.session.timeout`）、绝对 12 小时（`aiqa.session.absolute-timeout`，登录成功记录认证时刻到会话，`SessionExpiryFilter` 超期作废；本地账号登录轮换以「作废旧会话 + 新会话承载上下文」实现）。注销使服务端会话立即失效。匿名可访问 `/auth/csrf`，把返回的 token 放进 `X-CSRF-TOKEN`，同时发送 `XSRF-TOKEN` Cookie。API 默认不开放跨域；不在浏览器持久化 OIDC 或模型令牌。 |
| 权限来源 | 部署人员在首次启动前设置本地引导账号密码。后续系统管理员经审计管理角色和空间授权。`CurrentUser` 查询数据库中的当前账号、启用状态和角色。后台审核与普通问答分别鉴权，普通问答仍按知识空间授权过滤。 |
| 拓扑 | 单域名 HTTPS 入口转发静态前端与 `/api/v1`。API、解析 worker、数据库和存储按独立服务账号和最小网络权限部署。预发和生产使用独立的数据库、文档存储和模型凭据。 |
| 可配项 | 应用直接读取 `DB_URL`、`DB_USER`、`DB_PASSWORD`、`PROCESS_ROLE`、`WORKER_ENABLED`、`COOKIE_SECURE`、`OBJECT_STORE_TYPE` 及 `OBJECT_STORE_LOCAL_*` / `OBJECT_STORE_S3_*` 配置。网关配置保存在模型配置表中，凭据由服务端属性 `aiqa.model.credentials.<归一化引用>` 注入。见[后端运行说明](../backend/README.md)。 |
| 本地开发 | 用 JDK 17 和 Maven 直接运行开发配置，连接 PostgreSQL 17。用本地目录、本地账号和假模型验证主链路。数据库集成测试使用独立 schema 或专用测试数据库。 |
| 上线 | 预发和生产优先使用获准的内部服务。数据库迁移走单独发布步骤，确认应用与 schema 向前兼容后再滚动启动。生产变更前备份并演练恢复。就绪探针校验主库和对象存储，存活探针检查进程。日志和审计用 `requestId` 关联，监控队列滞留、解析失败、授权拒绝、发布超时和模型用量缺失。 |

### 5.1 迁移版本

V1 核心结构，V2 JDBC 会话，V3 独立回答模板，V4 审计补充字段，V5 本地账号，V6 多模型配置，V7 网关字段，V8 会话归档与删除，V9 队列容量索引，V10 引用片段与来源位置，V11 持久化每次提问所选模型 ID，V12 回填并记录最近一次撤回代次，V13 会话自动标题标记。

默认限额：单文件 100 MiB、每批最多 10 个文件且总和不超过 200 MiB、单次提问最多 2,000 字符。上线前配置本地引导账号、目标文档存储和获准使用的模型。选用 S3 兼容对象存储时配置对应存储桶和权限。若政策资料不能出域，则只接入已批准的内部模型；未获得批准前对模型方向保持关闭，不影响先开发和用假模型验证主链路。

## 6. 开工顺序与验收门槛

| 顺序 | 实现与通过条件 |
| --- | --- |
| 1 | 建立用户/空间/文档/草稿/批次契约与 Flyway；使用 PostgreSQL 集成测试验证同名并发只接收一份、租约过期写入失败、扫描件预览不进正文索引、重试成功不增加重复索引。 |
| 2 | 冻结快照、审核、发布、撤回；用并发测试证明旧审核任务在撤回后被拒，重审期间只有旧快照可读，新发布换指针失败仍保留旧快照，撤回后不等待索引清理即可拒绝读取。为重新发布测试「仅审批元数据不开放未审核新文档」。 |
| 3 | 助手、会话、证据与历史读取；用假模型测试返回不存在的引用、混合依赖整段屏蔽、撤回发生在模型执行期间、直接打开旧引用、追问不得续用旧证据。对不可见段落断言 JSON 不含文本或引用，而非仅看页面。 |
| 4 | 用户用量及运维；用固定事件对账跨空间、网络重发、最终失败、未上报及北京时间日周月边界；用本地账号验证未登录、跨用户资源访问、CSRF、会话注销与权限撤回。完成备份恢复、指标与审计方可发布。 |

文档参考：[PostgreSQL 行锁规则](https://www.postgresql.org/docs/17/explicit-locking.html) · [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) · [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)。
