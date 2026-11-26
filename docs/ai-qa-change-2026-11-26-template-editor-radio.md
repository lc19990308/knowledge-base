# 变更记录：回答模板编辑器编辑模式 radio 与正文内边距（2026-11-26）

## 背景

回答模板弹窗的「模板正文」编辑器有两个问题：

1. 「结构化 / 高级文本」模式切换是纵向堆叠的小按钮，视觉上不像可选项，用户反馈希望换成「好看、符合项目主题 token 的 radio」。
2. 结构化与高级文本两个编辑器的正文紧贴边框，缺少内边距。

## 实现过程

1. **新增通用单选卡片组 `frontend/src/components/FormRadioGroup.vue`**
   - 原生 `radio` 保留语义与键盘操作（方向键切换、空格选中），视觉上隐藏；外层用 `label` 包成卡片，点击整卡即选中。
   - props：`modelValue`、`options`（`label` / `value` / 可选 `hint`）、`label`、`disabled`；`generic="T extends string"` 让父组件的联合类型（`EditorMode`）保持收窄。
   - `useId()` 生成组内唯一 `name`，同页多组单选不会串选。
   - 选中态只改数据，不直接改 props；父组件通过 `update:modelValue` 决定是否切换。
   - 样式写在 `frontend/src/styles/components.css` 的 `.bk-radio-*`，全部复用既有 token：`--color-border`、`--color-surface`、`--color-surface-selected`、`--color-action`、`--color-action-text`、`--radius-control`、`--space-*`、`--motion-fast`、`--ease-standard`、`--shadow-focus`、`--form-*`；未新增变量。选中文字（`--color-action` 压 `--color-surface-selected`）实测对比度约 4.68:1。
   - 焦点环用 `:has(.bk-radio-input:focus-visible)` 落在整张卡片上；`prefers-reduced-motion` 下关闭过渡。
2. **回答模板编辑器替换模式切换**
   - `TemplateBodyEditor.vue` 用 `<FormRadioGroup :model-value="mode" :options="EDITOR_MODES" … @update:model-value="switchMode" />` 取代原 `.bk-segmented` 按钮组；保留原 `switchMode` 负责两种编辑格式的互转，避免 v-model 直改跳过同步。
   - 两个选项带用途说明：「结构化 · 按段落编排，可插入规则区块」「高级文本 · 直接编辑模板原文，支持变量补全」。
3. **编辑器正文内边距**
   - 结构化：`.structured-editor .tiptap { padding: var(--space-4) }`。
   - 高级文本：新增 `.template-code-editor .cm-content { padding: var(--space-4) }`。
   - 同时修正了该组件 `<style>` 为非 scoped 却使用 `:deep(...)` 的既有问题（浏览器会丢弃整条规则，导致结构化编辑器此前 padding / 段间距 / 占位符样式实际都没生效），改为普通后代选择器。
4. **ESLint 白名单**
   - `frontend/eslint.config.mjs` 的 `vue/no-restricted-html-elements` 白名单加入 `FormRadioGroup.vue`，提示文案同步改为「请使用 FormInput、FormSwitch、FormRadioGroup 或 FormFileInput」。

## 验证命令与结果

- `cd frontend && npm run typecheck`：通过，0 错误。
- `cd frontend && npx eslint src/components/TemplateBodyEditor.vue src/components/FormRadioGroup.vue eslint.config.mjs --max-warnings 0`：通过。
- 浏览器实测（Chrome / Playwright，本地 dev `http://localhost:5173/answer-templates`，admin 登录后打开「编辑：冒烟模板」）：
  - radio 数量 2，文案「结构化 / 高级文本」，说明文案正确；默认选中「结构化」，点击第二项后 `is-active` 切到「高级文本」。
  - 结构化 `.tiptap` 计算样式 `padding: 16px`；高级文本 `.cm-content` 计算样式 `padding: 16px`。
  - 截图：`docs/e2e-results/answer-template-editor-radio-structured-2026-11-26.png`、`docs/e2e-results/answer-template-editor-radio-advanced-2026-11-26.png`。
- 深色主题实测（`localStorage['kb.theme']='dark'` 后打开同一弹窗）：`data-theme="dark"`，选中卡片背景 `rgb(26, 43, 69)`（`--color-surface-selected`）、描边 `rgb(59, 130, 246)`（`--color-action`）、标题同为主色，正文 padding 仍为 16px；radio 与编辑器均无亮色残留。截图 `docs/e2e-results/answer-template-editor-radio-dark-2026-11-26.png`。
- 窄屏实测（390×844）：radio 组自动变为单列（`grid-template-columns` 实测 304px），弹窗完整落在视口内，正文未溢出。截图 `docs/e2e-results/answer-template-editor-radio-narrow-2026-11-26.png`。
- 顺带移除 CodeMirror 上重复的内联 `minHeight: '240px'`（容器 CSS 已有同值），减少审计提示的重复字面值。

## 遗留问题

- **窄屏顶栏搜索溢出（既有问题，不在本次范围）**：viewport 390px 时整页 `scrollWidth` 为 406px，定位到 `App.vue` 的 `.topbar-search` 提交按钮右边缘到 405.9px；列表页在打开弹窗前就已存在，与回答模板编辑器无关，未修改。
- 仓库内临时截图脚本（`frontend/e2e/tmp-*.mjs`）在本次处理前已被清理，整仓 `npm run lint` 现已通过。
