# 变更记录：数据清洗前端页面（2026-11-26）

## 背景

知识库缺少数据清洗能力。已定方案是「上传文件夹/压缩包 → 清洗成条目 → 全局清洗草稿箱 → 管理员审核 → 流转成 Markdown 文档版本进空间草稿 → 走现有发布审核」，见[数据清洗实施计划](./ai-qa-data-cleaning-plan.md)。

本轮先交付前端页面，后端接口按同一份契约随后实现。页面此时调用的是计划中的接口，后端未就绪时列表会显示接口错误，属预期状态。

## 交付内容

### 页面结构

`/cleaning`「数据清洗」，两个页签：

- **清洗草稿箱**（默认）：关键词/状态/批次/目标空间/最低总分筛选、多选、批量通过/驳回/流转、条目详情抽屉。
- **清洗批次**：批次状态筛选、文件/失败与条目/去重统计、平均分、上传后的状态轮询、批次详情抽屉（来源文件与失败原因、重新打分）。

浮层：上传弹窗、条目详情抽屉（清洗前后对照 + 评分明细 + 审核记录）、审核确认弹窗、流转弹窗、批次详情抽屉。

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `frontend/src/api/cleaning.ts` | 8 个接口封装与全部类型：批次、来源文件、条目、评分明细、审核与流转 |
| `frontend/src/api/query.ts` | 从 `knowledge.ts` 抽出的查询串构建器，两个 API 模块共用 |
| `frontend/src/views/CleaningView.vue` | 页签、筛选、两张表、多选、审核与流转入口、轮询 |
| `frontend/src/components/CleaningUploadDialog.vue` | 文件夹（`webkitdirectory`）/压缩包、批次名、目标空间、上传进度 |
| `frontend/src/components/CleaningItemDrawer.vue` | 清洗前后对照、五维规则分、模型理由、审核与流转记录 |
| `frontend/src/components/CleaningPromotionDialog.vue` | 目标空间、文档名、政策状态与生效区间、提交流转 |
| `frontend/src/components/FormCheckbox.vue` | 原生 checkbox 语义 + 半选状态；页面禁止直接写 `<input>`，多选表格需要它 |
| `frontend/src/components/ScoreMeter.vue` | 分数数字 + 三档配色 + 细进度条，表格与抽屉共用 |

### 修改文件

| 文件 | 改动 |
| --- | --- |
| `frontend/src/components/FormFileInput.vue` | 新增 `directory`（挂载后设置 `webkitdirectory`，该属性不在 Vue 输入元素类型里）与 `paths` 透出（`webkitRelativePath`）；原有调用不受影响 |
| `frontend/src/components/DataTable.vue` | `rows` 允许显式 `undefined`，表示加载中不渲染空态 |
| `frontend/src/api/display.ts` | 批次/条目状态文案与徽章色、来源文件状态、评分状态、清洗失败原因提示 |
| `frontend/src/api/knowledge.ts` | 移除本地 `listQuery`，改用 `api/query.ts` |
| `frontend/src/formValidation.ts` | 新增 `batchName`、`documentName` 长度限制 |
| `frontend/src/navigation.ts`、`router.ts` | 工作区新增「数据清洗」，加入管理员路由集合 |
| `frontend/eslint.config.mjs` | 把 `FormCheckbox.vue` 加入允许原生 `input` 的组件清单 |

## 关键实现决定

- **分数不用图表**：总分数字 + `--color-success/warning/danger` 三档配色 + 4px 细条，表格里可扫读；不引入 ECharts。
- **多选只在当前页**：翻页或改筛选即清空选择，避免对看不见的条目执行批量操作；表头 checkbox 支持半选。
- **审核与流转都回读服务端**：写成功后重载列表与待审计数，不做乐观更新；409/412 提示刷新，不静默重放写请求。
- **批次轮询只刷一行**：上传后用 `pollUntil`（2 秒间隔、60 秒预算）原地替换批次行，完成后再刷新草稿箱与待审数量；组件卸载即取消。
- **默认文档名取「正在流转的条目」所属批次**：行内流转不会先勾选，最初误用页面勾选集会把批次名取成兜底文案，已修正并用断言覆盖。
- **样式只用既有 token**：新增的都是页面/组件级类（`.selection-bar`、`.score-*`、`.compare-grid`、`.dropzone`、`.filter-bar`），未新增全局变量；按钮语义严格按[操作按钮主题](./ai-qa-change-2026-11-26-button-variants.md)。

