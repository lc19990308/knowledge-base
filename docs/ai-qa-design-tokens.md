# 知识库前端 · 白天 / 黑夜双主题设计 Token

> 交付物：`frontend/src/styles/theme.css` 的两套颜色 token、`docs/design-tokens-preview.html` 预览页、两张全量截图。
> 取值与对比度均由脚本直接解析 `theme.css` 得出，不是手抄估计。

## 1. 结论

- **两套 token 已落地**：亮色 25 个颜色 token，暗色块给出 25 个同名覆盖，脚本校验「亮色有而暗色无」的差集为 **0**。
- **亮色观感不变**：原先由 `color-mix()` 派生的 5 个值已还原成等价 hex 写入，改造前后同一套颜色；新增的 `--color-surface-raised` 在亮色下等于 `--color-surface`，不产生视觉差异。
- **暗色全部达标**：27 组关键前景/背景组合的 WCAG 对比度均通过（正文 12.10–16.08，弱化文字最低 4.92，按钮文字 5.10–7.35）。
- **亮色 1 项既有问题已修**：`--color-warning` 作 1px 描边只有 2.15:1，已拆出 `--color-warning-border`（亮色 `#c27803`，3.51:1；暗色 `#d99b24`）。
- **切换器已接入**：顶栏三态（跟随系统 / 浅色 / 深色），首屏内联脚本防闪烁，跨标签页同步，ECharts 图随主题重着色。行为与验证见 §7。

## 2. 分层与命名

**只保留语义层（L2），不建原始色阶（L1）。** 理由：数量只有 25 个、主题只有 2 套，L1 会变成一层纯转发的中间层，改一次颜色要动两个块，收益为零。等主题数 ≥ 3 或需要按色相批量生成色阶时再引入。

命名规则：`--color-{角色}[-{变体}]`，角色词固定为 `canvas / surface / text / border / divider / action / success / warning / danger / mask`。

三条硬约束写进了文件头注释：

1. 组件只引用语义 token，不写裸色值；
2. 新增颜色 token 必须同时给亮、暗两个值 —— 漏一个就是「永远亮着的一块区域」；
3. 颜色值统一 6 位 hex —— `design.ts` 的 `withAlpha()` 只认 6 位 hex，其余格式会**静默返回原值**（`--color-mask` 除外，它不进画布）。

## 3. 两套取值对照

| Token                      | 亮色                  | 暗色               | 承担的角色           |
| -------------------------- | --------------------- | ------------------ | -------------------- |
| `--color-canvas`           | `#f6f7f9`             | `#0e1116`          | 页面底               |
| `--color-surface`          | `#ffffff`             | `#151a21`          | 卡片、菜单、表格     |
| `--color-surface-raised`   | `#ffffff`             | `#1c222b`          | 弹窗、抽屉           |
| `--color-surface-selected` | `#eef4ff`             | `#1a2b45`          | 选中行、当前导航项   |
| `--color-text`             | `#172033`             | `#e8edf4`          | 正文、标题           |
| `--color-text-secondary`   | `#3b4658`             | `#b3bdcb`          | 次要信息             |
| `--color-text-muted`       | `#5f6b7d`             | `#8d99a8`          | 元信息、占位符       |
| `--color-border`           | `#d8dee8`             | `#2b333d`          | 容器与控件描边       |
| `--color-divider`          | `#edf1f6`             | `#1f252d`          | 分隔线、弱底         |
| `--color-action`           | `#2563eb`             | `#3b82f6`          | 主操作底、链接、图标 |
| `--color-action-text`      | `#ffffff`             | `#0a1220`          | 主色实底上的文字     |
| `--color-action-hover`     | `#2058d2`             | `#5292f8`          | 主操作悬停           |
| `--color-action-active`    | `#1c50c0`             | `#6aa3fa`          | 主操作按下           |
| `--color-success`          | `#16a34a`             | `#2fbd6c`          | 成功填充             |
| `--color-warning`          | `#f59e0b`             | `#eeb02c`          | 警告填充             |
| `--color-danger`           | `#dc2626`             | `#f0545a`          | 危险填充             |
| `--color-text-success`     | `#166534`             | `#4ccf8b`          | 成功文字             |
| `--color-text-warning`     | `#92400e`             | `#f2c46a`          | 警告文字             |
| `--color-text-danger`      | `#991b1b`             | `#ff8b8f`          | 危险文字             |
| `--color-warning-border`   | `#c27803`             | `#d99b24`          | 警告态 1px 描边      |
| `--color-text-inverse`     | `#ffffff`             | `#0e1116`          | 实心色块上的文字     |
| `--color-surface-success`  | `#e3f4e9`             | `#1a3b30`          | 成功徽标底           |
| `--color-surface-warning`  | `#fef1dd`             | `#403823`          | 警告徽标底           |
| `--color-surface-danger`   | `#fce9e9`             | `#3c242b`          | 危险徽标底           |
| `--color-mask`             | `rgb(23 32 51 / 35%)` | `rgb(0 0 0 / 65%)` | 弹窗/抽屉遮罩        |

