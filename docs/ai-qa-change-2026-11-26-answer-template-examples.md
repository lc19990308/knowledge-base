# 变更记录：回答模板格式示例弹窗与下载导出（2026-11-26）

## 背景

回答模板页此前只提供空白编辑器，新用户不知道模板正文该按什么结构写、结构化与高级文本两种模式分别适合什么内容。需要在页面内直接给出两份「做好了的」示例，并且能下载保存。

## 实现过程

1. **示例内容抽成数据模块 `frontend/src/answerTemplateExamples.ts`**
   - 导出 `TemplateExample` 类型与 `TEMPLATE_EXAMPLES` 常量，每份包含 `mode`（`structured` / `advanced`）、`label`、`hint`、`title`、`description`、`fileName`、`body`。
   - 结构化示例《客服问答 · 规则分段模板》：按「回答要求 / 引用规则 / 可用资料 / 政策有效性 / 无依据处理 / 用户问题」分段，适合在结构化编辑器逐段调整。
   - 高级文本示例《知识库问答 · 约束提示词模板》：角色、任务、硬性约束、输出格式、自检五段，适合整体粘贴到高级文本编辑器精修。
   - 两份都只用系统支持的变量 `{question}`、`{references}`、`{validity}`，与 `TemplateBodyEditor` 的格式检查范围一致。
2. **页面入口与弹窗 `frontend/src/views/AnswerTemplatesView.vue`**
   - 页头新增「格式示例」按钮（原「＋ 新建模板」保留为主按钮）。
   - 弹窗按当前项目的浮层约定包在 `OverlayTransition` 内；顶部用既有 `FormRadioGroup` 单选卡片决定展开哪份示例（选项带 `hint` 短说明），同一时间只渲染选中的那一份：标题、适用场景说明、右上「下载 .txt」，正文分别用结构化渲染（`{变量}` 高亮）与等宽 `pre`（`pre-wrap`）展示。
   - 每次打开弹窗都重置回第一份示例；下载按钮只导出当前选中的那份，文件名与展示内容一致。
   - 复用既有分段函数：把原 `previewParagraphs` 里的分段逻辑抽成 `parsePreviewSegments()`，验证预览与示例共用。
   - 下载：`Blob` + `URL.createObjectURL` + 临时 `<a download>`，UTF-8 纯文本，文件名分别为 `回答模板示例-结构化.txt`、`回答模板示例-高级文本.txt`；用完立即 `revokeObjectURL`。DOM 访问统一走 `globalThis.document`，与 `ModalShell` 的写法一致。
   - 说明文案写明「下载后再粘贴到模板正文」，避免误解为直接创建模板记录。
3. **按钮语义色与复制**
   - 按项目既有按钮语义「只读操作（查看/预览/下载）用浅蓝 `bk-btn-info`」，「格式示例」与「下载 .txt」都改用 `bk-btn-info`；复制为普通次要操作，保持默认按钮，复制成功 / 失败时分别短暂切到 `bk-btn-success` / `bk-btn-danger`。
   - 「复制内容」用 `navigator.clipboard.writeText` 写入当前示例正文，2 秒后复位；失败（无权限、非安全上下文）显示「复制失败」并用 `role="status"` 播报。切换示例或重新打开弹窗会清掉上一次的复制反馈。
4. **展示细节**
   - `.tpl-preview p` 增加 `white-space: pre-wrap`，结构化示例与验证预览中的单换行不再被折叠成一行。
   - 示例正文不设内部滚动高度，只由弹窗自身滚动，避免嵌套滚动条。
   - 全部样式复用既有 token（`--color-surface`、`--color-border`、`--radius-md`、`--space-*`、`--font-mono` 等），未新增变量。

## 验证命令与结果

- `cd frontend && npx prettier --write src/views/AnswerTemplatesView.vue src/answerTemplateExamples.ts`：通过。
- `cd frontend && npx eslint src/views/AnswerTemplatesView.vue src/answerTemplateExamples.ts --max-warnings 0`：通过。
- `cd frontend && npm run typecheck`：通过，0 错误。
- 浏览器实测（Chrome / Playwright，`http://localhost:5173/answer-templates`，admin 登录）：
  - 点「格式示例」弹出窗口：radio 两项「结构化（按规则区块分段）」「高级文本（完整提示词约束）」，默认选中「结构化」，只渲染 1 张示例卡片，标题《客服问答 · 规则分段模板》，高亮变量为 `{references}`、`{validity}`、`{question}`。
  - 点选「高级文本」后选中态切换、卡片换成《知识库问答 · 约束提示词模板》，正文为 22 行 `pre`（`white-space: pre-wrap`）。
  - 键盘操作：聚焦 radio 后按方向键即可切换，内容同步更新。
  - 「下载 .txt」跟随当前选择：选中高级文本时下载 `回答模板示例-高级文本.txt`（988 字节，首行 `# 角色`）；结构化时下载 `回答模板示例-结构化.txt`（1024 字节），内容与展示一致且含全部三个变量。
  - 按钮语义色：「格式示例」「下载 .txt」均带 `bk-btn-info`，计算背景 `rgb(238, 242, 255)`；复制按钮默认中性色。
  - 复制：点击后按钮变为「已复制」、`role="status"` 输出「模板内容已复制到剪贴板」，读回剪贴板 392 字符且含三个变量；切换示例后按钮复位为「复制内容」，再次复制得到高级文本首行 `# 角色`。
  - 390×844 窄屏：radio 组转单列（304px），弹窗落在视口内、卡片与下载按钮正常换行；整页 `scrollWidth` 406 为既有顶栏搜索溢出，与本次无关。
  - 截图：`docs/e2e-results/answer-template-examples-2026-11-26.png`、`-advanced-2026-11-26.png`、`-narrow-2026-11-26.png`。

## 遗留问题

- 示例目前只支持下载，未提供「一键填入编辑器」；如需可在后续把示例内容直接带入新建模板弹窗。
- 窄屏顶栏搜索溢出（既有问题）仍未处理，见上一条变更记录。
