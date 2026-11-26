# AI 智能问答平台｜实施状态

- 核对日期：2026-11-23
- 当前交付范围包括知识空间与导入、发布与撤回、AI 助手与引用、问答统计、本地账号密码登录。企业 SSO 已移出交付范围，部署仅启用本地账号。

## 1. 后端

Java 17、Spring Boot 4.1、PostgreSQL。Flyway 迁移至 V13，包含会话生命周期、按任务类型的容量索引、文本块位置与引用证据，以及单次提问指定模型。

| 模块           | 当前实现                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 空间与授权     | 空间成员按读、维护授权。普通用户的搜索、问答和文档只读取授权且已发布的内容，发布管理和统计按角色过滤。                                                                                                                                                                                                                                                                                                                                                                           |
| 导入与检索     | 批量导入逐文件反馈，同空间同名跳过，解析失败可按文档版本重试。支持 PDF、DOCX、XLSX、PPTX、TXT、Markdown 的已有文字，无 OCR。关键词检索覆盖文件名和切分后的正文，区分 `READY`、`PROCESSING`、`NO_MATCH`。空间维护人员可经 API 发起索引重建。                                                                                                                                                                                                                                      |
| 审核与发布     | 冻结快照提交审核，支持驳回、重提、审核通过后异步生效。撤回清除当前发布指针，按撤回水位、当前发布快照和授权校验历史内容及旧链接。正常切版可保留仍存在且可读的历史引用。                                                                                                                                                                                                                                                                                                           |
| 助手与会话     | 按助手绑定、用户授权、当前发布状态的交集取证。首个问题由后端在同一事务内自动建会话，`POST /questions` 返回新会话，前端不再先建空会话。标题按首个问题提炼，问题过短时用首轮回答补全，超过 30 个字符截断并加省略号；用户显式命名的标题不被覆盖。提问异步受理；客户端订阅 `GET /questions/{id}/stream`（SSE）逐字显示，结束后读问题详情取段落与引用，流不可用或超时回退轮询。会话支持归档、恢复和软删除。归档后可读历史，恢复后可继续提问。每次模型调用以当前问题和本次证据为输入。 |
| 回答与引用     | 回答保存实际传给模型的证据，引用预览可返回原文片段、来源位置（可提取时）、文档版本和有效性。旧引用没有证据片段时回退到文档预览。历史回答按段屏蔽失去授权的证据依赖。冲突标记使用同名有效文档多版本规则，跨文档政策冲突仍需样本验收。                                                                                                                                                                                                                                             |
| 模型配置       | 管理员可管理多条配置和全局默认项。登录用户可读取模型选项，在助手里为单次提问指定模型。协议为 `openai_compatible` 和 `anthropic`，凭据按引用在服务端解析。未指定模型且无有效默认网关时使用本地 `FakeAnswerModel`，用量为估算值。真实网关未上报用量时保留缺失状态。                                                                                                                                                                                                                |
| 语义相关性判定 | 配置了默认模型后，回答前先把问题与前 8 条候选证据发给同一网关做一次判定（只回一行 JSON）；判定不足则按 `NO_EVIDENCE` 返回且不写引用，判定失败按失败开放继续回答，判定消耗的 Tokens 单独记一条用量。开关 `aiqa.answer.relevance`（`model`/`fake`/`off`）。2026-11-25 用本机 Magpie 网关验收：20 题全通过，`QA09` 由判定器拒答。                                                                                                                                                   |
| 统计           | 按用户统计会话、提问、失败和 Tokens，按北京时间自然日、周、月对账。支持分桶趋势、排行、逐提问明细和按空间的文档指标。管理员可查看全员或指定用户，Tokens 按提问受理时间归期。                                                                                                                                                                                                                                                                                                     |
| 登录与审计     | 本地账号密码、管理员建号和改密、按用户名限制登录失败次数。现有代码保留 OIDC，交付部署保持关闭。会话默认空闲 30 分钟、绝对期限 12 小时，登录、管理员账户管理、权限变更及发布撤回有审计记录。                                                                                                                                                                                                                                                                                      |
| 队列与限流     | PostgreSQL 任务队列支持按类型限制待处理容量和每实例并发数，通过数据库租约领取任务。用户限流在进程内，读限流默认只观察，配置后可强制执行。                                                                                                                                                                                                                                                                                                                                        |
| API            | `/api/v1` 下 50 个路径、65 个操作，含 Spring Security 提供的登出。列表接口用 `limit`（1–200，省略返回全部）/`offset` 分页并在 `total` 给出筛选后的总数；知识空间与发布申请支持状态、名称筛选，`GET /conversations/{id}` 补齐分页后不在当前页的会话。`OpenApiContractTest` 双向检查 HTTP 方法和路径，典型响应另由集成测试覆盖。                                                                                                                                                   |

