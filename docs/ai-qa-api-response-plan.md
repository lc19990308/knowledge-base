# 知识库前后端响应契约统一方案

## 目标契约

[OpenAPI](./ai-qa-openapi.yaml) 定义 `/api/v1` 的目标响应契约。原有业务 DTO、字段和空值语义不变；所有 2xx JSON 成功响应仅在外层增加 `code: OK`、`data`、`requestId`。HTTP 状态表达操作结果：`200` 成功、`201` 已创建、`202` 已受理。`requestId` 与 `X-Request-Id` 响应头相同，不是 `data.requestId` 中的发布申请标识。

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Location: /api/v1/questions/11111111-1111-4111-8111-111111111111
X-Request-Id: qa-123

{"code":"OK","data":{"questionId":"11111111-1111-4111-8111-111111111111","status":"QUEUED"},"requestId":"qa-123"}
```

- 原本分页的业务体继续使用 `data.items` 与 `data.nextCursor`；空列表为 `[]`，末页游标为 `null`。列表接口用 `limit`/`offset` 分页并在 `data.total` 返回筛选后的总数（省略 `limit` 时返回全部记录），当前不消费游标。助手列表返回 `items` 与 `total`，不含游标。
- `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED` 继续作为任务的 `data.status`，失败原因由 `data.failureCode` 表达。`202` 表示受理，不表示处理成功。文件批次继续使用 `data.files[].status` 和 `data.files[].code`；安全检查拒绝单个文件时，该项是 `REJECTED / SUSPICIOUS_FILE`，不是整批 HTTP 错误。
- `204` 保持空体，下载成功保持原始字节与 `Content-Disposition`，下载失败用 Problem Details。保留适用的 `Location`、`ETag`、`Retry-After`、`Set-Cookie` 和 `X-Request-Id`。登录重定向、静态资源和健康探针不使用成功包装。

API 错误继续使用 `application/problem+json`。正文必含 `type`（`about:blank`）、`title`、`status`、`code`、`requestId`；可选 `detail` 只包含安全信息。`status` 必须等于实际 HTTP 状态，`requestId` 必须等于 `X-Request-Id`。客户端按 HTTP 状态与 `code` 的组合处理，不解析文案；未处理服务端异常不返回内部异常详情。

```http
HTTP/1.1 412 Precondition Failed
Content-Type: application/problem+json
X-Request-Id: qa-124

