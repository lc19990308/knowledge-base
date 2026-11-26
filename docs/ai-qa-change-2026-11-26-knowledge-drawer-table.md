# 变更记录：知识库文档抽屉的表格与底栏排版（2026-11-26）

## 背景

知识库页「查看」打开的文档抽屉有三个问题：

1. **表格空间不足**：抽屉宽 640px 却塞了 7 列（文档名称 / 版本 / 格式·大小 / 可检索 / 状态 / 导入时间 / 操作），
   结果每列都被压到一行放不下：文档名竖排成 4 行，「状态」「导入时间」折行，「预览 / 更新」上下堆叠。
   「格式 / 大小」列的格子实际只显示体积，表头与内容不一致。
2. **底栏左右错位**：底栏顺序是 `刷新草稿 / 提交变更审核 / 草稿版本 / 导入新文档`，
   靠 `.drawer-draft-version { margin-inline-start: auto }` 把版本号和「导入新文档」一起推到右侧，
   于是左半是操作、右半也是操作但夹着一段说明文字，读起来没有分组边界，视觉上左右不齐。
3. **刷新草稿占位过重**：它只是重新拉一次草稿，却和一个文字按钮等重，与「导入新文档」「提交变更审核」抢同一行宽度。

## 方案

- 抽屉宽度 640px → 960px（`DrawerPanel` 已有的 `min(--drawer-width, 100vw - 16px)` 保证窄屏自动降级）。
- 表格 7 列收敛为 6 列：删掉独立的「版本」列，版本信息挪到文档名下方的小字（`第 N 版`，原来只有 v>1 才显示、v=1 显示文档 ID 片段，改为始终显示版本号）；
  「格式 · 大小」列改为两行：上面是 MIME 压成的短标签，下面是体积。
- 短内容列（可检索徽章、状态、导入时间、操作）统一 `white-space: nowrap`，
  只让文档名这一列收缩换行（`min-width: 200px` + `overflow-wrap: anywhere`），避免整张表互相挤压。
- 行内「预览 / 更新」改成 flex 并排、同高、间隔 `var(--space-2)`。
- 底栏改为两组：左侧草稿版本说明，右侧操作组 `[⟳] 导入新文档 提交变更审核`，
  右组用 `margin-inline-start: auto` 靠右；「刷新草稿」改成纯图标按钮（`@lucide/vue` 的 `RefreshCw`）。

取舍：

- 刷新按钮沿用 `.bk-btn-sm .bk-btn-ghost`（与《操作按钮按语义分主题样式》一致，ghost 用于纯工具操作），只把左右内边距收成正方形，
  没有新增按钮变体；可访问名称仍是「刷新草稿」，并补 `title` 作 tooltip。
- 刷新进行中给图标加匀速旋转，并禁用按钮，与抽屉内的「正在读取草稿…」呼应；`prefers-reduced-motion` 下不转。
- 「草稿版本」留在左侧而不是混进按钮组：它是只读状态，不是操作，左右分组后底栏才有清晰边界。

## 实现过程

改动集中在 `frontend/src/views/KnowledgeView.vue`：

1. 引入 `RefreshCw`，新增文件级常量 `MEDIA_TYPE_LABELS`（常见文档 MIME → 扩展名短标签）与 `mediaTypeLabel()`
   （未登记类型只保留简洁子类型，`vnd.*` 这类长串显示「其他」，避免把列撑开）。
2. 抽屉 `width="960px"`；表格表头与行按新列结构重排，`colspan` 7 → 6。
3. 底栏重排为「版本说明 + `.drawer-action-group`」，新增 `.drawer-action-group` / `.icon-btn` / `.is-loading` 旋转样式；
   `.drawer-draft-version` 去掉 `margin-inline-start: auto`。
4. 新增 `.nowrap` / `.row-actions` / `.doc-size` 局部样式；token 全部复用既有 `--space-*`、`--text-xs`、`--color-text-muted`、`--button-small-height`，未新增全局变量。

## 验证

命令（均在 `frontend/` 下执行）：

- `npx eslint src/views/KnowledgeView.vue` → 0 问题。
- `npm run typecheck` → 通过（exit 0）。

渲染验证：用 Playwright 打开本地 dev server（`http://localhost:5173/knowledge`），
在浏览器层拦截 `/api/v1/**` 返回固定数据（响应信封 `{ code: 'OK', requestId, data }`），
真实渲染抽屉后读取布局并截图：

| 检查项           | 结果                                                                    |
| ---------------- | ----------------------------------------------------------------------- |
| 960px 抽屉内表格 | `scrollWidth 919 = clientWidth 919`，无横向滚动                         |
| 行高             | 三行均 66px，内容全部单行（截图确认）                                   |
| 底栏             | 版本文字左距 20px；操作组右距 20px，与版本号同一行                      |
| 图标按钮         | 30×30，`getByRole('button', { name: '刷新草稿' })` 命中 1 个            |
| 加载态           | 按钮 `disabled: true`，图标 `animationName: icon-spin`                  |
| 900px 视口       | 抽屉 884px，表格仍不溢出，名称最多两行                                  |
| 420px 视口       | 底栏换行但保持「版本一行、操作组靠右一行」，表格可横向滚动（753 / 380） |

截图证据：`/tmp/kb-drawer-wide.png`、`/tmp/kb-drawer-narrow.png`、`/tmp/kb-drawer-mobile.png`、`/tmp/kb-drawer-loading.png`（临时目录，未入库）。

## 遗留问题

- `frontend/e2e/` 下存在其他会话新增的探针脚本（`sidebar-collapse-check.mjs`、`sidebar-probe2.mjs`、`sidebar-probe3.mjs`），
  它们用了浏览器全局变量但未被 ESLint 配置覆盖，导致仓库级 `npm run lint` 报 22 条 `no-undef`。
  这些文件不属于本次改动范围，未修改、未删除，按要求记录并上报。
- 抽屉内文档名超过 200px 宽度时仍会换行（中文文件名无空格），未做省略号截断，避免隐藏信息。
