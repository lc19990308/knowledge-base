# 变更记录：AI 回答逐字显示（SSE 增量流，2026-11-25）

## 背景

问答链路原本是「POST 受理 → worker 生成 → 前端轮询」，整段回答生成完才一次性出现，等待期间只有「正在等待回答…」。用户要求回答像打字机一样逐字出现，而不是一下子吐出来全部。

两种做法摆在这里：前端把已完成文本按节奏揭示（改动小，但首字时间不变），或后端把模型增量实时下发（真正的流式）。用户确认选后者，并接受后端接口与模型适配器一起改。

## 实现过程

1. **进程内增量广播 `AnswerStreams`**
   - 按问题 ID 维护通道：`delta` 追加文本并推给订阅者，`finish`/`fail` 结束并保留 60 秒（覆盖「回答刚结束才连上」），维护任务按保留时长与通道年龄清理。
   - 订阅时先回放已有增量再注册，回放与注册在同一把锁内，客户端不会先收到结束再收到迟到增量；单条回答最多缓存 64K 字符。
   - 明确边界：这是单实例内存广播，与现有「限流在进程内、任务用数据库租约」一致；多实例部署需要换成 Redis 等共享通道。

2. **模型适配器流式化**
   - 请求统一带 `stream: true`：OpenAI 兼容协议读 `choices[0].delta.content` 与 `usage` 分片，Anthropic 读 `content_block_delta` / `message_start` / `message_delta`，`[DONE]` 或 `message_stop` 结束。
   - 网关忽略 `stream` 直接返回 JSON 时按原解析路径处理，并把整段作为一次增量交给调用方，行为与改造前一致。
   - `HttpClient` 的请求超时只覆盖到响应头，新增 `aiqa.model.stream-timeout`（默认 180 秒）看门狗：到点关闭响应体，读取抛错并归一化为「网关不可达」，避免 worker 线程被挂住。
   - 非 JSON 行（心跳、空行、坏分片）跳过，不让个别分片打断整段回答。

3. **worker 收尾时机**
   - `AnswerService.answer` 改为返回是否真正执行（`markRunning` 成功）；只在其上加 `@Transactional`，增量在事务内推送。
   - `AnswerWorker` 在回答事务提交后再读一次问题状态并 `finish` 通道，客户端收到的 `done` 一定对应已提交状态；异常路径 `fail` 通道让前端回退轮询。重复领取任务的 worker 不会关闭别人的流。

4. **SSE 接口 `GET /api/v1/questions/{questionId}/stream`**
   - `SseEmitter`，事件 `delta` / `done` / `error` / `timeout`；15 秒注释心跳，总等待 `aiqa.answer.stream-timeout-seconds`（默认 120 秒）。
   - 归属校验与会话鉴权沿用 `CurrentUser`；连接算一次状态读取，与轮询共用读取限流。
   - 已结束的问题直接回 `done`，客户端照常读详情接口；增量只用于显示，段落、引用与证据有效性仍以 `GET /questions/{questionId}` 为准。

5. **前端逐字显示**
   - `api/http.ts` 新增 `openEventStream`，复用统一鉴权、CSRF 与 Problem Details 处理；`api/stream.ts` 用 fetch 逐块解析 SSE 帧（跨块半帧留在缓冲）。
   - `typewriter.ts` 按 60 字/秒基础节奏显示，积压时按 800ms 窗口追赶（本地替身一次性返回也不会长时间落后）；`prefers-reduced-motion` 或页面切后台直接显示全部。
   - `AssistantView` 待回答时优先走增量流，逐字区域带闪烁光标；收到 `done` 后并行读一次详情，等打字机追上再替换为正式段落与引用，避免最后一下跳字。
   - 出错、超时、代理断开或后端未部署该接口时自动回退原来的轮询，「继续查询」按钮保持不变。

6. **契约、配置与测试**
   - OpenAPI 新增该路径（`text/event-stream`）与事件说明。
   - 新增 `aiqa.model.stream-timeout`、`aiqa.answer.stream-timeout-seconds` 两个配置项，均有默认值和环境变量。
   - 单测：`AnswerStreamsTest`（回放顺序、结束、迟到订阅、退订、清理、缓存上限）、`GatewayAnswerModelTest`（两种协议的流式分片、JSON 回退、网关 HTTP 错误）。
   - E2E 新增 `STREAM-01`：进程内起一个不校验密钥的慢速 OpenAI 兼容网关，断言浏览器出现逐字区域、结束时换成带引用的正式回答；接口侧断言增量先于结束、分片拼接等于最终落库段落。慢速网关让该用例不依赖真实模型与外部服务。

## 涉及文件

后端：

- `backend/src/main/java/dev/aiqa/answer/AnswerStreams.java`（新增）
- `backend/src/main/java/dev/aiqa/api/AnswerStreamController.java`（新增）
- `backend/src/main/java/dev/aiqa/answer/GatewayAnswerModel.java`
- `backend/src/main/java/dev/aiqa/answer/OpenAiCompatibleAnswerModel.java`
- `backend/src/main/java/dev/aiqa/answer/AnthropicAnswerModel.java`
- `backend/src/main/java/dev/aiqa/answer/AnswerService.java`
- `backend/src/main/java/dev/aiqa/worker/AnswerWorker.java`
- `backend/src/main/java/dev/aiqa/worker/MaintenanceWorker.java`
- `backend/src/main/resources/application.yml`
- `backend/src/test/java/dev/aiqa/answer/AnswerStreamsTest.java`、`GatewayAnswerModelTest.java`