## 2. 前端

Vue 3、TypeScript、Vite 7。10 个业务页面：工作台、知识库、导入、发布管理、问答统计、系统设置、AI 助手、助手配置、回答模板、文档详情。另有登录页、无权访问和找不到页面的提示页。

- AI 助手在首个问题时建立会话，可查看已归档会话、恢复或删除。引用弹窗展示本次命中的证据，允许按授权下载原件。统计页通过 `BaseChart` 渲染 ECharts 折线图。
- AI 助手以助手为中心：切换助手即切换检索范围上限，提问区在「助手绑定 ∩ 授权 ∩ 已发布」内多选、默认全选，取消部分空间即收窄本次取证，不能超出助手绑定；全选时省略 `requestedSpaceIds`。选择只保留在内存中，换助手或空间撤回后回到全选，旧版本写在 localStorage 里的按助手范围记录已废弃并清理。
- 助手配置的绑定空间改为「已选 chips + 名称搜索」两段式：空间可搜索、分页加载、逐个移除，已绑定项在结果里显示「已添加」；未绑定空间的助手保存时被拦截；列表「绑定空间」列显示前两个空间名与 `+N` 余量。
- 系统设置、回答模板与助手配置要求系统管理员权限。普通用户可通过直接地址访问发布管理与统计，由服务端限制可见数据。
- 访问受限路由或收到会话失效的 `401` 时，登录入口保留原页面路径及参数。退出登录需要确认。
- 列表页走服务端分页：接口按 `limit`/`offset` 返回一页并附带筛选后的 `total`，公共分页器提供 10/20/50 条/页（默认 10），知识库名称/状态与发布状态筛选同步下推到接口，会话侧栏按「会话 / 已归档」分别分页。表格默认高度可容纳 10 条数据行，统计页与抽屉内的表格保持原紧凑高度。
- 原型对齐的历轮变更和现存差异见[前端偏离报告](./ai-qa-frontend-deviation-report.md)。

## 3. 本地运行与验证

启动与配置见[后端 README](../backend/README.md)和[前端 README](../frontend/README.md)。Vite 默认将 `/api`、`/oauth2` 和 `/login/oauth2` 代理到 `127.0.0.1:8080`，`/login` 由 SPA 处理。后端集成测试需将 `IT_DB_URL`、`IT_DB_USER`、`IT_DB_PASSWORD` 指向隔离 PostgreSQL，测试为每个用例类创建独立 schema。

**2026-11-23 本地验证**

