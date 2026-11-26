<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import BaseChart from '../components/BaseChart.vue'
import FormInput from '../components/FormInput.vue'
import SelectMenu from '../components/SelectMenu.vue'
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type Component,
} from 'vue'
import { RouterLink } from 'vue-router'
import { BookOpen, MessageSquare, Radio, RefreshCw, Search } from '@lucide/vue'
import {
  getUsage,
  getUsageRanking,
  getUsageSeries,
  listAllPublishRequests,
  listAssistants,
  listConversations,
  listSpaces,
  searchSpace,
  type Assistant,
  type Conversation,
  type PublishRequest,
  type SearchHit,
  type Space,
  type Usage,
  type UsageBucket,
  type UsageRankingRow,
} from '../api/knowledge'
import { isApiError } from '../api/http'
import { isAdmin } from '../auth'
import {
  beijingToday,
  describeError,
  formatDateTime,
  statusLabel,
  validityLabel,
  validityTone,
} from '../api/display'
import { FORM_LIMITS } from '../formValidation'
import { designToken, withAlpha } from '../design'

type Period = 'DAY' | 'WEEK' | 'MONTH'

const PERIOD_OPTIONS: { label: string; value: Period }[] = [
  { label: '今日', value: 'DAY' },
  { label: '本周', value: 'WEEK' },
  { label: '本月', value: 'MONTH' },
]

const QUICK_LINKS: {
  to: string
  label: string
  hint: string
  icon: Component
}[] = [
  {
    to: '/knowledge',
    label: '知识库',
    hint: '空间、草稿与发布',
    icon: BookOpen,
  },
  {
    to: '/assistant',
    label: 'AI 助手',
    hint: '基于已发布资料提问',
    icon: MessageSquare,
  },
  { to: '/publishing', label: '发布管理', hint: '审核与发布记录', icon: Radio },
]

/** 检索命中带上所属空间，文档详情路由需要它做授权校验。 */
type ScopedHit = SearchHit & { spaceId: string }

const EMPTY_PAGE = { items: [], nextCursor: null, total: 0 }

/**
 * 统计基准日：页面可能长时间开着（跨天），所以每次加载用量前都重新取北京时间当天，
 * 不能在初始化时定死，否则刷新仍按旧日期查询，指标和近 7 天窗口都停在昨天。
 */
const today = ref(beijingToday())
const period = ref<Period>('WEEK')
const admin = computed(() => isAdmin())

const spaces = ref<Space[]>([])
const requests = ref<PublishRequest[]>([])
const conversations = ref<Conversation[]>([])
const usage = ref<Usage | null>(null)
const buckets = ref<UsageBucket[]>([])
const ranking = ref<UsageRankingRow[]>([])
const rankingNote = ref<string | null>(null)
const structureLoading = ref(true)
const usageLoading = ref(true)
const error = ref<string | null>(null)

/**
 * 文档检索沿用「空间只和助手绑定」：没有空间选择器，检索范围就是所选助手绑定的空间，
 * 用户不需要在提问和检索之间重复选一遍范围。
 */
const assistants = ref<Assistant[]>([])
const searchAssistantId = ref('')
const searchTerm = ref('')
const searching = ref(false)
const searchResults = ref<ScopedHit[]>([])
const searchMessage = ref<string | null>(null)
const searchAttempted = ref(false)
let searchRequest: AbortController | undefined

const assistantOptions = computed(() =>
  assistants.value.map((item) => ({
    value: item.assistantId,
    label: item.name,
    detail: `${String(item.bindings.length)} 个空间`,
  })),
)
const searchAssistant = computed(
  () =>
    assistants.value.find(
      (item) => item.assistantId === searchAssistantId.value,
    ) ?? assistants.value[0],
)
const searchSpaceIds = computed(
  () => searchAssistant.value?.bindings.map((item) => item.spaceId) ?? [],
)

let structureRequest: AbortController | undefined
let usageRequest: AbortController | undefined

const refreshing = computed(() => structureLoading.value || usageLoading.value)
const periodLabel = computed(
  () =>
    PERIOD_OPTIONS.find((item) => item.value === period.value)?.label ?? '本周',
)
function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined
    ? '—'
    : value.toLocaleString('zh-CN')
}

