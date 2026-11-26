# AI 问答平台后端

Java 17 + Spring Boot 4.1.0，模块化单体，本地直接运行，不依赖 Docker。

## 本地运行

准备 JDK 17、Maven 3.6.3+ 和 PostgreSQL 17 开发库。在后端目录运行，Flyway 启动时应用 V1–V13。

```sh
# 凭据放在项目之外、权限 600 的本地文件中，由 shell 载入
set -a; . ~/.config/ai-qa/dev.env; set +a
mvn spring-boot:run
```

必需变量：`DB_URL`、`DB_USER`、`DB_PASSWORD`。常用可选变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `OBJECT_STORE_LOCAL_ROOT` | `~/.config/ai-qa/objects` | 本地对象存储根目录，开发用 |
| `DEV_HEADER_AUTH` | `false` | 开发身份头（`X-Dev-Subject`）；仅本地开启，生产必须关闭 |
| `CAPTCHA_TTL` | `PT2M` | 登录图形验证码有效期；每个验证码只校验一次 |
| `CAPTCHA_FIXED_CODE` | 空 | 固定验证码，仅供隔离 E2E 与集成测试；生产必须留空（留空即每次随机） |
| `WORKER_ENABLED` | `true` | 是否启动解析/发布/问答后台任务 |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `PROCESS_ROLE` | `all` | `api`（只服务 HTTP，不跑任务）、`worker`（只消费任务，不启 Web）、`all`（本地开发） |
| `COOKIE_SECURE` | `false` | HTTPS 部署设为 `true` |
| `AIQA_BOOTSTRAP_ENABLED` | `true` | 无任何本地账号时初始化引导管理员和普通用户 |
| `AIQA_BOOTSTRAP_ADMIN_PASSWORD` / `AIQA_BOOTSTRAP_USER_PASSWORD` | 内置开发值 | 初始化前通过本地环境设置。已有本地账号时不影响已保存密码 |
| `MODEL_PROVIDER` | `fake` | 默认注册假模型供未配置网关时使用。配置可用默认模型后按其协议选真实适配器 |
| `OBJECT_STORE_TYPE` | `local` | `local` 或 `s3`；S3 需配置 `OBJECT_STORE_S3_*` |
| `RATE_LIMIT_WRITES_PER_MINUTE` | `120` | 每用户每分钟写操作上限 |
| `RATE_LIMIT_QUESTIONS_PER_MINUTE` | `30` | 每用户每分钟提问上限 |
| `MODEL_STREAM_TIMEOUT` | `PT180S` | 单次流式回答的总读取上限，到点按网关不可达处理 |
| `ANSWER_STREAM_TIMEOUT_SECONDS` | `120` | 单条 SSE 增量流的最长等待，超时后客户端回退轮询 |

浏览器使用本地账号密码登录。登录前先 `GET /api/v1/auth/captcha` 取得 `captchaId` 与 PNG data URL，登录请求体必须带上 `captchaId`、`captchaCode`，验证码缺失/过期/错误返回 400 `INVALID_CAPTCHA`（先于密码校验）。交付部署不启用 `oidc` profile。`DEV_HEADER_AUTH` 仅用于本地开发。未登录可请求 `GET /api/v1/auth/csrf`，写请求携带 `data.token` 作为 `X-CSRF-TOKEN`，同时保留 `XSRF-TOKEN` Cookie。成功 JSON 返回 `{code, data, requestId}`，错误统一使用 Problem Details。登录会话使用 `QA_SESSION`。

系统管理员可配置 OpenAI 兼容或 Anthropic 网关，并在全局设置中选择启用的默认模型。凭据引用由服务端属性 `aiqa.model.credentials.<归一化引用>` 解析，引用中的非 ASCII 字母数字转为下划线后转小写。密钥原文通过服务端配置注入。已登录用户可通过 `/api/v1/model-configs/options` 读取可选模型，提交问题时可传 `modelId` 仅用于本次提问。省略后由 worker 选择全局默认模型。

## 验证

```sh
mvn test
```

Surefire 匹配 `*Test` 和 `*IT`。数据库测试在未配置 `IT_DB_URL` 时跳过。用 `IT_DB_URL`、`IT_DB_USER`、`IT_DB_PASSWORD` 指向测试库后，每个数据库测试类用独立 schema 并在结束后清理。可按下列测试类定位覆盖范围。