- 在独立 PostgreSQL 实例运行 `mvn -o test`，34 项通过，无失败或跳过。覆盖本地登录、模拟 OIDC 回调、会话归档恢复与删除、发布撤回、权限过滤、索引重建、文本提取和模型协议适配。
- 前端 `npm run lint`、`npm run typecheck`、`npm run build` 均通过。Vite 报告统计页和模板页的构建分块超过 500 kB。
- 现有本地服务返回健康状态 `UP`。浏览器通过 Vite 代理读取知识空间和助手会话，普通与已归档会话查询均返回 `200`，页面显示对应分类。直接请求登录提供方返回 `200` 和 `items: []`。
- 会话自动建与标题（2026-11-23）：`mvn -o test`（`IT_DB_*` 指向本地 PostgreSQL）47 项通过；前端 `lint`、`typecheck` 通过。浏览器以 Smoke Admin 实测：点击「新提问」不发任何请求，只在左侧列表插入本地占位「新会话」；首个问题只发 `POST /api/v1/questions`，成功后占位条目替换为真实会话及自动标题，随后侧栏出现标题「年假和调休的具体规定，包括申请需要提前几个工作日、由谁审批…」（30 字符含省略号），短问题「年假提前几天申请」保持原文。工作台直接提问同样走该接口，并在「最近会话」显示标题。

**2026-11-24 助手与空间绑定重构**

- 前端 `lint`、`typecheck` 通过。
- 浏览器实测（现有本地实例、系统管理员）：
  - 助手配置编辑弹窗：已绑定空间显示为 chips（带「未发布」标记和移除按钮）；搜索「差旅常规」后结果由 12 条收敛到 1 条，按钮变为「全选筛选结果 1 个」，已绑定项显示「已添加」；添加「员工手册」后保存 `PUT /api/v1/assistants/{id}` 返回 200，列表列随即显示「员工手册、E2E 驻点专项-e2e-eb19be79 +1」，移除后保存还原为 2 个空间。
  - 绑定 0 个空间保存被拦截，表单顶部提示「至少绑定 1 个空间…」，未发出请求。
  - AI 助手页：提问区显示助手切换器与范围内多选控件（默认全选），弹层列出空间名、发布状态与可检索文档数；切换助手同步地址参数，无消息时直接生效，有消息时弹确认「会开始一个新会话」。
  - 新会话提问只发 `POST /api/v1/questions`（202）；全选时请求体不含 `requestedSpaceIds`，收窄时只提交选中子集。
- 本轮提问的模型调用返回 `MODEL_UNAVAILABLE`，属于当前模型网关状态；未用真实回答复核检索范围。问答取证逻辑未改动。

**2026-11-24 助手列表筛选与绑定空间名回填**

- 接口：`GET /api/v1/assistants` 新增可选 `q`（按助手名称包含匹配，与空间列表一致）；助手响应中每条绑定新增只读 `spaceName`，写入仍只接受 `spaceId` 与 `role`。契约见 [OpenAPI](./ai-qa-openapi.yaml) 的 `AssistantBindingView`。
- 前端：助手配置页新增名称筛选，250ms 防抖，清空筛选立即回到未筛选的第一页；「绑定空间」列优先使用接口回填的名称，旧响应缺名称时回退到已加载的空间列表。
- 验证：`mvn -o compile` 与 `OpenApiContractTest`（1 项）通过。临时实例上 `GET /assistants?management=true&q=差旅` 返回 `total: 3` 且绑定项带 `spaceName`，不传 `q` 时 `total: 7`。浏览器在 5173 实测：输入「差旅」列表收敛为 3 条，清空后恢复全量；「差旅助手-e2e-eb19be79」的绑定空间列显示「E2E 差旅常规-e2e-eb19be79、E2E 驻点专项-e2e-eb19be79」。

**2026-11-25 登录页密码框显示/隐藏切换**