const publishedSpaces = computed(
  () =>
    spaces.value.filter((space) => Boolean(space.currentPublicationId)).length,
)
const totalDocuments = computed(() =>
  spaces.value.reduce((sum, space) => sum + space.documentCount, 0),
)
const totalSearchable = computed(() =>
  spaces.value.reduce((sum, space) => sum + space.searchableCount, 0),
)
const searchableRate = computed(() =>
  totalDocuments.value === 0
    ? '—'
    : `${((totalSearchable.value / totalDocuments.value) * 100).toFixed(1)}%`,
)
const pendingReview = computed(
  () =>
    requests.value.filter((item) => item.status === 'PENDING_REVIEW').length,
)
const waitingIndex = computed(
  () =>
    requests.value.filter((item) => item.status === 'APPROVED_WAITING_INDEX')
      .length,
)
const openRequests = computed(() => pendingReview.value + waitingIndex.value)
const failureRate = computed(() => {
  const value = usage.value
  if (!value || value.questionCount === 0) return null
  return `${((value.failedQuestionCount / value.questionCount) * 100).toFixed(1)}%`
})

interface KpiCard {
  key: string
  label: string
  value: string
  delta?: string
  tone?: 'up' | 'down'
}

const kpis = computed<KpiCard[]>(() => {
  const value = usage.value
  const failed = value?.failedQuestionCount ?? 0
  return [
    {
      key: 'questions',
      label: `${periodLabel.value}提问`,
      value: formatCount(value?.questionCount),
      delta:
        failureRate.value === null
          ? '本周期还没有提问'
          : `失败 ${formatCount(failed)} 次 · 失败率 ${failureRate.value}`,
      ...(failed > 0 ? { tone: 'down' as const } : {}),
    },
    {
      key: 'conversations',
      label: `${periodLabel.value}会话`,
      value: formatCount(value?.conversationCount),
    },
    {
      key: 'tokens',
      label: `${periodLabel.value} Tokens`,
      value:
        value?.reportedTokenCount === null || value === null
          ? '未上报'
          : formatCount(value.reportedTokenCount),
      delta:
        value === null
          ? '尚未取到用量数据'
          : `未上报调用 ${formatCount(value.unreportedModelCallCount)} 次`,
    },
    {
      key: 'spaces',
      label: '知识空间',
      value: formatCount(publishedSpaces.value),
      delta: `已发布 · 可见空间 ${formatCount(spaces.value.length)} 个`,
    },
    {
      key: 'documents',
      label: '文档',
      value: formatCount(totalDocuments.value),
      delta: `可检索 ${formatCount(totalSearchable.value)} · 可检索率 ${searchableRate.value}`,
    },
    {
      key: 'requests',
      label: '待处理申请',
      value: formatCount(openRequests.value),
      delta: `待审核 ${formatCount(pendingReview.value)} · 待索引 ${formatCount(waitingIndex.value)}`,
    },
  ]
})

/** 同一空间既有线上版本又有审核中申请时，计入「审核中」，三类互斥。 */
const reviewingSpaceIds = computed(() => {
  const ids = new Set<string>()
  for (const item of requests.value) {
    if (
      item.status === 'PENDING_REVIEW' ||
      item.status === 'APPROVED_WAITING_INDEX'
    ) {
      ids.add(item.spaceId)
    }
  }
  return ids
})

const publishDistribution = computed(() => {
  const reviewing = reviewingSpaceIds.value
  let published = 0
  let pending = 0
  let draft = 0
  for (const space of spaces.value) {
    if (reviewing.has(space.spaceId)) pending += 1
    else if (space.currentPublicationId) published += 1
    else draft += 1
  }
  return [
    { name: '已发布', value: published, color: designToken('--color-success') },
    { name: '审核中', value: pending, color: designToken('--color-warning') },
    { name: '未发布', value: draft, color: designToken('--color-text-muted') },
  ]
})

const spaceBars = computed(() =>
  [...spaces.value]
    .filter((space) => space.documentCount > 0)
    .sort((left, right) => right.documentCount - left.documentCount)
    .slice(0, 8)
    .reverse(),
)
const spaceChartHeight = computed(
  () => `${String(Math.max(spaceBars.value.length * 32 + 56, 176))}px`,
)

const pendingQueue = computed(() =>
  requests.value
    .filter(
      (item) =>
        item.status === 'PENDING_REVIEW' ||
        item.status === 'APPROVED_WAITING_INDEX',
    )
    .sort((left, right) => right.submittedAt.localeCompare(left.submittedAt))
    .slice(0, 5),
)
const recentConversations = computed(() => conversations.value.slice(0, 3))
const topRanking = computed(() => ranking.value.slice(0, 5))

/** 排行横向条形从下往上画，反转后提问数最多的用户排在顶部。 */
const rankingBars = computed(() => [...topRanking.value].reverse())

const beijingDayFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

const DAY_MS = 86_400_000

