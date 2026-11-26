# 变更记录：引入 motion-v，弹窗 / 抽屉 / 下拉菜单补全展开收起动画（2026-11-26）

## 背景

弹窗（`ModalShell`）、右侧抽屉（`DrawerPanel`）此前只有 CSS `@keyframes` 的进场动画，关闭时因为父级 `v-if="false"` 直接卸载节点，没有任何退场；下拉菜单（`SelectMenu`）展开/收起完全是瞬时的。用户要求引入动画库，并给弹窗、抽屉、下拉菜单都补上展开/收起动画；随后要求处理两个边界：**程序化关闭也要退场**、**下拉打开后遇到滚动不要被误关**。

## 实现过程

### 1. 依赖

- `frontend/package.json` 新增 `motion-v@^2.13.0`（Motion for Vue，`motion.dev` 的 Vue 版）及其传递依赖 `@vueuse/core`、`framer-motion`、`motion-dom`。
- 选型理由：项目已是 Vue 3.5 + TypeScript，`motion-v` 提供 `motion` 组件与 `asChild`、时长曲线等能力，可按需 tree-shake；手感接近当前设计 token 的「短时长 + 缓出」基调。

### 2. 动效预设 `frontend/src/motion.ts`（新增）

- 时长与曲线直接对齐既有 token：`--motion-fast`(140ms) / `--motion-base`(220ms) / `--ease-standard`(`cubic-bezier(0.2,0,0,1)`)，没有新增设计 token。
- 量值：`MASK_OPEN/CLOSED`（遮罩只做透明度）、`DIALOG_OPEN/CLOSED`（`opacity + y:8 + scale:0.985`）、`DRAWER_OPEN/CLOSED`（`x:40 + opacity`）、`POPOVER_OPEN/CLOSED`（`scaleY:0.92 + y:∓4 + opacity`）；进场 220ms、退场 140ms。
- `EXIT_DURATION_MS` / `EXIT_SETTLE_MS`（退场时长 + 40ms 缓冲）供退场收尾使用。
- 系统开启「减少动效」时时长压到 0，动画与收尾定时器都立即结束，不会出现「关不掉」的等待。
- `useExitAnimation(notify)`：只给**自己管理显隐、不会随父级卸载**的浮层用（当前只有下拉菜单）。`leaving = true` 先播退场，`时延 + 缓冲` 后再 `notify()` 并复位内部状态，支持同一实例反复开合。

### 3. 退场包装 `frontend/src/components/OverlayTransition.vue`（新增）

- 弹窗 / 抽屉的显隐由调用方 `v-if` 控制，调用方一置 `false`，节点当拍就被移除，组件内部没有机会播退场；程序化关闭（保存成功、接口返回后直接关）因此完全没有动画。
- 该组件用 `<Transition :css="false" @leave>` 包住调用方的浮层节点：节点被判定移除时先回调 `@leave`，在这里用 motion-v 的 `animate()` 播「遮罩淡出 + 面板收起」（`dialog` / `drawer` 两种 `variant` 决定面板选择器与滑出方向），按 `EXIT_SETTLE_MS` 放行移除。
- 只接管退场：进场仍由 `ModalShell` / `DrawerPanel` 内的 motion 组件完成，避免两套动画打架。退场期间遮罩 `pointer-events: none`，不会被二次点击。
- 于是「用户点 X / 取消 / 点遮罩 / 按 Esc」与「保存成功后父级直接置 false」走的是同一条退场路径。

### 4. 弹窗 `frontend/src/components/ModalShell.vue`

- 遮罩与卡片改用 `<Motion as-child>` 包住原来的 `div.modal-mask` / `section.modal-card`：动画直接写在真实 DOM 上，DOM 结构、scoped 样式、`role="dialog"`、模板 ref（焦点查询 / Tab 循环）全部保持原样；这部分只负责进场。
- 关闭入口（关闭按钮、Esc、点遮罩）恢复为直接 `emit('close')`，退场交给 `OverlayTransition`。
- 删除原 `modal-in` keyframes 与 `prefers-reduced-motion` 兜底块。

### 5. 抽屉 `frontend/src/components/DrawerPanel.vue`

- 同一套改法：`as-child` + `DRAWER_OPEN`，从右侧 40px 滑入；关闭直接 `emit('close')`，退场由 `OverlayTransition` 完成。

### 6. 下拉菜单 `frontend/src/components/SelectMenu.vue`