- 前端（仅 `frontend/src/views/LoginView.vue`）：密码框右侧新增眼睛图标按钮（`@lucide/vue` 的 `Eye` / `EyeOff`），点击在密文与明文之间切换，输入内容不变。按钮为 `type="button"`，不触发表单提交；`disabled` 跟随登录中的忙碌态；可访问名称随状态在「显示密码」「隐藏密码」间切换，Tab 可达并显示焦点环。输入框右侧留位由组件内局部变量 `--password-toggle-size` 与 `--space-3` 同源计算，未新增全局 token；配色复用 `--color-text-muted` / `--color-text` / `--shadow-focus`，深浅色主题自动跟随。
- 验证命令：`cd frontend && npx eslint src --max-warnings 0 && npm run typecheck`；`npx prettier --check src/views/LoginView.vue`。
- 执行结果（Chrome 无头 + Playwright 实测 5173）：
  - `getByLabel('密码', { exact: true })` 仍唯一命中 `#login-password`，既有 E2E 选择器不受影响；初始 `type=password`，点击后 `type=text` 且值保持，再点恢复 `password`，`aria-label` 同步为「隐藏密码/显示密码」。
  - 切换按钮未产生任何 `POST /auth/login`；用户名与密码都填写后按 Enter 仍发出 1 次 `POST /auth/login`（401），页面留在 `/login` 并提示「用户名或密码错误。」。
  - 键盘 Tab 聚焦按钮时焦点环为 `0 0 0 4px`（`--shadow-focus`）；窄屏 390px 下密码框宽 300px、按钮右缘距输入框右缘 5px，无溢出；深色主题下图标色 `rgb(141,153,168)`、输入框背景 `rgb(21,26,33)`，对比可辨。
- 遗留问题：`frontend/update-shot.tmp.mjs`（更新文档流程的临时截图脚本，非本次改动产物）未纳入 eslint 忽略，导致根目录 `npm run lint` 报 `no-undef`；`eslint src` 与 `typecheck` 均通过，该文件未在本次范围内处理。

**2026-11-25 AI 回答逐字显示（SSE 增量流）**

- 后端：新增 `GET /api/v1/questions/{questionId}/stream`（`SseEmitter`，事件 `delta`/`done`/`error`/`timeout`，15 秒心跳，默认 120 秒总超时）；`AnswerStreams` 进程内按问题广播并回放已生成文本，结束后保留 60 秒；`AnswerService` 在模型调用中推送增量，`AnswerWorker` 在事务提交后收尾；OpenAI 兼容与 Anthropic 适配器改为流式解析，网关忽略 `stream` 时回退整段 JSON；新增 `aiqa.model.stream-timeout`（默认 180 秒）读取看门狗与 `aiqa.answer.stream-timeout-seconds`（默认 120 秒）。
- 前端：`api/stream.ts` 解析 SSE，`typewriter.ts` 按 60 字/秒并带 800ms 追赶窗口逐字渲染；`AssistantView` 待回答时先显示逐字区域（带闪烁光标），收到 `done` 后等打字机追上再替换为正式段落与引用，出错/超时/接口缺失时回退原轮询。
- 验证：`AnswerStreamsTest`（8 项）、`GatewayAnswerModelTest`（6 项）通过；前端 `lint`、`typecheck`、`build` 通过；全站 E2E（真实模型 + 向量召回）新增 `STREAM-01` 通过，`delta` 拼接与落库段落一致（报告 `docs/e2e-results/frontend-e2e-real-streaming-1f243695.zip`）。
- 本地开发栈实测（真实 Magpie `DeepSeek V4.1 Flash`）：首字 12.5 秒（检索 + 语义判定），随后 1.5 秒内可见文本从 3 字增长到 89 字；结束后显示 2 条引用。截图 `docs/e2e-results/streaming-2026-11-25-mid.png`、`streaming-2026-11-25-final.png`。
- 已知边界：增量通道是单实例内存广播，多实例需共享通道；生成中未闭合的 Markdown 标记会短暂显示；`OpenApiContractTest` 因另一处并发改动（文档更新接口未写入契约）失败，与本变更无关。

**部署验证与业务验收**

