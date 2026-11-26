# 前端页面偏离原型审查报告

## 当前源码复核

现有前端覆盖 10 个业务路由，另有登录、403 和未匹配路径回退。当前原型包含 10 个 HTML 页面、共享样式和多页模拟交互脚本。首轮发现的壳层、工作台组件和助手会话结构偏离已修复，但部分页面的布局或交互已经与原型分化。登录、配置和权限功能也扩展了原型范围。见[路由](../frontend/src/router.ts)与[原型需求复核](./ai-qa-prototype-review.md#4-当前原型与实现复核)。

| 页面或部件 | 当前源码结论 | 核对依据 |
| --- | --- | --- |
| 全局框架 | 顶栏标题、快捷搜索、毛玻璃、品牌方块与用户头像已恢复。正文在桌面端按用户确认的规则铺满、水平留 20px，原型仍限宽。快捷搜索最终在工作台的一个空间内执行，详情页不显示顶栏搜索。 | [App.vue](../frontend/src/App.vue) · [WorkbenchView.vue](../frontend/src/views/WorkbenchView.vue) · [原型样式](../原型图/style.css) |
| 工作台 | 四格 KPI、带部门与文档统计的空间卡、快捷操作和搜索状态均已实现。搜索摘要主动移除 `<mark>` 并按纯文本渲染，没有关键词高亮。当前原型的动态搜索脚本也输出纯文本。创建入口跳到知识库，用户从知识库按钮打开表单。 | [WorkbenchView.vue](../frontend/src/views/WorkbenchView.vue) · [原型搜索脚本](../原型图/prototype-workbench.js) |
| 知识库 | 名称筛选、部门字段、空间最近变更、文档导入时间和草稿/成员操作已接入。当前状态筛选仅区分已发布与未发布，原型细分草稿、审核中、驳回、撤回。知识库文档清单服务于维护流程，只读用户可从工作台检索并查看已发布文档。 | [KnowledgeView.vue](../frontend/src/views/KnowledgeView.vue) · [原型知识库](../原型图/knowledge.html) · [接口类型](../frontend/src/api/knowledge.ts) |
| 导入 | 步骤条、文件行、状态和逐文件重试已实现，设计中的虚线区域仍在。文件通过选择器上传，源码没有处理拖放事件。原型通过输入模拟文件名演示规则。 | [ImportView.vue](../frontend/src/views/ImportView.vue) · [FormFileInput.vue](../frontend/src/components/FormFileInput.vue) · [原型导入](../原型图/import.html) |
| 发布管理 | 审核提示、步骤、详情、驳回和撤回均有实现。历史右侧抽屉已改成通用对话框，原型仍为带遮罩的右侧抽屉。 | [PublishingView.vue](../frontend/src/views/PublishingView.vue) · [ModalShell.vue](../frontend/src/components/ModalShell.vue) · [原型发布管理](../原型图/publish.html) |
| 问答统计 | 趋势、用户排行、空间指标、折叠提问明细已接入接口，趋势用 ECharts 绘制。排行和空间指标按管理员权限取数。实现明细展示时间、提问、状态、Tokens，原型另外表达用户/会话、计数口径与文档引用来源。 | [StatisticsView.vue](../frontend/src/views/StatisticsView.vue) · [BaseChart.vue](../frontend/src/components/BaseChart.vue) · [原型统计](../原型图/stats.html) |
| 系统设置 | 权限矩阵与相关表单开关已实现，模型、成员和矩阵分标签呈现。成员改为表格与编辑对话框，增加模型配置列表、账号创建与改密。页面已不使用原型的 960px 限宽。 | [SettingsView.vue](../frontend/src/views/SettingsView.vue) · [原型设置](../原型图/settings.html) |
| AI 助手和文档详情 | 助手保留会话双栏、气泡、状态和引用，并增加会话归档、恢复、删除和单次提问的模型选择。引用改用独立预览组件。文档详情已采用卡片、卡头及状态展示，从搜索结果进入。 | [AssistantView.vue](../frontend/src/views/AssistantView.vue) · [CitationPreview.vue](../frontend/src/components/CitationPreview.vue) · [DocumentView.vue](../frontend/src/views/DocumentView.vue) |
| 助手配置与回答模板 | 现为表格列表 + 编辑对话框，已取代历史的左右栏。助手保留启停和绑定空间操作。模板保留启停、删除、预览，正文新增结构化与高级编辑。 | [AssistantConfigView.vue](../frontend/src/views/AssistantConfigView.vue) · [AnswerTemplatesView.vue](../frontend/src/views/AnswerTemplatesView.vue) · [TemplateBodyEditor.vue](../frontend/src/components/TemplateBodyEditor.vue) |

本节根据当前文件静态对照。下文保留各轮浏览器实测、修复与决策记录，计数、尺寸和测试结果只对应记录当时的版本。当前像素一致性、窄屏表现以及交互回归需单独实测。

## 历史审查摘要

- 首轮对象为当时的 `frontend/` 10 个业务路由，依据当时原型做 1440×900 截图、DOM 探针和样式比对。
- 首轮结论为已核对的主要 token 一致，结构和组件偏离明显。修复及后续改版记录见第 8–11 节。

---

## 0. 总览分级

| 级别 | 含义 | 数量 |
| --- | --- | --- |
| P0 结构性偏离 | 页面骨架/交互形态不同，视觉上一眼可辨 | 9 类 |
| P1 组件缺失 | 原型有标准组件，实现用自定义样式替代或缺失 | 11 类 |
| P2 细节偏离 | 尺寸、排版、文案格式不一致 | 8 类 |
| 已一致 | 颜色 token、间距刻度、圆角语义、字号刻度 | 4 类 |

> 数量沿用首轮归类。历史计数与取值来自当时的 DOM 探针、计算样式和样式表比对。未验证项见第 10 节。

---

## 1. 已一致的部分（先说不是问题的）

`theme.css` 与原型 `style.css` 的变量取值逐项相同，只是改名：

| 原型 | 前端 | 值 |
| --- | --- | --- |
| `--bg` | `--color-canvas` | `#f6f7f9` |
| `--surface` | `--color-surface` | `#ffffff` |
| `--surface-warm` | `--color-surface-selected` | `#eef4ff` |
| `--fg` / `--fg-2` / `--muted` | `--color-text` / `-secondary` / `-muted` | `#172033` / `#3b4658` / `#6b7689` |
| `--border` / `--border-soft` | `--color-border` / `--color-divider` | `#d8dee8` / `#edf1f6` |
| `--accent` | `--color-action` | `#2563eb` |
| `--radius-sm` / `--radius-md` | `--radius-control` / `--radius-panel` | `8px` / `12px` |
| `--sidebar-w` | `--sidebar-width` | `236px` |

实测：侧栏宽度 `236px` 一致，内容区 padding `24px` 一致。

**但 token 一致不等于看起来一致**——偏离全部发生在 token 之上的组件层。

---

## 2. P0：结构性偏离

### 2.1 顶栏形态完全不同 ⚠️ 最影响"第一眼"

| | 原型 | 实现 |
| --- | --- | --- |
| 结构 | `h1` 当前页标题 + 右侧**全局搜索框** | 面包屑「AI 智能问答平台 › 工作台」 |
| 实测 | `topbarHeight: 82px`，含 `input[type=search]` | 无输入框，`topbarHasSearch: false` |
| 背景 | `color-mix(surface 88%, transparent)` + `backdrop-filter: blur(8px)` | 纯 `--color-surface`，无毛玻璃 |

原型的顶栏是"页面标题 + 全站搜索"，实现成了"面包屑导航"。这是每个页面都会看到的第一处差异。

### 2.2 品牌区：图形标 vs 文字

- 原型：`.brand-mark` 是 32×32 圆角方块，内嵌单字「问」，蓝色底白字
- 实现：`.brand svg` 直接用一个 lucide 书本图标

### 2.3 用户区：头像+姓名+角色 vs 单行文字

- 原型：`.avatar` 32×32 圆形 + 姓名「刘超」+ 角色「系统管理员」两行
- 实现：`.identity` 一行「演示账号」，无头像、无角色

### 2.4 卡片组件被换名重写

原型 `.card`（`padding: 20px`、`radius-md`、`border`）对应实现的 `.card`，但：

- 原型统一用 `.card-head`（标题 + `.hint` + 右侧 `.more`）作卡头
- 实现每页各自写 `.section-heading`，样式分散在 10 个 `<style scoped>` 里
- 实测：10 个视图合计约 2000 行 scoped 样式，`.section-heading` 在 8 个文件中各定义一次
- 结果：同类卡片在不同页面的间距与标题字号由各处自行决定，**已见不一致**（见第 4 节字号表）

### 2.5 KPI 区结构不同

- 原型：`.grid-kpi` 四列栅格，每格 `.kpi` = `.label` + `.value`（30px）+ `.delta`（涨跌色）
- 实现：`.metrics` + `.metric`，**第一页只有 3 格**（文档数/可检索/发布状态），无 `.delta`

原型工作台是 4 个 KPI（已发布空间 5、文档总数 48/47、待审核申请 2、今日提问 87），实现只有 3 个且指标语义不同。

### 2.6 知识空间卡片的视觉重心丢了

- 原型 `.space-card`：40×40 圆角图标（字母「员/差/安」，按状态配色 blue/green/amber）+ 名称 + 部门·状态 + **两列大数字**（文档数 62 / 可检索 61）
- 实现 `.space`：`--control-height`(38px) 方块图标 + 名称 + 一行小字「3 份文档 · 3 份可检索」

原型的卡片是"大数字优先"，实现压成了一行说明文字。

### 2.7 工作台右侧"快捷操作"整块缺失

原型有 `.quick-actions` 两宫格（AI 助手 / 导入文档，`.qa-tile` 带图标与副标题），实现完全没有。

### 2.8 搜索区样式不同

- 原型 `.search-zone`：横向 flex，`.scope-select` 固定 180px，结果区 `.result-item` 带 `mark` 高亮（蓝底 18% 透明）、`.r-meta` 元信息行
- 实现：`.search-form` 网格布局，结果 `.result` 用 flex + 图标，**无 `mark` 高亮样式**（截断后的纯文本）

### 2.9 发布管理：抽屉 vs 内联面板

- 原型：`.drawer-mask` + `.detail-drawer`（右侧滑出抽屉，遮罩层）
- 实现：`.card` 直接内联在列表下方

实测：原型 `.drawer-mask: 1`、`.detail-drawer: 1`；实现均为 `0`。

### 2.10 AI 助手：聊天布局完全缺失 ⚠️ 偏离最大

| | 原型 | 实现 |
| --- | --- | --- |
| 布局 | `.chat-layout` 双栏（左侧会话列表 `aside.card`，右侧消息流） | 普通两列 grid，"最近会话"与问答分离在两个卡片 |
| 消息 | `.msg` + `.m-avatar` + `.m-bubble` + `.m-time`，共 10 条演示 | 无气泡、无头像 |
| 引用 | `.cite-block` + `.cite-item`（编号 + 标题 + 来源 + 版本 badge），3 组 | 简单 `<ul>` 列表 |
| 状态标 | `.ans-flag`（现行/失效/待核实/冲突） | `status` 徽标 |

实测：原型 `.chat-layout:1, .msg:10, .cite-block:3`；实现全部为 `0`。

### 2.11 问答统计：图表整块缺失

- 原型：`.line-chart` SVG 折线图 + `.chart-grid`/`.chart-axis`/`.chart-area` + `.legend` + 明细表 + 折叠详情
- 实现：只有 5 个数字格子，**没有任何图表**

实测：实现 `StatisticsView.vue` 中无 `svg`、无 `chart` 相关代码。

---

## 3. P1：组件缺失

以下原型标准组件在实现中完全未出现（DOM 探针计数为 0）：

| 组件 | 原型类 | 用途 |
| --- | --- | --- |
| 徽标体系 | `.badge` + `-published/-review/-draft/-rejected/-info/-warn/-fail/-ok` | 8 种状态徽标，实测原型单个页面最多 24 个；实现用 `.status` + 少量 `status-*` |
| 提示条 | `.notice` + `-info/-warn/-fail/-ok`（带 `.n-icon`） | 4 种语义横幅；实现用 `.status status-warning` 内联文字 |
| 表格容器 | `.table-wrap` | 带边框圆角、横向滚动；实现的 `<table>` 裸用，无容器 |
| 表头背景 | `th { background: var(--bg) }` + `white-space: nowrap` | 实现表头无底色 |
| 行悬停 | `tbody tr:hover` 淡蓝底 | 未实现 |
| 空态 | `.empty` + `.e-title` + `.e-actions` | 居中大标题 + 操作按钮；实现只是一行灰字 |
| 步骤条 | `.steps` + `.step` + `.s-idx` + `.step-arrow`（done/now 态） | 原型导入页有；实现自写 `.steps` 但无 done/now 三态 |
| 开关 | `.switch` + `.track` | 原型设置/助手配置用开关；实现用 checkbox（设置页 0 个 `.switch`，原型 1 个） |
| 标签 chips | `.chip` / `.chip.on` | 空间筛选、助手可用空间；实现未用 |
| 抽屉 | `.drawer-mask` + `.detail-drawer` | 见 2.9 |
| 拖放区 | `.dropzone`（1.5px 虚线 + 大字提示） | 导入页；实现用普通 `<input type=file>` |
| 导入行 | `.import-row` + `.f-icon` + `.f-name` + `.f-sub` + `.progress` | 原型 5 行带进度条；实现无 |
| 模板条目 | `.tpl-item` + `.t-main` + `.t-name` + `.t-sub` | 原型 4 条；实现用自定义 `.template` |
| 成员行 | `.member-row` + `.avatar` + `.m-info` | 原型 4 行带头像；实现裸表格 |
| 权限矩阵 | `.perm-matrix` + `.perm-yes` + `.perm-no` | 实现用普通表格，无 ✓/— 专用样式 |
| 配置页左栏 | `.asst-item` + `.a-icon` | 原型 3 条；实现自写 `.assistant` |
| 会话条目 | `.conv-item` + `.c-time` | 原型 4 条；实现自写 `.conversation` |

---

## 4. P2：细节偏离

| 项 | 原型 | 实现 |
| --- | --- | --- |
| 页面主标题 | `.page-head h2` = `30px`（`--text-2xl`），带 `letter-spacing: -0.015em` | `.page-heading h1` = `30px` 但**无负字距** |
| 字号刻度 | 有 `--text-2xl: 30px`、`--text-3xl: 42px` | 缺失这两级，改用 `--text-title: 1.875rem` |
| 圆角刻度 | 三级 `sm 8 / md 12 / lg 18` | 只有两级，缺 `18px` |
| 阴影 | `--elev-ring`、`--elev-raised`（0 16px 40px） | 只有 `--shadow-focus` |
| 动效 | `--motion-fast 140ms` + `--motion-base 220ms` | 只有 `--transition-fast` |
| 字体族 | `--font-display` 与 `--font-body` 分开定义 | 只有 `--font-body` |
| 链接 | 默认 `text-decoration: none`，hover 才加下划线 | 默认下划线（`text-underline-offset: 0.2em`） |
| 焦点 | `:focus-visible { outline: none; box-shadow: focus-ring }` | `outline: 2px solid` + offset |

---

## 5. 逐页偏离清单

| 页面 | 主要偏离 | 严重度 |
| --- | --- | --- |
| 工作台 `/` | 顶栏无搜索；KPI 3 格且无 delta；空间卡片无大数字/彩色字母图标；**缺快捷操作宫格**；会话列表无状态点 | P0 |
| 知识库 `/knowledge` | 无 `.filter-bar` 状态筛选 chips；表格无 `.table-wrap` 容器与表头底色；无 `.row-title`/`.row-sub`/`.ver` 层级；创建空间表单缺「维护部门」字段 | P0 |
| 导入文档 `/import` | **无 `.dropzone` 拖放区**；步骤条无三态；无 `.summary-bar`、`.import-row`、`.progress`；文件图标缺失 | P0 |
| 发布管理 `/publishing` | **详情应是右侧抽屉**，现为内联卡片；tabs 样式不同（原型下划线式，实现为胶囊）；无 `.notice` 审核规则横幅 | P0 |
| 问答统计 `/statistics` | **无折线图/Legend/明细表**；KPI 无 delta | P0 |
| 系统设置 `/settings` | 无 `.switch` 开关（用 checkbox）；成员区应是带头像的 `.member-row`；权限矩阵无 ✓/— 样式 | P1 |
| AI 助手 `/assistant` | **无聊天布局、无气泡、无引用块**；会话列表位置与样式不同 | P0 |
| 助手配置 `/assistant-config` | 左栏助手列表无 `.a-icon` 彩色图标与状态 badge；绑定空间应为 `.bind-row` + 开关 | P1 |
| 回答模板 `/answer-templates` | 列表应为 `.tpl-item`（名称+badge+副标题+编辑按钮）；编辑器应有 `.tpl-preview` 实时预览区样式 | P1 |
| 文档详情 `/documents/:id` | 原型是 `.card` + `.card-head` + `.badge` 结构，实现为裸 `<article>` | P2 |

---

## 6. 根因分析

1. **组件层没有落库**：原型 `style.css` 的 91 个组件类，只有颜色/间距变量被搬进 `theme.css`；`.btn/.card/.badge/.notice/.table-wrap/.tabs/.steps/.switch/.chip` 等**结构类从未进入实现**，每个页面各写一份 scoped CSS（10 个文件共约 2000 行样式）。
2. **实现时按"功能可用"取舍**：先保证接口打通，组件视觉按开发者直觉重写，未逐页比对原型。
3. **拿不到的资源被静默替换**：拖放区→普通 input、抽屉→内联卡片、折线图→数字格、气泡→列表。功能等价，但视觉不等价。

---

## 7. 建议修复顺序（不含实现，供决策）

| 优先级 | 动作 | 覆盖页面 |
| --- | --- | --- |
| 1 | 把原型 91 个结构类整理为 `components.css`（`.btn/.card/.badge/.notice/.table-wrap/.tabs/.steps/.switch/.chip/.empty`），页面只写布局 | 全部 |
| 2 | 重做 `App.vue` 顶栏（标题 + 全局搜索 + 毛玻璃）、品牌标、用户区头像 | 全部 |
| 3 | 按页替换为原型结构：工作台（KPI 四格 + 空间卡片大数字 + 快捷操作）、AI 助手（聊天布局 + 引用块） | 2 页 |
| 4 | 发布管理改抽屉、导入页改拖放区 + 导入行 + 进度条 | 2 页 |
| 5 | 问答统计补 SVG 折线图与图例 | 1 页 |
| 6 | 补齐缺失 token（`--text-2xl/--text-3xl/--radius-lg/--elev-*/--motion-base/--tracking-display`） | 全局 |

---

## 8. 修复记录（本轮）

### 8.1 组件层落库

新增 `frontend/src/styles/components.css`，把原型 91 个结构类中缺失的部分统一实现为 `bk-` 前缀组件（避免与既有 `.button/.status` 冲突）：

`.bk-btn(-primary/-danger/-sm)`、`.bk-card`、`.bk-card-head`、`.bk-kpi`、`.bk-badge(-published/-review/-draft/-rejected/-info/-warn/-fail/-ok)`、`.bk-dot`、`.bk-table-wrap`、`.bk-table`、`.bk-row-title`、`.bk-row-sub`、`.bk-notice(-info/-warn/-fail/-ok)`、`.bk-empty`、`.bk-grid-kpi`、`.bk-grid-2`、`.bk-grid-cards`、`.bk-field`、`.bk-input`、`.bk-select`、`.bk-textarea`、`.bk-switch`、`.bk-steps`、`.bk-step(-done/-now)`、`.bk-chip`、`.bk-tabs`、`.bk-tab`，以及 `.bk-mono/.bk-muted/.bk-flex/.bk-grow` 等工具类。取值逐项照抄原型 CSS，未自创数值。

### 8.2 token 补齐

`theme.css` 新增：`--text-2xl: 30px`、`--text-3xl: 42px`、`--radius-sm/md/lg`（8/12/18px）、`--elev-ring`、`--elev-raised`、`--motion-fast/base`、`--tracking-display`、`--font-display`；字号刻度改为 px 与原型一致。

### 8.3 逐页修复

| 页面 | 修复内容 | 验证（DOM 实测） |
| --- | --- | --- |
| 全局框架 | 顶栏改为「页面标题 + 全局搜索框 + 毛玻璃」；品牌改 32px「问」字方块；侧栏加分组标签与头像/姓名/角色 | `.topbar-search: 1`（9 页全部） |
| 工作台 | 4 个 `.bk-kpi`（含 delta）；空间卡片改彩色字母图标 + 双列大数字；补快捷操作宫格；最近会话带状态点；搜索三态用 `.bk-notice` | `.bk-kpi: 4` |
| 知识库 | 加筛选条（名称输入 + 状态下拉 + 计数，与原型 filter-bar 一致，原型未使用 chips）；空间表与草稿表均套 `.bk-table-wrap`；补 `.bk-row-title/.bk-row-sub/.ver` 层级与状态徽标 | `.bk-table-wrap: 3`、`.bk-badge: 6` |
| 导入 | 补 `.dropzone` 拖放区、四步 `.bk-step` 步骤条、`.summary-bar`、`.import-row` + 文件图标 + `.progress` | `.dropzone: 1`、`.bk-step: 4`、`.bk-notice: 1` |
| 发布管理 | 详情改右侧抽屉 + 遮罩；tabs 改原型下划线式；补审核规则 `.bk-notice-info`；抽屉内恢复步骤条与变更表格 | `.detail-drawer: 1`、`.drawer-mask: 1`、抽屉内 `.bk-step: 3` |
| 问答统计 | 新增 SVG 折线图（提问实线 + 失败虚线 + 面积 + 网格 + 轴标签）与图例；4 个 KPI 带 delta | `svg.line-chart: 1`、`.bk-kpi: 4` |
| 系统设置 | 两栏模型配置；`.bk-switch` 开关；成员区改 `.member-row` + 头像；权限矩阵 ✓/— 着色 | `.bk-switch: 1`、`.bk-table-wrap: 1` |
| 助手配置 | 左栏 `.asst-item` + 彩色字母图标 + 状态徽标；绑定空间改 `.bind-row` + 开关 | `.bk-switch: 3`、`.bk-badge: 4` |
| 回答模板 | 列表改 `.tpl-item`（名称 + 范围徽标 + 副标题 + 编辑）；编辑器加 `.tpl-preview` 预览区与启用开关 | `.bk-badge: 2`、`.bk-switch: 1` |
| AI 助手 | 恢复 `.chat-layout` 双栏、会话列表、消息气泡（用户/AI 头像）、引用块 `.cite-block`、四种 `.ans-flag`、底部输入区与 binding-note | `.chat-layout: 1`、`.msg: 2`（该会话 1 问 1 答） |
| 文档详情 | 改为 `.bk-card` + `.bk-card-head` + 状态徽标结构 | 结构对齐 |

### 8.4 为对齐原型而补的后端接口

统计页原型需要按时间分桶的趋势，而原后端只有周期汇总。先补后端再接前端：

- 新增 `GET /api/v1/usage/series`（DAY 按小时、WEEK/MONTH 按日分桶，北京时间）
- `UsageService.series(...)` + `AccessUsageAndReviewIT` 断言：24 个小时桶、标签形如 `HH:00`、非管理员带 `userId` 返回 403
- OpenAPI 契约同步到 37 个路径 / 46 个操作

### 8.5 验证结果

- 后端：`mvn test` **13 个测试全部通过**
- 前端：`lint`、`typecheck`、`build` 全部通过
- 浏览器：9 个路由逐页打开，**控制台 0 错误**；发布抽屉、助手会话、导入步骤条等交互态实测正常

---

## 9. 修复后仍存在的差异

| 项 | 说明 |
| --- | --- |
| 空间卡片的「部门 · 状态」 | 原型写 `hr · 已发布`；后端空间没有部门字段，现只显示状态，未编造部门 |
| 知识库「最近变更」「导入时间」列 | 后端未提供对应时间戳，接口里没有该数据，故未渲染该列 |
| 回答模板「新建模板」按钮行为 | 原型是弹出编辑器；现为切换到编辑器（单页内），交互等价但非弹窗 |
| 统计页「用户用量排行」「明细表」 | 原型有排行榜与明细表，后端只有单用户汇总，未虚构数据，故未实现 |
| 表格行内 hover 高亮 | 已实现；但原型 `tbody tr:hover` 的混色比例按 `surface-warm 45%` 复刻，未做像素比对 |

## 10. 未完成验证的部分

以下内容**没有**做像素级比对，报告中未据此下结论：

- 未做逐像素 diff（没有把两张截图做图像差分），对齐判断基于结构、计算样式与组件计数
- 未验证 1100px 以下断点的表现差异（原型 `@media (max-width: 1100px)` 与 `860px` 两档，实现用 `65rem/48rem/38rem`，断点值本身就不一致）
- 未做 320px 窄屏与打印样式验证
- 未评估 深色模式（原型与实现均未提供）

---

---

## 11. 第三轮修复：逐页几何对齐（原型 1440px 实测）

用户指出「页面布局都跟原型偏离太多」。本轮不再凭印象改样式，而是把 9 个页面在 1440px 视口下与原型逐页量几何（卡片 x/宽/高、栅格列宽、列头文案），按量出的差值修。

### 11.1 量出的偏离与修复

| 页面 | 量出的偏离 | 修复 | 复测结果 |
| --- | --- | --- | --- |
| 回答模板 | 编辑器含原型没有的「模板名称」「启用开关」字段；按钮在表单中部且含红色删除；预览是纯文本；列表副标题缺「编辑于」 | 重写：列表宽列（760px）/ 编辑器窄列（380px）与原型一致；编辑器仅「模板正文」（新建时才显示名称）；启停开关收进卡头；按钮移到底部右侧「取消、保存模板」（删除为最左功能位）；预览按段落渲染并把 {question} 等变量渲染成 `.ph` 高亮 chip；列表副标题接 `updatedAt` 显示「编辑于 …」 | 列表 x=260/w=760、编辑器 x=1036/w=380，与原型完全相同 |
| 知识库 | 文档清单没有卡片容器；列结构是自造的「政策状态/生效区间」，原型是「格式/大小、导入时间」；没有「导入新文档 ›」链接 | 文档列表包进 `bk-card`（卡内表格去边框）；列改为原型七列：名称/版本/格式·大小/可检索/状态/导入时间/操作；卡头加 `more` 链接「导入新文档 ›」；成员授权同样包卡 | `docCardFound=true`、`moreLink=导入新文档 ›`、七列齐全、首行 `travel.md · v1 · 169 B · 可检索 · 现行 · 2026-11-21` |
| 问答统计 | 排行与明细为上下两块全宽卡片；原型是「排行（2fr）+ 文档类指标（1fr）」双列 + 折叠明细；筛选散在页面中部 | 周期/日期/用户收进页头 `actions`；补「统计口径」横幅；grid-2 双列（排行含「未上报请求」列与合计行 + 文档类指标卡）；明细改为 `<details>` 折叠卡 | grid-2 内排行 6 列 + tfoot 合计，空间指标 1 行（员工手册 9 次引用/4 提问），`details.bk-card` 存在 |
| 系统设置 | 内容占满 1156px；原型是 `max-width:960px` 窄栏 | `.settings { max-width: 960px }` | `maxWidth=960px` |
| 助手配置 | 名称/模板/模型与绑定空间上下堆叠；启停是表单里的开关行；原型是「左字段右绑定」grid-2 + 卡头「停用」按钮；列表卡无容器 | 重写：详情卡头「启用中徽标 + 停用(danger)按钮」即时生效；grid-2 左（名称/回答模板/模型）右（绑定空间行 + 说明）；列表包进紧边距卡片，副标题显示「绑定 N 个空间 · 启用中/已停用」 | 卡头 `停用` 按钮 + `启用中` 徽标实测；停用→启用往返即时生效；绑定行 3 条 |

### 11.2 为对齐而补的后端

| 接口/字段 | 用途 |
| --- | --- |
| `answer_template.updated_at` 暴露（创建时也写入） | 模板列表「编辑于 2026-11-10」；契约 `AnswerTemplate.updatedAt` 必填 |
| `DraftEntry.createdAt` | 知识库「导入时间」列 |
| `GET /usage/spaces`（管理员） | 统计页「文档类指标（按空间统计）」：被引用次数、覆盖提问，从答案证据依赖聚合 |
| 排行补 `unreportedModelCallCount` | 「未上报请求」列，与单用户口径一致 |
| `AssistantView.enabled` | 助手列表显示「启用中/已停用」，管理员可见停用助手（`listAll`） |

契约现为 **40 路径 / 50 操作**，双向契约测试通过。

### 11.3 验证

- 后端 `mvn test`：18 个测试全部通过（含空间用量 403/200 断言、模板 updatedAt 断言）
- 前端 lint / typecheck / build：全部通过
- 浏览器 1440px：9 页 0 控制台错误、无 undefined/NaN；助手停用→启用即时往返；统计页排行/空间指标/折叠明细与原型同构

### 11.4 仍属功能扩展、原型未画的部分（保留并已在界面就近安放）

- 模板「删除」按钮（原型无；删除被引用模板由后端 409 拒绝）→ 底部按钮行最左
- 模板启停开关（原型无开关；列表用「使用中/未启用」徽标表达状态）→ 编辑器卡头
- 助手「保存配置」按钮（原型卡内无按钮）→ 表单底部
- 知识库「成员授权」卡（原型页面无此区；PRD 要求按空间授权）→ 文档列表卡之下

### 11.5 第四次微调：超宽窗口的内容列居中（用户确认）

**现象**：用户在超宽窗口（截图 2944 物理 px）下看到右侧大片空白并质疑。

**查证**：空白来自内容列 `max-width: 1280px`——该值原样取自原型 `style.css` 第 133 行 `.content { padding; max-width: 1280px }`。窗口 CSS 宽度超过 236（侧栏）+ 1280 = 1516px 后右侧全部留空；并排实测**原型在相同宽度下留着一模一样的空白**（1963px 下原型内容右缘同为 1516，空白 447px；2944px 下同为 1428px）。即这不是实现偏离，是原型自身的限宽设计；原型 mock 只按 ~1440px 绘制，从未展示过超宽窗口的形态。

**决策**：三选一（保持现状 / 居中 / 取消限宽铺满），用户选择**居中**。

**改法**（`App.vue` 的 `.content`）：由 `max-width` 改为居中的单列网格轨道——

```css
.content {
  display: grid;
  grid-template-columns: minmax(0, min(100%, var(--content-width)));
  justify-content: center;
  padding: var(--space-6);
}
```

限宽保留（沿用 token `--content-width: 80rem`），超出部分左右均分而不是单边堆积。

**后续修订（用户看效果后改主意，最终生效）**：居中版在宽窗口两侧仍留大片空白，用户明确要求「内容铺满窗口、两侧只留 20px」，**不再沿用原型的 1280px 限宽**（用户决策覆盖原型值）。最终实现：

```css
.content { padding: var(--space-6) 20px; }
```

`--content-width` token 随之移除；原型的限宽规则与本次决策差异记录在案。

**最终复测**（浏览器实测）：

| CSS 视口 | 内容铺满 | 左 / 右留白 |
| --- | --- | --- |
| 1398（用户实际窗口） | 是 | 20 / 20 |
| 1440 | 是 | 20 / 20 |

知识库 / 统计 / 模板页一致铺满；模板页 2fr:1fr 比例不变（列表 765 / 编辑器 383）。

**已知保留**：系统设置页仍按原型保持 `max-width: 960px` 窄栏（原型 settings.html 明确写了该值），如需同样铺满另行确认。

### 11.6 第五轮新增：图表库封装 + 登录与管理员模块（用户点名补齐）

用户要求：图表改用图表库并封装；补登录模块与管理员模块的页面与 API 接线。

**图表**：新增 `src/components/BaseChart.vue`——全站唯一接触 echarts 的地方（按需引入 LineChart/Grid/Legend/Tooltip/Canvas），负责初始化、`notMerge` 更新、ResizeObserver 自适应、卸载销毁；页面只传普通 option 对象。`StatisticsView` 的手写 SVG 全部移除，颜色/字号经 `src/design.ts`（`designToken`/`withAlpha`）取自主题 token，视觉与原实现一致。体积：echarts 进入统计页懒加载块（gzip 179KB），主包仅 +2KB。

**登录模块**（原型未画登录页，按主题 token 新设计）：
- `src/auth.ts`：全站唯一会话来源；`ensureSession` 幂等拉取 `/api/v1/auth/me`，401 即未登录。
- `LoginView.vue`（路由 `/login`，独立全屏、不套壳）：企业单点登录按钮 → 后端 OIDC（`/oauth2/authorization/enterprise`）；DEV 构建另提供开发身份登录。
- 路由守卫：未登录 → `/login?redirect=…`；登录后回跳原目标页。
- 侧栏用户区新增退出按钮 → `POST /api/v1/auth/logout` → 登录页。
- 后端配套：SSO 成功落地页由 `/api/v1/auth/me`（JSON）改为 `/`；`OidcLoginFlowIT` 断言同步更新。
- 开发头修正：`developmentHeaders()` 不再在无身份时默认回落 `dev-user`，未选择身份即真实未登录。

**管理员模块**：
- 路由 meta：`/settings`、`/answer-templates`、`/assistant-config` 标记 `requiresAdmin`；非管理员访问 → `ForbiddenView`（`/403`）。
- 侧栏「管理」组仅对 SYSTEM_ADMIN 渲染；发布管理与问答统计保持可达（后端按角色过滤数据：普通用户只见自己提交的申请与本人用量）。
- 用户与角色管理沿用系统设置页的「成员与角色」（原型设计如此），未另建重复页面。

**验证**（浏览器实测）：清身份+清 Cookie → `/login?redirect=/settings`；dev-user 登录 → 403 页、侧栏仅「工作区」；退出 → 登录页；smoke-admin 登录 → 「管理」组出现；统计页 echarts canvas 正常、手写 SVG 已删；5 页回归 0 控制台错误。后端 18 个测试全过（含更新后的 OIDC 流程断言）。

**未验证**：企业 SSO 按钮需要真实 IdP（本机未配置，生产联调项，沿用审核报告第 8 节口径）。

### 11.7 企业登录按钮导致 401 JSON 的修复（用户反馈）

**现象**：点击「使用企业账号登录」后看到 `{"title":"Unauthorized","status":401,"code":"UNAUTHENTICATED"}`，且没有任何企业登录页。

**两层原因**：
1. **企业登录页不在本系统里**：SSO 流程是跳转到企业 IdP 的登录页（IdP 自己渲染）。本地未接入 IdP，后端对 `/oauth2/authorization/enterprise` 返回 401 Problem JSON——按钮把用户送进了这个 JSON，属我方 UX 缺陷（按钮无条件可点）。
2. **SPA 登录页路由被 Vite 代理劫持**：`vite.config.ts` 为 OAuth2 回调配置的 `'/login'` 前缀代理（原意是 `/login/oauth2/code/*`）把 SPA 的 `/login` 路由整个转发给了后端，直接看到 401 JSON。

**修复**：
- 后端新增公开接口 `GET /api/v1/auth/providers`（无 IdP 时 `items` 为空，已接入则列出 SSO 注册，如 `enterprise`）；`SecurityConfig` 放行，并顺带修正了 `/auth/csrf` 的匹配路径（原写的短路径 `/auth/csrf` 与实际 `/api/v1/auth/csrf` 不匹配）。
- 登录页先取 providers：**有 SSO 才显示按钮**；未配置时显示提示「企业单点登录尚未配置（未接入 IdP）…」，不再有 401 JSON 死胡同。
- `vite.config.ts` 代理前缀收窄为 `/login/oauth2`，SPA 登录页不再被劫持。
- 契约同步：41 路径 / 51 操作；`AccessUsageAndReviewIT` 断言匿名取 providers 为空数组，`OidcLoginFlowIT` 断言 OIDC 上下文列出 enterprise。

**验证**（浏览器实测）：清身份+清 Cookie → `/login` 渲染登录页（无 SSO 按钮、显示未配置提示、开发身份表单可用）；`curl /api/v1/auth/providers` 匿名 200 `{"items":[]}`；dev 登录管理员后侧栏正常。后端 18 测试全过。

**仍未验证**：接好真实 IdP 后的完整 SSO 回调链（生产联调项；届时 providers 会列出 enterprise，按钮自动出现）。

### 11.8 本地账号登录落地（用户决策：登录页=管理员与普通用户的账号入口）

用户明确定位：「login 页面我就想要一个管理员登录和普通用户登录的地方而已，以及管理员去管理权限和需求文档写的配置信息」；技术方案原定的「首期不做自建密码库」按用户决策修订（记录见后端设计第 5 节）。

**后端**：V5 迁移 `user_credential`（BCrypt）；`POST /auth/login`（公开、失败统一 401、停用 403、限流 429、审计 LOGIN/LOGIN_FAILED）；`POST /users` 建号与 `POST /users/{id}/credential` 改密（仅管理员，企业账号不适用）；`AccessView` 增加 `issuer` 区分本地/企业账号；首次启动引导创建 admin（系统管理员）与 user（普通用户），默认密码仅用于开发并在日志 WARN，可用环境变量覆盖；`aiqa.bootstrap.enabled` 可关（AccessUsageAndReviewIT 因「最后一个管理员不可降级」断言需要单管理员语境而关闭）。契约 43 路径 / 54 操作，双向测试通过。

**前端**：登录页改为真实「用户名 + 密码」表单（本地账号为主入口）；SSO 按钮仅在 `GET /auth/providers` 返回已配置注册时出现；设置页「成员与角色」新增「＋ 新建账号」（用户名/显示名/初始密码/是否管理员）与本地账号「改密」入口，成员行标注「本地账号 / 企业 IdP」。修复两处真实缺陷：登出后模块级 CSRF 令牌未清导致立即登录被 403 拦截（现 `logout()` 清缓存）；登录错误按 `code` 区分（`ACCOUNT_DISABLED` 才显示停用，普通 403 提示刷新重试）。

**验证**（浏览器实测，真实账号非开发头）：`admin` 登录 → 侧栏双分组 + 系统管理员；设置页新建 `clerk02` → 登出 → `clerk02` 登录 → 普通用户、仅「工作区」分组；访问 `/settings` → 403 页；错误密码 → 「用户名或密码错误」。后端 19 个测试全过（新增 `LocalLoginIT`：登录/建号/重名 409/停用 403/限流 429/改密往返）。

**登录页 UI 重做（用户对首版不满意后的第二版）**：改为分屏布局——左侧品牌侧板（主色 token 派生的蓝色渐变、`问` 品牌块、价值主张与四条产品特性、右下角 6% 透明度「问」字水印），右侧 400px 居中表单（标题「登录账号」、全宽 46px 登录按钮、「其他登录方式」分隔线、SSO 未配置提示、开发身份折叠为 `<details>` 收起）。≤920px 时品牌板隐藏、表单转为卡片居中。全部颜色/圆角/字号取自 `theme.css` 既有 token（渐变端点由 `color-mix` 从 `--color-action` 派生，未引入新裸色值）。浏览器实测：admin 登录成功、错误密码提示、窄屏形态正常。

### 11.9 会话有效期与 401 回跳（用户需求四项全部落地）

1. **退出确认**：退出按钮改为先弹确认（取消不登出，确认后登出回登录页）。
2. **401 统一跳转**：`http.ts` 收到 401 即跳 `/login?redirect=<当前页路径+查询参数>`（登录页自身的探测不重复跳转；一次性防重入）。
3. **登录回跳**：登录成功由 redirect 参数带回原页面（浏览器实测：带 filter 参数的知识库、问答统计两种场景均精确回跳）。
4. **后端会话有效期**：补齐后端设计目标——空闲 30 分钟 + 绝对 12 小时（登录记录认证时刻并轮换会话 ID，`SessionExpiryFilter` 到期作废会话）；`SessionExpiryIT` 以 PT1S 实测到期即 401。实现要点：Boot 4 / Servlet 6.1 的 `HttpSession` 已无 `changeSessionId()`，轮换采用「作废旧会话 + 新会话承载上下文」。

## 12. 审查方法与可复现命令

```bash
# 原型静态服务
cd 原型图 && python3 -m http.server 4321

# 前端开发服务（代理到后端 8080）
cd frontend && npm run dev -- --host 127.0.0.1 --port 5173
```

- 截图对比：1440×900 视口，`原型图/workbench.html` ↔ `http://127.0.0.1:5173/`
- DOM 探针：`document.querySelectorAll('.badge').length` 等 25 个选择器逐页计数
- token 比对：正则抽取两份样式表的变量字典后逐键对照

证据文件（本地）：`.playwright-mcp/proto-workbench.png`、`.playwright-mcp/mine-workbench.png`