/** 近 7 天（含今日）新建会话数，按北京时间切日。 */
const conversationSeries = computed(() => {
  const endOfToday = Date.parse(`${today.value}T00:00:00Z`)
  const days = Array.from({ length: 7 }, (_, index) => {
    const key = new Date(endOfToday - (6 - index) * DAY_MS)
      .toISOString()
      .slice(0, 10)
    return { key, label: key.slice(5), count: 0 }
  })
  const byKey = new Map(days.map((day) => [day.key, day]))
  for (const item of conversations.value) {
    const key = beijingDayFormatter.format(new Date(item.createdAt))
    const day = byKey.get(key)
    if (day) day.count += 1
  }
  return days
})

function spaceName(spaceId: string): string {
  return (
    spaces.value.find((space) => space.spaceId === spaceId)?.name ??
    spaceId.slice(0, 8)
  )
}

function requestBadge(status: PublishRequest['status']): string {
  switch (status) {
    case 'PENDING_REVIEW':
      return 'bk-badge bk-badge-review'
    case 'APPROVED_WAITING_INDEX':
      return 'bk-badge bk-badge-warn'
    case 'PUBLISHED':
      return 'bk-badge bk-badge-ok'
    case 'REJECTED':
      return 'bk-badge bk-badge-fail'
    default:
      return 'bk-badge bk-badge-draft'
  }
}

/** 趋势横轴标签：今日按小时、本周按「月-日」、本月按「N 日」。 */
function bucketLabel(bucket: UsageBucket): string {
  if (period.value === 'DAY') return bucket.label
  if (period.value === 'MONTH') return bucket.label
  return bucket.bucketStart.slice(5)
}

const trendOption = computed(() => {
  const items = buckets.value
  // 全零的分桶（例如本周期还没提问）画出来只是一条贴底直线，不如直接给空状态。
  if (items.every((bucket) => bucket.questionCount === 0)) return null
  const action = designToken('--color-action')
  const danger = designToken('--color-danger')
  const muted = designToken('--color-text-muted')
  const divider = designToken('--color-divider')
  return {
    grid: { left: 44, right: 16, top: 14, bottom: 26 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: items.map((bucket) => bucketLabel(bucket)),
      axisLabel: {
        color: muted,
        fontSize: 10,
        interval: Math.max(Math.ceil(items.length / 8) - 1, 0),
        hideOverlap: true,
      },
      axisLine: { lineStyle: { color: divider } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: muted, fontSize: 10 },
      splitLine: { lineStyle: { color: divider } },
    },
    series: [
      {
        name: '提问次数',
        type: 'line',
        symbol: 'circle',
        symbolSize: 5,
        data: items.map((bucket) => bucket.questionCount),
        itemStyle: { color: action },
        lineStyle: { color: action, width: 2 },
        areaStyle: { color: withAlpha(action, 0.12) },
      },
      {
        name: '失败请求数',
        type: 'line',
        symbol: 'none',
        data: items.map((bucket) => bucket.failedQuestionCount),
        itemStyle: { color: danger },
        lineStyle: { color: danger, width: 2, type: 'dashed' },
      },
    ],
  }
})

const publishOption = computed(() => {
  const items = publishDistribution.value.filter((item) => item.value > 0)
  if (items.length === 0) return null
  const surface = designToken('--color-surface')
  const muted = designToken('--color-text-muted')
  return {
    tooltip: { trigger: 'item', formatter: '{b}：{c} 个（{d}%）' },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 14,
      textStyle: { color: muted, fontSize: 11 },
    },
    series: [
      {
        type: 'pie',
        radius: ['50%', '70%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: surface, borderWidth: 2 },
        data: items.map((item) => ({
          name: item.name,
          value: item.value,
          itemStyle: { color: item.color },
        })),
      },
    ],
  }
})

