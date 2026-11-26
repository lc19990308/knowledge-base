# 变更记录：回答引用列表支持收起 / 展开（2026-11-26）

## 背景

对话页每条 AI 回答末尾的「引用文档（N）」列表此前固定展开，引用较多时占用纵向空间、挤压后续对话。用户要求该区域支持收起与展开。

## 实现过程

1. **状态**（`frontend/src/views/AssistantView.vue`）
   - 新增 `collapsedCitations: ref<Set<string>>`，只记录被收起的段落 id；默认不记录即展开，保持原有默认视图。
   - `toggleCitationBlock(paragraphId)` 复制 Set 后增删并整体替换，触发 Vue 更新；每条段落独立记忆，互不影响。
2. **模板**
   - 「引用文档（N）」标题改为原生 `<button class="cite-toggle">`，带 `aria-expanded`、`aria-controls`，箭头用 lucide `ChevronDown`。
   - 引用条目外层包 `div`，`v-show` 控制显隐，`id` 与 `aria-controls` 对应，读屏可关联。
   - 收起后标题与计数仍可见，点击任一处整行切换；引用按钮的点击行为不变。
3. **样式**
   - `.cite-toggle` 复用现有 token：`--text-xs`、`--space-2`、`--motion-fast`、`--ease-standard`、`--color-text` / `--color-text-muted`，聚焦轮廓沿用 `--color-action`。
   - `.cite-chevron.is-collapsed` 旋转 -90°，`prefers-reduced-motion: reduce` 下关闭过渡。
4. **测试**（`frontend/e2e/assistant.mjs` 流式用例）
   - 终态回答出现后断言：默认 `aria-expanded=true`、引用项可见；点击后变 `false` 且引用项不可见；再点击恢复可见。

## 验证命令与结果

- `npx prettier --write src/views/AssistantView.vue e2e/assistant.mjs`：已格式化。
- `npm run typecheck`（vue-tsc --noEmit）：通过，0 错误。
- `npm run lint`（eslint --max-warnings 0）：通过，无告警。
- 浏览器实测（http://localhost:5173/assistant，admin 登录，真实会话「宁波日常每人每晚的住宿限额是多少」）：
  - 默认展开，显示 3 条引用，「引用文档（3）」右侧箭头朝下。
  - 点击标题后列表隐藏、箭头朝右；`aria-expanded` 由 `true` 变 `false`。
  - 再次点击恢复 3 条引用；DOM 校验 `aria-controls` 指向的列表 id 存在且首条引用 `offsetParent !== null`。
  - 截图：收起 / 展开两态均无布局跳动、无横向溢出。

## 遗留问题

- 折叠状态只存在当前页面内存中，刷新或切换会话后恢复默认展开；未做本地持久化（按需求未要求）。
