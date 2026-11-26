# AI 智能问答平台 · 前端

使用 Vue 3、TypeScript、Vue Router 和 Vite，业务页面通过同源 `/api/v1` 访问后端。需求、技术设计和视觉样例见 [PRD](../docs/ai-qa-prd.md)、[技术方案](../docs/ai-qa-technical-design.md)及 [Open Design 原型](../原型图/workbench.html)。

## 本地运行

Node.js 要求为 `20.19+`（20.x）、`22.13+`（22.x）或 `24.0+`。先按[后端 README](../backend/README.md)启动 API 和后台任务，再在此目录执行：

```sh
npm ci
npm run dev
```

以 Vite 输出的地址打开应用。开发服务将 `/api`、`/oauth2` 和 `/login/oauth2` 转发到 `http://127.0.0.1:8080`，SPA 的 `/login` 由前端接管。完整配置见 [vite.config.ts](vite.config.ts)。

- 管理员与普通用户共用 `/login` 的账号密码表单，角色由服务端会话确定。未登录会进入登录页，本地账号登录成功后回到原目标页。
- 当前交付仅启用本地账号登录。
- 登录必须填写图形验证码：页面加载与每次登录失败后都会向后端重新取一枚（`GET /api/v1/auth/captcha`），字符由服务端校验，一次有效、默认 2 分钟过期。登录页不提供开发身份入口。

`npm run lint`（警告视为失败）、`npm run typecheck`、`npm run format:check`、`npm run build` 分别检查 lint、类型、格式和生产构建。构建产物位于 `dist/`。部署时同源转发 API，前端页面路由回退到入口 HTML。静态资源 404 保持 404。

## 浏览器端到端测试

`npm run test:e2e` 启动隔离的前后端和数据库，执行真实浏览器流程与合成政策问答评测。Playwright 是固定版本的开发依赖；还需要本机 Chrome、Java、Maven 和 PostgreSQL 工具。依赖准备、环境要求与运行命令见[全站 E2E 测试计划](../docs/ai-qa-e2e-plan.md)，逐题结果和复现步骤见[测试报告](../docs/ai-qa-e2e-run-2026-11-25.md)。

## 页面和数据

| 路由                                                  | 当前功能                                                                                                                                                                                 |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/`                                                   | 工作台概览、授权范围内的空间与会话、快捷提问，以及按助手绑定范围检索文档名称与正文并打开预览；顶栏提交把文本交给 AI 助手并预填。检索范围随所选助手，不另设空间选择器。                   |
| `/knowledge`                                          | 空间筛选与创建；文档清单在右侧抽屉中查看，维护者可预览、刷新草稿并提交发布审核。成员授权与空间权限统一在系统设置中管理。                                                                 |
| `/import?space=…`                                     | 旧链接跳转到知识库并打开对应空间的上传弹窗。                                                                                                                                             |
| `/documents/:id?space=…`                              | 获授权的文档纯文本预览。问答引用另走引用预览接口，可按权限读取引用内容或下载原件。                                                                                                       |
| `/publishing`                                         | 列表、申请详情对话框、驳回原因、审核与撤回。后端按角色返回可见记录及允许的操作。                                                                                                         |
| `/assistant`                                          | 选择助手、「新提问」先插入本地占位会话、首个问题才自动建会话（标题按问题提炼，超长加省略号）、归档/恢复/删除会话、为单次提问选择模型、异步提问并轮询结果、历史消息、段落屏蔽与引用预览。 |
| `/statistics`                                         | 北京时间自然日、周、月的用量与趋势、提问明细。管理员还能查看用户排行和空间引用指标。                                                                                                     |
| `/assistant-config`、`/answer-templates`、`/settings` | 仅系统管理员可进入，分别管理助手及绑定、回答模板；设置页管理全局与模型配置、账号角色，并在成员操作中设置空间读取／维护权限。                                                             |
| `/login`、`/403`                                      | 登录与无权访问页。                                                                                                                                                                       |

侧栏「管理」组只对系统管理员显示。普通用户可凭直接地址访问发布管理和问答统计，数据范围由后端校验。前端导航与会话状态见 [router.ts](src/router.ts)、[App.vue](src/App.vue) 和 [auth.ts](src/auth.ts)。API 包装位于 [api/knowledge.ts](src/api/knowledge.ts)，请求使用 [api/http.ts](src/api/http.ts) 处理 Cookie、CSRF、`If-Match`、幂等键、错误和 401 回登录页。接口详情见 [OpenAPI](../docs/ai-qa-openapi.yaml)。旧的 [demo/knowledge.ts](src/demo/knowledge.ts) 为留存样例，当前业务视图读写 API。

## 编码和主题规范

- 页面使用 Vue 3 `<script setup lang="ts">`。props、emits 和路由数据明确类型，外部输入按 `unknown` 收窄，派生值用 `computed`，互斥状态用可辨识联合。
- TypeScript 启用严格模式、严格模板、索引安全和精确可选属性。ESLint 启用 Vue 与 TypeScript 类型感知规则，禁止 `v-html`、直接修改 props 和浮空 Promise。
- 列表数据走服务端分页：接口按 `limit`/`offset` 取数并返回 `total`，[PaginationBar.vue](src/components/PaginationBar.vue) 提供页码与每页条数（10/20/50，默认 10），分页状态由 [pagination.ts](src/pagination.ts) 的 `usePagedList` 统一管理。表格默认高度按「表头 + 10 条数据行」计算（`--table-height`），统计页、抽屉和弹窗内的表格用 `table-compact` 保持紧凑高度。
- 颜色、间距、圆角等语义值见 [theme.css](src/styles/theme.css)。全局基础与控件样式见 [base.css](src/styles/base.css) 和 [components.css](src/styles/components.css)。表单、表格、对话框、引用预览等优先复用 [components](src/components/) 中的现有组件。统计图表由 [BaseChart.vue](src/components/BaseChart.vue) 封装 ECharts，模板正文由 [TemplateBodyEditor.vue](src/components/TemplateBodyEditor.vue) 提供结构化和高级编辑方式。
- 动态数据提供空态和异常态，纯图标交互提供可访问名称。原型与当前页面的差异见 [前端偏离报告](../docs/ai-qa-frontend-deviation-report.md)。