- **对象存储**：默认将文档原件存在本地目录，S3 适配器已通过模拟端点测试。仅在部署选用 S3 兼容存储时，需要用实际存储桶验证上传、读取和应用账号权限。
- **模型网关**：模型协议已通过本地替身测试。部署使用正式模型时，需要用目标地址、模型 ID 和服务端凭据发送一个测试问题，核对回答、错误处理和模型上报的 Tokens。
- **政策样本**：由业务方选取有标准答案和出处的政策资料，用样题核对检索是否找到正确版本、回答与引文是否一致、遇到冲突或失效政策时是否提示，以及无依据时是否明确说明。方法见[政策资料检索评测](./ai-qa-retrieval-evaluation.md)。
- 企业病毒扫描引擎、多实例下的限流与任务租约、性能与并发仍待对应环境验证。

**2026-11-26 回答模板编辑器 radio 与内边距**

- 前端（仅 `frontend/src/components/TemplateBodyEditor.vue`、新增 `frontend/src/components/FormRadioGroup.vue`、`frontend/src/styles/components.css`、`frontend/eslint.config.mjs`）：回答模板弹窗「模板正文」的编辑模式从纵向小按钮改为单选卡片组（原生 radio 语义、整卡可点、选中态用主题 token），两个编辑器正文统一 16px 内边距；顺带修正该组件非 scoped 样式里 `:deep()` 被浏览器丢弃的既有问题。
- 验证：`npm run typecheck` 通过；`npx eslint` 检查三个改动文件通过。本地浏览器实测 radio 两项、切换选中正常，结构化 `.tiptap` 与高级文本 `.cm-content` 计算 padding 均为 16px；深色主题选中态取 `--color-surface-selected` / `--color-action` 无亮色残留，390px 窄屏 radio 自动单列、弹窗完整落在视口内。截图 `docs/e2e-results/answer-template-editor-radio-structured-2026-11-26.png`、`-advanced-2026-11-26.png`、`-dark-2026-11-26.png`、`-narrow-2026-11-26.png`。详见[变更记录](./ai-qa-change-2026-11-26-template-editor-radio.md)。
- 遗留：窄屏（390px）顶栏搜索 `.topbar-search` 右边缘 405.9px 超出视口，属既有全站布局问题，本次未改。

**2026-11-26 AI 辅助生成回答模板**

- 后端：新增 V16 迁移 `answer_template_generation`（反范式快照字段、幂等唯一约束、状态 CHECK）+ `job.GENERATE_TEMPLATE` 任务类型；`TemplateGenerationService/Repository/PromptBuilder/OutputValidator/Model/Worker` 与两个接口（POST 创建 202、GET 轮询）；默认模型解析、并发 3 / 24h 20 次配额、网关错误重试与 failureCode、审计与指标；OpenAPI 已补齐。
- 前端：页面头部新增「AI 生成模板」独立弹窗 `TemplateGenerationDialog`（格式 radio、场景、需求 0/2000、模型下拉、轮询 1.5s 上限 120s、失败码中文提示）；生成结果只提供「复制内容」（不自动回填、不写模板）；示例「复制内容 / 下载 .txt」补 lucide 图标。
- 验证：后端新增 3 个单测类通过（提示词、输出校验、两种协议解析与 429），`mvn -DskipTests compile` 通过；前端 `npm run typecheck`、改文件 eslint、整仓 lint 通过。Playwright 桩接口实测独立弹窗成功链路（排队→生成→复制内容，剪贴板与预览一致、无「应用」按钮）、失败链路（INVALID_MODEL_OUTPUT 文案）、390px 弹窗与图标。截图 `docs/e2e-results/ai-template-generation-{form,result,copied,narrow}-2026-11-26.png`、`answer-template-examples-icons-2026-11-26.png`。详见[变更记录](./ai-qa-change-2026-11-26-ai-template-generation.md)。
- 未验证：真实模型网关调用与 V16 在真实 PostgreSQL 的执行；`OpenApiContractTest` 被另一会话未登记的 DocumentController 新路由阻塞；生成历史列表未做。

**2026-11-26 回答模板格式示例与下载**