- 面板 `div.select-popover` 用 `<Motion as-child>` 包裹，展开 `scaleY 0.92→1` + `y:∓4→0` + 淡入，收起反向；`transform-origin` 随展开方向切换（向上展开 `center bottom`，否则 `center top`）。
- 原生 popover 顶层渲染保留不动（面板在弹窗 `overflow: hidden` 与 `transform` 祖先内仍要不被裁切），因此收起时不能在动画开始前 `hidePopover()`，改为动画播完的 `finishClose()` 里再 `hidePopover()` 并卸载。
- 触发器 `ChevronDown` 增加 `is-open` 旋转 180°；`aria-expanded` 退场期间即为 `false`。
- **滚动处理重构（边界修复）**：原本任何滚动都会把面板直接收起，浏览器把触发器滚入视野、键盘聚焦、动画引起的滚动都会误关刚打开的面板。现在把定位逻辑抽成 `positionMenu()`，滚动 / 窗口尺寸变化时**重新贴合触发器**（上下定位互斥、高度按可用空间重算）；只有触发器整个离开视口才收起。

### 7. 调用方

- 13 个组件 / 页面里的 14 处浮层挂载点（含 `ImportDialog`、`CitationPreview` 的调用处）全部用 `<OverlayTransition>` 包一层，`v-if`、props、`@close`、插槽内容一行未改：
  `AppLayout`（退出确认）、`AssistantView`（切换助手、删除会话、引用预览）、`AssistantConfigView`（新建/编辑助手）、`AnswerTemplatesView`（新建/编辑模板）、`SettingsView`（全局配置、模型配置、新建账号、编辑成员）、`PublishingView`（审核弹窗）、`KnowledgeView`（创建空间、文档抽屉、导入弹窗）。

## 验证命令与结果

- `npx vue-tsc --noEmit`：0 错误。
- `npm run lint`（`eslint . --max-warnings 0`，全仓库）：通过，无告警。
- `npx prettier --write`（改动文件）：已格式化。
- `npm run build`：通过（仅剩既有 echarts/tiptap chunk 体积告警）。
- 浏览器实测（Chrome，`http://localhost:5173`，admin 登录，真实后端；每处都采样遮罩 opacity 帧，确认有中间值即「真的播了动画」，末帧 `gone` 即「节点已移除」）：
  - 知识库-创建空间 `1.00 → 0.31 → 0.00 → gone`；文档抽屉 `1.00 → 0.59 → 0.19 → 0.05 → 0.00 → gone`；抽屉内导入弹窗 `0.35 → 0.20 → 0.06 → 0.01 → gone`。
  - 设置-全局配置 / 新建模型配置 / 新建账号 / 编辑成员、助手配置-新建助手、回答模板-新建模板、布局-退出确认：均为 `1.00 → ~0.48 → ~0.16 → ~0.04 → 0.00 → gone`。
  - AI 助手-删除会话、切换助手、引用预览（`Teleport` 到 body 的弹窗）：同样有退场并移除。
  - **程序化关闭**：填「空间名称」点「创建草稿」，接口成功后由 `closeCreate()` 关闭，遮罩 `1.00 → 1.00 → 0.76 → 0.24 → 0.07 → 0.01 → 0.00 → gone`，列表出现新空间，无报错。
  - **嵌套**：抽屉内开导入弹窗，弹窗遮罩仍覆盖整屏 `0,0,1440,900`；关闭弹窗后抽屉仍在且可用，再关抽屉正常移除。
  - **下拉滚动**：小幅滚动时面板跟随触发器（`triggerTop 160→74`、`popoverTop 205→119`，间距仍 6px）不被关闭；滚动到触发器离开视口（`triggerTop -306`）才收起；Esc 收起 `1.00 → 0.49 → 0.04 → 0.00 → gone`；弹窗内下拉仍 `:popover-open === true`。
  - `prefers-reduced-motion: reduce`：弹窗与下拉都在 80ms 内收起，节点正常移除。
  - 全程 `pageerror` 与 console error 为空（只有未登录时的 401 探测请求）。
  - 截图：`docs/e2e-results/motion-modal-2026-11-26.png`、`motion-drawer-2026-11-26.png`、`motion-popover-in-modal-2026-11-26.png`。

## 遗留问题

- `PublishingView` 的审核弹窗未做浏览器实测：当前开发库该状态下没有发布申请（表格为空态），无法打开；其挂载方式与其他弹窗完全一致（同一个 `OverlayTransition`），代码路径相同。
- 面板动画结束后 motion 会在 `.modal-card` / `.drawer-panel` 上保留内联 `transform: matrix(1,0,0,1,0,0)`（此前 CSS 关键帧结束后会回落到 `none`），即这两个面板常驻一个层叠上下文 / 包含块。现有代码没有把 `position: fixed` 元素放在面板内部的用法（下拉面板走 popover 顶层，导入弹窗挂在页面级），因此无影响；后续若要在面板内部放固定定位元素需注意。
- 动效时长在模块加载时读取一次系统「减少动效」设置，会话中途切换系统设置需刷新页面生效（与 CSS 变量行为一致）。
- 验证过程创建了一个知识空间 `动效验证-649780`（用于验证「程序化关闭」），后端没有删除空间的接口，需要在开发库里手工清理。
