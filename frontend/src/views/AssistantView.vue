<script setup lang="ts">
import { FORM_LIMITS } from '../formValidation'
import AssistantMessageText from '../components/AssistantMessageText.vue'
import CitationPreview from '../components/CitationPreview.vue'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import FormTextarea from '../components/FormTextarea.vue'
import SelectMenu from '../components/SelectMenu.vue'
import {
  Archive,
  ArchiveRestore,
  ChevronDown,
  PanelLeftClose,
  PanelLeftOpen,
  Trash2,
} from '@lucide/vue'
import { usePagedList } from '../pagination'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AnswerPollingTimeout,
  ask,
  deleteConversation,
  getConversation,
  getQuestion,
  listAssistants,
  listConversations,
  listMessages,
  listModelOptions,
  listSpaces,
  setConversationArchived,
  startQuestion,
  waitForAnswer,
  type Assistant,
  type Conversation,
  type Message,
  type ModelOption,
  type NewQuestionAccepted,
  type Paragraph,
  type Question,
  type QuestionStatus,
  type Space,
} from '../api/knowledge'
import {
  answerFlag,
  describeError,
  formatDateTime,
  statusLabel,
  type AnswerFlag,
} from '../api/display'
import { isApiError, newIdempotencyKey } from '../api/http'
import { streamAnswer } from '../api/stream'
import { createTypewriter } from '../typewriter'
import { canRetryWrite } from '../api/polling'
import { session } from '../auth'

interface Exchange {
  questionId: string
  text: string
  createdAt: string
  paragraphs: Paragraph[]
  status: QuestionStatus
  failureCode?: string | undefined
  lookup: 'idle' | 'polling' | 'streaming' | 'paused'
  lookupMessage?: string | undefined
  /** 流式生成中的可见文本；结束后由 paragraphs 接管。 */
  streamedText?: string | undefined
}

const answerToneClass: Record<AnswerFlag['tone'], string> = {
  masked: 'af-expired',
  expired: 'af-expired',
  uncertain: 'af-unknown',
  conflict: 'af-conflict',
  none: 'af-none',
}

const route = useRoute()
const router = useRouter()
const assistants = ref<Assistant[]>([])
const spaces = ref<Space[]>([])
const exchanges = ref<Exchange[]>([])
/** 草稿按会话分桶；未建立会话时共用新会话草稿，避免串到别的会话。 */
const drafts = ref<Record<string, string>>({})
const draftKey = computed(() => conversationId.value || 'new')
const question = computed({
  get: () => drafts.value[draftKey.value] ?? '',
  set: (value: string) => {
    drafts.value = { ...drafts.value, [draftKey.value]: value }
  },
})

/**
 * 顶栏快捷输入把整段文字带到这里：写进新会话草稿，不自动发送。
 * 返回 true 表示这次进入确实带着可用的提问文本，调用方据此决定是否停在空白新会话。
 */
function consumeSearchQuery(): boolean {
  const raw = typeof route.query.q === 'string' ? route.query.q.trim() : ''
  const usable = raw !== '' && raw.length <= FORM_LIMITS.question
  if (usable) drafts.value = { ...drafts.value, new: raw }
  if (route.query.q !== undefined) {
    const rest = { ...route.query }
    delete rest.q
    void router.replace({ query: rest })
  }
  return usable
}

/** 首次加载时带着快捷提问进来：不要自动打开最近会话，否则预填的新会话草稿看不见。 */
const prefillPending = ref(false)
const selectedModel = ref('')
const models = ref<
  { status: 'loading' | 'failed' } | { status: 'ready'; items: ModelOption[] }
>({ status: 'loading' })
const modelOptions = computed(() => {
  const items = models.value.status === 'ready' ? models.value.items : []
  const defaultAlias = items.find((item) => item.isDefault)?.alias
  return [
    {
      value: '',
      label: defaultAlias ? `系统默认 · ${defaultAlias}` : '系统默认',
    },
    ...items.map((item) => ({ value: item.id, label: item.alias })),
  ]
})
const asking = ref(false)
const loading = ref(true)
const readingHistory = ref(false)
const archiveView = ref(false)
/** 本地占位会话：只在列表中显示，发出第一条消息后才创建真实会话。 */
const draftActive = ref(false)
const draftStartedAt = ref('')
/** 正在归档/删除的会话；操作期间只锁操作入口，不冻结提问。 */
const managingId = ref<string | null>(null)
/** 待确认删除的会话；非空即弹窗打开。 */
const pendingDelete = ref<Conversation | null>(null)
/** 删除目标在列表中的位置，刷新后用它决定焦点落点。 */
let pendingIndex = 0
const deleteError = ref<string | null>(null)
/** 侧栏内联提示，靠近操作点，避免错误藏在对话区顶部。 */
const convError = ref<string | null>(null)
const convList = ref<HTMLElement | null>(null)
/** 距列表底部这么近就算「滚到底」，提前一屏半屏开始取下一页，滚动更连贯。 */
const CONV_MORE_THRESHOLD = 120
const newQuestionButton = ref<HTMLButtonElement | null>(null)
const error = ref<string | null>(null)
const chatScroll = ref<HTMLElement | null>(null)
const citationPreview = ref<InstanceType<typeof CitationPreview> | null>(null)
const polls = new Map<string, AbortController>()
/** 每条问题至多一条增量流；切换会话或卸载时逐条中止。 */
const streams = new Map<string, AbortController>()
let historyRequest: AbortController | undefined
let historyLoad: Promise<void> | undefined
const lifecycle = new AbortController()
const isDisposed = () => lifecycle.signal.aborted
let pendingSend:
  | {
      text: string
      conversationId: string
      modelId: string | undefined
      key: string
    }
  | undefined
let pendingNew:
  | {
      text: string
      assistantId: string
      modelId: string | undefined
      key: string
    }
  | undefined

const conversationId = computed(() =>
  typeof route.query.conversation === 'string' ? route.query.conversation : '',
)
/**
 * 会话列表按「会话 / 已归档」在服务端分页，侧栏用滚动到底部加载下一页，不摆分页器；
 * 当前打开的会话单独保存，不在已加载范围时用会话详情接口补齐标题、归属助手和归档状态。
 */
const {
  items: conversations,
  total: conversationTotal,
  loading: conversationLoading,
  error: conversationError,
  hasMore: conversationsHasMore,
  loadMore: loadMoreConversations,
  reload: reloadConversations,
  reset: resetConversations,
} = usePagedList<Conversation>(
  (query, signal) =>
    listConversations({ ...query, archived: archiveView.value, signal }),
  {
    describeError: describe,
    append: true,
    // 分页之间新建会话会把列表整体后移一行，按会话 ID 去重，避免出现重复行。
    keyOf: (conversation) => conversation.conversationId,
  },
)
const openedConversation = ref<Conversation | null>(null)
const currentConversation = computed(
  () =>
    conversations.value.find(
      (item) => item.conversationId === conversationId.value,
    ) ??
    (openedConversation.value?.conversationId === conversationId.value
      ? openedConversation.value
      : null),
)

