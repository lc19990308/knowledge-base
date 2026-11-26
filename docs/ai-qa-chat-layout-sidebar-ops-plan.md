# AI 对话页：消息左右分布 + 会话操作迁入侧栏（方案，待确认）

## 1. 现状与问题

浏览器实测（1440 宽，`/assistant`）与源码一致：

- **消息不分左右**：`AssistantView.vue` 里用户和 AI 都用 `.msg` 同一套布局（`.m-avatar` + `.m-body`），而 `.m-body { flex: 1 }`，气泡被拉满整行，视觉上两条消息同构，看不出谁在提问、谁在回答。
- **操作位置错**：`归档会话 / 删除会话` 在对话主区右上 `.chat-actions`，数据源是 `currentConversation`，所以必须先点开某条会话才能归档或删除它，无法在侧栏直接整理列表。

目标：

1. AI 回答靠左、用户提问靠右，形成可扫读的对话流。
2. 归档/恢复/删除会话的入口放到左侧会话侧栏，作用于**任意一条会话**，不要求先打开。
3. 交互可验收：鼠标、键盘、触摸、读屏、失败重试、焦点回落都要说清楚。

不在本次范围：后端接口与数据模型、其他页面、新增下拉/菜单组件、会话搜索与重命名。

## 2. 方案 A：消息左右分布

### 2.1 DOM 不变

保留现有顺序（头像 → 正文），只用 CSS 控制视觉方向。消息模板不动：

```html
<div class="msg msg-user">
  <span class="m-avatar">我</span>
  <div class="m-body">…气泡 + 时间…</div>
</div>
```

这样读屏顺序仍是「谁说的 → 说了什么」，`row-reverse` 只反转视觉，不反转语义。

### 2.2 样式改动（`.chat-layout` 作用域）

```css
.chat-layout {
  /* 对话区两处宽度约束，模块内共享 */
  --chat-user-max: min(78%, 640px);
  --chat-ai-max: min(100%, 920px);
}

/* 用户：整体右对齐，头像落到最右；行间距收紧，和下方回答成组 */
.msg-user {
  flex-direction: row-reverse;
  margin-bottom: var(--space-3);
}

.msg-user .m-body {
  flex: 0 1 auto;
  min-width: 0;
  max-width: var(--chat-user-max);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

/* AI：保持左侧，限制阅读宽度；引用列表和表格仍有余量 */
.msg-ai .m-body {
  flex: 1 1 auto;
  min-width: 0;
  max-width: var(--chat-ai-max);
}

.msg-user .m-time {
  text-align: end;
}

.m-bubble {
  overflow-wrap: anywhere;
}
```

窄屏补一条：

```css
@media (max-width: 1100px) {
  .chat-layout {
    --chat-user-max: 88%;
  }
}
```

### 2.3 细节与回归点

- `row-reverse` 下 DOM 顺序不变，Tab 顺序、读屏、文案都不用改。
- `.m-body` 的 `align-items: flex-end` 让用户气泡按内容收缩、时间贴右；气泡内文本仍 `text-align: start`。
- 气泡内的代码块、表格已有 `.message-code` / `.message-table { overflow-x: auto }` 与 `overflow-wrap: anywhere`，窄气泡下横向滚动，不会撑破布局。
- 头像和气泡的 `gap`、`m-time` 的 `margin-top` 复用现有 token，不新增间距值。
- 滚动逻辑（`scrollToLatest`、轮询完成后的 `followLatest`）只依赖 `.chat-scroll`，与左右对齐无关，不改。
- 可选视觉增强（不默认做）：用户气泡右下角、AI 气泡左下角各收一个小圆角，强化“对话方向”。需要时再加，避免一次改太多。

## 3. 方案 B：归档/删除迁入会话侧栏

### 3.1 交互模型

每条会话行右侧放两个图标按钮：归档/恢复、删除。删除仍走确认弹窗（危险且不可恢复）；归档/恢复即时生效、可逆，不弹窗。

为什么放在行内而不是侧栏底部：