{"type":"about:blank","title":"Precondition Failed","status":412,"code":"ETAG_MISMATCH","requestId":"qa-124"}
```

## HTTP 状态与错误码对照

OpenAPI 的操作响应声明该操作可返回的组合；`405` 是请求使用未声明方法时的通用框架响应。`INVALID_CURSOR` 为预留码（分页用 limit/offset，不接收游标），`INDEX_NOT_READY` 是发布 worker 内部等待原因，不是 HTTP Problem；客户端以 `data.status: APPROVED_WAITING_INDEX` 表达已受理的发布申请正在等待索引。

| HTTP | `code` | 适用场景 / 前端动作 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`、`IDEMPOTENCY_KEY_REQUIRED`、`INVALID_CURSOR`（预留） | 请求无法解析或缺少/传入非法幂等键；提示检查输入，记录请求 ID |
| 401 | `UNAUTHENTICATED`、`INVALID_CREDENTIALS` | 前者回登录并保留目标地址；后者在本地登录表单提示 |
| 403 | `FORBIDDEN`、`CSRF_INVALID`、`ACCOUNT_DISABLED` | 分别对应越权、CSRF 令牌缺失/不匹配、账号停用；CSRF 失败清理令牌缓存并提示重试，不静默重放写操作 |
| 404 | `RESOURCE_NOT_FOUND` | 不区分不存在或不可访问；提示资源不可用 |
| 405 | `METHOD_NOT_ALLOWED` | 请求方法不受目标路由支持；可返回 `Allow` |
| 406 | `NOT_ACCEPTABLE` | `Accept` 不接受该操作的响应媒体类型 |
| 409 | `STATE_CONFLICT`、`PUBLICATION_CHANGED`、`PUBLISH_REQUEST_OPEN`、`DOCUMENT_NOT_RETRYABLE`、`USERNAME_TAKEN`、`IDEMPOTENCY_KEY_REUSED`、`IDEMPOTENCY_IN_PROGRESS` | 不同操作接受不同冲突类型；按场景刷新或修正输入；同一操作有明确幂等重试约定时复用原键 |
| 412 | `ETAG_MISMATCH` | `If-Match` 格式非法或版本不匹配；保留未保存输入，获取最新版本，由用户确认后再提交 |
| 413 | `PAYLOAD_TOO_LARGE` | 上传超出文件数量、单文件或整批体积限制 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 请求 `Content-Type` 不受支持 |
| 422 | `VALIDATION_FAILED`、`UNSUPPORTED_MEDIA_TYPE`（仅导入文件扩展名） | 提示修正业务输入。导入扩展名不支持是现有 422，与请求媒体类型不支持的 415 区分 |
| 428 | `IF_MATCH_REQUIRED` | 缺少 `If-Match`；要求先获取版本 |
| 429 | `RATE_LIMITED` | 如提供 `Retry-After` 则展示等待时间 |
| 500 | `INTERNAL_ERROR` | 通用提示并展示请求 ID |
| 503 | `DEPENDENCY_UNAVAILABLE`、`AUTH_VALIDATION_UNAVAILABLE` | 不返回部分受保护资源；如提供 `Retry-After` 则展示等待时间；写操作仅在有明确幂等保障时重试 |

## 实施与发布

1. **冻结 OpenAPI。** 成功包装引用原来的 DTO；错误响应约束 Problem Details 必填字段及状态/错误码组合。草稿、发布审核和配置等 `If-Match` 操作不再使用 `409 ETAG_MISMATCH`；`409` 留给业务状态与幂等冲突。旧版本实测为草稿旧 ETag 返回 `409 ETAG_MISMATCH`、缺失 CSRF 返回 `403 FORBIDDEN`，均不是目标契约。
2. **统一后端响应。** 作用域限定为 `/api/v1`；保留控制器状态、头和无正文/非 JSON 响应。MVC 错误与鉴权过滤层走一致的 Problem Details 写出逻辑，明确区分 CSRF 失败与其他 403。
3. **统一前端判断。** 公共请求客户端先按 HTTP 状态和媒体类型选择解析路径，再检查成功包装并解包 `data`，或按受支持的状态/错误码组合处理。格式错误和未知组合使用兜底提示并记录请求 ID。若部署必须短时接入旧服务，只在明确的迁移兼容路径接受旧响应；切换完成后移除兼容。保留 204、下载与 ETag 的专用处理。
4. **配对发布和回滚。** 同批 Web 与 API 沿用 `/api/v1` 协调切换；发布前盘点独立客户端，未能同步迁移时须保留旧契约并另行版本化。发生回归时成对恢复前后端，或切回保留的旧版本，避免让新客户端长期依赖双契约。

## 验收标准

- 静态检查全部 `/api/v1` 操作：2xx JSON 只包装一次，`data` 仍符合原 DTO；检查原本无游标与有游标的数据结构、204 空体、原件字节（SHA-256）、响应头及无包装的重定向。
- 集成测试至少覆盖 `201/202`、两类 401、三类 403、409 业务冲突、412 非法及过期 `If-Match`、428 缺头、405/406/413/415、429 与适用的 `Retry-After`、500 脱敏，以及正文 `requestId` 与 `X-Request-Id` 一致。
- 真实环境回归登录、导入、草稿、审核、检索、问答、引用下载与撤回。`202` 后跟踪任务终态；搜索无匹配、问答无依据、文件部分拒绝按 `data` 的业务状态断言。记录实际 HTTP 状态、业务码、请求 ID 与数据读回，并验证配对回滚可用。