/** 当前会话可能不在本页：先用列表与已打开的记录，再退回会话详情接口。 */
async function resolveConversation(id: string): Promise<Conversation | null> {
  if (!id) return null
  const local =
    conversations.value.find((item) => item.conversationId === id) ??
    (openedConversation.value?.conversationId === id
      ? openedConversation.value
      : null)
  if (local) return local
  try {
    const row = await getConversation(id, lifecycle.signal)
    if (!isDisposed()) openedConversation.value = row
    return row
  } catch {
    return null
  }
}

/** 切到另一个分类时回到第一页并重新拉取该分类的会话。 */
watch(archiveView, () => {
  void resetConversations()
})

/** 用户在本页主动选中的助手；优先于地址参数和列表首项。 */
const selectedAssistantId = ref('')
const defaultAssistant = computed(
  () =>
    assistants.value.find(
      (item) =>
        item.assistantId ===
        (selectedAssistantId.value || route.query.assistant),
    ) ?? assistants.value[0],
)
const activeAssistant = computed(() => {
  const id = currentConversation.value?.assistantId
  return id
    ? assistants.value.find((item) => item.assistantId === id)
    : defaultAssistant.value
})
/** 新会话的占位条目：没有真实会话 ID，点击不会请求后端。 */
const draftConversation = computed<Conversation | null>(() =>
  draftActive.value
    ? {
        conversationId: 'draft',
        assistantId: activeAssistant.value?.assistantId ?? '',
        createdAt: draftStartedAt.value,
        archived: false,
        title: '新会话',
      }
    : null,
)
const availableSpaces = computed(() => {
  const bound = new Set(
    activeAssistant.value?.bindings.map((binding) => binding.spaceId) ?? [],
  )
  return spaces.value.filter(
    (space) =>
      bound.has(space.spaceId) && space.canRead && !!space.currentPublicationId,
  )
})
/** 只有助手尚未绑定可检索空间时，才禁止提问。 */
const scopeEmpty = computed(() => availableSpaces.value.length === 0)
/**
 * 会话列表用推挤式抽屉：展开时挤占 260px，收起时整体滑出且不留占位。
 * 开合状态按用户记在本机；窄屏改由遮罩覆盖，不位移对话区。
 */
const convOpen = ref(true)
/** 挂载完成前不启用过渡，避免刷新时先闪一帧展开态。 */
const convReady = ref(false)
const convPanel = ref<HTMLElement | null>(null)
const convToggleButton = ref<HTMLButtonElement | null>(null)
function convStorageKey(): string {
  return `aiqa.assistant.sidebar.${session().user?.userId ?? ''}`
}
function readConvOpen(): boolean {
  try {
    return (
      globalThis.window.localStorage.getItem(convStorageKey()) !== 'collapsed'
    )
  } catch {
    return true
  }
}
function persistConvOpen(): void {
  try {
    globalThis.window.localStorage.setItem(
      convStorageKey(),
      convOpen.value ? 'expanded' : 'collapsed',
    )
  } catch {
    // 仅影响下次打开时的初始状态。
  }
}
function closeConvPanel(): void {
  if (!convOpen.value) return
  const focusInside = !!convPanel.value?.contains(
    globalThis.document.activeElement,
  )
  convOpen.value = false
  persistConvOpen()
  if (focusInside) convToggleButton.value?.focus({ preventScroll: true })
}
function toggleConvPanel(): void {
  if (convOpen.value) closeConvPanel()
  else {
    convOpen.value = true
    persistConvOpen()
  }
}
/**
 * 每条回答段落的引用列表各自的折叠状态：默认展开，点击标题收起。
 * 只保存被收起的段落 id，问句重渲染或换会话时不会残留到其他段落。
 */
const collapsedCitations = ref<Set<string>>(new Set())

/** 切换指定段落的引用列表；替换整个 Set 以触发视图更新。 */
function toggleCitationBlock(paragraphId: string): void {
  const next = new Set(collapsedCitations.value)
  if (next.has(paragraphId)) next.delete(paragraphId)
  else next.add(paragraphId)
  collapsedCitations.value = next
}

/** 页面级阻塞只包含加载与提问；会话操作不冻结输入框，另由 managingId 防重。 */
const busy = computed(() => loading.value || asking.value)
/** 供读屏播报回答完成等状态，正文不重复播报。 */
const announceText = ref('')
const scopeMessage = computed(() => {
  if (!activeAssistant.value) {
    return '尚未配置可用助手。'
  }
  return ''
})
const canSend = computed(
  () =>
    !busy.value &&
    managingId.value === null &&
    !readingHistory.value &&
    !scopeEmpty.value &&
    !!activeAssistant.value &&
    (conversationId.value === '' ||
      currentConversation.value?.archived === false),
)

function describe(failure: unknown): string {
  if (isApiError(failure, 404, 'RESOURCE_NOT_FOUND')) {
    return '该会话或引用已不可用。'
  }
  return describeError(failure)
}

function isPending(exchange: Exchange): boolean {
  return exchange.status === 'QUEUED' || exchange.status === 'RUNNING'
}

function scrollToLatest(): void {
  const area = chatScroll.value
  if (area) area.scrollTop = area.scrollHeight
}

function rememberSelection(id: string): void {
  const userId = session().user?.userId
  if (!userId) return
  try {
    const key = `aiqa.assistant.lastConversation.${userId}`
    if (id) globalThis.window.localStorage.setItem(key, id)
    else globalThis.window.localStorage.removeItem(key)
  } catch {
    // The URL still restores the conversation when browser storage is unavailable.
  }
}

function rememberedSelection(): string | null {
  const userId = session().user?.userId
  if (!userId) return null
  try {
    return globalThis.window.localStorage.getItem(
      `aiqa.assistant.lastConversation.${userId}`,
    )
  } catch {
    return null
  }
}

function selectConversation(id: string): Promise<unknown> {
  rememberSelection(id)
  if (id === conversationId.value) return Promise.resolve()
  return router.replace({
    query: { ...route.query, conversation: id || undefined },
  })
}

async function loadModels(): Promise<void> {
  models.value = { status: 'loading' }
  try {
    const { items } = await listModelOptions(lifecycle.signal)
    if (!isDisposed()) models.value = { status: 'ready', items }
  } catch {
    if (!isDisposed()) models.value = { status: 'failed' }
  }
}

async function load(): Promise<void> {
  try {
    const [assistantList, spacePage] = await Promise.all([
      listAssistants({ signal: lifecycle.signal }),
      listSpaces({ signal: lifecycle.signal }),
    ])
    if (isDisposed()) return
    assistants.value = assistantList.items
    spaces.value = spacePage.items
    await reloadConversations()
    if (isDisposed()) return
    if (conversationId.value) {
      await loadHistory(conversationId.value)
      return
    }
    if (prefillPending.value) {
      prefillPending.value = false
      return
    }
    const preferred = route.query.assistant
    const lastId = rememberedSelection()
    if (lastId) {
      const remembered = await resolveConversation(lastId)
      if (
        remembered &&
        (!preferred || remembered.assistantId === preferred) &&
        !isDisposed()
      ) {
        archiveView.value = remembered.archived
        await selectConversation(remembered.conversationId)
        return
      }
    }
    const latest = conversations.value.find(
      (item) => !preferred || item.assistantId === preferred,
    )
    if (latest) await selectConversation(latest.conversationId)
  } catch (failure) {
    if (!isDisposed()) error.value = describe(failure)
  } finally {
    loading.value = false
  }
}