- 操作对象是“某条会话”，不是“当前会话”；
- 行内入口让对象和动作在一起，不用先打开会话再回侧栏；
- 保留 `.chat-actions` 会形成两个入口，`aria-label` 重复，回归脚本容易点错。

不引入下拉菜单：项目没有现成 Dropdown，菜单需要定位、外部点击、Esc、方向键、焦点回退一整套逻辑；两个图标按钮零新状态、可直接键盘到达，先用这个。

### 3.2 DOM 结构（`conv-list` 内）

现有 `<button class="conv-item">` 不能内嵌按钮，改为「行容器 + 会话按钮 + 操作区」三个兄弟节点：

```html
<div class="conv-list" ref="convList">
  <!-- 草稿行：没有真实 ID，不显示操作 -->
  <div v-if="draftConversation && !archiveView" class="conv-row on">
    <button class="conv-item" type="button" :disabled="busy" @click="startNewConversation">
      <span class="c-title">{{ draftConversation.title }}</span>
      <span class="c-time">{{ formatDateTime(draftConversation.createdAt) }}</span>
    </button>
  </div>

  <div
    v-for="(conversation, index) in conversations"
    :key="conversation.conversationId"
    class="conv-row"
    :class="{ on: isCurrent(conversation) }"
  >
    <button
      class="conv-item"
      type="button"
      :aria-pressed="isCurrent(conversation)"
      :disabled="busy"
      @click="selectConversation(conversation.conversationId)"
    >
      <span class="c-title">{{ conversationTitle(conversation) }}</span>
      <span class="c-time">{{ formatDateTime(conversation.createdAt) }}</span>
    </button>

    <div class="conv-ops" @click.stop>
      <button
        class="conv-op"
        type="button"
        :aria-busy="managingId === conversation.conversationId"
        :aria-label="
          conversation.archived
            ? `恢复会话：${conversationTitle(conversation)}`
            : `归档会话：${conversationTitle(conversation)}`
        "
        :title="conversation.archived ? '恢复会话' : '归档会话'"
        @click="toggleArchived(conversation)"
      >
        <ArchiveRestore v-if="conversation.archived" aria-hidden="true" />
        <Archive v-else aria-hidden="true" />
      </button>
      <button
        class="conv-op danger"
        type="button"
        :aria-label="`删除会话：${conversationTitle(conversation)}`"
        title="删除会话"
        @click="requestDelete(conversation, index)"
      >
        <Trash2 aria-hidden="true" />
      </button>
    </div>
  </div>
</div>
```

要点：

- 操作按钮与会话按钮是**兄弟节点**，不嵌套 `<button>`，HTML 合法；点击操作不会触发行选择，不需要靠冒泡拦截。
- `.conv-ops` 上的 `@click.stop` 是防御性写法：即使将来给行容器加点击，也不会穿透到选择逻辑。
- 图标来自已安装的 `@lucide/vue`（`Archive` / `ArchiveRestore` / `Trash2` 均已确认导出）。
- `aria-label` 带会话标题，读屏和自动化定位都能区分同名按钮；`title` 提供鼠标提示（纯图标按钮必须有文字说明）。
- 归档/恢复共用同一个按钮，图标和标签随 `conversation.archived` 切换。

### 3.3 事件绑定流程

**选择会话**（不变）

```text
click .conv-item
  → selectConversation(id)
  → router.replace({ query: { ...route.query, conversation: id } })
  → watch(conversationId)
  → loadHistory(id)：abort 上一次读取与轮询 → listMessages → 恢复轮询
```

**归档 / 恢复**

```text
click .conv-op（归档/恢复）
  → toggleArchived(conversation)
  → if (managingId) return                  // 防重复
  → managingId = id; convError = null
  → setConversationArchived(id, !conversation.archived)
  → 成功：
      - 若 id 是当前打开的会话：openedConversation = updated，canSend 随 archived 变化
      - reloadConversations()
      - 当前页被清空且 page > 1 → resetConversations()
      - announceText = archived ? '会话已归档…' : '会话已恢复'
      - 焦点兜底（见 3.5）
  → 失败：convError = describe(failure)，列表不变，按钮可重试
  → finally: managingId = null
```

**删除**

