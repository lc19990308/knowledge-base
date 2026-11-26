# 变更记录：操作按钮按语义分主题样式（2026-11-26）

## 背景

全站按钮此前只有三种外观：默认描边、`bk-btn-primary`（实心蓝）、`bk-btn-danger`（红描边）。
「查看」「编辑」「导入」「删除」「停用」「启用」等性质完全不同的操作在视觉上没有区分，
同一条表格行里常出现一排等权重描边按钮。本次把**每一个操作按钮**都归入语义变体，
保证同一操作在全站同色、同一页面内不同操作可一眼区分。

## 方案

共 8 种变体。新增 `--color-surface-info` / `--color-text-info` 两个颜色 token（亮暗各一份），
其余全部复用 `theme.css` 既有 token，不写裸色值：

| 变体 | 语义 | 典型操作 | 形态 |
| --- | --- | --- | --- |
| `.bk-btn`（默认） | 中性基线 | 取消、关闭、返回 | 白底描边 |
| `.bk-btn-primary` | 创建 / 提交类写操作 | 新建、保存、提交、发送、登录、检索、上传、导入、添加账号 | 实心主色渐变 |
| `.bk-btn-neutral` | 编辑类操作 | 编辑、更新文件、编辑全局配置、导入新文档 | 浅灰底 + 深灰字 |
| `.bk-btn-info` | 只读操作 | 查看、预览、下载原文件、问答统计入口 | 浅蓝底 + 蓝字 |
| `.bk-btn-success` | 让对象进入可用状态 | 启用、通过并发布、添加空间授权 | 绿色浅底 + 绿字 |
| `.bk-btn-warning` | 降低可用性但可恢复 | 停用、撤回发布、清空已选 | 琥珀浅底 + 琥珀字 |
| `.bk-btn-danger` | 不可逆 / 高代价 | 删除、驳回、退出登录 | 红色浅底 + 红字 |
| `.bk-btn-ghost` | 纯工具 | 刷新、重试、继续查询、读取最新版本、全选 | 无边框无投影，悬停出浅底 |

`ghost` 与 `danger` 可叠加（`bk-btn-ghost bk-btn-danger`），用于文件条目的「× 移除」。

取舍说明：

- 只读操作不借用主色实心按钮，否则每行「查看」都会和真正的提交动作抢注意力；浅蓝底足以表达「可点、只看」。
- 编辑类单独用浅灰，是因为它既不是只读也不是提交，落在页面里出现频率最高，给一个稳定的中间层。
- 页头「刷新」在发布管理页给的是 `neutral` 而不是 `ghost`：实测无边框后孤立在页头右侧像一段静态文字，可点感不足；工作台的刷新带图标，保留 `ghost` 反而更轻。
- 发布弹窗的「驳回 / 通过并发布 / 撤回发布」分别是 `danger / success / warning`，与列表、设置页的同类操作保持一致。

## 实现过程

1. **token**（`frontend/src/styles/theme.css`）
   - 亮色：`--color-surface-info: #eef2ff`、`--color-text-info: #1d4ed8`。
   - 暗色：`--color-surface-info: #1b2a45`、`--color-text-info: #8bb6ff`（深底上提亮，实测 6.99:1）。
   - 只新增这一组，其余变体全部复用 `--color-surface-*` / `--color-text-*` / `--color-action` / `--color-border`。
2. **样式**（`frontend/src/styles/components.css`）
   - 新增 `.bk-btn-neutral`、`.bk-btn-info` 两组规则（默认、`:hover`、`:active`）；已有的 success / warning / danger / ghost 不变。
   - `.bk-btn-ghost` 仍声明在语义色之后，负责「去底、留色」，支持与语义色叠加。
3. **全站落类名**
   - 知识库：查看 → `info`、预览 → `info`、行内导入 → `primary`、抽屉导入新文档 → `neutral`、刷新草稿 → `ghost`。
   - 发布管理：刷新 → `neutral`、查看 → `info`、驳回 → `danger`、通过并发布 → `success`、撤回发布 → `warning`。
   - 系统设置：编辑全局配置 / 行内编辑 / 成员编辑 → `neutral`、新建模型配置 / 新建账号 → `primary`、删除 → `danger`、全选 → `ghost`、清空 → `warning`。
   - 助手配置：编辑 → `neutral`、启用 → `success`、停用 → `warning`（原为危险红，停用可恢复，不该与删除同色）。
   - 回答模板：编辑 → `neutral`、读取最新版本 → `ghost`、删除 → `danger`。
   - 对话页：重试 → `ghost`、继续查询 → `ghost`、发送 → `primary`、确认删除 → `danger`。
   - 工作台：刷新 → `ghost`、问答统计 → `info`、检索 → `primary`。
   - 导入弹窗：预览 → `info`、× 移除 → `ghost + danger`、重试 → `ghost`、重试加入草稿 → `neutral`。
   - 引用预览：重试 → `ghost`、下载原文件 → `info`。
   - 空间选择器：全选筛选结果 → `ghost`、添加 → `success`。
   - 文件选择组件：`secondary` 态由默认描边改为 `neutral`，与行内「更新」的编辑语义一致。
   - 仍未加变体的只剩中性基线：取消、关闭、返回、登录页「以此身份进入」、分页器与图标按钮。

## 验证命令与结果

- `npx prettier --write`（仅改动的 13 个文件）：全部格式化通过。
- `npm run lint`（eslint --max-warnings 0）：本次改动文件 0 告警（全量 lint 当时被其他会话的临时脚本 `e2e/tmp-nested.mjs` 阻塞，非本次改动）。
- `npm run typecheck`（vue-tsc --noEmit）：通过，0 错误。
- 浏览器实测（http://localhost:5173，admin 登录，Chrome headless + Playwright）：
  - 知识库：行内「查看」浅蓝、「导入」实心蓝，两行一致；导入弹窗内「选择文件」「开始导入」实心蓝、「关闭」描边。
  - 系统设置：卡片「新建模型配置」实心蓝，行内「编辑」浅灰、「删除」浅红；默认模型删除按钮禁用态变淡。
  - 助手配置：「编辑」浅灰、「停用」琥珀；知识库抽屉「刷新草稿」无边框。
  - 发布管理：「刷新」浅灰，不再是一条裸文字。
  - 工作台：刷新（带图标）保持无边框，问答统计浅蓝。
  - 亮/暗两套主题下按钮文字与底色实测对比度：只读 5.99 / 6.99，编辑 8.40 / 8.13，成功 6.24 / 6.19，警示 6.36 / 7.12，危险 7.11 / 6.31，均 ≥ 4.5:1（亮/暗）。
  - 截图存于 `.cache/ui-button-check/v2-*.png`（本地临时证据目录，不入库）。

## 遗留问题

- 「通过并发布 / 驳回 / 撤回发布」只在有待审申请时出现，本次未造数据，仅用同变体对照面板确认亮/暗渲染，未在真实弹窗内点击。
- 未补按钮变体的组件级单测；现有 E2E 只断言文案与接口行为，不校验配色。
- 本次编辑期间有其他会话同时改动 `frontend/src/pagination.ts`、`frontend/src/views/AssistantView.vue`、`frontend/e2e/tmp-nested.mjs`，未纳入本次改动范围，也未清理其临时文件；全量 lint 是否转绿取决于对方收尾。