function toExchanges(messages: Message[]): Exchange[] {
  const byQuestion = new Map<string, Exchange>()
  for (const message of messages) {
    if (message.role === 'USER') {
      byQuestion.set(message.questionId, {
        questionId: message.questionId,
        text: message.text ?? '',
        createdAt: message.createdAt,
        paragraphs: [],
        status: 'QUEUED',
        lookup: 'idle',
      })
    }
  }
  for (const message of messages) {
    if (message.role !== 'ASSISTANT') continue
    const exchange = byQuestion.get(message.questionId)
    if (!exchange) continue
    exchange.paragraphs = message.paragraphs ?? []
    exchange.status =
      message.status ?? (exchange.paragraphs.length ? 'SUCCEEDED' : 'QUEUED')
    exchange.failureCode = message.failureCode ?? undefined
  }
  return [...byQuestion.values()]
}

function applyAnswer(exchange: Exchange, answer: Question): void {
  announceText.value =
    answer.status === 'FAILED' ? '回答失败，请重新提问。' : '回答已生成。'
  exchange.status = answer.status
  exchange.failureCode = answer.failureCode
  exchange.paragraphs = answer.paragraphs ?? []
  exchange.lookup = 'idle'
  exchange.streamedText = undefined
  // 首轮回答完成后后端可能按上下文补全标题，回读一次让侧栏即时更新。
  if (exchanges.value.length === 1 && answer.status === 'SUCCEEDED') {
    void refreshConversations()
  }
}

/** 标题由后端自动维护，静默回读当前分类的这一页；失败不影响当前会话展示。 */
async function refreshConversations(): Promise<void> {
  await reloadConversations()
}

/** 打字机光标：逐字显示时在末尾闪一个方块，提示还在生成。 */
const caretOn = ref(true)
let caretTimer: ReturnType<typeof globalThis.setInterval> | undefined

function startCaret(): void {
  if (caretTimer !== undefined) return
  caretOn.value = true
  caretTimer = globalThis.setInterval(() => {
    caretOn.value = !caretOn.value
  }, 600)
}

function stopCaret(): void {
  if (caretTimer === undefined) return
  globalThis.clearInterval(caretTimer)
  caretTimer = undefined
  caretOn.value = true
}

/** 逐字渲染时保持贴底；用户自己往上翻时不打扰。 */
function keepLatest(): void {
  const area = chatScroll.value
  if (area && area.scrollHeight - area.scrollTop - area.clientHeight < 120) {
    area.scrollTop = area.scrollHeight
  }
}

/** 待回答的问题优先走增量流；流不可用时在内部回退轮询。 */
function followAnswer(exchange: Exchange): void {
  if (streams.has(exchange.questionId) || polls.has(exchange.questionId)) return
  if (exchange.lookup === 'streaming' || exchange.lookup === 'polling') return
  void streamAnswerFlow(exchange)
}

/**
 * 订阅一条回答的增量流并逐字渲染。
 *
 * 正常结束时先取一次问题详情，等打字机把剩余文本显示完再换成正式段落，避免最后一下跳字；
 * 出错、超时或连接中断则回到原来的轮询，最终状态始终以详情接口为准。
 */
async function streamAnswerFlow(exchange: Exchange): Promise<void> {
  const controller = new AbortController()
  streams.set(exchange.questionId, controller)
  exchange.lookup = 'streaming'
  exchange.lookupMessage = undefined
  exchange.streamedText = ''
  startCaret()

  // 用对象包一层：多个内部函数都会改它，读的地方不会被当成常量。
  const state = { settled: false }
  let finalAnswer: Question | undefined

  // 用函数读写，避免 TypeScript 把对象属性当作常量 false 收敛掉。
  function isSettled(): boolean {
    return state.settled
  }

  function markSettled(): void {
    state.settled = true
  }

  const typewriter = createTypewriter(
    (text) => {
      exchange.streamedText = text
      keepLatest()
    },
    () => {
      applyFinal()
    },
  )

  /** 收尾：先断开增量流，再把正式段落换上来。 */
  function applyFinal(): void {
    if (isSettled() || !finalAnswer || isDisposed()) return
    markSettled()
    stopCaret()
    streamEnded()
    applyAnswer(exchange, finalAnswer)
  }

  /** 流已经不用再读了：中止连接并从集合里移除。 */
  function streamEnded(): void {
    controller.abort()
    streams.delete(exchange.questionId)
  }

  /** 增量流不可用时回到轮询，提示文案与原来的等待逻辑一致。 */
  function fallback(message?: string): void {
    if (isSettled()) return
    markSettled()
    stopCaret()
    typewriter.stop()
    streamEnded()
    exchange.streamedText = undefined
    exchange.lookup = 'idle'
    if (message) exchange.lookupMessage = message
    void pollAnswer(exchange)
  }

  /** 增量结束后取一次详情；等打字机追上再替换。 */
  async function loadFinal(): Promise<void> {
    if (isSettled()) return
    try {
      const answer = await getQuestion(exchange.questionId, controller.signal)
      if (isSettled() || controller.signal.aborted) return
      if (answer.status === 'QUEUED' || answer.status === 'RUNNING') {
        fallback()
        return
      }
      finalAnswer = answer
      typewriter.finish()
    } catch (failure) {
      if (isSettled() || controller.signal.aborted) return
      fallback(`暂时查不到回答。${describe(failure)}`)
    }
  }

  try {
    await streamAnswer(
      exchange.questionId,
      {
        onDelta: (text) => {
          typewriter.push(text)
        },
        onDone: () => {
          void loadFinal()
        },
        onFailure: () => {
          fallback()
        },
        onTimeout: () => {
          fallback()
        },
      },
      controller.signal,
    )
    // 代理或服务端提前收尾但没发 done：按同一路径读一次最终状态。
    if (!isSettled()) void loadFinal()
  } catch (failure) {
    if (controller.signal.aborted || isDisposed()) {
      typewriter.stop()
      stopCaret()
      return
    }
    fallback(describe(failure))
  }
}

async function pollAnswer(exchange: Exchange): Promise<void> {
  if (polls.has(exchange.questionId)) return
  const controller = new AbortController()
  polls.set(exchange.questionId, controller)
  exchange.lookup = 'polling'
  exchange.lookupMessage = undefined
  try {
    const answer = await waitForAnswer(exchange.questionId, {
      signal: controller.signal,
    })
    if (controller.signal.aborted) return
    const area = chatScroll.value
    const followLatest =
      area && area.scrollHeight - area.scrollTop - area.clientHeight < 64
    applyAnswer(exchange, answer)
    if (followLatest) void nextTick(scrollToLatest)
  } catch (failure) {
    if (controller.signal.aborted) return
    exchange.lookup = 'paused'
    exchange.lookupMessage =
      failure instanceof AnswerPollingTimeout
        ? '等待较久，回答仍可能在后台处理。'
        : `暂时查不到回答。${describe(failure)}`
  } finally {
    if (polls.get(exchange.questionId) === controller)
      polls.delete(exchange.questionId)
  }
}