前端与文档：

- `frontend/src/api/http.ts`、`frontend/src/api/stream.ts`（新增）、`frontend/src/typewriter.ts`（新增）
- `frontend/src/views/AssistantView.vue`
- `frontend/e2e/assistant.mjs`、`frontend/e2e/run.mjs`、`frontend/e2e/stack.mjs`
- `docs/ai-qa-openapi.yaml`、`docs/ai-qa-status.md`、`docs/ai-qa-technical-design.md`、`docs/ai-qa-backend-design.md`

## 验证命令

```sh
# 1. 后端单测（增量广播 + 两种协议流式解析）
cd backend
mvn -o -q -Dtest='AnswerStreamsTest,GatewayAnswerModelTest' test

# 2. 前端门禁
cd frontend
npm run lint && npm run typecheck && npm run build

# 3. 全站 E2E（真实模型 + 向量召回；含新增 STREAM-01）
E2E_REAL_MODEL=1 E2E_EMBEDDING=1 RELEVANCE_GATE=model \
AIQA_MODEL_CREDENTIALS_MAGPIE=local \
E2E_MODEL_BASE_URL=http://127.0.0.1:3425/v1 \
E2E_MODEL_NAME=auto/deepseek-v4.1-flash \
npm run test:e2e
```

## 执行结果

| 项        | 结果                                                                                                       |
| --------- | ---------------------------------------------------------------------------------------------------------- |
| 后端单测  | `AnswerStreamsTest` 8 项、`GatewayAnswerModelTest` 6 项通过（`mvn -o -Dtest='AnswerStreamsTest,GatewayAnswerModelTest' test`） |
| 前端门禁  | `npm run lint`、`npm run typecheck`、`npm run build` 通过（构建分块超 500 kB 为既有告警）                   |
| 全站 E2E  | 真实模型（Magpie `auto/deepseek-v4.1-flash`）+ 向量召回：82 项流程通过、1 项失败（另一处并发改动的 `KB-UPD`），质量题 19/20（`QA06` 漏答失效日期，真实模型内容抖动） |
| 增量流用例 | `STREAM-01` 通过：24 个 `delta` 后收到 `done`；增量拼接与落库段落逐字一致；生成中浏览器显示 `…应在 **15 个工▍`，结束后渲染为带 1 条引用的正式回答 |

本地开发栈实测（真实 Magpie，2026-11-25 23:42）：

- 首个字符出现在提问后 12.5 秒（前段耗时是检索与语义相关性判定，改造前同样存在，只是此前整段返回，用户感知不到）；随后 1.5 秒内逐字区域从 3 字增长到 89 字，光标正常闪烁；结束后 2 条引用和「已回答」正常。
- SSE 响应为 `200 text/event-stream`；截图见 [生成中](e2e-results/streaming-2026-11-25-mid.png) 与 [结束后](e2e-results/streaming-2026-11-25-final.png)。

E2E 报告：`docs/e2e-results/frontend-e2e-real-streaming-1f243695.zip`（含 `streaming-in-progress.png`、`cases.jsonl` 里的 `STREAM-01` 证据）。

## 边界与遗留

- **单实例**：增量通道在进程内存里。多实例部署时，只有接收到任务的实例能推增量，其他实例的 SSE 会等到超时后回退轮询——结果正确但失去逐字效果；届时把 `AnswerStreams` 换成共享通道实现即可，接口与前端不用改。
- **事务时长**：模型调用仍在 `AnswerService.answer` 的事务内（改造前也是如此），流式只是把等待时间拉长到网关输出时间，单个回答会占用一个数据库连接更久。当前 worker 并发为 2、连接池 10，够用；并发再提高时应先把模型调用移出事务。
- **乐观显示**：流式文本是生成中的预览。生成期间来源撤回或权限变化时，后端仍按原规则丢弃结果，前端在 `done` 后读到失败状态或重新等待，不把预览当作最终回答。
- **未覆盖**：网关在流中途断连只验证了单元级（读取抛错归一为不可达），没有对真实网关做断流演练；多实例与反向代理（nginx 等）下的长连接行为需在目标部署环境验证。
- **生成中的 Markdown**：强调标记还没闭合时，逐字区域会短暂显示原始 `**`（结束时渲染正常）。这是「边生成边按 Markdown 解析」的固有限制，未做特殊处理。
- **真实模型抖动**：E2E 真实模型运行里质量题两次分别为 19/20（分别漏答 `QA09` 的拒答措辞和 `QA06` 的失效日期），属于模型内容抖动，与本次链路改造无关；判定口径未改动。
- `OpenApiContractTest` 当前因另一处并发改动（`POST /spaces/{spaceId}/documents/{documentVersionId}/versions` 未写入契约）失败，与本变更无关，未在本轮修改。