- `SchemaMigrationTest`：Flyway 全量迁移、关键表与部分唯一索引。
- `PublishingAndWithdrawalIT`：导入与同名跳过、未审不可见、审核发布、检索、问答证据引用、撤回后历史段落屏蔽与引用 404。
- `AccessUsageAndReviewIT`：驳回必填原因、已终结申请不可再审、CSRF、跨用户 404、用户用量时区、助手绑定隐藏无权空间、幂等重放。
- `SecurityAndLimitsIT`：伪造扩展名文件被拒、提问限流 429 与 Retry-After、Tokens 已上报/未上报分列、索引重建入队。
- `ConcurrencyAndWaitingIndexIT`：审核先于索引时进入等待发布且检索 404，索引完成后只切换一次；两个并发撤回恰好一个成功、代次只加一。
- `OidcIdentityIT`：OIDC 主体按 (issuer, subject) 稳定绑定同一账号，停用账号下一次请求即 403。
- `OidcLoginFlowIT`：进程内 OIDC 提供方跑通真实授权码流程（跳转、回调、换取令牌、id_token 验签、会话建立），两次登录绑定同一账号。
- `OpenApiContractTest`：双向比对控制器与 OpenAPI 的方法和路径，并纳入安全过滤器提供的登出。该测试不校验响应 JSON schema。
- `LocalLoginIT` / `SessionExpiryIT`：本地登录和会话时效。
- `ModelConfigIT` / `GatewayAnswerModelTest`：模型配置管理与网关适配器。
- `ConversationLifecycleIT`：会话归档、恢复、软删除及历史访问边界。
- `TextExtractorTest` / `IndexRebuildIT`：结构化提取、来源位置、重建索引与引用。
- `TemplateResolverTest`：助手模板优先于全局模板，均为空时使用内置默认。
- `AnswerTemplateIT`：模板为独立实体的增查改删、不可重复创建、被助手引用或全局占用时拒绝删除、停用后回退、过期 If-Match 返回 412。
- `ContractEndpointsIT`：契约声明但非主流程的端点——全局审核申请列表、审核文档预览、引用原件下载（越权 404）、助手配置读取（非管理员 403）、登出。

## 已实现范围

- 空间、成员授权、草稿（强 ETag）、批量导入（同名跳过、逐文件状态、单文件重试、幂等）。
- 文字提取：TXT/MD 直接读取；PDF/DOCX/XLSX/PPTX 提取文本，支持页码、标题、表格行、工作表行和幻灯片位置。纯扫描件没有可提取文字时标记 `NO_TEXT`。
- 冻结快照、发布申请、驳回/通过、索引就绪后原子切换、撤回并作废旧申请。
- 当前发布快照内的名称与正文检索（Lucene SmartChinese 分词 + PostgreSQL `tsvector`）。
- 助手绑定、会话归档与软删除、异步提问、真实网关与假模型适配器、段落级证据依赖和撤回后整段屏蔽。引用按片段冻结，无片段的历史引用按文档版本预览。回答增量经 `GET /questions/{id}/stream`（SSE）逐字下发，结束后仍以问题详情为准；通道是进程内广播，多实例需共享通道。
- 北京时间自然日、周、月汇总与趋势，支持用户排行、提问明细和管理员空间指标。
- 统一 Problem Details 错误、`If-Match`/ETag、`Idempotency-Key`、审计记录。
- 回答模板为独立实体（`answer_template`），助手与全局配置以 ID 引用；正文只有一处来源。
- 多数列表返回 `items` 和 `nextCursor: null`，现有接口没有续页。
- 登出 `POST /api/v1/auth/logout` 注销服务端会话并让 Cookie 过期；引用原件下载按最近撤回代次、当前快照与当前授权重新鉴权。
- 文件内容检查（扩展名与字节一致性、EICAR 探测）、按用户限流、索引重建、过期幂等记录清理。
- 对象存储 SPI：本地目录与 S3 兼容实现（S3 适配器经进程内 path-style 端点验证读写删）。
- 同一产物支持 `api` / `worker` 进程角色：`PROCESS_ROLE=worker` 不启动 Tomcat，`PROCESS_ROLE=api` 不运行后台任务（均已用真实进程验证启动日志与健康检查）。
- 审核材料预览（`/publish-requests/{id}/documents/{docId}`）与草稿文档预览（`/spaces/{id}/draft/documents/{docId}`），复用同一权限边界。
- 成员授权与角色变更写入审计；运维指标端点仅系统管理员可访问。
- 监控：`/actuator/health` 提供存活/就绪探针；`/actuator/metrics` 提供 `aiqa.api.denied`、`aiqa.parse.failures`、`aiqa.publish.waiting_index`、`aiqa.model.usage.unreported`、`aiqa.job.queued`；日志带 `requestId`。健康探针可匿名访问，其他 Actuator 端点要求系统管理员。

## 实施边界

- 交付仅使用本地账号，企业 SSO 不列入验收。
- S3 适配器已用进程内端点验证读写。选用 S3 存储时，使用部署环境的实际存储桶验证读写与权限。
- 文件检查是开发级内容校验，未接入企业病毒扫描引擎。
- 限流为单进程内存实现，多实例需网关或共享存储。

- 全局 `contextLimit` 和 `usageReportEnabled` 当前仅保存与回显。问答固定最多检索 20 个正文块，按返回用量记账，模型上下文不含历史对话。