function stopReads(): void {
  historyRequest?.abort()
  for (const controller of polls.values()) controller.abort()
  polls.clear()
  for (const controller of streams.values()) controller.abort()
  streams.clear()
  stopCaret()
}

async function loadHistory(id: string): Promise<void> {
  stopReads()
  exchanges.value = []
  readingHistory.value = false
  if (!id || isDisposed()) return
  error.value = null
  if (conversationId.value === id) {
    const row = await resolveConversation(id)
    if (isDisposed()) return
    if (row) archiveView.value = row.archived
  }
  const controller = new AbortController()
  historyRequest = controller
  readingHistory.value = true
  try {
    const page = await listMessages(id, controller.signal)
    if (controller.signal.aborted) return
    exchanges.value = toExchanges(page.items)
    for (const exchange of exchanges.value) {
      if (isPending(exchange)) followAnswer(exchange)
    }
    void nextTick(scrollToLatest)
  } catch (failure) {
    if (!controller.signal.aborted) error.value = describe(failure)
  } finally {
    if (historyRequest === controller) readingHistory.value = false
  }
}

/**
 * 新提问只切到本地占位会话，并在左侧列表插入一条「新会话」；
 * 真实会话记录由首个问题提交时由后端创建，避免留下空会话。
 */
async function startNewConversation(): Promise<void> {
  if (busy.value) return
  error.value = null
  announceText.value = ''
  archiveView.value = false
  if (!draftActive.value) {
    draftActive.value = true
    draftStartedAt.value = new Date().toISOString()
  }
  await selectConversation('')
}

/** 可切换的助手：后端默认列表已过滤「启用中 + 至少一个可读绑定」。 */
const assistantOptions = computed(() =>
  assistants.value.map((item) => ({
    value: item.assistantId,
    label: item.name,
    detail: `${String(item.bindings.length)} 个空间`,
  })),
)
const pendingAssistantId = ref<string | null>(null)
const pendingAssistant = computed(
  () =>
    assistants.value.find(
      (item) => item.assistantId === pendingAssistantId.value,
    ) ?? null,
)

/** 会话归属的助手不可变：已有消息时先确认，确认后开一个新会话。 */
function requestAssistant(assistantId: string): void {
  if (busy.value || assistantId === activeAssistant.value?.assistantId) return
  if (conversationId.value !== '' && exchanges.value.length > 0) {
    pendingAssistantId.value = assistantId
    return
  }
  void applyAssistant(assistantId)
}

async function applyAssistant(assistantId: string): Promise<void> {
  selectedAssistantId.value = assistantId
  pendingAssistantId.value = null
  error.value = null
  // 同步地址参数，刷新后仍停留在选中的助手。
  await router.replace({
    query: { ...route.query, assistant: assistantId, conversation: undefined },
  })
  await startNewConversation()
}

function cancelAssistantSwitch(): void {
  pendingAssistantId.value = null
}

/** 首个问题：后端在同一事务内建会话并返回会话信息。 */
async function askInNewConversation(
  text: string,
  modelId: string | undefined,
): Promise<void> {
  const assistant = activeAssistant.value
  if (!assistant) return
  if (
    pendingNew?.text !== text ||
    pendingNew.modelId !== modelId ||
    pendingNew.assistantId !== assistant.assistantId
  ) {
    pendingNew = {
      text,
      modelId,
      assistantId: assistant.assistantId,
      key: newIdempotencyKey(),
    }
  }
  let accepted: NewQuestionAccepted
  try {
    accepted = await startQuestion(text, {
      assistantId: assistant.assistantId,
      modelId,
      idempotencyKey: pendingNew.key,
    })
  } catch (failure) {
    if (!canRetryWrite(failure)) pendingNew = undefined
    throw failure
  }
  pendingNew = undefined
  if (isDisposed()) return
  draftActive.value = false
  question.value = ''
  openedConversation.value = accepted.conversation
  archiveView.value = false
  await resetConversations()
  await selectConversation(accepted.conversation.conversationId)
  await nextTick()
  await historyLoad
  void nextTick(scrollToLatest)
}

/** 输入法确认候选词时的 Enter 不能触发送出。 */
function onEnter(event: KeyboardEvent): void {
  if (event.isComposing) return
  event.preventDefault()
  void submit()
}

async function submit(): Promise<void> {
  const text = question.value.trim()
  if (!canSend.value) return
  if (!text || text.length > FORM_LIMITS.question) {
    error.value = '请输入 1–2000 个字符的问题。'
    return
  }
  const modelId = selectedModel.value || undefined
  asking.value = true
  error.value = null
  try {
    const id = conversationId.value
    if (!id) {
      await askInNewConversation(text, modelId)
      return
    }
    await historyLoad
    if (isDisposed() || id !== conversationId.value) return
    if (
      pendingSend?.text !== text ||
      pendingSend.conversationId !== id ||
      pendingSend.modelId !== modelId
    ) {
      pendingSend = {
        text,
        conversationId: id,
        modelId,
        key: newIdempotencyKey(),
      }
    }
    const accepted = await ask(id, text, undefined, pendingSend.key, modelId)
    pendingSend = undefined
    if (isDisposed() || id !== conversationId.value) return
    question.value = ''
    let exchange = exchanges.value.find(
      (item) => item.questionId === accepted.questionId,
    )
    if (!exchange) {
      exchanges.value.push({
        questionId: accepted.questionId,
        text,
        createdAt: new Date().toISOString(),
        paragraphs: [],
        status: accepted.status,
        lookup: 'idle',
      })
      exchange = exchanges.value.at(-1)
    }
    if (exchange && isPending(exchange)) followAnswer(exchange)
    if (currentConversation.value) {
      openedConversation.value = currentConversation.value
      await resetConversations()
    }
    void nextTick(scrollToLatest)
  } catch (failure) {
    if (!canRetryWrite(failure)) pendingSend = undefined
    if (!isDisposed()) error.value = describe(failure)
  } finally {
    asking.value = false
  }
}

/** 会话标题：为空时回退到 ID 前缀，列表、弹窗和 aria-label 共用。 */
function conversationTitle(conversation: Conversation): string {
  return conversation.title || `会话 ${conversation.conversationId.slice(0, 8)}`
}

function isCurrent(conversation: Conversation): boolean {
  return conversationId.value === conversation.conversationId
}

const pendingDeleteTitle = computed(() =>
  pendingDelete.value ? conversationTitle(pendingDelete.value) : '',
)

/**
 * 操作完成后目标行会消失，焦点可能掉到 body；此时交给同位置的会话，
 * 列表空了交给「新提问」。用户已主动移走焦点时不抢。
 */