```text
click .conv-op.danger
  → requestDelete(conversation, index)      // 只记录目标，不发请求
  → pendingDelete = conversation; pendingIndex = index; deleteError = null

ModalShell 挂载（initial-focus="first-action"）
  → 记录 previousFocus（行内删除按钮）→ 聚焦「取消」
  → 取消 / Esc / 遮罩：pendingDelete = null，ModalShell 卸载时还焦触发按钮

点击「确认删除」
  → confirmDelete()
  → managingId = id
  → deleteConversation(id)
  → 成功：
      - pendingDelete = null（弹窗卸载）
      - openedConversation 清理
      - reloadConversations()；本页空且 page > 1 → resetConversations()
      - 若删的是当前会话：selectConversation(同位置下一条 ?? 第一条 ?? '')
      - nextTick 后焦点兜底
  → 失败：deleteError 留在弹窗内，可原地重试
  → 404：视为已删除，刷新列表并关闭弹窗（多端/重复删除幂等）
```

### 3.4 状态与函数改造

```ts
const pendingDelete = ref<Conversation | null>(null)
let pendingIndex = 0
const managingId = ref<string | null>(null)
const convError = ref<string | null>(null)
const convList = ref<HTMLElement | null>(null)

/** 页面级阻塞只包含加载与提问；操作会话不再冻结输入框。 */
const busy = computed(() => loading.value || asking.value)
const cannotSend = computed(() => busy.value || managingId.value !== null)
const canSend = computed(
  () =>
    !cannotSend.value &&
    !readingHistory.value &&
    !scopeEmpty.value &&
    !!activeAssistant.value &&
    (conversationId.value === '' ||
      currentConversation.value?.archived === false),
)
```

- 删除 `managing`、`confirmDelete` 两个旧状态；模板改用 `pendingDelete`。
- 新增 `conversationTitle(conversation)`：标题为空时回退 `会话 ${id.slice(0, 8)}`，模板和 `aria-label` 共用。
- 新增 `isCurrent(conversation)`：`conversationId.value === conversation.conversationId`。
- `manageConversation` 拆成 `toggleArchived(target)`、`requestDelete(target, index)`、`confirmDelete()` 三个函数，各自单一职责。
- `busy` 不再包含 `managingId`：归档/删除期间会话列表可继续浏览和切换，避免“操作一条会话时整个页面冻结”。`canSend` 单独排除操作中状态，防止提问与归档并发。
- `watch(conversationId)` 里把 `confirmDelete = false` 改为 `pendingDelete = null`，并清 `convError`。

### 3.5 焦点管理（最容易出错的部分）

1. **归档/恢复**：请求成功后该行被移除，原按钮消失，焦点会掉到 `body`。处理：
   - 操作前记录行索引 `index`；
   - `nextTick` 后若 `document.activeElement === document.body`（说明焦点确实丢了，用户没有主动点别处），聚焦刷新后列表第 `min(index, conversations.length - 1)` 行的 `.conv-item`；
   - 列表为空时聚焦「＋ 新提问」按钮（`newQuestionButton` 已存在）。
   - 判断“用户是否已主动移走焦点”，避免抢焦点。
2. **删除弹窗取消**：ModalShell 已实现挂载时记录 `previousFocus`、卸载时还焦。触发按钮仍在，自动恢复。
3. **删除确认成功**：触发按钮随行消失，ModalShell 还焦时会因 `isConnected === false` 跳过；由第 1 条的兜底接管。
4. **操作失败**：行未移除、按钮未禁用（靠 `managingId` 防重），焦点保持原位，错误用 `convError`（`role="alert"`）播报。
5. 操作按钮**不要加 `disabled`**：`disabled` 会让按钮失焦，直接制造上面的焦点丢失；用 `aria-busy` + 函数入口判重即可。

### 3.6 错误、分页与竞态

