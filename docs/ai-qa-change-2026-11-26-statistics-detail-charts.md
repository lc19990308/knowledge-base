# 变更记录：问答统计「提问明细」图表展示（2026-11-26）

## 背景

用户要求「提问明细（供对账，最多最近 200 条）」不要只有表格，还要图表展示。原有明细是折叠区的纯表格，只能逐条看，难以快速判断提问高峰和失败占比。

## 实现过程

仅在 `frontend/src/views/StatisticsView.vue` 内改动，复用现有 `BaseChart`（ECharts 封装）与主题 token，未新增依赖、未改接口。

1. **北京时间切片工具**：新增模块内 `detailTimeSlice()`，用 `Intl.DateTimeFormat`（`Asia/Shanghai`、`hourCycle: h23`）把 `acceptedAt` 转成「日期 + 小时」，解析失败返回 `null`，避免产生空桶。
2. **时间分布图**（`detailTimeOption`）：
   - 自然日：预置 0–23 小时桶，按小时看峰值，x 轴每 4 小时一个标签；
   - 自然周/月：按明细实际日期分桶，`yyyy-MM-dd` 定宽字典序即时间序；
   - 双柱系列「提问次数 / 失败请求数」，颜色取 `--color-action` / `--color-danger`。
   - 首版小时桶用 `Map` + 字符串排序，出现 `0,1,10,11,12…` 错序；改为数组预置 + 数值顺序后修正，同时去掉 `hideOverlap`，改用固定 `interval` 保证刻度等距。
3. **状态构成图**（`detailStatusOption`）：按状态聚合为环形图，颜色沿用语义 token（`SUCCEEDED` → `--color-success`、`FAILED` → `--color-danger`、`RUNNING` → `--color-warning`，未知状态回退 `--color-text-muted`），图例在下、标签显示「状态 + 条数」。
4. **布局**：两张图置于明细表格上方，`grid-template-columns: repeat(auto-fit, minmax(260px, 1fr))` 自适应（宽屏并排、窄屏单列），卡片内标题用 `--text-xs` / `--color-text-muted`，边框 `--color-divider`、圆角 `--radius-sm`。折叠区关闭时图表随明细一起隐藏，展开后 `BaseChart` 的 `ResizeObserver` 自动恢复尺寸。

## 验证命令与结果

- `cd frontend && npx eslint src/views/StatisticsView.vue --max-warnings 0`：通过。
- `cd frontend && npx vue-tsc --noEmit`：通过，0 错误。
- 浏览器实测（Chrome + Playwright，1440×1100，本地 5173 + 8080）：
  - 真实接口（普通用户、自然周/月/日多周期）均返回 `200`、`total: 0`，页面正确显示「该周期没有提问记录」，无图表容器残留。
  - 本地库中普通用户无历史提问，为验证渲染，用 Playwright 拦截 `GET /api/v1/usage/questions` 返回构造的 200 条明细（跨 11-23～11-26、含「已回答 / 处理失败 / 生成中」三种状态与部分未上报 Tokens），其余接口走真实后端。
  - 自然周：展开明细后 2 个 canvas 各 529×200，表格 200 行；自然日：24 个小时桶顺序正确、标签等距（`00:00 / 04:00 / … / 20:00`）。
  - 截图：`docs/e2e-results/statistics-detail-charts-2026-11-26-week.png`、`docs/e2e-results/statistics-detail-charts-2026-11-26-day.png`。
- 控制台仅有登录前 `GET /auth/me` 的预期 401，无图表相关报错。

## 遗留问题的处理（同日补充）

1. **口径容易误读**：明细图表上方新增一行说明「图表基于明细接口最近 N 条记录，与上方全量趋势口径不同」，N 取实际返回条数；不改图表数据源，保持与表格逐条对账一致。
2. **移动端横向溢出**：390×844 实测发现统计页整页被撑到 508px。定位到三处 min-content 传导，均在本页 scoped 样式内修复：
   - `.statistics` 的 grid 列由隐式 `1fr` 改为 `minmax(0, 1fr)`，不再被表格 min-content 撑开；
   - 640px 以下页头筛选控件收成整行、KPI 单列，`.bk-grid-2` 改 `minmax(0, 1fr)`；
   - 明细表格设 `min-width: 720px`，窄屏在 `.bk-table-wrap` 内横向滚动，避免时间和提问被压成逐字换行。
   
   修复后 `document.documentElement.scrollWidth` 由 524 降到 406，统计页自身 `scrollWidth` 等于容器宽 358；剩余 16px 来自全站顶栏搜索按钮（`AppLayout`），所有页面共存，不在本次范围，未改动。
3. **深色主题**：切换到深色主题后图表柱色、状态环色、坐标轴与图例均随 token 变化，无残留浅色；截图见下。
4. **lint 门禁被外部临时脚本卡住**：`e2e/tmp-nested.mjs` 等临时调试脚本（非本次产物）触发 `no-undef`。在 `frontend/eslint.config.mjs` 的 `globalIgnores` 增加 `**/tmp-*.mjs`，临时脚本不参与 lint，正式脚本检查不变。

### 补充验证

- `cd frontend && npm run lint`：通过（0 问题）。
- `cd frontend && npx vue-tsc --noEmit`：通过。
- 浏览器实测（Chrome + Playwright）：
  - 1440×1100 深色主题：口径说明正常显示；两张图颜色随主题切换；控制台无报错。
  - 390×844 浅色：两张图上下单列、各 316×252；明细表格横向滚动、无逐字换行；`statistics.scrollWidth === 358`（容器宽）。
- 新增截图：`docs/e2e-results/statistics-detail-charts-2026-11-26-dark.png`、`statistics-detail-charts-2026-11-26-mobile.png`。

## 遗留问题

- 图表数据仍来自明细接口（最多 200 条），已在图上方写明口径；若需要按全量对账，应改用 `/usage/series` 的分桶数据，属于另一次改动。
- 390px 下全站顶栏搜索按钮有 16px 溢出（`AppLayout` 既有问题，其他页面同样存在），未在本次处理。