async function focusAfterRowRemoved(index: number): Promise<void> {
  await nextTick()
  const active = globalThis.document.activeElement
  if (active && active !== globalThis.document.body) return
  const items = convList.value?.querySelectorAll<HTMLElement>('.conv-item')
  if (items && items.length > 0) {
    items[Math.max(0, Math.min(index, items.length - 1))]?.focus({
      preventScroll: true,
    })
    return
  }
  newQuestionButton.value?.focus({ preventScroll: true })
}

/** 归档/恢复即时生效、可逆，不弹确认框。 */
async function toggleArchived(target: Conversation): Promise<void> {
  if (managingId.value) return
  const archived = !target.archived
  const index = conversations.value.findIndex(
    (item) => item.conversationId === target.conversationId,
  )
  managingId.value = target.conversationId
  convError.value = null
  try {
    const updated = await setConversationArchived(
      target.conversationId,
      archived,
    )
    if (isDisposed()) return
    // 当前打开的会话：留在原处，输入区按 archived 切换为提示，不打断阅读。
    if (conversationId.value === updated.conversationId) {
      openedConversation.value = updated
    }
    await reloadConversations()
    announceText.value = archived
      ? '会话已归档，可在「已归档」中查看。'
      : '会话已恢复。'
    await focusAfterRowRemoved(index)
  } catch (failure) {
    if (!isDisposed()) convError.value = describe(failure)
  } finally {
    managingId.value = null
  }
}

function requestDelete(target: Conversation, index: number): void {
  if (managingId.value) return
  pendingDelete.value = target
  pendingIndex = index
  deleteError.value = null
}

/** 确认删除：成功后才关闭弹窗，失败留在弹窗内就地重试。 */
async function confirmDeleteConversation(): Promise<void> {
  const target = pendingDelete.value
  if (!target || managingId.value) return
  const index = pendingIndex
  managingId.value = target.conversationId
  deleteError.value = null
  try {
    await deleteConversation(target.conversationId)
    if (isDisposed()) return
    pendingDelete.value = null
    if (openedConversation.value?.conversationId === target.conversationId) {
      openedConversation.value = null
    }
    await reloadConversations()
    if (conversationId.value === target.conversationId) {
      const next =
        conversations.value[index] ??
        conversations.value[index - 1] ??
        conversations.value[0]
      await selectConversation(next?.conversationId ?? '')
    }
    announceText.value = '会话已删除。'
    await focusAfterRowRemoved(index)
  } catch (failure) {
    if (isDisposed()) return
    if (isApiError(failure, 404, 'RESOURCE_NOT_FOUND')) {
      // 已不存在（重复删除或其他端已删）：按成功处理，刷新列表并关闭弹窗。
      pendingDelete.value = null
      await reloadConversations()
      if (conversationId.value === target.conversationId) {
        await selectConversation(conversations.value[0]?.conversationId ?? '')
      }
      await focusAfterRowRemoved(index)
      return
    }
    deleteError.value = describe(failure)
  } finally {
    managingId.value = null
  }
}

watch(conversationId, (id) => {
  // 离开占位会话（切到历史会话或首问已建会话）就丢弃这条假数据。
  if (id) draftActive.value = false
  citationPreview.value?.close()
  pendingDelete.value = null
  deleteError.value = null
  convError.value = null
  historyLoad = loadHistory(id)
})

/** 已经停留在助手页时再点顶栏提问：切到新会话并把文字预填进去。 */
watch(
  () => route.query.q,
  (value) => {
    if (value === undefined) return
    if (!consumeSearchQuery()) return
    prefillPending.value = false
    void startNewConversation()
  },
)

/**
 * 旧版本把「按助手记住的提问范围」写在 localStorage；范围已改由助手决定，
 * 上线后清理这些键，避免残留空间 id 被误读。
 */
function purgeLegacyScopeKeys(): void {
  try {
    const storage = globalThis.window.localStorage
    const stale: string[] = []
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index)
      if (key !== null && key.startsWith('aiqa.assistant.scope.'))
        stale.push(key)
    }
    for (const key of stale) storage.removeItem(key)
  } catch {
    // 本机存储不可用时无事可做。
  }
}

/**
 * 侧栏会话列表改成滚动加载，替代原来的分页器：滚动区就是列表本身，
 * 滚到距底部 {@link CONV_MORE_THRESHOLD} 像素以内就补下一页。
 * 抽屉收起时列表不可见，一律不补，避免在后台把整份会话拉完。
 */
function requestMoreConversations(): void {
  if (!convOpen.value) return
  if (!conversationsHasMore.value || conversationLoading.value) return
  const area = convList.value
  if (!area) return
  const remaining = area.scrollHeight - area.scrollTop - area.clientHeight
  if (remaining > CONV_MORE_THRESHOLD) return
  void loadMoreConversations()
}

/**
 * 每页数据、加载状态或抽屉开合变化后重新量一次：
 * 内容不足一屏时不会触发 scroll，靠这里接着补，直到填满或有更多。
 */
watch(
  [conversations, conversationLoading, convOpen],
  () => {
    void nextTick(requestMoreConversations)
  },
  { flush: 'post' },
)

onMounted(() => {
  if (consumeSearchQuery()) prefillPending.value = true
  convOpen.value = readConvOpen()
  purgeLegacyScopeKeys()
  void nextTick(() => {
    convReady.value = true
  })
  void load()
  void loadModels()
})
onBeforeUnmount(() => {
  lifecycle.abort()
  stopReads()
})
</script>