const spaceOption = computed(() => {
  const items = spaceBars.value
  if (items.length === 0) return null
  const action = designToken('--color-action')
  const muted = designToken('--color-text-muted')
  const secondary = designToken('--color-text-secondary')
  const divider = designToken('--color-divider')
  return {
    // ECharts 6 里 containLabel 已废弃，等价写法是 outerBounds* 两个字段。
    grid: {
      left: 8,
      right: 24,
      top: 30,
      bottom: 8,
      outerBoundsMode: 'same',
      outerBoundsContain: 'axisLabel',
    },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { color: muted, fontSize: 11 },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: muted, fontSize: 10 },
      splitLine: { lineStyle: { color: divider } },
    },
    yAxis: {
      type: 'category',
      data: items.map((space) => space.name),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: divider } },
      axisLabel: {
        color: secondary,
        fontSize: 11,
        width: 132,
        overflow: 'truncate',
      },
    },
    series: [
      {
        name: '文档数',
        type: 'bar',
        barMaxWidth: 10,
        data: items.map((space) => space.documentCount),
        itemStyle: {
          color: withAlpha(muted, 0.35),
          borderRadius: [0, 4, 4, 0],
        },
      },
      {
        name: '可检索',
        type: 'bar',
        barMaxWidth: 10,
        data: items.map((space) => space.searchableCount),
        itemStyle: { color: action, borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
})

const rankingChartHeight = computed(
  () => `${String(Math.max(rankingBars.value.length * 30 + 56, 168))}px`,
)

const rankingOption = computed(() => {
  const items = rankingBars.value
  if (items.length === 0) return null
  const action = designToken('--color-action')
  const danger = designToken('--color-danger')
  const muted = designToken('--color-text-muted')
  const secondary = designToken('--color-text-secondary')
  const divider = designToken('--color-divider')
  return {
    grid: {
      left: 8,
      right: 28,
      top: 30,
      bottom: 8,
      outerBoundsMode: 'same',
      outerBoundsContain: 'axisLabel',
    },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: {
      top: 0,
      right: 0,
      icon: 'roundRect',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { color: muted, fontSize: 11 },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: muted, fontSize: 10 },
      splitLine: { lineStyle: { color: divider } },
    },
    yAxis: {
      type: 'category',
      data: items.map((row) => row.displayName),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: divider } },
      axisLabel: {
        color: secondary,
        fontSize: 11,
        width: 120,
        overflow: 'truncate',
      },
    },
    series: [
      {
        name: '提问',
        type: 'bar',
        barMaxWidth: 10,
        data: items.map((row) => row.questionCount),
        itemStyle: { color: action, borderRadius: [0, 4, 4, 0] },
      },
      {
        name: '失败',
        type: 'bar',
        barMaxWidth: 10,
        data: items.map((row) => row.failedQuestionCount),
        itemStyle: {
          color: withAlpha(danger, 0.75),
          borderRadius: [0, 4, 4, 0],
        },
      },
    ],
  }
})

const conversationOption = computed(() => {
  const items = conversationSeries.value
  if (items.every((day) => day.count === 0)) return null
  const action = designToken('--color-action')
  const muted = designToken('--color-text-muted')
  const divider = designToken('--color-divider')
  return {
    grid: { left: 36, right: 12, top: 16, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'category',
      data: items.map((day) => day.label),
      axisLabel: { color: muted, fontSize: 10, interval: 0 },
      axisLine: { lineStyle: { color: divider } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: muted, fontSize: 10 },
      splitLine: { lineStyle: { color: divider } },
    },
    series: [
      {
        name: '新建会话',
        type: 'bar',
        barMaxWidth: 22,
        data: items.map((day) => day.count),
        itemStyle: { color: action, borderRadius: [4, 4, 0, 0] },
      },
    ],
  }
})

const SEARCH_RESULT_LIMIT = 20

/** ts_headline 用 <mark> 标出命中词；按标签切成片段渲染，避免 v-html 注入。 */
function snippetSegments(snippet: string): { text: string; hit: boolean }[] {
  const segments: { text: string; hit: boolean }[] = []
  let highlighted = false
  for (const part of snippet.split(/(<\/?mark>)/i)) {
    if (/^<mark>$/i.test(part)) {
      highlighted = true
    } else if (/^<\/mark>$/i.test(part)) {
      highlighted = false
    } else if (part !== '') {
      segments.push({ text: part, hit: highlighted })
    }
  }
  return segments
}