- 归档/恢复失败提示放在侧栏顶部 `conv-error`（`role="alert"`），比放到对话区顶部更靠近操作点，也不会挤动聊天记录。
- 删除失败保持在弹窗内（`deleteError`），保留现有“失败不关弹窗、可重试”的行为。
- 分页：删除或归档后若当前页为空且 `page > 1`，回退到第 1 页（现有删除逻辑，归档也要补上）。
- 竞态：请求返回后先判断目标是否仍是当前 URL 的会话（`conversationId.value === target.id`），再决定是否切换；请求期间用户已切走的会话不被二次切走。
- 卸载：沿用现有 `isDisposed()` 检查，写请求返回后不再改状态。
- 提问进行中（`asking = true`）：`busy` 为真，会话按钮禁用，操作按钮的点击会被 `managingId`/`busy` 判断拦住。

### 3.7 侧栏样式

```css
.conv-row {
  position: relative;
}

.conv-item {
  /* 现有 10px var(--space-3)；右侧给操作区留位，避免标题被盖住 */
  padding-inline-end: 62px;
}

.conv-item .c-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-ops {
  position: absolute;
  inset-block-start: 4px;
  inset-inline-end: 4px;
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--radius-sm);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--motion-fast) var(--ease-standard);
}

.conv-row:hover .conv-ops,
.conv-row:focus-within .conv-ops,
.conv-row.on .conv-ops {
  opacity: 1;
  pointer-events: auto;
}

/* 用行自身的底色盖住标题尾部，避免透字 */
.conv-row:hover .conv-ops {
  background: var(--color-divider);
}

.conv-row.on .conv-ops {
  background: var(--color-surface-selected);
}

.conv-op {
  display: inline-grid;
  place-items: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.conv-op:hover {
  background: var(--color-surface);
  color: var(--color-text);
}

.conv-op.danger:hover {
  color: var(--color-danger);
}

.conv-op svg {
  width: 14px;
  height: 14px;
}

.conv-op[aria-busy='true'] {
  opacity: 0.45;
  cursor: progress;
}

/* 触摸设备没有 hover：操作常显 */
@media (hover: none) {
  .conv-ops {
    opacity: 1;
    pointer-events: auto;
  }
}
```

- 隐藏时 `opacity: 0` 但仍在 Tab 序列；`focus-within` 会让整行操作显形，键盘可用。
- `pointer-events: none` 让隐藏状态下的点击穿透到会话按钮，不会误触。
- 颜色全部复用现有 token，不新增色值；只新增 `--chat-user-max` / `--chat-ai-max` 两个模块内宽度变量，说明理由：用户与 AI 两处共用同一组宽度约束，未来一起调整。
- 选中行（`.conv-row.on`）常显操作，避免“当前会话归档后找不到入口”。

## 4. 改动清单

| 文件 | 改动 |
| --- | --- |
| `frontend/src/views/AssistantView.vue` | 模板：`conv-list` 行结构、操作区、移除 `.chat-actions`、删除弹窗改用 `pendingDelete`；脚本：新增 `pendingDelete`/`managingId`/`convError`/`convList`、拆分函数、`busy` 调整、焦点兜底；样式：消息左右布局、行内操作区、移除 `.chat-actions` 样式 |
| `.e2e-live-work/manage.mjs` | 本地 e2e 脚本里按名点「归档会话 / 删除会话」的定位改为行内按钮（现在按钮名带会话标题，且每行一个） |
| `docs/ai-qa-ui-ux-review.md` | 可选：补一条本轮验收记录 |

不改后端：`PUT /api/v1/conversations/{id}/archive`、`DELETE /api/v1/conversations/{id}` 已存在且行为不变。

## 5. 实施顺序

1. 先做消息左右布局（纯 CSS，无逻辑风险），`typecheck` + 浏览器确认三种宽度。
2. 再改侧栏 DOM 与状态（一次提交内完成，避免模板和脚本半改状态）。
3. 补焦点兜底与失败提示。
4. 同步 e2e 定位。
5. 跑门禁与浏览器回归。

## 6. 验收

命令：

```bash
cd frontend
npm run typecheck
npm run lint
npm run build
```

浏览器（1440 / 1100 / 390 三档）：