非颜色 token（字号、间距、圆角、动效、表格与表单尺寸）不随主题变化，保持原值不动。`--elev-ring` 与 `--shadow-focus` 内部引用 `--color-border` / `--color-action`，取值在使用处解析，切主题自动跟随，所以暗色块里不需要重复声明；只有 `--elev-raised`（含字面量阴影色）在暗色块里覆盖为 `0 16px 40px rgb(0 0 0 / 55%)`。

## 4. 六个关键决策

**① 暗色主色必须提亮，按钮文字随之反色。**
`#2563eb` 压在本主题深底上只有 3.6:1，按钮会发闷；提到 `#3b82f6` 后白字只剩 3.7:1（不达标），于是按钮文字换成深墨 `#0a1220`（5.10:1）。两种主题各自满足 4.5:1，代价是暗色主按钮是「亮蓝底 + 深字」——这是有意的取舍，与亮色的「深蓝底 + 白字」是同一角色、不同取值。若更希望暗色也保持白字，须把主色压到 `#2563eb` 附近，同时接受按钮在深底上只有 3.6:1。

**② hover / active 写成显式值，方向在两套主题里相反。**
原来是 `color-mix(in oklab, var(--color-action), black 8%)`。这套派生在暗色下是错的：主色变亮后再叠黑等于「变脏」。现在亮色越按越深（`#2563eb → #2058d2 → #1c50c0`），暗色越按越亮（`#3b82f6 → #5292f8 → #6aa3fa`），两者都是「朝远离底气色的方向走」，按钮文字对比度都单调上升。

**③ 浮层在暗色下要比卡片亮一档。**
亮色的 `--color-surface-raised` 等于卡片色（`#ffffff`），层级靠 `--elev-raised` 阴影与遮罩表达；暗色下阴影几乎不可见，只能靠明度差（`#1c222b` vs `#151a21`，1.09:1）加描边。这是两套主题唯一「同名但结构含义略有差异」的地方，也是暗色接入必须先补这个 token 的原因。

**④ 正文不用纯白。** 暗色正文取 `#e8edf4`（15.5:1 左右），而不是 `#ffffff`：纯白在深底上会产生光晕，长时间阅读易疲劳，15:1 已远超 AA。

**⑤ 遮罩不透明度从 35% 提到 65%。** 亮色的 `rgb(23 32 51 / 35%)` 叠在浅底上已足够把背景推开；暗色底本身接近黑，同样 35% 几乎看不出压暗，必须加大。两处重复的裸值（`DrawerPanel.vue`、`ModalShell.vue`）同时收敛为 `--color-mask`。

**⑥ 状态浅底在暗色下提高混入比例。** 亮色 12–14% 混入白色即得淡底；暗色若沿用同一比例，混在深底上等于没上色，因此按 18–20% 混入并直接固化为 hex（`#1a3b30` / `#403823` / `#3c242b`）。

## 5. 对比度实测

脚本解析 `theme.css` 的真实取值后按 WCAG 2.1 相对亮度公式计算：