- 前端（`frontend/src/views/AnswerTemplatesView.vue`、新增 `frontend/src/answerTemplateExamples.ts`）：回答模板页头新增「格式示例」按钮，弹窗用 `FormRadioGroup` 单选决定展开哪份模板——结构化《客服问答 · 规则分段模板》按规则区块分段并高亮 `{变量}`，高级文本《知识库问答 · 约束提示词模板》以等宽 `pre` 展示完整提示词；同一时间只显示选中的那份，「下载 .txt」用 Blob 导出当前示例为 UTF-8 纯文本。
- 验证：`npm run typecheck` 通过；两个改动文件 `eslint --max-warnings 0`、`prettier --write` 通过。浏览器实测 radio 两项且默认选中结构化、只显示 1 张卡片、结构化变量高亮 3 处；切到高级文本后内容变为 22 行 `pre-wrap`，方向键可切换；下载跟随选择（`回答模板示例-结构化.txt` 1024B / `回答模板示例-高级文本.txt` 988B，含全部三个变量）；390px 窄屏 radio 转单列、弹窗正常换行。截图 `docs/e2e-results/answer-template-examples-2026-11-26.png`、`-advanced-2026-11-26.png`、`-narrow-2026-11-26.png`。详见[变更记录](./ai-qa-change-2026-11-26-answer-template-examples.md)。
- 补充：按按钮语义把「格式示例」「下载 .txt」改为 `bk-btn-info` 浅蓝（只读操作），并新增「复制内容」——`navigator.clipboard.writeText` 复制当前示例，按钮 2 秒内显示「已复制/复制失败」并播报；切换示例自动复位。浏览器实测复制内容与展示一致。
- 遗留：示例仅支持下载/复制，未做「一键填入编辑器」。
- 待评审：AI 辅助生成模板的详细方案（异步任务、反范式快照字段、幂等与配额）见[计划](./ai-qa-ai-template-generation-plan.md)。

**2026-11-26 提问明细图表展示**

- 前端（仅 `frontend/src/views/StatisticsView.vue`）：「提问明细（供对账，最多最近 200 条）」折叠区在表格上方新增两张图——时间分布（自然日按 24 小时、自然周/月按日期，双柱显示提问与失败）和状态构成（成功/失败/生成中的环形图）。复用 `BaseChart` 与现有主题 token，明细表保留供逐条对账。
- 验证：`npx eslint src/views/StatisticsView.vue --max-warnings 0` 与 `npx vue-tsc --noEmit` 通过。本地浏览器实测：真实接口多周期为空时正确提示「该周期没有提问记录」；用构造的 200 条明细验证渲染，两张 canvas 各 529×200、表格 200 行，自然日 24 个小时桶顺序与刻度正确。截图 `docs/e2e-results/statistics-detail-charts-2026-11-26-week.png`、`-day.png`。详见[变更记录](./ai-qa-change-2026-11-26-statistics-detail-charts.md)。
- 同日晚补充处理遗留项：图表上方增加「基于明细接口最近 N 条，与全量趋势口径不同」说明；统计页 390px 窄屏溢出修复（grid 列 `minmax(0, 1fr)`、页头与 KPI 收列、明细表 `min-width: 720px` 横向滚动），页面自身 `scrollWidth` 358 等于容器宽；深色主题截图确认颜色跟随；`eslint.config.mjs` 忽略临时脚本 `**/tmp-*.mjs` 后全量 `npm run lint` 通过。
- 未覆盖：390px 下全站顶栏搜索按钮仍有 16px 溢出（`AppLayout` 既有问题，所有页面共存）；图表仍基于明细 200 条口径。截图 `docs/e2e-results/statistics-detail-charts-2026-11-26-{week,day,dark,mobile}.png`。

**2026-11-26 弹窗 / 抽屉 / 下拉菜单动画**