1. 用户消息靠右、头像在右、时间右对齐；AI 消息靠左；两条消息不再等宽。
2. 长文本、代码块、表格、引用列表在窄屏不溢出、不遮挡。
3. 鼠标悬停会话行出现两个操作按钮；点击归档后该行从「会话」消失，切到「已归档」可见；恢复后回到「会话」。
4. 删除：弹窗显示目标会话标题；取消/Esc 关闭且焦点回到原按钮；确认后列表更新，删的是当前会话时自动切到同位置下一条或新会话态。
5. 键盘：Tab 可达行内操作按钮，Enter 触发；删除弹窗 Esc 关闭后焦点不丢；删除当前会话后焦点落到相邻会话或「＋ 新提问」。
6. 触摸（devtools 模拟 `hover: none`）：操作按钮常显且可点。
7. 失败路径：断网或把接口改成 500，确认 `convError` / 弹窗内错误可见，状态不脏。
8. 归档/删除当前正在查看的会话：归档后输入框替换为「此会话已归档…」提示；删除后自动离开该会话。

通过标准：以上 8 条全部复现；`typecheck`、`lint`、`build` 全绿；无控制台报错。

## 7. 风险与回滚

- **主要风险**：移走主区按钮后，依赖按钮名的自动化脚本会失效（已列入改动清单）。
- 消息布局是纯 CSS，回滚只需还原样式段。
- 侧栏结构改动集中在单个视图组件，无共享组件影响；后端无改动，数据可随时恢复。
- 若触摸端操作常显导致列表过挤，可退化为“点击行尾更多按钮展开”，届时再评估引入菜单。

## 8. 待确认

1. 归档当前打开的会话后：**留在原会话并显示“已归档”提示**（推荐，不打断阅读），还是自动切到「已归档」分类？
2. 操作按钮：**hover / focus 显示 + 触摸常显**（推荐），还是所有设备常显（可发现性更好，但列表更吵）？

## 9. 实施记录（2026-11-24）

两项待确认按推荐方案执行：归档当前会话后留在原处并显示提示；操作按钮 hover/focus 显示、触摸常显。

已落地：

- `frontend/src/views/AssistantView.vue`
  - 消息布局：`.chat-layout` 新增 `--chat-user-max` / `--chat-ai-max`；`.msg-user` 改 `row-reverse`，`.m-body` 收缩右对齐，时间贴右；AI 侧限宽 920px。
  - 侧栏：`.conv-row` + `.conv-item` + `.conv-ops` 三兄弟节点，归档/恢复、删除改用 `@lucide/vue` 图标按钮，`aria-label` 带会话标题；移除主区 `.chat-actions`。
  - 状态：`managing`/`confirmDelete` 拆为 `managingId`、`pendingDelete`、`convError`、`convList`；`busy` 只含加载与提问，`canSend` 额外排除操作中。
  - 函数：`toggleArchived`、`requestDelete`、`confirmDeleteConversation`、`settleConversationPage`、`focusAfterRowRemoved`、`conversationTitle`、`isCurrent`。
  - 删除弹窗显示目标标题；删除 404 按成功处理；失败留在弹窗内重试。
  - 焦点：行被移除后若焦点掉到 `body`，落到同位置会话或「＋ 新提问」。
- `.e2e-live-work/manage.mjs`：会话生命周期用例改为行内按钮，并按「会话 → 归档 → 已归档 → 恢复 → 会话 → 删除」的真实路径操作。

验证：

- `npm run typecheck`、`npm run lint`、`npm run build` 均通过。
- 1440px 实测：用户消息右对齐、头像在右、时间右对齐，AI 消息左对齐；侧栏选中行常显操作按钮。
- 归档：点击行内归档 → 该行从「会话」消失、主区显示“此会话已归档…”；切「已归档」可见、按钮变“恢复会话”；恢复后回到「会话」。
- 删除：弹窗显示目标标题与危险按钮；Esc 关闭后焦点回到行内删除按钮；确认删除后列表刷新、URL 自动切到相邻会话。
- 390px（触摸模拟）：操作按钮常显，用户气泡靠右且无水平溢出。

并发说明：实施期间该文件同时被另一条工作线修改（新增助手切换与 `SpaceScopeSummary`）。本次改动已与之共存并通过门禁；如对方继续写同一文件，需重新核对 `.conv-row` 结构与状态名。