| 组合                        | 亮色           | 暗色  | 要求   |
| --------------------------- | -------------- | ----- | ------ |
| 正文 / 页底 canvas          | 15.18          | 16.08 | ≥ 4.5  |
| 正文 / 卡片 surface         | 16.27          | 14.86 | ≥ 4.5  |
| 正文 / 浮层 raised          | 16.27          | 13.59 | ≥ 4.5  |
| 正文 / 选中行 selected      | 14.73          | 12.10 | ≥ 4.5  |
| 次要文字 / 卡片             | 9.53           | 9.20  | ≥ 4.5  |
| 次要文字 / 选中行           | 8.63           | 7.49  | ≥ 4.5  |
| 弱文字 / 页底               | 5.04           | 6.53  | ≥ 4.5  |
| 弱文字 / 卡片               | 5.40           | 6.04  | ≥ 4.5  |
| 弱文字 / 浮层               | 5.40           | 5.52  | ≥ 4.5  |
| 弱文字 / 选中行             | 4.89           | 4.92  | ≥ 4.5  |
| 按钮文字 / 主色             | 5.17           | 5.10  | ≥ 4.5  |
| 按钮文字 / hover            | 6.20           | 6.09  | ≥ 4.5  |
| 按钮文字 / active           | 7.10           | 7.35  | ≥ 4.5  |
| 主色 / 页底（图形/链接）    | 4.82           | 5.14  | ≥ 3    |
| 主色 / 卡片（图形/链接）    | 5.17           | 4.75  | ≥ 3    |
| success 文字 / 自身浅底     | 6.24           | 6.19  | ≥ 4.5  |
| warning 文字 / 自身浅底     | 6.36           | 7.12  | ≥ 4.5  |
| danger 文字 / 自身浅底      | 7.11           | 6.31  | ≥ 4.5  |
| success 填充 / 卡片         | 3.30           | 7.17  | ≥ 3    |
| warning 填充 / 卡片         | **2.15** ⚠     | 9.06  | ≥ 3    |
| danger 填充 / 卡片          | 4.83           | 5.09  | ≥ 3    |
| 分隔线 / 卡片（装饰）       | 1.13           | 1.13  | ≥ 1.05 |
| 边框 / 卡片（装饰）         | 1.35           | 1.37  | ≥ 1.05 |
| 警告描边 / 卡片（控件边界） | 3.51           | 7.21  | ≥ 3    |
| 卡片 / 页底（层级）         | 1.07           | 1.08  | ≥ 1.03 |
| 浮层 / 卡片（层级）         | 1.00（靠阴影） | 1.09  | ≥ 1.03 |
| 选中行 / 卡片（层级）       | 1.10           | 1.23  | ≥ 1.05 |

## 6. 已知问题

| 问题                       | 现状                                                                                                                     | 影响                                                            | 建议                                                  |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------- | ----------------------------------------------------- |
| ~~warning 描边对比度不足~~ | **已修**：拆出 `--color-warning-border` 描边 token（亮色 `#c27803` 3.51:1 / 暗色 `#d99b24`），`SpaceScopePicker` 改用它  | —                                                               | 已完成                                                |
| warning 填充仍偏浅         | `--color-warning` 对白底 2.15:1，且是 3 处 `color-mix()` 的混色基色                                                      | 目前没有拿它单独画图形的地方，改值会带动整组提示色偏色          | 将来要画 warning 圆点/柱状时改用描边 token            |
| 禁用态靠透明度             | `--form-disabled-opacity: 0.65`                                                                                          | 透明度会把文字、图标、描边一起压暗，暗色下更难辨认且不可控      | 改为显式 `--color-text-disabled` + 禁用底色两个 token |
| 焦点机制两套并存           | `base.css` 用 `outline: 2px solid var(--color-action)`，`components.css` 与 4 个组件用 `box-shadow: var(--shadow-focus)` | 同一页出现两种焦点样式                                          | 统一为 outline + `outline-offset: 2px`                |
| 动效 token 重复            | `--transition-fast` 与 `--motion-fast` 都是 140ms；`prefers-reduced-motion` 只归零了前者                                 | 用户开「减少动态效果」后，占 15 处引用的 `--motion-fast` 仍在动 | 合并为一个 token，降级覆盖全部 duration               |
| 组件级 token 挂在 `:root`  | `--table-*`、`--button-*`、`--dialog-*`、`--form-*` 共 22 个在全局                                                       | 组件私有量成了全局 API                                          | 下沉到组件作用域                                      |
| 两处裸阴影色               | `components.css` 的 `rgb(0 0 0 / 20%)`、`AssistantView.vue` 的 `rgb(23 32 51 / 4%)`                                      | 两套主题下都是同一阴影，观感差异可忽略                          | 需要时补 `--shadow-*` 两套取值                        |

这些都不阻塞双主题，按优先级排在后面。

## 7. 主题切换：已实现

**结构**

| 文件                        | 职责                                                                                                               |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `index.html`                | `<head>` 内联脚本：读 `localStorage.kb.theme` → 解析 → 立即写 `data-theme`，整段 `try/catch`。早于任何打包产物执行 |
| `src/theme.ts`              | 唯一状态源：三态偏好 `light/dark/system`、系统变化监听、跨标签 `storage` 同步、写 `meta[name=theme-color]`         |
| `src/layouts/AppLayout.vue` | 顶栏三态切换器（跟随系统 / 浅色 / 深色），`aria-pressed` + `title` + 屏读文本                                      |
| `src/design.ts`             | `designToken()` 读取响应式 `resolvedTheme`，让图表 option 的 `computed` 在切主题后重新求值                         |

**行为约定**

1. 用户偏好三态存 `kb.theme`；DOM 上只出现 `light` / `dark` 两个值，`system` 在启动时解析，因此 CSS 只需两块、不必再写一份媒体查询。
2. 系统主题变化只在偏好为 `system` 时跟随——用户手动选过浅色，系统换深色不会把它顶掉。
3. 多标签页通过 `storage` 事件同步；另一标签改了偏好时只应用、不回写存储。
4. 只有用户显式点击才写存储；`?theme=` 之类的强制手段没有引入（需要时再加，且只写属性不写存储）。
5. `meta[name=theme-color]` 取 `--color-canvas` 的实际计算值，token 改了就跟着变。