<template>
  <div
    class="chat-layout"
    :class="{ 'conv-open': convOpen, 'is-ready': convReady }"
  >
    <div
      v-if="convOpen"
      class="conv-scrim"
      aria-hidden="true"
      @click="closeConvPanel"
    ></div>
    <aside
      id="conv-panel"
      ref="convPanel"
      class="bk-card conv-panel"
      aria-label="会话列表"
      :inert="convOpen ? undefined : true"
      @keydown.esc="closeConvPanel"
    >
      <button
        ref="newQuestionButton"
        class="bk-btn bk-btn-primary new-question"
        type="button"
        :disabled="busy || !defaultAssistant"
        @click="startNewConversation"
      >
        ＋ 新提问
      </button>
      <div class="conv-tabs" aria-label="会话分类">
        <button
          type="button"
          class="bk-btn bk-btn-sm"
          :class="{ 'bk-btn-primary': !archiveView }"
          :disabled="busy"
          :aria-pressed="!archiveView"
          @click="archiveView = false"
        >
          会话
        </button>
        <button
          type="button"
          class="bk-btn bk-btn-sm"
          :class="{ 'bk-btn-primary': archiveView }"
          :disabled="busy"
          :aria-pressed="archiveView"
          @click="archiveView = true"
        >
          已归档
        </button>
      </div>
      <p
        v-if="convError"
        class="bk-notice bk-notice-fail conv-error"
        role="alert"
      >
        {{ convError }}
      </p>
      <p v-if="loading" class="bk-text-xs bk-muted">正在加载…</p>
      <p
        v-else-if="
          conversationTotal === 0 && (!draftConversation || archiveView)
        "
        class="bk-text-xs bk-muted"
      >
        {{ archiveView ? '还没有已归档会话。' : '还没有会话。' }}
      </p>
      <div
        ref="convList"
        class="conv-list"
        @scroll.passive="requestMoreConversations"
      >
        <div v-if="draftConversation && !archiveView" class="conv-row on">
          <button
            class="conv-item"
            type="button"
            aria-pressed="true"
            :disabled="busy"
            @click="startNewConversation"
          >
            <span class="c-title">{{ draftConversation.title }}</span>
            <span class="c-time">{{
              formatDateTime(draftConversation.createdAt)
            }}</span>
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
            :title="conversationTitle(conversation)"
            :aria-pressed="isCurrent(conversation)"
            :disabled="busy"
            @click="selectConversation(conversation.conversationId)"
          >
            <span class="c-title">{{ conversationTitle(conversation) }}</span>
            <span class="c-time">{{
              formatDateTime(conversation.createdAt)
            }}</span>
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
        <div
          v-if="
            conversations.length > 0 &&
            (conversationsHasMore || conversationLoading)
          "
          class="conv-more"
          role="status"
          aria-live="polite"
        >
          <button
            v-if="conversationError"
            class="bk-btn bk-btn-sm"
            type="button"
            @click="loadMoreConversations"
          >
            加载更多失败，重试
          </button>
          <span v-else class="bk-text-xs bk-muted">
            {{ conversationLoading ? '正在加载更多…' : '滚动加载更多' }}
          </span>
        </div>
      </div>
    </aside>

    <section class="bk-card chat-main" aria-label="对话主区">
      <div class="chat-meta">
        <button
          ref="convToggleButton"
          class="conv-toggle"
          type="button"
          :aria-expanded="convOpen"
          aria-controls="conv-panel"
          :aria-label="convOpen ? '收起会话列表' : '展开会话列表'"
          :title="convOpen ? '收起会话列表' : '展开会话列表'"
          @click="toggleConvPanel"
        >
          <PanelLeftClose v-if="convOpen" aria-hidden="true" />
          <PanelLeftOpen v-else aria-hidden="true" />
        </button>
        <button
          v-if="!convOpen"
          class="bk-btn bk-btn-sm conv-new"
          type="button"
          aria-label="新提问"
          title="新提问"
          :disabled="busy || !defaultAssistant"
          @click="startNewConversation"
        >
          ＋
        </button>
        <SelectMenu
          v-if="assistantOptions.length > 1"
          class="assistant-switch"
          :model-value="activeAssistant?.assistantId ?? ''"
          :options="assistantOptions"
          label="切换助手"
          :disabled="busy"
          @update:model-value="requestAssistant"
        />
        <span v-else class="bk-text-xs bk-muted">
          助手「{{ activeAssistant?.name ?? '未配置' }}」
        </span>
        <span v-if="scopeMessage" class="bk-text-xs bk-muted">
          {{ scopeMessage }}
        </span>
        <span class="bk-grow"></span>
      </div>

      <p class="visually-hidden" role="status" aria-live="polite">
        {{ announceText }}
      </p>

      <p v-if="error" class="bk-notice bk-notice-fail chat-notice" role="alert">
        {{ error }}
      </p>

      <div
        ref="chatScroll"
        class="chat-scroll"
        tabindex="0"
        aria-label="聊天记录"
      >
        <p v-if="readingHistory" class="bk-muted chat-empty">
          正在读取聊天记录…
        </p>
        <p
          v-else-if="exchanges.length === 0 && !error"
          class="bk-muted chat-empty"
        >
          {{
            conversationId
              ? '当前会话还没有消息。'
              : '提出第一个问题后会自动建立会话。'
          }}
        </p>

        <template v-for="exchange in exchanges" :key="exchange.questionId">
          <div class="msg msg-user">
            <span class="m-avatar">我</span>
            <div class="m-body">
              <div class="m-bubble">
                <AssistantMessageText :text="exchange.text" plain />
              </div>
              <div class="m-time">{{ formatDateTime(exchange.createdAt) }}</div>
            </div>
          </div>

          <div class="msg msg-ai">
            <span class="m-avatar">AI</span>
            <div class="m-body">
              <div class="m-bubble">
                <div v-if="isPending(exchange)" class="answer-wait">
                  <div
                    v-if="exchange.streamedText"
                    class="ans-stream"
                    aria-busy="true"
                  >
                    <AssistantMessageText
                      :text="exchange.streamedText + (caretOn ? '▍' : '')"
                    />
                  </div>
                  <template v-else>
                    <p class="bk-muted" role="status">
                      {{ exchange.lookupMessage ?? '正在等待回答…' }}
                    </p>
                    <button
                      v-if="exchange.lookup === 'paused'"
                      class="bk-btn bk-btn-sm bk-btn-ghost"
                      type="button"
                      @click="pollAnswer(exchange)"
                    >
                      继续查询
                    </button>
                  </template>
                </div>
                <template v-else-if="exchange.status === 'FAILED'">
                  <span class="ans-flag af-expired">回答失败</span>
                  <p>
                    请重新提问{{
                      exchange.failureCode ? `（${exchange.failureCode}）` : ''
                    }}。
                    <template v-if="exchange.failureCode === 'CONTEXT_CHANGED'">
                      期间权限或发布状态发生变化，历史结果不再使用。
                    </template>
                    <template
                      v-else-if="exchange.failureCode === 'MODEL_UNAVAILABLE'"
                    >
                      当前模型服务暂不可用，请稍后重试或联系管理员检查模型配置。
                    </template>
                  </p>
                </template>
                <template v-else>
                  <div
                    v-for="paragraph in exchange.paragraphs"
                    :key="paragraph.id"
                    class="paragraph"
                  >
                    <template v-if="paragraph.status === 'MASKED'">
                      <span class="ans-flag af-expired">⛔ 来源已不可用</span>
                      <p>来源已撤回，内容不可见。</p>
                    </template>
                    <template v-else>
                      <span
                        v-if="answerFlag(paragraph)"
                        class="ans-flag"
                        :class="
                          answerToneClass[answerFlag(paragraph)?.tone ?? 'none']
                        "
                      >
                        {{ answerFlag(paragraph)?.text }}
                      </span>
                      <AssistantMessageText :text="paragraph.text ?? ''" />
                      <div
                        v-if="
                          paragraph.citations && paragraph.citations.length > 0
                        "
                        class="cite-block"
                      >
                        <div class="cite-label">
                          <button
                            class="cite-toggle"
                            type="button"
                            :aria-expanded="
                              !collapsedCitations.has(paragraph.id)
                            "
                            :aria-controls="`cite-list-${paragraph.id}`"
                            @click="toggleCitationBlock(paragraph.id)"
                          >
                            <span
                              >引用文档（{{
                                paragraph.citations.length
                              }}）</span
                            >
                            <ChevronDown
                              class="cite-chevron"
                              :class="{
                                'is-collapsed': collapsedCitations.has(
                                  paragraph.id,
                                ),
                              }"
                              :size="14"
                              aria-hidden="true"
                            />
                          </button>
                        </div>
                        <div
                          v-show="!collapsedCitations.has(paragraph.id)"
                          :id="`cite-list-${paragraph.id}`"
                        >
                          <button
                            v-for="citation in paragraph.citations"
                            :key="citation.citationId"
                            class="cite-item"
                            type="button"
                            aria-haspopup="dialog"
                            @click="citationPreview?.open(citation, $event)"
                          >
                            <span class="c-no" aria-hidden="true">{{
                              citation.marker
                            }}</span>
                            <span class="c-main">
                              <span class="c-title">{{ citation.label }}</span>
                              <span class="c-sub">查看引用</span>
                            </span>
                          </button>
                        </div>
                      </div>
                    </template>
                  </div>
                  <p v-if="exchange.paragraphs.length === 0" class="bk-muted">
                    没有可展示的内容。
                  </p>
                </template>
              </div>
              <div v-if="!isPending(exchange)" class="m-time">
                {{ formatDateTime(exchange.createdAt) }} ·
                {{ statusLabel(exchange.status) }}
              </div>
            </div>
          </div>
        </template>
      </div>

      <p v-if="currentConversation?.archived" class="binding-note">
        此会话已归档，可在「已归档」中恢复后继续提问。
      </p>
      <form v-else class="chat-input" @submit.prevent="submit">
        <div class="chat-model">
          <SelectMenu
            v-model="selectedModel"
            label="回答模型"
            :options="modelOptions"
            :disabled="busy || modelOptions.length === 1"
          />
          <span
            v-if="models.status === 'loading'"
            class="bk-muted"
            role="status"
            >正在加载模型…</span
          >
          <button
            v-else-if="models.status === 'failed'"
            class="bk-btn bk-btn-ghost"
            type="button"
            @click="loadModels"
          >
            模型加载失败，重试
          </button>
          <span class="bk-grow"></span>
          <span v-if="scopeEmpty && !loading" class="scope-empty" role="status">
            该助手暂无可检索空间
          </span>
        </div>
        <div class="chat-entry">
          <FormTextarea
            v-model="question"
            :maxlength="FORM_LIMITS.question"
            required
            class="chat-field"
            compact
            aria-label="提问内容"
            placeholder="输入问题，Enter 发送"
            :disabled="!canSend"
            @keydown.enter.exact="onEnter"
          />
          <button
            class="bk-btn bk-btn-primary send"
            type="submit"
            :disabled="!canSend || question.trim() === ''"
          >
            {{ asking ? '提交中…' : '发送' }}
          </button>
        </div>
      </form>
    </section>
    <CitationPreview ref="citationPreview" />
    <OverlayTransition>
      <ModalShell
        v-if="pendingAssistant"
        title="切换助手"
        width="420px"
        initial-focus="first-action"
        @close="cancelAssistantSwitch"
      >
        <p>
          当前会话属于助手「{{
            activeAssistant?.name ?? '未配置'
          }}」。切换到「{{
            pendingAssistant.name
          }}」会开始一个新会话，已有记录保持不变。
        </p>
        <template #actions>
          <button class="bk-btn" type="button" @click="cancelAssistantSwitch">
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="button"
            @click="void applyAssistant(pendingAssistant.assistantId)"
          >
            开始新会话
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
    <OverlayTransition>
      <ModalShell
        v-if="pendingDelete"
        title="删除会话"
        width="420px"
        initial-focus="first-action"
        @close="managingId === null && (pendingDelete = null)"
      >
        <p>
          <strong>「{{ pendingDeleteTitle }}」</strong>
        </p>
        <p>删除后，该会话及聊天记录将从会话列表移除，无法在页面恢复。</p>
        <p v-if="deleteError" class="bk-notice bk-notice-fail" role="alert">
          {{ deleteError }}
        </p>
        <template #actions>
          <button
            class="bk-btn"
            type="button"
            :disabled="managingId !== null"
            @click="pendingDelete = null"
          >
            取消
          </button>
          <button
            class="bk-btn bk-btn-danger"
            type="button"
            :disabled="managingId !== null"
            @click="confirmDeleteConversation"
          >
            {{ managingId ? '删除中…' : '确认删除' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
  </div>
</template>

<style scoped>
.chat-layout {
  --conv-panel-width: 260px;
  /* 用户与 AI 两处宽度约束，模块内共享，调整时一起变化 */
  --chat-user-max: min(78%, 640px);
  --chat-ai-max: min(100%, 920px);
  /* 贝塞尔缓动：起步快、收尾缓，抽屉停稳不生硬 */
  --conv-motion: 260ms;
  --conv-ease: cubic-bezier(0.22, 1, 0.36, 1);
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  height: calc(100vh - 140px);
  min-height: 560px;
  /* 抽屉只在本区域内平移，离场时被裁掉，不会扫过左侧导航。 */
  overflow: hidden;
}

/* 面板脱离栅格：收起后整体离场，不占列宽也不留高度。 */
.conv-panel {
  position: absolute;
  inset: 0 auto 0 0;
  width: var(--conv-panel-width);
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  min-height: 0;
  transform: translateX(calc(-100% - var(--space-4)));
  visibility: hidden;
  transition:
    transform var(--conv-motion) var(--conv-ease),
    visibility 0s linear var(--conv-motion);
  will-change: transform;
}

.chat-layout.conv-open .conv-panel {
  transform: none;
  visibility: visible;
  transition:
    transform var(--conv-motion) var(--conv-ease),
    visibility 0s;
}

.chat-main {
  margin-left: 0;
  transition: margin-left var(--conv-motion) var(--conv-ease);
}

.chat-layout.conv-open .chat-main {
  margin-left: calc(var(--conv-panel-width) + var(--space-4));
}

/* 首帧不播动画：挂载完成前禁用过渡，避免刷新时闪一帧展开态。 */
.chat-layout:not(.is-ready) .conv-panel,
.chat-layout:not(.is-ready) .chat-main {
  transition: none;
}

.conv-scrim {
  display: none;
}

.conv-toggle {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--color-text-secondary);
  font: inherit;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  cursor: pointer;
}

.conv-toggle:hover {
  color: var(--color-text);
  background: var(--color-divider);
}

.conv-toggle svg {
  width: var(--space-4);
  height: var(--space-4);
}

.scope-empty {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.conv-tabs,
.answer-wait {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

/* 流式回答占满气泡宽度，逐字显示时保持正常段落排版。 */
.answer-wait > .ans-stream {
  flex: 1 1 100%;
  min-width: 0;
}

.conv-tabs {
  margin-bottom: var(--space-3);
}

.conv-error {
  margin-bottom: var(--space-3);
}

.new-question {
  width: 100%;
  margin-bottom: var(--space-3);
}

.conv-list {
  display: flex;
  /* 撑满面板剩余高度：列表自己滚，底部「加载更多」提示随内容一起滚出视口。 */
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  overscroll-behavior: contain;
  gap: 2px;
}

/* 加载更多占位：固定高度，加载中就地提示，不推挤已渲染的行。 */
.conv-more {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: var(--space-2) 0;
}

.conv-row {
  position: relative;
}

.conv-item {
  display: block;
  width: 100%;
  padding: 10px var(--space-3);
  /* 右侧给行内操作留位，标题省略时不压到图标下面 */
  padding-inline-end: 62px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  text-align: start;
  cursor: pointer;
  transition:
    background var(--motion-fast) var(--ease-standard),
    color var(--motion-fast) var(--ease-standard);
}

.conv-item .c-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-item:hover {
  background: var(--color-divider);
}

.conv-row.on .conv-item {
  background: var(--color-surface-selected);
  color: var(--color-action);
  font-weight: 600;
}

.conv-item .c-time {
  display: block;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-weight: 400;
  margin-top: 2px;
}

/* 行内操作：悬停、键盘聚焦和当前会话常显；隐藏时让点击穿透到会话按钮。 */
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

/* 触摸设备没有悬停：操作常显 */
@media (hover: none) {
  .conv-ops {
    opacity: 1;
    pointer-events: auto;
  }
}

.chat-main {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.chat-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-border);
}

.assistant-switch {
  min-width: 160px;
  max-width: 220px;
}

.chat-notice {
  margin-top: var(--space-3);
}

.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding-right: var(--space-2);
  margin-top: var(--space-4);
}

.chat-empty {
  font-size: var(--text-sm);
}

.msg {
  display: flex;
  gap: var(--space-3);
  margin-bottom: var(--space-5);
}

/* 用户消息右对齐：头像落到最右，气泡与时间贴右；行距收紧，与下方回答成组。 */
.msg-user {
  flex-direction: row-reverse;
  margin-bottom: var(--space-3);
}

.m-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
}