/** 在所选助手绑定的空间内检索文件名与正文，合并后按"文件名命中优先"排列。 */
async function runSearch(): Promise<void> {
  const term = searchTerm.value.trim()
  if (term === '' || term.length > FORM_LIMITS.search) {
    searchAttempted.value = false
    searchResults.value = []
    searchMessage.value = `请输入 1–${String(FORM_LIMITS.search)} 个字符的关键词。`
    return
  }
  const scope = searchSpaceIds.value
  if (scope.length === 0) {
    searchAttempted.value = false
    searchResults.value = []
    searchMessage.value = '还没有绑定空间的助手，暂时无法检索文档。'
    return
  }
  searchRequest?.abort()
  const request = new AbortController()
  searchRequest = request
  const { signal } = request
  searching.value = true
  searchMessage.value = null
  searchAttempted.value = false
  try {
    const scoped = await Promise.all(
      scope.map((spaceId) =>
        searchSpace(spaceId, term, 'ALL', signal).then(
          (result) => ({ spaceId, result, failure: null }),
          (failure: unknown) => ({ spaceId, result: null, failure }),
        ),
      ),
    )
    if (signal.aborted) return
    const hits: ScopedHit[] = []
    let unavailable = 0
    let processing = false
    for (const entry of scoped) {
      if (entry.failure) {
        unavailable += 1
        continue
      }
      if (entry.result?.status === 'PROCESSING') processing = true
      for (const item of entry.result?.items ?? []) {
        hits.push({ ...item, spaceId: entry.spaceId })
      }
    }
    hits.sort(
      (left, right) =>
        (left.match === 'NAME' ? 0 : 1) - (right.match === 'NAME' ? 0 : 1),
    )
    searchResults.value = hits.slice(0, SEARCH_RESULT_LIMIT)
    searchAttempted.value = true
    if (hits.length === 0) {
      searchMessage.value = processing
        ? '文档正在解析，完成后再检索。'
        : unavailable > 0
          ? '没有检索到匹配内容，部分空间当前不可读取。'
          : '没有检索到匹配的文件名或正文。'
      return
    }
    if (hits.length > SEARCH_RESULT_LIMIT) {
      searchMessage.value = `结果较多，只显示前 ${String(SEARCH_RESULT_LIMIT)} 条，可换更精确的关键词。`
    } else if (unavailable > 0) {
      searchMessage.value = `${String(unavailable)} 个空间当前不可读取，结果只覆盖其余空间。`
    }
  } catch (failure) {
    if (signal.aborted) return
    searchResults.value = []
    searchMessage.value = describeError(failure)
  } finally {
    if (searchRequest === request) searchRequest = undefined
    if (!signal.aborted) searching.value = false
  }
}

async function loadStructure(): Promise<void> {
  structureRequest?.abort()
  const request = new AbortController()
  structureRequest = request
  const { signal } = request
  try {
    const [spacePage, requestPage, conversationPage, assistantPage] =
      await Promise.all([
        listSpaces({ signal }),
        // 审核申请与最近会话属于辅助信息，取不到也不影响其余区块。
        listAllPublishRequests({ signal }).catch(() => EMPTY_PAGE),
        listConversations({ signal }).catch(() => EMPTY_PAGE),
        // 检索范围由助手决定；没有可用助手时检索区给出明确说明。
        listAssistants({ signal }).catch(() => ({ items: [] })),
      ])
    if (signal.aborted) return
    spaces.value = spacePage.items
    requests.value = requestPage.items
    conversations.value = conversationPage.items
    assistants.value = assistantPage.items
    if (
      !assistants.value.some(
        (item) => item.assistantId === searchAssistantId.value,
      )
    ) {
      searchAssistantId.value = assistants.value[0]?.assistantId ?? ''
    }
  } catch (failure) {
    if (signal.aborted) return
    error.value = describeError(failure)
  } finally {
    if (structureRequest === request) structureRequest = undefined
    if (!signal.aborted) structureLoading.value = false
  }
}

async function loadUsage(): Promise<void> {
  usageRequest?.abort()
  const request = new AbortController()
  usageRequest = request
  const { signal } = request
  const scope = admin.value ? 'all' : undefined
  // 每次加载都按「此刻」重取基准日：跨天后再刷新，「今日」指标和活跃度窗口立刻切到新的一天。
  today.value = beijingToday()
  const date = today.value
  usageLoading.value = true
  try {
    const rankResult = admin.value
      ? getUsageRanking(period.value, date, signal).then(
          (page) => ({ ok: true as const, items: page.items }),
          (failure: unknown) => ({ ok: false as const, failure }),
        )
      : Promise.resolve({ ok: true as const, items: [] as UsageRankingRow[] })
    const [summary, series, ranks] = await Promise.all([
      getUsage(period.value, date, scope, signal),
      getUsageSeries(period.value, date, scope, signal),
      rankResult,
    ])
    if (signal.aborted) return
    usage.value = summary
    buckets.value = series.buckets
    if (ranks.ok) {
      ranking.value = ranks.items
      rankingNote.value = null
    } else {
      ranking.value = []
      rankingNote.value = isApiError(ranks.failure, 403)
        ? '当前账号没有全局排行的查看权限。'
        : describeError(ranks.failure)
    }
  } catch (failure) {
    if (signal.aborted) return
    usage.value = null
    buckets.value = []
    error.value = describeError(failure)
  } finally {
    if (usageRequest === request) usageRequest = undefined
    if (!signal.aborted) usageLoading.value = false
  }
}

function refresh(): void {
  error.value = null
  void loadStructure()
  void loadUsage()
}

watch(period, () => {
  void loadUsage()
})

// 换助手等于换检索范围：旧结果不再对应当前范围，直接清掉而不是留在页面上。
watch(searchAssistantId, () => {
  searchRequest?.abort()
  searchRequest = undefined
  searching.value = false
  searchResults.value = []
  searchAttempted.value = false
  searchMessage.value = null
})

