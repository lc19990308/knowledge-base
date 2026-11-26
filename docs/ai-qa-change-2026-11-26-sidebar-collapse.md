# 变更记录：平台侧栏支持收起 / 展开并带过渡动画（2026-11-26）

## 背景

`AppLayout` 的左侧导航一直是固定 236px 展开态，文档与助手页内容区被永久挤占。用户要求侧栏可收起、可展开，且开合要有过渡动画，不能生硬瞬切。

## 实现过程

### 1. 设计 token：`frontend/src/styles/theme.css`

- 在既有 `--sidebar-width: 236px` 旁新增 `--sidebar-width-collapsed: 64px`，两者放在一起方便对照维护，并写明 64px 的来历：侧栏内边距 12px + 导航链接内边距 12px + 图标 16px/2 = 图标中点正好落在 32px（64/2），收起时图标不需要再做位移。

### 2. 布局壳层：`frontend/src/layouts/AppLayout.vue`

结构改动（都很小）：

- `.app-shell` 栅格第一列从 `var(--sidebar-width)` 改为 `auto`，让列宽跟着侧栏自身 `width` 走。这样只对侧栏的 `width` 做过渡，主区宽度随列宽每帧平滑让位，不必去插值 `grid-template-columns`（各浏览器支持不一致）。
- 侧栏加 `width: var(--sidebar-width)` + `transition: width var(--motion-base) var(--ease-standard)`（220ms，比 hover 的 140ms 慢一档），收起态 `.is-collapsed` 切到 64px。
- 文字退场方式：**保留文字的自然宽度，靠侧栏变窄裁切 + 自身透明度淡出**（`--motion-fast` 140ms），不在收起时把文字宽度瞬间设为 0。这样图标位置全程不动，不会出现「图标先跳一下再淡出」。
  - 为此给 `.brand` / `.nav-link` / `.user-chip` 加 `overflow: hidden`，给 `.sidebar` 加 `overflow-x: hidden`，并给 `.nav-link svg` 加 `flex: none`——**这一条是必须的**：导航文字挤出容器后，图标作为可收缩 flex 项会被压成 0 宽，收起态会只剩空导航条（实测宽度为 0）。
  - 品牌块与账号区在收起态把 `padding-inline` 从 8px 收到 4px（同样带过渡），让 32px 的图标块 / 头像对齐到 32px 中线上。
- 分组标题收起后文字不可读，改为淡出并显示一条 `::after` 细分割线，继续表达「工作区 / 管理」的分组。
- 开合按钮放在顶栏标题左侧（`.sidebar-toggle`，30px，沿用 AI 助手会话栏按钮 `PanelLeftClose/PanelLeftOpen` 的同一套尺寸与 hover），带 `aria-expanded` / `aria-controls="app-sidebar"` / `aria-label` / `title`。
- 收起态侧栏只剩头像，退出登录改由顶栏承接：`.topbar-user` 在桌面端按 `is-shown` 显示（仅 `min-width: 861px` 生效，窄屏本来就有这一块），侧栏内的退出按钮加 `:inert`，避免被裁掉的按钮还能被 Tab 聚焦。
- 折叠状态持久化到 `localStorage`（`kb.layout.sidebar-collapsed`，与 `kb.theme` 同前缀），读写都做 try/catch，无痕模式下失败不影响本次开合。
- 首帧保护：`.app-shell` 挂载并完成一次 `requestAnimationFrame` 后才加 `is-ready`，`is-ready` 之前对侧栏与文字类元素设 `transition: none`，避免刷新时先闪一下展开态再收起。

### 3. 性能：顶栏 `will-change: transform`

开合时主区每帧变宽，顶栏是 `position: sticky` + `backdrop-filter: blur(10px)`，不加提示时 Chrome 会跟着逐帧重建背景光栅。软件渲染的无头 Chrome 实测把 220ms 过渡拖成数百毫秒空档；加 `will-change: transform` 让顶栏常驻独立图层后，同一页面同一操作恢复到 16ms 一帧、宽度按时长单调收敛（236 → 222 → 174 → 139 → 117 → 101 → 90 → 82 → 76 → 71 → 68 → 66 → 64.5 → 64）。

> 也试过相反思路：在动画窗口内用 JS 加类临时关掉 `backdrop-filter`。实测**更差**（图层反复建/销毁，单次长任务反而更大），所以没有采用，代码里不留这条路径。

### 4. 窄屏与减少动效

- `max-width: 860px`：侧栏本就是一条横排导航，没有收起概念，媒体查询里重置为 `width: auto` 并忽略 `.is-collapsed`（文字恢复可见、分割线取消），同时隐藏开合按钮。
- `prefers-reduced-motion: reduce`：侧栏宽度、品牌块/账号区内边距、文字淡出全部 `transition: none`，直接切到目标宽度。

## 验证命令与结果

- `npx vue-tsc --noEmit`：0 错误。
- `npm run lint`（`eslint . --max-warnings 0`）：0 错误、0 警告。
- `npx prettier --write src/layouts/AppLayout.vue`：已格式化。
- 浏览器实测（Playwright + 本机 Chrome，`http://localhost:5174`，admin 登录，真实后端）：
  - 宽度：展开态 236px → 收起态 64px，展开点回去仍是 236px。
  - 收起态图标中点 x=32（侧栏中线），文字透明度 `1 → 0.77 → 0.24 → 0.07 → 0`，宽度逐帧单调收敛，无跳变。
  - 导航链接在收起态带 `title="工作台"`，hover 有原生提示。
  - 顶栏出现「系统管理员 + 退出」入口（收起态可见），侧栏内退出按钮 `opacity: 0` 且 `inert`。
  - 开合按钮 `aria-expanded` 与 `aria-label`（收起侧栏 / 展开侧栏）随状态切换。
  - 刷新后保持收起态（`localStorage = "1"`，宽度 64px）。
  - 窄屏 720px：开合按钮不可见，侧栏与主区都是 720px 满宽。
  - `prefers-reduced-motion: reduce`：侧栏 `transition-property` 为 `none`。
  - 全流程 `pageerror` 为空。
  - 截图：`docs/e2e-results/sidebar-expanded.png`、`sidebar-collapsed.png`。
- 既有 E2E 断言未受影响：`frontend/e2e/admin.mjs` 依赖 `getByRole('complementary', { name: '平台侧栏' })` 与链接可访问名，收起态只是把文字视觉上淡出裁切，DOM 与可访问名不变。

## 遗留问题

- **图表页开合可能掉帧**：主区每帧变宽会让 `BaseChart` 的 `ResizeObserver` 每帧调用一次 `chart.resize()`，工作台 / 统计页这类多图表页面在低性能环境（软件渲染）下开合会看到卡顿。根治要改 `BaseChart`（例如合并 resize、动画期间暂停），属于另一个组件的行为，本次未动。
- **`will-change: transform` 是常驻提示**：会一直占一个合成层。以顶栏这种长期可见、自带毛玻璃的元素来说代价可接受；若后续顶栏不再使用 `backdrop-filter`，这一行可以一并删掉。
- 开合状态按浏览器本地存储记忆，没有同步到用户账号（换浏览器/设备不同步），与主题偏好的处理方式一致。
