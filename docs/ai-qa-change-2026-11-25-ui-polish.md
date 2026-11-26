# 变更记录：全站 UI 组件与表单美化（2026-11-25）

## 背景

用户要求对 localhost:5173 项目的所有页面、UI 组件、表单做整体美化。走查了工作台、知识库、AI 助手、发布管理、问答统计、回答模板、助手配置、系统设置、登录页共 9 个页面（亮暗双主题），确认视觉基线（token 体系、对比度验算）已有良好基础，本次做的是**质感层**升级：不改任何 DOM 结构、类名、交互逻辑，只动 CSS。

## 实现过程

1. **theme.css 设计 token 升级**
   - 字体栈补中文优先回退：`Inter, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei'`；`--font-display` 同步（原文件底部有重复声明，顺带收敛为同一栈）。
   - 新增三档质感 token（亮暗成对）：`--elev-card`（卡片静态轻投影）、`--elev-pop`（弹窗/下拉第二档投影）、`--action-gradient`（主按钮 2%–10% 微渐变，暗色反转方向模拟环境光）。
2. **components.css / base.css 组件层美化**
   - 按钮：默认轻投影；主按钮渐变底 + hover 聚拢投影；全局 `:active` 下沉 1px；危险按钮 hover 描边加深。
   - 卡片 / 表格容器：统一 `--elev-card`；表头补 0.02em 字距；数据行底色与行内主标题 hover 过渡 + 提色。
   - 徽标 / 状态胶囊：内侧 4% 极淡描边，浅底徽标在白卡上不再"浮"。
   - 表单：输入框 hover 描边加深（不与 focus 冲突）；chip / tab / step 补过渡。
   - 链接：下划线透明→悬停显色（base.css 全局），不再"闪"。
   - 全局细滚动条（`scrollbar-width: thin`）+ 抗锯齿文字渲染。
3. **浮层组件**
   - ModalShell：改用 `--elev-pop`，新增 180ms 入场动画（上浮 + 微缩放，`prefers-reduced-motion` 关闭）。
   - DrawerPanel / SelectMenu 下拉：投影统一换 `--elev-pop`，下拉底改 `surface-raised`。
4. **壳层与页面**
   - AppLayout：品牌标 / 头像投影；当前导航项左侧 3px 主色竖条；侧栏右缘 3% 极淡投影；顶栏毛玻璃 blur 8→10px + 细投影；主题切换器补过渡。
   - LoginView：登录主按钮主色 35% 投影；品牌标内侧高光；特性圆点加外环。
   - AssistantView：用户气泡偏主色浅底 + 内侧高光；AI 气泡 `--elev-card`；引用卡 hover 底色联动；会话项 / 发送按钮补过渡与投影。
   - WorkbenchView：KPI 卡 hover 微抬 1px + 投影聚拢；快捷入口 hover 上浮。

## 验证命令与结果

- `npm run typecheck`（vue-tsc --noEmit）：通过，0 错误。
- `npm run lint`（eslint --max-warnings 0）：通过，No issues found。
- `npx prettier --write`（改动文件）：全部格式化。
- `npm run build`：构建成功 4.13s（chunk 大小警告为既有状况，与本次无关）。
- 浏览器实测（Chrome DevTools MCP，1440×900）：
  - 新 token 运行时全部解析成功（`--elev-card` / `--elev-pop` / `--action-gradient` / 中文字体栈）。
  - 组件实测取用：主按钮背景已是 `linear-gradient(oklab(...))`，卡片/徽标投影/内描边生效。
  - 暗色主按钮文字对比度 5.10:1（≥4.5 达标）。
  - 工作台 / 知识库 / 助手 / 统计 / 设置 / 回答模板 / 登录页均无横向溢出（overflowX=0）。
  - 亮暗双主题逐页截图核对：层级、描边、徽标、hover 态无回归。

## 遗留问题

- KPI 卡 hover 微抬属于浏览型组件的轻反馈，若用户觉得多余可去掉 `transform` 一行。
- `bk-table td` 的 hover 过渡在超长表格滚动时会有轻微重绘开销，实测无感知。
