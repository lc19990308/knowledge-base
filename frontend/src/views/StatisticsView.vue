<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import { FORM_LIMITS, validCalendarDate } from '../formValidation'
import FormInput from '../components/FormInput.vue'
import SelectMenu from '../components/SelectMenu.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import BaseChart from '../components/BaseChart.vue'
import {
  getUsage,
  getUsageQuestions,
  getUsageRanking,
  getUsageSeries,
  getUsageSpaces,
  listUsers,
  type Usage,
  type UsageBucket,
  type UsageQuestionRow,
  type UsageRankingRow,
  type UsageSpaceRow,
  type UserAccess,
} from '../api/knowledge'
import {
  beijingToday,
  describeError,
  formatDateTime,
  statusLabel,
} from '../api/display'
import { designToken, withAlpha } from '../design'
import { isAdmin } from '../auth'

type Period = 'DAY' | 'WEEK' | 'MONTH'

const PERIODS: { key: Period; label: string }[] = [
  { key: 'DAY', label: '自然日' },
  { key: 'WEEK', label: '自然周' },
  { key: 'MONTH', label: '自然月' },
]

const period = ref<Period>('WEEK')
const date = ref(beijingToday())
const admin = computed(() => isAdmin())
const target = ref('')
const userQuery = ref('')
const usersError = ref<string | null>(null)
const selectedUser = ref<UserAccess | null>(null)
const users = ref<UserAccess[]>([])
const userOptions = computed(() => {
  const entries = users.value.map((user) => ({
    value: user.userId,
    label: user.displayName,
  }))
  const selected = selectedUser.value
  if (selected && !entries.some((entry) => entry.value === selected.userId)) {
    entries.unshift({ value: selected.userId, label: selected.displayName })
  }
  return [{ value: '', label: '全部用户' }, ...entries]
})
const usage = ref<Usage | null>(null)
const buckets = ref<UsageBucket[]>([])
const ranking = ref<UsageRankingRow[]>([])
const spaceUsage = ref<UsageSpaceRow[]>([])
const detail = ref<UsageQuestionRow[]>([])
/** 接口返回的明细总数，可能大于本次加载的 200 条，用于工具条文案。 */
const detailTotal = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

const metrics = computed(() => {
  const value = usage.value
  if (!value) return []
  const failureRate =
    value.questionCount === 0
      ? '本周期没有提问'
      : `失败率 ${((value.failedQuestionCount / value.questionCount) * 100).toFixed(1)}%`
  return [
    {
      label: '会话数',
      value: value.conversationCount,
      delta: '',
    },
    {
      label: '提问次数',
      value: value.questionCount,
      delta: '',
    },
    {
      label: '失败请求数',
      value: value.failedQuestionCount,
      delta: failureRate,
    },
    {
      label: 'Tokens 用量',
      value: value.reportedTokenCount ?? '—',
      delta:
        value.reportedTokenCount === null &&
        value.unreportedModelCallCount === 0
          ? '本周期没有模型调用'
          : value.reportedTokenCount === null
            ? `已调用但全部 ${String(value.unreportedModelCallCount)} 次未上报`
            : `未上报调用 ${String(value.unreportedModelCallCount)} 次`,
    },
  ]
})

/** 排行合计行，与原型一致。 */
const rankingTotal = computed(() =>
  ranking.value.reduce(
    (sum, row) => ({
      conversations: sum.conversations + row.conversationCount,
      questions: sum.questions + row.questionCount,
      failures: sum.failures + row.failedQuestionCount,
      unreported: sum.unreported + row.unreportedModelCallCount,
    }),
    { conversations: 0, questions: 0, failures: 0, unreported: 0 },
  ),
)