- 依赖：前端新增 `motion-v@^2`（含 `@vueuse/core`、`framer-motion` 等传递依赖）。
- 新增 `frontend/src/motion.ts`：动画时长与曲线复用既有 `--motion-fast`(140ms) / `--motion-base`(220ms) / `--ease-standard`，导出遮罩、弹窗、抽屉、下拉的开关状态与退场时长。
- 进场：`ModalShell`、`DrawerPanel`（遮罩 + 面板）与 `SelectMenu`（下拉面板）改用 `Motion as-child` 把动画落在真实 DOM 上，DOM 结构、scoped 样式、模板 ref、`role="dialog"` 均不变；下拉箭头随展开旋转，收起动画播完才 `hidePopover()`。
- 退场：新增 `OverlayTransition`（`<Transition :css="false" @leave>`）包住 14 处浮层挂载点，用户关闭与「保存成功后程序化关闭」走同一条退场路径；`SelectMenu` 因自己管理显隐，使用 `useExitAnimation` 先播动画再卸载。
- 边界修复：下拉面板遇到页面滚动改为**重新贴合触发器**（抽出 `positionMenu()`，滚动 / 尺寸变化都复用），只有触发器整个离开视口才收起，不再被浏览器的滚入视野等滚动误关。
- 验证：`npx vue-tsc --noEmit`、全仓库 `npm run lint`、`npm run build` 均通过；浏览器逐帧采样确认 14 处浮层（含 Teleport 引用预览、抽屉内嵌套弹窗）退场动画完整且节点正常移除，程序化关闭同样有退场，下拉滚动后保持 6px 贴合、触发器离屏才收起，`reducedMotion: reduce` 下 80ms 内收起。详见[变更记录](./ai-qa-change-2026-11-26-motion-animation.md)。
- 遗留：`PublishingView` 审核弹窗因开发库无发布申请数据未实测（挂载方式与其他弹窗一致）；验证过程创建的知识空间 `动效验证-649780` 需手工清理（后端无删除空间接口）。

**2026-11-26 问答统计页信息架构重排**

- 前端（`frontend/src/views/StatisticsView.vue`）：KPI 四卡改为一条指标带；趋势图与状态堆叠条合并为一张卡，删除明细区的重复图表；排行与空间指标合卡并只展示 Top 5（标题提示「另有 N 位」）、表尾保留全员/全部空间合计；提问明细去掉折叠、直接平铺，表头吸顶、表内滚动最近 200 条。1440×900 页面总高由 1920 降到 **900（无整页滚动）**。
- 附带：`frontend/eslint.config.mjs` 为 `e2e/**/*.mjs` 声明浏览器只读全局（其他会话残留的临时探测脚本此前会卡住全量 lint），未改这些脚本文件。
- 验证：`npm run lint`、`npm run typecheck`、`npm run build` 通过；浏览器实测浅色/深色 1440×900 无滚动、表内滚动与吸顶表头正常、真实空数据各区块空状态正确、390px 下本页无横向溢出。截图 `docs/e2e-results/statistics-after-relayout-2026-11-26.png`、`-dark-`、`-mobile-`、`-scrolled-`。详见[变更记录](./ai-qa-change-2026-11-26-statistics-layout.md)。
- 遗留：明细固定约 6 行高、排行只到 Top 5，全量对账需表内滚动；390px 全站顶栏搜索按钮的 16px 溢出仍属 `AppLayout` 既有问题。

**2026-11-26 数据清洗（前端页面 + 后端管线）**