.msg-user .m-avatar {
  background: var(--color-divider);
  color: var(--color-text-secondary);
}

.msg-ai .m-avatar {
  background: var(--color-action);
  color: var(--color-action-text);
}

.m-body {
  flex: 1;
  min-width: 0;
}

/* 用户气泡按内容收缩，不再拉满整行；时间与气泡右对齐。 */
.msg-user .m-body {
  flex: 0 1 auto;
  max-width: var(--chat-user-max);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

/* AI 回答保留左侧并限制阅读宽度，引用列表和表格仍有余量。 */
.msg-ai .m-body {
  flex: 1 1 auto;
  max-width: var(--chat-ai-max);
}

.m-bubble {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  overflow-wrap: anywhere;
  transition:
    border-color var(--motion-fast) var(--ease-standard),
    box-shadow var(--motion-fast) var(--ease-standard);
}

/* 用户气泡：偏主色的浅底 + 同向描边，形成"我说的"与"AI 答的"两种质感。 */
.msg-user .m-bubble {
  background: var(--color-surface-selected);
  border-color: color-mix(
    in srgb,
    var(--color-action) 26%,
    var(--color-border)
  );
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 8%);
}

.msg-ai .m-bubble {
  box-shadow: var(--elev-card, 0 1px 2px rgb(23 32 51 / 4%));
}