onMounted(() => {
  void loadStructure()
  void loadUsage()
})

onBeforeUnmount(() => {
  structureRequest?.abort()
  usageRequest?.abort()
  searchRequest?.abort()
})
</script>

<template>
  <div class="dashboard">
    <PageHeader title="运营看板">
      <template #actions>
        <SelectMenu
          v-model="period"
          class="period-select"
          label="统计周期"
          :options="PERIOD_OPTIONS"
        />
        <button
          class="bk-btn bk-btn-sm bk-btn-ghost"
          type="button"
          :disabled="refreshing"
          @click="refresh"
        >
          <RefreshCw
            :size="14"
            :class="{ 'is-spinning': refreshing }"
            aria-hidden="true"
          />
          刷新
        </button>
        <RouterLink class="bk-btn bk-btn-sm bk-btn-info" to="/statistics">
          问答统计 ›
        </RouterLink>
      </template>
    </PageHeader>

    <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>

    <section class="bk-card dash-search" aria-label="文档检索">
      <div class="bk-card-head">
        <h3>文档检索</h3>
        <span v-if="searchAssistant" class="dash-search-scope">
          范围：{{ searchAssistant.name }} ·
          {{ String(searchSpaceIds.length) }} 个空间
        </span>
      </div>
      <form class="dash-search-form" role="search" @submit.prevent="runSearch">
        <SelectMenu
          v-if="assistantOptions.length > 1"
          v-model="searchAssistantId"
          class="dash-search-assistant"
          label="检索助手"
          :options="assistantOptions"
        />
        <FormInput
          v-model="searchTerm"
          class="dash-search-input"
          type="search"
          role="searchbox"
          aria-label="搜索关键词"
          :maxlength="FORM_LIMITS.search"
          placeholder="搜索已发布文档的文件名或正文"
        />
        <button
          class="bk-btn bk-btn-primary"
          type="submit"
          :disabled="searching || searchSpaceIds.length === 0"
        >
          <Search :size="14" aria-hidden="true" />
          {{ searching ? '检索中…' : '检索' }}
        </button>
      </form>
      <p
        v-if="assistantOptions.length === 0 && !structureLoading"
        class="dash-search-note"
        role="status"
      >
        还没有可读取绑定空间的助手，暂时无法检索文档。
      </p>
      <p v-if="searchMessage" class="dash-search-note" role="status">
        {{ searchMessage }}
      </p>
      <ul
        v-if="searchAttempted && searchResults.length > 0"
        class="dash-search-list"
      >
        <li
          v-for="hit in searchResults"
          :key="hit.documentVersionId"
          class="dash-search-item"
        >
          <div class="dash-search-head">
            <RouterLink
              class="dash-search-name"
              :to="{
                path: `/documents/${hit.documentVersionId}`,
                query: { space: hit.spaceId },
              }"
            >
              {{ hit.name }}
            </RouterLink>
            <span :class="validityTone(hit.validity)">{{
              validityLabel(hit.validity)
            }}</span>
            <span class="dash-search-space">{{ spaceName(hit.spaceId) }}</span>
          </div>
          <p v-if="hit.snippet" class="dash-search-snippet">
            <span
              v-for="(segment, index) in snippetSegments(hit.snippet)"
              :key="index"
              :class="{ 'is-hit': segment.hit }"
              >{{ segment.text }}</span
            >
          </p>
        </li>
      </ul>
    </section>

    <section class="dash-kpi" aria-label="核心指标" aria-live="polite">
      <div v-for="item in kpis" :key="item.key" class="bk-card bk-kpi">
        <span class="label">{{ item.label }}</span>
        <span class="value">{{ item.value }}</span>
        <span v-if="item.delta" class="delta" :class="item.tone">{{
          item.delta
        }}</span>
      </div>
    </section>

    <div class="bk-grid-2">
      <section class="bk-card" aria-label="提问与失败趋势">
        <div class="bk-card-head">
          <h3>提问与失败趋势</h3>
        </div>
        <div class="legend">
          <span><i class="swatch-question"></i>提问次数</span>
          <span><i class="swatch-failure"></i>失败请求数</span>
        </div>
        <BaseChart v-if="trendOption" :option="trendOption" height="240px" />
        <p v-else class="chart-empty" style="height: 240px">
          该周期还没有提问记录。
        </p>
      </section>

      <section class="bk-card" aria-label="发布状态分布">
        <div class="bk-card-head">
          <h3>空间发布状态</h3>
        </div>
        <BaseChart
          v-if="publishOption"
          :option="publishOption"
          height="268px"
        />
        <p v-else class="chart-empty" style="height: 268px">
          还没有可访问的空间。
        </p>
      </section>
    </div>

    <div class="bk-grid-2">
      <section class="bk-card" aria-label="空间文档构成">
        <div class="bk-card-head">
          <h3>空间文档构成</h3>
        </div>
        <BaseChart
          v-if="spaceOption"
          :option="spaceOption"
          :height="spaceChartHeight"
        />
        <p v-else class="chart-empty" :style="{ height: spaceChartHeight }">
          可见空间里还没有文档。
        </p>
      </section>

      <section class="bk-card" aria-label="待处理申请">
        <div class="bk-card-head">
          <h3>待处理申请</h3>
          <RouterLink class="more" to="/publishing">全部 ›</RouterLink>
        </div>
        <p v-if="pendingQueue.length === 0" class="queue-empty">
          没有待审核或待索引的申请。
        </p>
        <ul v-else class="queue-list">
          <li
            v-for="item in pendingQueue"
            :key="item.requestId"
            class="queue-item"
          >
            <span class="q-title">{{ spaceName(item.spaceId) }}</span>
            <span :class="requestBadge(item.status)">{{
              statusLabel(item.status)
            }}</span>
            <span class="q-meta">
              {{ formatDateTime(item.submittedAt) }} ·
              {{ item.snapshot.length }} 份文档
            </span>
          </li>
        </ul>
      </section>
    </div>

    <div :class="admin ? 'bk-grid-2' : ''">
      <section v-if="admin" class="bk-card" aria-label="用户用量排行">
        <div class="bk-card-head">
          <h3>用户用量排行</h3>
          <RouterLink class="more" to="/statistics">全部 ›</RouterLink>
        </div>
        <p
          v-if="rankingNote"
          class="chart-empty"
          :style="{ height: rankingChartHeight }"
        >
          {{ rankingNote }}
        </p>
        <p
          v-else-if="rankingOption === null"
          class="chart-empty"
          :style="{ height: rankingChartHeight }"
        >
          该周期还没有用量记录。
        </p>
        <BaseChart
          v-else
          :option="rankingOption"
          :height="rankingChartHeight"
        />
      </section>

      <section class="bk-card" aria-label="最近会话">
        <div class="bk-card-head">
          <!-- 数据源是当前用户自己的会话列表（后端按 owner 过滤），标题写明范围，避免管理员误读成全站活跃度。 -->
          <h3>我的会话活跃度</h3>
          <RouterLink class="more" to="/assistant">全部 ›</RouterLink>
        </div>
        <BaseChart
          v-if="conversationOption"
          :option="conversationOption"
          height="200px"
        />
        <p v-else class="chart-empty" style="height: 200px">
          近 7 天没有新建会话。
        </p>
        <div v-if="recentConversations.length > 0" class="recent-links">
          <RouterLink
            v-for="item in recentConversations"
            :key="item.conversationId"
            class="recent-link"
            :to="{
              path: '/assistant',
              query: { conversation: item.conversationId },
            }"
          >
            <span class="r-name">{{
              item.title || `会话 ${item.conversationId.slice(0, 8)}`
            }}</span>
            <span class="r-time">{{ formatDateTime(item.createdAt) }}</span>
          </RouterLink>
        </div>
      </section>
    </div>

    <nav class="quick-dock" aria-label="快捷入口">
      <RouterLink
        v-for="link in QUICK_LINKS"
        :key="link.to"
        class="dock-item"
        :to="link.to"
        :title="`${link.label} · ${link.hint}`"
        :aria-label="link.label"
      >
        <component :is="link.icon" :size="18" aria-hidden="true" />
        <span class="dock-label">{{ link.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.dashboard {
  display: grid;
  gap: var(--space-4);
}

/* 检索卡片：范围由助手决定，因此没有空间选择器；窄屏时输入框整行。 */
.dash-search {
  display: grid;
  gap: var(--space-3);
}

.dash-search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.dash-search-assistant {
  flex: none;
  width: 180px;
}

.dash-search-input {
  flex: 1 1 220px;
  min-width: 0;
}

.dash-search-scope,
.dash-search-note {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.dash-search-list {
  display: grid;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.dash-search-item {
  display: grid;
  gap: var(--space-1);
  padding-bottom: var(--space-2);
  border-bottom: 1px solid var(--color-divider);
}

.dash-search-item:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.dash-search-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.dash-search-name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-action);
  text-decoration: none;
  overflow-wrap: anywhere;
}

.dash-search-name:hover,
.dash-search-name:focus-visible {
  text-decoration: underline;
}

.dash-search-space {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.dash-search-snippet {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.dash-search-snippet .is-hit {
  background: var(--color-surface-selected);
  color: var(--color-text);
}

/* 六格 KPI 排成一行，窄屏折成 3 / 2 列；卡片样式沿用全局 .bk-kpi。 */
.dash-kpi {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: var(--space-4);
}

/* KPI 卡片 hover 微抬起：数值仪表是浏览型组件，不需要每张都跳动。 */
.dash-kpi .bk-card {
  transition:
    box-shadow var(--motion-fast) var(--ease-standard),
    transform var(--motion-fast) var(--ease-standard);
}

.dash-kpi .bk-card:hover {
  box-shadow: var(--elev-pop, var(--elev-card));
  transform: translateY(-1px);
}

@media (max-width: 1400px) {
  .dash-kpi {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .dash-kpi {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

:deep(.page-head > .actions) {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex: none;
}

.period-select {
  width: 104px;
}

/* 与同行的 .bk-btn-sm 同高同字号，避免页头出现高低错位。 */
.period-select :deep(.bk-select-trigger) {
  min-height: var(--button-small-height);
  padding: 0 var(--button-small-padding-inline);
  font-size: var(--button-small-text-size);
}

.is-spinning {
  animation: dash-spin 900ms linear infinite;
}

@keyframes dash-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning {
    animation: none;
  }
}

/* 快捷入口固定在视口右侧悬浮；≤1100px 改成底部横向条。 */
.quick-dock {
  position: fixed;
  top: 50%;
  right: var(--space-4);
  z-index: 20;
  display: grid;
  gap: var(--space-1);
  padding: var(--space-2);
  transform: translateY(-50%);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  box-shadow: var(--elev-raised);
}

/* 宽屏时给悬浮条留出一条不压内容的轨道。 */
@media (min-width: 1101px) {
  .dashboard {
    padding-inline-end: calc(var(--space-12) + var(--space-2));
  }

  .quick-dock {
    right: var(--space-2);
  }
}

.dock-item {
  position: relative;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  color: var(--color-text-secondary);
  text-decoration: none;
  transition:
    background var(--motion-fast) var(--ease-standard),
    color var(--motion-fast) var(--ease-standard),
    transform var(--motion-fast) var(--ease-standard);
}

.dock-item:hover,
.dock-item:focus-visible {
  background: var(--color-surface-selected);
  color: var(--color-action);
  transform: translateY(-1px);
}

/* 纯图标按钮的可读标签：悬停或键盘聚焦时从左侧滑出。 */
.dock-label {
  position: absolute;
  top: 50%;
  right: calc(100% + 10px);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  background: var(--color-text);
  color: var(--color-surface);
  font-size: var(--text-xs);
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transform: translate(4px, -50%);
  transition:
    opacity var(--motion-fast) var(--ease-standard),
    transform var(--motion-fast) var(--ease-standard);
}

.dock-item:hover .dock-label,
.dock-item:focus-visible .dock-label {
  opacity: 1;
  transform: translate(0, -50%);
}

.legend {
  display: flex;
  gap: var(--space-4);
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  margin-bottom: var(--space-2);
}

.legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  margin-right: 5px;
  vertical-align: -1px;
}

.swatch-question {
  background: var(--color-action);
}

.swatch-failure {
  background: var(--color-danger);
}

.chart-empty {
  display: grid;
  place-items: center;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.queue-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--space-3);
}

/* 待办为空时把提示居中，避免整张卡看起来像没渲染完。 */
.queue-empty {
  display: grid;
  place-items: center;
  min-height: 160px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.queue-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-1) var(--space-2);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--color-divider);
}

.queue-item:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.q-title {
  font-size: var(--text-sm);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.q-meta {
  grid-column: 1 / -1;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

/* 图表下方保留少量可点击的最近会话，视觉重量低于图表。 */
.recent-links {
  display: grid;
  gap: var(--space-1);
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-divider);
}

.recent-link {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  text-decoration: none;
}

.recent-link:hover .r-name {
  color: var(--color-action);
}

.recent-link .r-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-link .r-time {
  margin-left: auto;
  flex: none;
}

@media (max-width: 1100px) {
  .quick-dock {
    top: auto;
    right: auto;
    bottom: var(--space-4);
    left: 50%;
    grid-auto-flow: column;
    transform: translateX(-50%);
  }

  .dock-label {
    top: auto;
    bottom: calc(100% + 10px);
    left: 50%;
    right: auto;
    transform: translate(-50%, 4px);
  }

  .dock-item:hover .dock-label,
  .dock-item:focus-visible .dock-label {
    transform: translate(-50%, 0);
  }

  .dashboard {
    padding-bottom: calc(var(--space-12) + var(--space-4));
  }
}
</style>