- 新增 `/cleaning`「数据清洗」页：清洗草稿箱与清洗批次两个页签，管理员可筛选、多选、批量通过/驳回，并把已通过条目流转成 Markdown 文档版本进空间草稿；上传支持文件夹（保留相对路径）与 `.zip` 压缩包。方案见[实施计划](./ai-qa-data-cleaning-plan.md)。
- 后端按计划实现：Flyway `V17` 新增三张清洗表并扩展 `job` 任务类型；`dev.aiqa.cleaning` 包提供上传/安全解包/格式读取/去噪拆条/五维规则分/模型打分/审核/流转；流转生成的 Markdown 文档接入既有解析、草稿与发布链路，未审核前对普通用户不可见。
- 接口：`/api/v1/cleaning/**` 共 8 个操作、7 个路径，仅系统管理员；上传、审核、流转均要求 `Idempotency-Key`，审核驳回必填原因，流转仅接受已通过条目。契约已写入 OpenAPI。
- 前端复用既有组件与按钮语义；新增 `FormCheckbox`（原生 checkbox + 半选）与 `ScoreMeter`（分数三档 + 细进度条），未新增全局样式 token。
- 验证：后端 `mvn -o test` 125 项中 124 通过（唯一失败为 `PublishingAndWithdrawalIT` 与既有管理员读取策略不一致，详见变更记录）；本次新增清洗相关单测 18 项与全链路集成测试 1 项全部通过。前端 `npm run lint`、`typecheck`、`build` 通过，Playwright 9 项交互检查通过、`pageerror` 0，截图 `docs/e2e-results/cleaning-*.png`。详见[后端变更](./ai-qa-change-2026-11-26-cleaning-backend.md)与[前端变更](./ai-qa-change-2026-11-26-cleaning-frontend.md)。
- 遗留：未用真实模型网关验收打分；前后端尚未联调（需重启后端）；正式 E2E 用例未入库；打分 Token 未计入问答统计；390px 下全站顶栏搜索仍有 16px 既有溢出。

## 4. 文档索引

| 文档                                                                      | 用途                                  |
| ------------------------------------------------------------------------- | ------------------------------------- |
| [产品需求](./ai-qa-prd.md)                                                | 业务规则、验收标准和当前实现对照      |
| [首期技术方案](./ai-qa-technical-design.md)                               | 架构、关键流程与实现边界              |
| [后端详细设计](./ai-qa-backend-design.md)                                 | 数据模型、任务队列、身份与部署        |
| [OpenAPI 契约](./ai-qa-openapi.yaml)                                      | HTTP 接口、数据结构与错误响应         |
| [后端 API 审核](./ai-qa-backend-api-audit.md)                             | 历史缺口、修复记录与本轮对照          |
| [前端偏离报告](./ai-qa-frontend-deviation-report.md)                      | 页面、原型差异与修复记录              |
| [原型需求审查](./ai-qa-prototype-review.md)                               | 原型评审发现及其当前适用范围          |
| [政策资料检索评测](./ai-qa-retrieval-evaluation.md)                       | 关键词基线、证据与业务评测方法        |
| [回答逐字显示变更](./ai-qa-change-2026-11-25-answer-streaming.md)         | SSE 增量流、适配器流式与回退策略      |
| [提问明细图表变更](./ai-qa-change-2026-11-26-statistics-detail-charts.md) | 统计页明细的时间分布与状态构成图      |
| [回答模板编辑器变更](./ai-qa-change-2026-11-26-template-editor-radio.md)  | 编辑模式 radio 卡片与正文内边距       |
| [回答模板格式示例](./ai-qa-change-2026-11-26-answer-template-examples.md) | 结构化/高级文本示例弹窗与下载导出     |
| [AI 生成模板变更](./ai-qa-change-2026-11-26-ai-template-generation.md)    | 异步生成、反范式快照与前端弹窗        |
| [AI 生成模板方案](./ai-qa-ai-template-generation-plan.md)                 | 设计说明、数据模型与实施顺序          |
| [弹窗/抽屉/下拉动效变更](./ai-qa-change-2026-11-26-motion-animation.md)   | motion-v 入场退场与下拉展开收起       |
| [文档抽屉排版变更](./ai-qa-change-2026-11-26-knowledge-drawer-table.md)   | 草稿表格列收敛、底栏左右分组          |
| [统计页布局重排](./ai-qa-change-2026-11-26-statistics-layout.md)          | 一屏式统计页：指标带、Top 5、明细平铺 |
| [统计页布局方案](./ai-qa-statistics-layout-plan.md)                       | 重排方案、备选对比与验收标准          |