/** 趋势图配置：颜色/字号取自主题 token，图表实例由 BaseChart 管理。 */
const trendOption = computed(() => {
  const items = buckets.value
  if (items.length === 0) {
    return null
  }
  const action = designToken('--color-action')
  const danger = designToken('--color-danger')
  const muted = designToken('--color-text-muted')
  const divider = designToken('--color-divider')
  return {
    grid: { left: 40, right: 20, top: 12, bottom: 30 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: items.map((bucket) => bucket.label),
      axisLabel: {
        color: muted,
        fontSize: 10,
        interval: items.length > 12 ? 4 : 0,
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
        symbolSize: 6,
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

/** 明细只保留表格，状态构成改用堆叠条：一屏放得下，也不再重复上方趋势图。 */
const DETAIL_STATUS_ORDER = ['SUCCEEDED', 'RUNNING', 'FAILED'] as const
const DETAIL_STATUS_TOKEN: Record<string, string> = {
  SUCCEEDED: '--color-success',
  RUNNING: '--color-warning',
  FAILED: '--color-danger',
}

/** 状态构成：已回答 / 生成中 / 处理失败，其余状态合并为「其他状态」。 */
const detailStatusRows = computed(() => {
  const total = detail.value.length
  const counts = new Map<string, number>()
  for (const row of detail.value) {
    const key = (DETAIL_STATUS_ORDER as readonly string[]).includes(row.status)
      ? row.status
      : 'OTHER'
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return [...DETAIL_STATUS_ORDER, 'OTHER' as const]
    .filter((key) => (counts.get(key) ?? 0) > 0)
    .map((key) => {
      const count = counts.get(key) ?? 0
      return {
        key,
        label: key === 'OTHER' ? '其他状态' : statusLabel(key),
        count,
        percent: total === 0 ? 0 : (count / total) * 100,
        color: designToken(DETAIL_STATUS_TOKEN[key] ?? '--color-text-muted'),
      }
    })
})

/** 排行与空间只展示前 5，其余用一行汇总提示，保证卡片高度固定。 */
const TOP_LIMIT = 5
const rankingTop = computed(() => ranking.value.slice(0, TOP_LIMIT))
const spaceTop = computed(() => spaceUsage.value.slice(0, TOP_LIMIT))
const rankingRest = computed(() =>
  Math.max(0, ranking.value.length - TOP_LIMIT),
)
const spaceRest = computed(() =>
  Math.max(0, spaceUsage.value.length - TOP_LIMIT),
)

/** 空间引用合计：表格只展示前 5，合计行仍按全量累加，避免与全员对账口径不一致。 */
const spaceTotals = computed(() =>
  spaceUsage.value.reduce(
    (sum, row) => ({
      citations: sum.citations + row.citationCount,
      questions: sum.questions + row.questionCount,
    }),
    { citations: 0, questions: 0 },
  ),
)

const FILTER_DEBOUNCE_MS = 250
let filterTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let filterRequest: AbortController | undefined
let usersRequest: AbortController | undefined
let usersTimer: ReturnType<typeof globalThis.setTimeout> | undefined

async function load(): Promise<void> {
  const request = new AbortController()
  filterRequest = request
  const { signal } = request
  const selectedPeriod = period.value
  const selectedDate = date.value
  const selectedTarget = admin.value ? target.value || 'all' : undefined
  loading.value = true
  error.value = null
  try {
    const [summary, series, rank, spaces, questions] = await Promise.all([
      getUsage(selectedPeriod, selectedDate, selectedTarget, signal),
      getUsageSeries(selectedPeriod, selectedDate, selectedTarget, signal),
      admin.value
        ? getUsageRanking(selectedPeriod, selectedDate, signal)
        : Promise.resolve({ items: [] }),
      admin.value
        ? getUsageSpaces(selectedPeriod, selectedDate, signal)
        : Promise.resolve({ items: [] }),
      getUsageQuestions(selectedPeriod, selectedDate, selectedTarget, signal),
    ])
    if (signal.aborted) return
    usage.value = summary
    buckets.value = series.buckets
    ranking.value = rank.items
    spaceUsage.value = spaces.items
    detail.value = questions.items
    detailTotal.value = questions.total
  } catch (failure) {
    if (signal.aborted) return
    usage.value = null
    buckets.value = []
    ranking.value = []
    spaceUsage.value = []
    detail.value = []
    detailTotal.value = 0
    error.value = describeError(failure)
  } finally {
    if (filterRequest === request) {
      filterRequest = undefined
      loading.value = false
    }
  }
}

async function loadUsers(): Promise<void> {
  const request = new AbortController()
  usersRequest = request
  try {
    const query = userQuery.value.trim()
    if (query.length > FORM_LIMITS.search) return
    const page = await listUsers(query, { signal: request.signal })
    if (!request.signal.aborted) users.value = page.items
  } catch (failure) {
    if (!request.signal.aborted) usersError.value = describeError(failure)
  }
}

watch(
  userQuery,
  () => {
    globalThis.clearTimeout(usersTimer)
    usersRequest?.abort()
    users.value = []
    usersError.value = null
    usersTimer = globalThis.setTimeout(
      () => void loadUsers(),
      FILTER_DEBOUNCE_MS,
    )
  },
  { flush: 'sync' },
)

watch(target, (id) => {
  selectedUser.value =
    users.value.find((user) => user.userId === id) ??
    (selectedUser.value?.userId === id ? selectedUser.value : null)
})

function userLabel(userId: string): string {
  return (
    users.value.find((user) => user.userId === userId)?.displayName ??
    userId.slice(0, 8)
  )
}

function cancelFilterLoad(): void {
  globalThis.clearTimeout(filterTimer)
  filterRequest?.abort()
  filterRequest = undefined
}

watch(
  [period, date, target],
  () => {
    cancelFilterLoad()
    if (!validCalendarDate(date.value)) {
      loading.value = false
      error.value = '请选择有效的统计日期。'
      return
    }
    loading.value = true
    error.value = null
    filterTimer = globalThis.setTimeout(() => void load(), FILTER_DEBOUNCE_MS)
  },
  { flush: 'sync' },
)

onBeforeUnmount(() => {
  cancelFilterLoad()
  globalThis.clearTimeout(usersTimer)
  usersRequest?.abort()
})

onMounted(() => {
  void load()
  if (admin.value) void loadUsers()
})
</script>

<template>
  <div class="statistics">
    <PageHeader title="问答统计">
      <template #actions>
        <SelectMenu
          v-model="period"
          class="period-select"
          label="周期类型"
          :options="
            PERIODS.map((item) => ({ value: item.key, label: item.label }))
          "
        />
        <FormInput
          v-model="date"
          required
          class="date-input"
          type="date"
          aria-label="周期内日期"
        />
        <template v-if="admin">
          <FormInput
            v-model="userQuery"
            :maxlength="FORM_LIMITS.search"
            aria-label="搜索统计用户"
            placeholder="输入姓名或用户 ID"
          />
          <SelectMenu
            v-model="target"
            class="user-select"
            label="按用户筛选"
            :options="userOptions"
          />
        </template>
      </template>
    </PageHeader>

    <p v-if="usersError" role="alert">{{ usersError }}</p>

    <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>
    <p v-if="loading" class="bk-text-sm bk-muted">正在加载…</p>

    <template v-if="usage">
      <section class="metrics-strip" aria-label="统计指标" aria-live="polite">
        <div v-for="item in metrics" :key="item.label" class="metric">
          <span class="metric-label">{{ item.label }}</span>
          <span class="metric-value">{{ item.value }}</span>
          <span class="metric-meta">{{ item.delta || '&nbsp;' }}</span>
        </div>
      </section>

      <section class="bk-card overview-card" aria-label="提问趋势与状态">
        <div class="overview-chart">
          <div class="card-headline">
            <h3>提问与失败趋势</h3>
            <div class="legend">
              <span><i class="swatch-question"></i>提问次数</span>
              <span><i class="swatch-failure"></i>失败请求数</span>
            </div>
          </div>
          <BaseChart v-if="trendOption" :option="trendOption" height="135px" />
          <p v-else class="bk-text-sm bk-muted">该周期没有可展示的趋势数据。</p>
        </div>
        <div class="overview-status">
          <div class="card-headline">
            <h3>状态构成</h3>
            <span class="headline-note">最近 {{ detail.length }} 条</span>
          </div>
          <p v-if="detailStatusRows.length === 0" class="bk-text-sm bk-muted">
            该周期没有提问记录。
          </p>
          <div v-else class="status-bars">
            <div
              v-for="row in detailStatusRows"
              :key="row.key"
              class="status-row"
            >
              <span class="status-label">{{ row.label }}</span>
              <span class="status-track">
                <i
                  :style="{ width: `${row.percent}%`, background: row.color }"
                ></i>
              </span>
              <span class="status-count">{{ row.count }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="bk-card structure-card" aria-label="用户排行与空间指标">
        <div class="structure-col">
          <div class="card-headline">
            <h3>用户 Top 5（按提问）</h3>
            <span v-if="rankingRest > 0" class="headline-note">
              另有 {{ rankingRest }} 位
            </span>
          </div>
          <p v-if="rankingTop.length === 0" class="bk-text-sm bk-muted">
            该周期没有用量记录。
          </p>
          <table v-else class="bk-table compact-table">
            <thead>
              <tr>
                <th>用户</th>
                <th class="num">会话</th>
                <th class="num">提问</th>
                <th class="num">失败</th>
                <th class="num">Tokens</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rankingTop" :key="row.userId">
                <td class="cell-name">{{ row.displayName }}</td>
                <td class="num">{{ row.conversationCount }}</td>
                <td class="num">{{ row.questionCount }}</td>
                <td class="num">{{ row.failedQuestionCount }}</td>
                <td class="num">
                  {{ row.reportedTokenCount ?? '未上报' }}
                </td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>全员合计</td>
                <td class="num">{{ rankingTotal.conversations }}</td>
                <td class="num">{{ rankingTotal.questions }}</td>
                <td class="num">{{ rankingTotal.failures }}</td>
                <td class="num">—</td>
              </tr>
            </tfoot>
          </table>
        </div>
        <div class="structure-col">
          <div class="card-headline">
            <h3>空间 Top 5（按引用）</h3>
            <span v-if="spaceRest > 0" class="headline-note">
              另有 {{ spaceRest }} 个
            </span>
          </div>
          <p v-if="spaceTop.length === 0" class="bk-text-sm bk-muted">
            该周期没有空间被答案引用。
          </p>
          <table v-else class="bk-table compact-table">
            <thead>
              <tr>
                <th>空间</th>
                <th class="num">被引用</th>
                <th class="num">覆盖提问</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in spaceTop" :key="row.spaceId">
                <td class="cell-name">{{ row.name }}</td>
                <td class="num">{{ row.citationCount }}</td>
                <td class="num">{{ row.questionCount }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>全部空间</td>
                <td class="num">{{ spaceTotals.citations }}</td>
                <td class="num">{{ spaceTotals.questions }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </section>

      <section class="bk-card detail-card" aria-label="提问明细">
        <div class="detail-head">
          <h3>提问明细</h3>
          <span class="headline-note">
            最近 {{ detail.length }} 条 / 共 {{ detailTotal }} 条（北京时间）
          </span>
        </div>
        <p v-if="detail.length === 0" class="bk-text-sm bk-muted">
          该周期没有提问记录。
        </p>
        <div v-else class="detail-table">
          <table class="bk-table compact-table">
            <thead>
              <tr>
                <th>时间</th>
                <th v-if="admin">用户</th>
                <th>提问</th>
                <th>状态</th>
                <th class="num">Tokens</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in detail" :key="row.questionId">
                <td class="cell-time">{{ formatDateTime(row.acceptedAt) }}</td>
                <td v-if="admin">{{ userLabel(row.userId) }}</td>
                <td class="cell-question" :title="row.questionId">
                  <span class="cell-name">{{ row.text }}</span>
                </td>
                <td>
                  <span
                    :class="
                      row.status === 'SUCCEEDED'
                        ? 'bk-badge bk-badge-ok'
                        : row.status === 'FAILED'
                          ? 'bk-badge bk-badge-fail'
                          : 'bk-badge bk-badge-review'
                    "
                    >{{ statusLabel(row.status) }}</span
                  >
                </td>
                <td class="num">
                  {{ row.tokenTotal === null ? '未上报' : row.tokenTotal }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.statistics {
  display: grid;
  /* 显式 minmax(0, 1fr)：默认的 auto 列宽会被表格/筛选项的 min-content 撑出横向滚动。 */
  grid-template-columns: minmax(0, 1fr);
  gap: var(--space-2);
}

/*
 * 与工作台页头一致：筛选控件等高（30px）、垂直居中、靠右排列。
 * 控件固定宽度不参与压缩，只有搜索框吃掉剩余宽度，窄屏整体换行。
 */
:deep(.page-head > .actions) {
  display: flex;
  flex: 1 1 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  min-width: 0;
}

:deep(.page-head > .actions .bk-input),
:deep(.page-head > .actions .bk-select-trigger) {
  height: var(--button-small-height);
  min-height: var(--button-small-height);
  font-size: var(--button-small-text-size);
}

:deep(.page-head > .actions .bk-select-trigger) {
  padding: 0 var(--button-small-padding-inline);
}

:deep(.page-head > .actions > input.bk-input:not(.date-input)) {
  flex: 1 1 10rem;
  min-width: 8rem;
  max-width: 22rem;
}

.period-select {
  flex: none;
  width: 120px;
}

.date-input {
  flex: none;
  width: 170px;
}

.user-select {
  flex: none;
  width: 170px;
}

/*
 * KPI 指标带：一条分隔带承载四项，不再各占一张大卡。
 * 分隔线用 1px 边框而不是卡片投影，避免四块内容视觉上"各说各话"。
 */
.metrics-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
}

.metric {
  display: grid;
  gap: 2px;
  padding: 4px var(--space-3);
  min-width: 0;
}

.metric + .metric {
  border-left: 1px solid var(--color-divider);
}

.metric-label {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.metric-value {
  font-size: var(--text-lg);
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.metric-meta {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

/* 主图与状态构成同排：左 2/3 时间趋势，右 1/3 状态堆叠条，只保留一张图。 */
.overview-card {
  display: grid;
  grid-template-columns: minmax(0, 2.1fr) minmax(0, 1fr);
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
}

.card-headline {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.card-headline h3,
.detail-head h3 {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
}

.headline-note {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  white-space: nowrap;
}

.overview-chart {
  min-width: 0;
}

.overview-status {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding-left: var(--space-4);
  border-left: 1px solid var(--color-divider);
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  font-size: var(--text-xs);
  color: var(--color-text-muted);
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

.status-bars {
  display: grid;
  gap: var(--space-2);
}

.status-row {
  display: grid;
  grid-template-columns: 4.5em minmax(0, 1fr) 2.5em;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-xs);
}

.status-label {
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.status-track {
  height: 6px;
  background: var(--color-divider);
  border-radius: var(--radius-pill);
  overflow: hidden;
}

.status-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.status-count {
  color: var(--color-text);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* 排行与空间指标并排，各取 Top 5，卡片高度固定。 */
.structure-card {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  padding: var(--space-2) var(--space-4);
}

.structure-col {
  min-width: 0;
}

.compact-table {
  width: 100%;
  border-collapse: collapse;
}

.compact-table th,
.compact-table td {
  padding: 3px var(--space-2);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.compact-table thead th {
  padding-top: 4px;
  padding-bottom: 4px;
}

.cell-name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-sub {
  display: block;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.cell-time {
  white-space: nowrap;
  color: var(--color-text-secondary);
}

.cell-question {
  max-width: 460px;
}

/* 明细平铺：表头吸顶、表内滚动查看最近 200 条，页面本身不再拉长。 */
.detail-card {
  padding: var(--space-2) var(--space-4);
}

.detail-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: 6px;
}

.detail-table {
  max-height: 200px;
  overflow: auto;
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-sm);
}

.detail-table td {
  padding-top: 4px;
  padding-bottom: 4px;
}

.detail-table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding-top: 6px;
  padding-bottom: 6px;
  background: var(--color-surface-raised);
}

@media (max-width: 1100px) {
  .overview-card,
  .structure-card {
    grid-template-columns: minmax(0, 1fr);
  }

  .overview-status {
    padding-top: var(--space-3);
    padding-left: 0;
    border-top: 1px solid var(--color-divider);
    border-left: none;
  }
}

/* 窄屏：KPI 两列、其余单列，筛选控件收整行避免撑宽页面。 */
@media (max-width: 640px) {
  .statistics {
    min-width: 0;
  }

  :deep(.page-head) {
    flex-wrap: wrap;
  }

  :deep(.page-head > .actions) {
    width: 100%;
    justify-content: flex-start;
  }

  :deep(.page-head > .actions > *) {
    flex: 1 1 auto;
    min-width: 0;
  }

  .period-select,
  .date-input,
  .user-select {
    width: auto;
  }

  .metrics-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric + .metric {
    border-left: none;
  }

  .metric:nth-child(even) {
    border-left: 1px solid var(--color-divider);
  }

  .metric:nth-child(n + 3) {
    border-top: 1px solid var(--color-divider);
  }
}
</style>