.m-time {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  margin-top: 6px;
}

.msg-user .m-time {
  text-align: end;
}

.paragraph + .paragraph {
  border-top: 1px solid var(--color-divider);
  padding-top: var(--space-3);
  margin-top: var(--space-3);
}

.ans-flag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  font-weight: 700;
  padding: 4px 10px;
  border-radius: var(--radius-pill);
  margin-bottom: var(--space-2);
}

.af-none,
.af-unknown {
  background: color-mix(
    in srgb,
    var(--color-warning) 14%,
    var(--color-surface)
  );
  color: var(--color-text-warning);
}

.af-expired {
  background: color-mix(in srgb, var(--color-danger) 10%, var(--color-surface));
  color: var(--color-danger);
}

.af-conflict {
  background: color-mix(in srgb, var(--color-action) 12%, var(--color-surface));
  color: var(--color-action);
}

.cite-block {
  margin-top: var(--space-3);
  border-top: 1px solid var(--color-divider);
  padding-top: var(--space-3);
}

.cite-label {
  font-size: var(--text-xs);
  font-weight: 700;
  color: var(--color-text-muted);
  margin-bottom: 8px;
}

/* 标题即开关：整行可点，箭头指示当前是展开还是收起。 */
.cite-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0;
  color: inherit;
  font: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

.cite-toggle:hover {
  color: var(--color-text);
}

.cite-toggle:focus-visible {
  outline: 2px solid var(--color-action);
  outline-offset: 2px;
}

.cite-chevron {
  flex: none;
  transition: transform var(--motion-fast) var(--ease-standard);
}

/* 收起时箭头转向上，和展开态区分开。 */
.cite-chevron.is-collapsed {
  transform: rotate(-90deg);
}

.cite-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  margin-bottom: 6px;
  width: 100%;
  background: var(--color-canvas);
  font-size: var(--text-xs);
  text-align: start;
  overflow-wrap: anywhere;
  cursor: pointer;
  transition:
    border-color var(--motion-fast) var(--ease-standard),
    background var(--motion-fast) var(--ease-standard);
}

.cite-item:hover {
  border-color: color-mix(
    in oklab,
    var(--color-action) 55%,
    var(--color-border)
  );
  background: var(--color-surface-selected);
}

.cite-item .c-no {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  flex: none;
  margin-top: 1px;
  background: var(--color-surface-selected);
  color: var(--color-action);
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
}

.cite-item .c-main {
  display: grid;
}

.cite-item .c-title {
  font-weight: 600;
  color: var(--color-text);
}

.cite-item .c-sub {
  color: var(--color-text-muted);
  margin-top: 2px;
}

.chat-input {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.chat-model,
.chat-entry {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.chat-model {
  flex-wrap: wrap;
}

.chat-entry {
  align-items: stretch;
}

.chat-field {
  flex: 1;
  max-height: 120px;
}

/* 发送按钮：主操作样式 + 与输入框等高的呼吸感内边距。 */
.chat-input .send {
  height: auto;
  padding: 0 var(--space-5);
  box-shadow: 0 2px 8px
    color-mix(in oklab, var(--color-action) 30%, transparent);
}

.binding-note {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  padding: 8px var(--space-3);
  background: var(--color-canvas);
  border-radius: var(--radius-sm);
  margin-top: var(--space-3);
}

@media (max-width: 1100px) {
  .chat-layout {
    --chat-user-max: 88%;
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }

  /* 窄屏没有并排空间：抽屉浮在内容之上，对话区不被挤动。 */
  .conv-panel {
    position: fixed;
    top: 0;
    bottom: 0;
    z-index: 40;
    box-shadow: var(--elev-raised);
  }

  .chat-layout.conv-open .chat-main {
    margin-left: 0;
  }

  .conv-scrim {
    position: fixed;
    inset: 0;
    z-index: 30;
    background: color-mix(in srgb, var(--color-text) 32%, transparent);
  }

  .chat-main {
    height: max(30rem, 68dvh);
    padding: var(--space-3);
  }

  .conv-list {
    max-height: min(26vh, 14rem);
  }
}

/* 系统开启减少动态效果时直接切换。 */
@media (prefers-reduced-motion: reduce) {
  .chat-layout {
    --conv-motion: 0ms;
  }

  .cite-chevron {
    transition: none;
  }
}
</style>