## 接口契约（后端按此实现）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/cleaning/batches` | multipart：`files[]`、`paths[]`（与 files 同序）、`archive`、`name`、`targetSpaceId`；`202` 返回批次 |
| `GET` | `/api/v1/cleaning/batches` | `status`、`limit`、`offset`；返回 `{ items, total, nextCursor }` |
| `GET` | `/api/v1/cleaning/batches/{batchId}` | 批次 + `sourceFiles[]` |
| `POST` | `/api/v1/cleaning/batches/{batchId}/rescore` | 返回 `{ enqueued }` |
| `GET` | `/api/v1/cleaning/items` | `status`、`batchId`、`targetSpaceId`、`minScore`、`q`、`limit`、`offset` |
| `GET` | `/api/v1/cleaning/items/{itemId}` | 条目详情（正文、原文摘录、`scoreDetail`、审核与流转字段） |
| `POST` | `/api/v1/cleaning/items/review` | `{ itemIds, decision, reason }` |
| `POST` | `/api/v1/cleaning/promotions` | `{ itemIds, spaceId, documentName, policyState, effectiveFrom, effectiveUntil, reason }`；返回 `documentVersionId`、`documentName`、`versionNumber`、`draftRevision` |

字段口径以 `frontend/src/api/cleaning.ts` 的类型为准，与计划文档 §6 一致。

## 验证

命令（均在 `frontend/` 下）：

```bash
npx prettier --write <本次改动文件>
npm run lint        # eslint . --max-warnings 0 → 0 问题
npm run typecheck   # vue-tsc --noEmit → 通过
npm run build       # vite build → 通过（分块告警为既有，统计/模板页历史已有）
```

浏览器实测：Playwright（本机 Chrome）+ 在浏览器层拦截 `/api/v1/**` 返回固定数据，真实渲染页面并操作，9 项检查全部通过、`pageerror` 为 0：

| 检查 | 结果 |
| --- | --- |
| 页面加载与待审核计数 | 页签显示「清洗草稿箱（5）」 |
| 多选与选择条 | 勾选 1 条出现选择条；表头半选为 `indeterminate`；未审核时「流转」禁用 |
| 条目详情抽屉 | 清洗前后对照、五维规则分、审核/流转记录齐全；Esc 关闭 |
| 筛选全部状态 | 由 5 条变为 8 条 |
| 通过与驳回弹窗 | 提交体 `{decision:'APPROVE', itemIds:[…]}`；通过后行变「已通过」 |
| 流转弹窗与成功提示 | 提交体 `spaceId` 正确；文档名 `清洗知识-客服语料-1126-20261126.md`；出现「去发布管理」 |
| 批次页签与批次抽屉 | 来源文件、失败原因（UTF-8 提示）、重新打分按钮正常 |
| 上传弹窗与文件夹属性 | 文件夹输入框带 `webkitdirectory` |
| 窄屏与深色主题 | 390px 下本页容器无横向溢出；深色主题颜色跟随 token |

截图：`docs/e2e-results/cleaning-inbox-1440.png`、`-item-drawer.png`、`-review-dialog.png`、`-promotion-dialog.png`、`-batches-1440.png`、`-batch-drawer.png`、`-upload-dialog.png`、`-inbox-390.png`、`-inbox-dark.png`。

## 遗留问题

- 后端接口尚未实现，页面在后端就绪前只能看到接口错误态；联调后需要复核筛选参数与响应字段。
- 正式 E2E 用例（`frontend/e2e/cleaning.mjs`）尚未加入仓库，本轮用的是临时脚本（已删除），截图与断言结果留在本文。
- 960px 高视口下，页签内容底部的分页条落在首屏之下（表格固定容纳 10 行是既有约定），属正常滚动，不另调高度。
- 390px 下全站顶栏搜索按钮仍有 16px 溢出，是 `AppLayout` 既有问题，本次未处理。
- 条目正文与原文摘录按纯文本 `pre-wrap` 展示；如需高亮差异对比再单独评估。