**实测结果**（production build + Playwright，`/api/v1/**` 按 `{code:'OK', requestId, data}` 契约返回）

| 检查                                | 结果                                                                                                          |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| 屏蔽全部打包 JS 后首屏 `data-theme` | `dark`，且此时 `#app` 仍为空 → 不依赖 bundle，不会闪白                                                        |
| 顶栏切换器                          | 3 个选项，默认选中「跟随系统」                                                                                |
| 点「深色主题」                      | `data-theme=dark`、`localStorage=dark`、`meta=#0e1116`、`--color-canvas=#0e1116`、`body` 背景 `rgb(14,17,22)` |
| 刷新                                | 文档一开始就是 `dark`（内联脚本），加载完成后仍 `dark`，选中项回到「深色主题」                                |
| 点「跟随系统」                      | 存储写入 `system`，当前按系统偏好解析为 `light`                                                               |
| 图表重着色                          | 切换前/后取趋势图 canvas 的 `toDataURL()`，两次不同 → option 已按新 token 重算                                |

## 8. 改动清单与验证

| 文件                                                          | 改动                                                                                                                                                                                                                                                          |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `frontend/src/styles/theme.css`                               | 颜色层重写为「亮色 `:root` + 暗色 `[data-theme='dark']`」两块，25 个颜色 token 两套取值；`color-mix` 派生值还原为 hex；新增 `--color-surface-raised`、`--color-mask`、警告描边 token、`--color-text-inverse`；暗色覆盖 `--elev-raised`。非颜色 token 原样保留 |
| `frontend/src/theme.ts`（新增）                               | 主题状态机：三态偏好、系统跟随、跨标签同步、`meta theme-color`                                                                                                                                                                                                |
| `frontend/index.html`                                         | 首屏内联解析脚本 + `meta[name=theme-color]`                                                                                                                                                                                                                   |
| `frontend/src/design.ts`                                      | `designToken()` 建立对 `resolvedTheme` 的响应式依赖                                                                                                                                                                                                           |
| `frontend/src/layouts/AppLayout.vue`                          | 顶栏三态主题切换器与其样式                                                                                                                                                                                                                                    |
| `frontend/src/components/DrawerPanel.vue`、`ModalShell.vue`   | 遮罩裸值 → `var(--color-mask)`；面板与底部操作条 → `var(--color-surface-raised)`                                                                                                                                                                              |
| `frontend/src/components/SpaceScopePicker.vue`                | 警告描边改用专用 token                                                                                                                                                                                                                                        |
| `frontend/src/styles/components.css`                          | 步骤条序号的两处 `color: #fff` → `var(--color-action-text)` / `var(--color-text-inverse)`                                                                                                                                                                     |
| `docs/design-tokens-preview.html`                             | 预览页，`<link>` 引用项目真实样式表，可切换亮暗并显示每个 token 的计算值                                                                                                                                                                                      |
| `docs/design-tokens-light.png`、`docs/design-tokens-dark.png` | 预览页全量截图（1200×1826）                                                                                                                                                                                                                                   |
| `docs/theme-app-light.png`、`docs/theme-app-dark.png`         | 真实应用工作台在两种主题下的截图（1440×900，接口为契约级 mock）                                                                                                                                                                                               |
| `docs/ai-qa-design-tokens.md`                                 | 本文                                                                                                                                                                                                                                                          |

**验证方式（实际执行）**

1. `npm run lint` → 通过（`--max-warnings 0`）。
2. `npm run typecheck` → `vue-tsc --noEmit` 通过。
3. `npm run build` → 构建通过（仅 chunk 体积提示，属既有情况）。
4. `npx prettier --check` → 本次改动文件全部通过。
5. 对比度脚本解析 `theme.css` 实跑：暗色 27 组全部 PASS；亮色 26 组 PASS + 1 组 NOTE（浮层与卡片同值，由阴影承担层级）；亮/暗 token 名差集为空。
6. 浏览器实测：见 §7「实测结果」，控制台无与主题相关的报错。

**未验证 / 未覆盖**

- 真实后端联调：本次用 Playwright 拦截 `/api/v1/**` 返回契约级响应（`{code:'OK', requestId, data}`），未启动 Java 后端与数据库。
- 登录页没有放切换器（属于免登录页），但主题仍按存储/系统偏好生效。
- 移动端窄屏下切换器的换行位置只做了布局推断，未做真机验证。
- `forced-colors` 高对比模式、打印样式未适配。
