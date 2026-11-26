<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import DataTable from '../components/DataTable.vue'
import DrawerPanel from '../components/DrawerPanel.vue'
import FormCheckbox from '../components/FormCheckbox.vue'
import FormInput from '../components/FormInput.vue'
import FormTextarea from '../components/FormTextarea.vue'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import PaginationBar from '../components/PaginationBar.vue'
import ScoreMeter from '../components/ScoreMeter.vue'
import SelectMenu from '../components/SelectMenu.vue'
import CleaningUploadDialog from '../components/CleaningUploadDialog.vue'
import CleaningItemDrawer from '../components/CleaningItemDrawer.vue'
import CleaningPromotionDialog from '../components/CleaningPromotionDialog.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { usePagedList } from '../pagination'
import {
  getCleaningBatch,
  getCleaningItem,
  isCleaningBatchActive,
  listCleaningBatches,
  listCleaningItems,
  rescoreCleaningBatch,
  reviewCleaningItems,
  type CleaningBatch,
  type CleaningBatchDetail,
  type CleaningBatchStatus,
  type CleaningItem,
  type CleaningItemDetail,
  type CleaningItemStatus,
  type CleaningPromotionResult,
  type CleaningReviewDecision,
} from '../api/cleaning'
import { listSpaces, type Space } from '../api/knowledge'
import {
  beijingToday,
  cleaningBatchLabel,
  cleaningBatchTone,
  cleaningFileErrorHint,
  cleaningItemLabel,
  cleaningItemTone,
  cleaningSourceStateLabel,
  describeError,
  formatBytes,
  formatDateTime,
} from '../api/display'
import { isApiError } from '../api/http'
import { FORM_LIMITS } from '../formValidation'
import { pollUntil } from '../api/polling'

/**
 * 数据清洗页：全局清洗草稿箱 + 批次列表。
 *
 * 草稿箱是管理员的主要工作面：筛选、审核、流转；批次页签用于确认上传结果与失败原因。
 * 页面不缓存业务状态，写操作完成后统一回到服务端重新读取。
 */
type TabKey = 'inbox' | 'batches'

interface Notice {
  tone: string
  text: string
  action?: { label: string; path: string }
}

/** 名称筛选去抖：避免每个字符都发一次列表请求。 */
const FILTER_DELAY_MS = 250
/** 批次状态轮询间隔与总预算：清洗通常几十秒内出结果。 */
const POLL_DELAY_MS = 2000
const POLL_TIMEOUT_MS = 60000
const RECENT_BATCH_LIMIT = 60

const ITEM_STATUS_OPTIONS: readonly {
  label: string
  value: CleaningItemStatus | ''
}[] = [
  { label: '全部状态', value: '' },
  { label: '待审核', value: 'PENDING_REVIEW' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已流转', value: 'PROMOTED' },
]

const BATCH_STATUS_OPTIONS: readonly {
  label: string
  value: CleaningBatchStatus | ''
}[] = [
  { label: '全部状态', value: '' },
  { label: '排队中', value: 'QUEUED' },
  { label: '提取中', value: 'EXTRACTING' },
  { label: '打分中', value: 'SCORING' },
  { label: '已完成', value: 'READY' },
  { label: '部分成功', value: 'PARTIAL' },
  { label: '处理失败', value: 'FAILED' },
]

const MIN_SCORE_OPTIONS = [
  { label: '不限分数', value: '0' },
  { label: '≥ 60 分', value: '60' },
  { label: '≥ 70 分', value: '70' },
  { label: '≥ 80 分', value: '80' },
  { label: '≥ 90 分', value: '90' },
] as const

const router = useRouter()

const tab = ref<TabKey>('inbox')
const initialLoading = ref(true)
const loadError = ref<string | null>(null)
const notice = ref<Notice | null>(null)
const busy = ref(false)

const spaces = ref<Space[]>([])
const recentBatches = ref<CleaningBatch[]>([])
const pendingCount = ref(0)

const itemStatus = ref<CleaningItemStatus | ''>('PENDING_REVIEW')
const batchFilter = ref('')
const spaceFilter = ref('')
const minScoreFilter = ref('0')
const keyword = ref('')
let filterTimer: ReturnType<typeof globalThis.setTimeout> | undefined

const batchStatus = ref<CleaningBatchStatus | ''>('')

/**
 * 草稿箱列表：服务端筛选与分页。
 * 最低分以字符串存放（下拉组件只接受字符串），请求时再转成数字。
 */
const {
  items,
  page: itemsPage,
  pageSize: itemsPageSize,
  total: itemsTotal,
  loading: itemsLoading,
  error: itemsError,
  reload: reloadItems,
  reset: resetItems,
} = usePagedList<CleaningItem>(
  (query, signal) =>
    listCleaningItems(
      {
        status: itemStatus.value,
        batchId: batchFilter.value,
        targetSpaceId: spaceFilter.value,
        minScore: Number(minScoreFilter.value),
        q: keyword.value.trim(),
        ...query,
      },
      { signal },
    ),
  { describeError: describe },
)

const {
  items: batches,
  page: batchesPage,
  pageSize: batchesPageSize,
  total: batchesTotal,
  loading: batchesLoading,
  error: batchesError,
  reload: reloadBatches,
  reset: resetBatches,
} = usePagedList<CleaningBatch>(
  (query, signal) =>
    listCleaningBatches({ status: batchStatus.value }, { ...query, signal }),
  { describeError: describe },
)

const batchOptions = computed(() => [
  { label: '全部批次', value: '' },
  ...recentBatches.value.map((batch) => ({
    label: `${batch.name} · ${shortId(batch.batchId)}`,
    value: batch.batchId,
  })),
])

const spaceOptions = computed(() => [
  { label: '全部空间', value: '' },
  ...spaces.value.map((space) => ({ label: space.name, value: space.spaceId })),
])

/* ---------- 多选：只在当前页内生效 ---------- */
const selectedIds = ref<string[]>([])
const selection = computed(() =>
  items.value.filter((item) => selectedIds.value.includes(item.itemId)),
)
const pageAllSelected = computed(
  () => items.value.length > 0 && selection.value.length === items.value.length,
)
const pageSomeSelected = computed(
  () => selection.value.length > 0 && !pageAllSelected.value,
)
const selectionAllReviewable = computed(
  () =>
    selection.value.length > 0 &&
    selection.value.every((item) => item.status === 'PENDING_REVIEW'),
)
const selectionAllApproved = computed(
  () =>
    selection.value.length > 0 &&
    selection.value.every((item) => item.status === 'APPROVED'),
)

/* ---------- 条目详情抽屉 ---------- */
const drawerOpen = ref(false)
const detail = ref<CleaningItemDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
let detailRequest: AbortController | undefined

/* ---------- 批次详情抽屉 ---------- */
const batchDrawerOpen = ref(false)
const batchDetail = ref<CleaningBatchDetail | null>(null)
const batchDetailLoading = ref(false)
const batchDetailError = ref<string | null>(null)
let batchRequest: AbortController | undefined
let pollController: AbortController | undefined
const pollingBatchId = ref<string | null>(null)

/* ---------- 弹窗 ---------- */
const uploadOpen = ref(false)
const reviewDialog = ref<{
  ids: string[]
  decision: CleaningReviewDecision
} | null>(null)
const reviewReason = ref('')
const reviewError = ref<string | null>(null)
const promotionItems = ref<CleaningItem[]>([])

const promotionDefaultName = computed(() => {
  // 默认名取「正在流转的条目」所属批次，而不是页面勾选集：
  // 行内流转不会先勾选，用 selection 会把批次名取成兜底文案。
  const source = promotionItems.value[0]?.batchName ?? '清洗知识'
  return `清洗知识-${source}-${beijingToday().replaceAll('-', '')}`
})

/** 8 位短 ID：批次与文档版本在界面上统一用短号，避免整列被 UUID 撑开。 */
function shortId(value: string | null | undefined): string {
  return value ? value.slice(0, 8) : '—'
}

/** 统一错误文案：状态冲突时提示刷新，其余走公共映射。 */
function describe(failure: unknown): string {
  if (isApiError(failure, 409, 'STATE_CONFLICT')) {
    return describeError(failure, '条目状态已变化，请刷新后重试。')
  }
  return describeError(failure)
}

function spaceName(spaceId: string | null): string {
  if (!spaceId) return '未指定空间'
  return (
    spaces.value.find((space) => space.spaceId === spaceId)?.name ??
    shortId(spaceId)
  )
}

/** 条目行的小字：来源路径 · 批次 · 创建时间。 */
function itemMeta(item: CleaningItem): string {
  return [
    item.sourcePath ?? '未记录来源',
    `${item.batchName} · ${shortId(item.batchId)}`,
    formatDateTime(item.createdAt),
  ].join(' · ')
}

/* ---------- 数据加载 ---------- */
async function loadSpaces(): Promise<void> {
  try {
    const page = await listSpaces()
    spaces.value = page.items
  } catch (failure) {
    loadError.value = describe(failure)
  }
}

/** 批次下拉只取最近一批，够筛选用，不把全部历史塞进选择器。 */
async function loadRecentBatches(): Promise<void> {
  try {
    const page = await listCleaningBatches({}, { limit: RECENT_BATCH_LIMIT })
    recentBatches.value = page.items
  } catch {
    // 下拉数据失败不阻塞主列表；用户仍可用状态与关键词筛选。
  }
}

/** 页签上的待审核数量：单独取一次 total，不依赖当前页数据。 */
async function refreshPendingCount(): Promise<void> {
  try {
    const page = await listCleaningItems(
      { status: 'PENDING_REVIEW' },
      { limit: 1 },
    )
    pendingCount.value = page.total
  } catch {
    pendingCount.value = 0
  }
}

async function loadAll(): Promise<void> {
  initialLoading.value = true
  loadError.value = null
  try {
    await Promise.all([
      loadSpaces(),
      loadRecentBatches(),
      refreshPendingCount(),
    ])
    await Promise.all([reloadItems(), reloadBatches()])
  } finally {
    initialLoading.value = false
  }
}

/* ---------- 选择操作 ---------- */
function isSelected(itemId: string): boolean {
  return selectedIds.value.includes(itemId)
}

function toggleItem(itemId: string): void {
  selectedIds.value = isSelected(itemId)
    ? selectedIds.value.filter((id) => id !== itemId)
    : [...selectedIds.value, itemId]
}

/** 全选只作用于当前页：跨页操作容易把看不见的条目一起改掉。 */
function toggleAll(): void {
  selectedIds.value = pageAllSelected.value
    ? []
    : items.value.map((item) => item.itemId)
}

function clearSelection(): void {
  selectedIds.value = []
}

/* ---------- 条目详情 ---------- */
async function openItem(item: CleaningItem): Promise<void> {
  drawerOpen.value = true
  detail.value = null
  detailError.value = null
  detailLoading.value = true
  detailRequest?.abort()
  const controller = new AbortController()
  detailRequest = controller
  try {
    const loaded = await getCleaningItem(item.itemId, controller.signal)
    if (controller.signal.aborted) return
    detail.value = loaded
  } catch (failure) {
    if (controller.signal.aborted) return
    detailError.value = describe(failure)
  } finally {
    if (!controller.signal.aborted) detailLoading.value = false
  }
}

function closeDrawer(): void {
  drawerOpen.value = false
  detailRequest?.abort()
  detail.value = null
  detailError.value = null
}

/** 审核或流转后重新读取当前条目：状态与记录都会变化。 */
async function refreshDetail(): Promise<void> {
  const current = detail.value
  if (!current) return
  try {
    detail.value = await getCleaningItem(current.itemId)
  } catch (failure) {
    if (isApiError(failure, 404, 'RESOURCE_NOT_FOUND')) {
      closeDrawer()
      return
    }
    detailError.value = describe(failure)
  }
}

/** 抽屉底部的审核动作：只针对当前打开的条目。 */
function reviewCurrent(decision: CleaningReviewDecision): void {
  const current = detail.value
  if (!current) return
  openReview([current.itemId], decision)
}

/** 抽屉底部的流转动作：只针对当前打开的条目。 */
function promoteCurrent(): void {
  const current = detail.value
  if (!current) return
  openPromotion([current])
}

/* ---------- 批次详情 ---------- */
async function openBatch(batch: CleaningBatch): Promise<void> {
  batchDrawerOpen.value = true
  batchDetail.value = null
  batchDetailError.value = null
  batchDetailLoading.value = true
  batchRequest?.abort()
  const controller = new AbortController()
  batchRequest = controller
  try {
    const loaded = await getCleaningBatch(batch.batchId, controller.signal)
    if (controller.signal.aborted) return
    batchDetail.value = loaded
  } catch (failure) {
    if (controller.signal.aborted) return
    batchDetailError.value = describe(failure)
  } finally {
    if (!controller.signal.aborted) batchDetailLoading.value = false
  }
}

function closeBatchDrawer(): void {
  batchDrawerOpen.value = false
  batchRequest?.abort()
  batchDetail.value = null
  batchDetailError.value = null
}

/** 在列表里原地替换一行，轮询期间不整表闪烁。 */
function updateBatchRow(next: CleaningBatch): void {
  batches.value = batches.value.map((batch) =>
    batch.batchId === next.batchId ? next : batch,
  )
}

/** 上传后跟踪这一批的状态，完成即刷新草稿箱与待审数量。 */
function startBatchPolling(batchId: string): void {
  pollController?.abort()
  const controller = new AbortController()
  pollController = controller
  pollingBatchId.value = batchId
  void (async () => {
    try {
      const finished = await pollUntil(
        (signal) => getCleaningBatch(batchId, signal),
        {
          delayMs: POLL_DELAY_MS,
          timeoutMs: POLL_TIMEOUT_MS,
          waitBeforeFirst: true,
          signal: controller.signal,
          isComplete: (value) => !isCleaningBatchActive(value.status),
          onValue: (value) => {
            updateBatchRow(value)
          },
        },
      )
      if (controller.signal.aborted) return
      notice.value = finished
        ? {
            tone: 'bk-notice bk-notice-ok',
            text: `批次「${finished.name}」已完成，结果已进入清洗草稿箱。`,
          }
        : {
            tone: 'bk-notice bk-notice-warn',
            text: '批次仍在处理中，可稍后点「刷新」查看结果。',
          }
      await Promise.all([
        reloadBatches(),
        reloadItems(),
        refreshPendingCount(),
        loadRecentBatches(),
      ])
    } catch (failure) {
      if (controller.signal.aborted) return
      notice.value = {
        tone: 'bk-notice bk-notice-warn',
        text: `批次状态查询中断：${describe(failure)}`,
      }
    } finally {
      if (pollingBatchId.value === batchId) pollingBatchId.value = null
    }
  })()
}

/** 重新打分：网关恢复后的补偿入口，成功后继续轮询批次状态。 */
async function rescore(batch: CleaningBatchDetail): Promise<void> {
  busy.value = true
  batchDetailError.value = null
  try {
    const result = await rescoreCleaningBatch(batch.batchId)
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已重新排队打分，待处理 ${String(result.enqueued)} 条。`,
    }
    startBatchPolling(batch.batchId)
  } catch (failure) {
    batchDetailError.value = describe(failure)
  } finally {
    busy.value = false
  }
}

/* ---------- 上传 ---------- */
async function onUploadAccepted(batch: CleaningBatch): Promise<void> {
  uploadOpen.value = false
  tab.value = 'batches'
  notice.value = {
    tone: 'bk-notice bk-notice-ok',
    text: `批次「${batch.name}」已受理，正在清洗。`,
  }
  await Promise.all([reloadBatches(), loadRecentBatches()])
  startBatchPolling(batch.batchId)
}

/* ---------- 审核 ---------- */
function openReview(ids: string[], decision: CleaningReviewDecision): void {
  if (ids.length === 0) return
  reviewDialog.value = { ids, decision }
  reviewReason.value = ''
  reviewError.value = null
}

function closeReview(): void {
  if (busy.value) return
  reviewDialog.value = null
  reviewReason.value = ''
  reviewError.value = null
}

async function submitReview(): Promise<void> {
  const dialog = reviewDialog.value
  if (!dialog) return
  const reason = reviewReason.value.trim()
  if (dialog.decision === 'REJECT' && reason === '') {
    reviewError.value = '驳回必须填写具体原因。'
    return
  }
  busy.value = true
  reviewError.value = null
  try {
    await reviewCleaningItems(dialog.ids, dialog.decision, reason)
    const verb = dialog.decision === 'APPROVE' ? '通过' : '驳回'
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已${verb} ${String(dialog.ids.length)} 条。`,
    }
    reviewDialog.value = null
    clearSelection()
    await Promise.all([
      reloadItems(),
      refreshPendingCount(),
      loadRecentBatches(),
      reloadBatches(),
    ])
    if (drawerOpen.value) await refreshDetail()
  } catch (failure) {
    reviewError.value = describe(failure)
  } finally {
    busy.value = false
  }
}

/* ---------- 流转 ---------- */
function openPromotion(targets: CleaningItem[]): void {
  const approved = targets.filter((item) => item.status === 'APPROVED')
  if (approved.length === 0) {
    notice.value = {
      tone: 'bk-notice bk-notice-warn',
      text: '只有已通过的条目可以流转到知识库。',
    }
    return
  }
  promotionItems.value = approved
}

async function onPromoted(result: CleaningPromotionResult): Promise<void> {
  promotionItems.value = []
  notice.value = {
    tone: 'bk-notice bk-notice-ok',
    text: `已生成文档「${result.documentName}」并加入空间草稿（第 ${String(result.versionNumber)} 版），请在发布管理提交变更审核。`,
    action: { label: '去发布管理', path: '/publishing' },
  }
  clearSelection()
  closeDrawer()
  await Promise.all([
    reloadItems(),
    reloadBatches(),
    refreshPendingCount(),
    loadRecentBatches(),
  ])
}

function go(path: string): void {
  void router.push(path)
}

/* ---------- 筛选与页签 ---------- */
function cancelFilterTimer(): void {
  globalThis.clearTimeout(filterTimer)
}

function reloadInbox(): void {
  clearSelection()
  void resetItems()
}

watch([itemStatus, batchFilter, spaceFilter, minScoreFilter], () => {
  cancelFilterTimer()
  reloadInbox()
})

watch(keyword, () => {
  cancelFilterTimer()
  filterTimer = globalThis.setTimeout(reloadInbox, FILTER_DELAY_MS)
})

watch(batchStatus, () => {
  void resetBatches()
})

watch([itemsPage, itemsPageSize], () => {
  clearSelection()
})

watch(tab, (next) => {
  if (next === 'batches') {
    void reloadBatches()
    return
  }
  void reloadItems()
  void refreshPendingCount()
})

onMounted(() => {
  void loadAll()
})

onBeforeUnmount(() => {
  cancelFilterTimer()
  detailRequest?.abort()
  batchRequest?.abort()
  pollController?.abort()
})
</script>

<template>
  <div class="cleaning">
    <PageHeader
      title="数据清洗"
      description="上传文件夹或压缩包，清洗成知识条目；审核通过后流转到知识库。"
    >
      <template #actions>
        <button
          class="bk-btn bk-btn-neutral"
          type="button"
          :disabled="initialLoading || busy"
          @click="loadAll"
        >
          刷新
        </button>
        <button
          class="bk-btn bk-btn-primary"
          type="button"
          :disabled="busy"
          @click="uploadOpen = true"
        >
          上传原始数据
        </button>
      </template>
    </PageHeader>

    <p v-if="loadError" class="bk-notice bk-notice-fail" role="alert">
      {{ loadError }}
    </p>
    <p v-if="notice" :class="notice.tone" role="status">
      {{ notice.text }}
      <button
        v-if="notice.action"
        class="bk-btn bk-btn-sm bk-btn-info notice-action"
        type="button"
        @click="go(notice.action.path)"
      >
        {{ notice.action.label }}
      </button>
    </p>

    <div class="bk-tabs" role="tablist" aria-label="数据清洗视图">
      <button
        class="bk-tab"
        type="button"
        role="tab"
        :aria-selected="tab === 'inbox'"
        @click="tab = 'inbox'"
      >
        清洗草稿箱<template v-if="pendingCount > 0"
          >（{{ String(pendingCount) }}）</template
        >
      </button>
      <button
        class="bk-tab"
        type="button"
        role="tab"
        :aria-selected="tab === 'batches'"
        @click="tab = 'batches'"
      >
        清洗批次
      </button>
    </div>

    <template v-if="tab === 'inbox'">
      <div class="filter-bar">
        <FormInput
          v-model="keyword"
          class="name-filter"
          type="search"
          :maxlength="FORM_LIMITS.search"
          aria-label="筛选条目标题或正文"
          placeholder="筛选条目标题或正文…"
        />
        <SelectMenu
          v-model="itemStatus"
          class="status-filter"
          label="筛选条目状态"
          :options="ITEM_STATUS_OPTIONS"
        />
        <SelectMenu
          v-model="batchFilter"
          class="batch-filter"
          label="筛选来源批次"
          :options="batchOptions"
        />
        <SelectMenu
          v-model="spaceFilter"
          class="space-filter"
          label="筛选目标空间"
          :options="spaceOptions"
        />
        <SelectMenu
          v-model="minScoreFilter"
          class="score-filter"
          label="筛选最低总分"
          :options="MIN_SCORE_OPTIONS"
        />
        <span class="bk-grow"></span>
        <span class="bk-text-xs bk-muted" role="status">
          {{
            initialLoading || itemsLoading
              ? '正在加载…'
              : `共 ${String(itemsTotal)} 条${
                  keyword.trim() ||
                  itemStatus !== '' ||
                  batchFilter !== '' ||
                  spaceFilter !== '' ||
                  minScoreFilter !== '0'
                    ? '（已筛选）'
                    : ''
                }`
          }}
        </span>
      </div>

      <p v-if="itemsError" class="bk-notice bk-notice-fail" role="alert">
        {{ itemsError }}
      </p>

      <DataTable
        label="清洗草稿箱"
        :rows="itemsLoading || itemsError ? undefined : items.length"
        empty-text="清洗草稿箱里还没有条目"
        empty-hint="上传文件夹或压缩包后，清洗结果会进入这里等待审核。"
      >
        <template #empty-actions>
          <button
            class="bk-btn bk-btn-primary"
            type="button"
            :disabled="busy"
            @click="uploadOpen = true"
          >
            上传原始数据
          </button>
        </template>
        <table class="bk-table cleaning-table">
          <thead>
            <tr>
              <th class="check-col">
                <FormCheckbox
                  :model-value="pageAllSelected"
                  :indeterminate="pageSomeSelected"
                  accessible-name="全选本页条目"
                  :disabled="items.length === 0"
                  @update:model-value="toggleAll"
                />
              </th>
              <th>条目</th>
              <th>总分</th>
              <th>状态</th>
              <th>目标空间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="itemsLoading">
              <td colspan="6" class="empty-cell">正在加载…</td>
            </tr>
            <template v-else>
              <tr
                v-for="item in items"
                :key="item.itemId"
                :class="{ selected: isSelected(item.itemId) }"
              >
                <td class="check-col">
                  <FormCheckbox
                    :model-value="isSelected(item.itemId)"
                    :accessible-name="`选择条目：${item.title}`"
                    @update:model-value="toggleItem(item.itemId)"
                  />
                </td>
                <td>
                  <button
                    class="link-button"
                    type="button"
                    @click="openItem(item)"
                  >
                    <span class="bk-row-title">{{ item.title }}</span>
                  </button>
                  <div class="bk-row-sub">{{ itemMeta(item) }}</div>
                </td>
                <td>
                  <ScoreMeter
                    :score="item.totalScore"
                    :rule-score="item.ruleScore"
                    :model-score="item.modelScore"
                  />
                </td>
                <td class="nowrap">
                  <span :class="cleaningItemTone(item.status)">{{
                    cleaningItemLabel(item.status)
                  }}</span>
                </td>
                <td class="nowrap">{{ item.targetSpaceName ?? '未指定' }}</td>
                <td>
                  <div class="row-actions">
                    <button
                      class="bk-btn bk-btn-sm bk-btn-info"
                      type="button"
                      @click="openItem(item)"
                    >
                      查看
                    </button>
                    <button
                      v-if="item.status === 'PENDING_REVIEW'"
                      class="bk-btn bk-btn-sm bk-btn-success"
                      type="button"
                      :disabled="busy"
                      @click="openReview([item.itemId], 'APPROVE')"
                    >
                      通过
                    </button>
                    <button
                      v-if="item.status === 'PENDING_REVIEW'"
                      class="bk-btn bk-btn-sm bk-btn-danger"
                      type="button"
                      :disabled="busy"
                      @click="openReview([item.itemId], 'REJECT')"
                    >
                      驳回
                    </button>
                    <button
                      v-if="item.status === 'APPROVED'"
                      class="bk-btn bk-btn-sm bk-btn-primary"
                      type="button"
                      :disabled="busy"
                      @click="openPromotion([item])"
                    >
                      流转
                    </button>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </DataTable>

      <div v-if="selection.length > 0" class="selection-bar" role="status">
        <span class="selection-count"
          >已选 {{ String(selection.length) }} 条</span
        >
        <button
          class="bk-btn bk-btn-sm bk-btn-success"
          type="button"
          :disabled="busy || !selectionAllReviewable"
          :title="
            selectionAllReviewable ? undefined : '只有待审核的条目可以审核'
          "
          @click="
            openReview(
              selection.map((entry) => entry.itemId),
              'APPROVE',
            )
          "
        >
          通过
        </button>
        <button
          class="bk-btn bk-btn-sm bk-btn-danger"
          type="button"
          :disabled="busy || !selectionAllReviewable"
          :title="
            selectionAllReviewable ? undefined : '只有待审核的条目可以审核'
          "
          @click="
            openReview(
              selection.map((entry) => entry.itemId),
              'REJECT',
            )
          "
        >
          驳回
        </button>
        <button
          class="bk-btn bk-btn-sm bk-btn-primary"
          type="button"
          :disabled="busy || !selectionAllApproved"
          :title="selectionAllApproved ? undefined : '只有已通过的条目可以流转'"
          @click="openPromotion(selection)"
        >
          流转到知识库
        </button>
        <button
          class="bk-btn bk-btn-sm bk-btn-ghost"
          type="button"
          :disabled="busy"
          @click="clearSelection"
        >
          清除选择
        </button>
      </div>

      <PaginationBar
        v-model:page="itemsPage"
        v-model:page-size="itemsPageSize"
        :total="itemsTotal"
        unit="条"
        label="清洗草稿箱分页"
      />
    </template>

    <template v-else>
      <div class="filter-bar">
        <SelectMenu
          v-model="batchStatus"
          class="status-filter"
          label="筛选批次状态"
          :options="BATCH_STATUS_OPTIONS"
        />
        <span class="bk-grow"></span>
        <span class="bk-text-xs bk-muted" role="status">
          {{
            batchesLoading
              ? '正在加载…'
              : `共 ${String(batchesTotal)} 个批次${
                  batchStatus !== '' ? '（已筛选）' : ''
                }`
          }}
          <template v-if="pollingBatchId"> · 正在刷新状态… </template>
        </span>
      </div>

      <p v-if="batchesError" class="bk-notice bk-notice-fail" role="alert">
        {{ batchesError }}
      </p>

      <DataTable
        label="清洗批次"
        :rows="batchesLoading || batchesError ? undefined : batches.length"
        empty-text="还没有清洗批次"
        empty-hint="上传文件夹或压缩包后，这里会显示每批的处理结果。"
      >
        <template #empty-actions>
          <button
            class="bk-btn bk-btn-primary"
            type="button"
            :disabled="busy"
            @click="uploadOpen = true"
          >
            上传原始数据
          </button>
        </template>
        <table class="bk-table">
          <thead>
            <tr>
              <th>批次</th>
              <th>状态</th>
              <th class="num">文件 / 失败</th>
              <th class="num">条目 / 去重</th>
              <th>平均分</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="batchesLoading">
              <td colspan="7" class="empty-cell">正在加载…</td>
            </tr>
            <template v-else>
              <tr v-for="batch in batches" :key="batch.batchId">
                <td>
                  <div class="bk-row-title">{{ batch.name }}</div>
                  <div class="bk-row-sub">
                    {{ shortId(batch.batchId) }}
                    <template v-if="batch.targetSpaceId">
                      · {{ spaceName(batch.targetSpaceId) }}
                    </template>
                    <template v-if="batch.skippedCount > 0">
                      · 已忽略 {{ String(batch.skippedCount) }}
                    </template>
                  </div>
                </td>
                <td class="nowrap">
                  <span :class="cleaningBatchTone(batch.status)">{{
                    cleaningBatchLabel(batch.status)
                  }}</span>
                  <span
                    v-if="pollingBatchId === batch.batchId"
                    class="bk-text-xs bk-muted"
                    >· 刷新中</span
                  >
                </td>
                <td class="num">
                  {{ String(batch.fileCount) }} /
                  <span :class="{ 'text-danger': batch.failedFileCount > 0 }">{{
                    String(batch.failedFileCount)
                  }}</span>
                </td>
                <td class="num">
                  {{ String(batch.itemCount) }} /
                  {{ String(batch.duplicateCount) }}
                </td>
                <td>
                  <ScoreMeter
                    v-if="batch.averageScore !== null"
                    :score="batch.averageScore"
                    :show-breakdown="false"
                    :show-bar="false"
                  />
                  <span v-else class="bk-muted">—</span>
                </td>
                <td class="num nowrap">
                  {{ formatDateTime(batch.createdAt) }}
                </td>
                <td>
                  <button
                    class="bk-btn bk-btn-sm bk-btn-info"
                    type="button"
                    @click="openBatch(batch)"
                  >
                    查看
                  </button>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </DataTable>

      <PaginationBar
        v-model:page="batchesPage"
        v-model:page-size="batchesPageSize"
        :total="batchesTotal"
        unit="个批次"
        label="清洗批次分页"
      />
    </template>

    <!-- 上传弹窗 -->
    <OverlayTransition>
      <CleaningUploadDialog
        v-if="uploadOpen"
        :spaces="spaces"
        @close="uploadOpen = false"
        @accepted="onUploadAccepted"
      />
    </OverlayTransition>

    <!-- 条目详情抽屉 -->
    <OverlayTransition variant="drawer">
      <CleaningItemDrawer
        v-if="drawerOpen"
        :item="detail"
        :loading="detailLoading"
        :error="detailError"
        :busy="busy"
        @close="closeDrawer"
        @review="reviewCurrent"
        @promote="promoteCurrent"
      />
    </OverlayTransition>

    <!-- 批次详情抽屉 -->
    <OverlayTransition variant="drawer">
      <DrawerPanel
        v-if="batchDrawerOpen"
        :title="batchDetail ? `批次：${batchDetail.name}` : '批次详情'"
        width="720px"
        @close="closeBatchDrawer"
      >
        <template v-if="batchDetail" #hint>
          {{ shortId(batchDetail.batchId) }} ·
          {{ formatDateTime(batchDetail.createdAt) }}
        </template>

        <p v-if="batchDetailLoading" class="bk-text-sm bk-muted">
          正在读取批次…
        </p>
        <p
          v-else-if="batchDetailError"
          class="bk-notice bk-notice-fail"
          role="alert"
        >
          {{ batchDetailError }}
        </p>

        <template v-else-if="batchDetail">
          <section class="batch-summary" aria-label="批次概况">
            <span :class="cleaningBatchTone(batchDetail.status)">{{
              cleaningBatchLabel(batchDetail.status)
            }}</span>
            <span class="bk-text-sm bk-muted">
              文件 {{ String(batchDetail.fileCount) }} 个（失败
              {{ String(batchDetail.failedFileCount) }}） · 条目
              {{ String(batchDetail.itemCount) }} 条（去重
              {{ String(batchDetail.duplicateCount) }}） · 已忽略
              {{ String(batchDetail.skippedCount) }}
            </span>
            <span class="bk-text-xs bk-muted">
              {{ batchDetail.modelScoring ? '规则分 + 模型分' : '仅规则分' }}
              <template v-if="batchDetail.targetSpaceId">
                · 目标空间 {{ spaceName(batchDetail.targetSpaceId) }}
              </template>
            </span>
          </section>

          <div class="bk-table-wrap table-in-card table-compact">
            <table class="bk-table">
              <thead>
                <tr>
                  <th>来源文件</th>
                  <th class="num">大小</th>
                  <th>状态</th>
                  <th class="num">条目</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="batchDetail.sourceFiles.length === 0">
                  <td colspan="5" class="empty-cell">没有来源文件。</td>
                </tr>
                <tr
                  v-for="file in batchDetail.sourceFiles"
                  :key="file.sourceFileId"
                >
                  <td>
                    <div class="bk-row-title source-path">
                      {{ file.relativePath }}
                    </div>
                  </td>
                  <td class="num nowrap">{{ formatBytes(file.sizeBytes) }}</td>
                  <td class="nowrap">
                    {{ cleaningSourceStateLabel(file.state) }}
                  </td>
                  <td class="num">{{ String(file.recordCount) }}</td>
                  <td class="bk-text-xs">
                    {{ cleaningFileErrorHint(file.errorCode) || '—' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <template #actions>
          <button
            class="bk-btn"
            type="button"
            :disabled="
              busy ||
              batchDetail === null ||
              isCleaningBatchActive(batchDetail.status)
            "
            @click="batchDetail ? rescore(batchDetail) : undefined"
          >
            重新打分
          </button>
          <button class="bk-btn" type="button" @click="closeBatchDrawer">
            关闭
          </button>
        </template>
      </DrawerPanel>
    </OverlayTransition>

    <!-- 审核弹窗 -->
    <OverlayTransition>
      <ModalShell
        v-if="reviewDialog"
        :title="
          reviewDialog.decision === 'APPROVE' ? '确认通过' : '驳回清洗条目'
        "
        width="560px"
        @close="closeReview"
      >
        <p class="bk-text-sm">
          <template v-if="reviewDialog.decision === 'APPROVE'">
            确认通过已选 {{ String(reviewDialog.ids.length) }}
            条？通过后可以流转到知识库。
          </template>
          <template v-else>
            驳回已选 {{ String(reviewDialog.ids.length) }}
            条。驳回原因会记录在条目详情里。
          </template>
        </p>
        <div class="bk-field">
          <label for="cleaning-review-reason">
            {{
              reviewDialog.decision === 'REJECT'
                ? '驳回原因（必填）'
                : '审核意见（可选）'
            }}
          </label>
          <FormTextarea
            id="cleaning-review-reason"
            v-model="reviewReason"
            :maxlength="FORM_LIMITS.reviewReason"
            :disabled="busy"
            compact
            placeholder="例如：表述完整，可作为差旅标准依据"
          />
        </div>
        <p v-if="reviewError" class="bk-notice bk-notice-fail" role="alert">
          {{ reviewError }}
        </p>
        <template #actions>
          <button
            class="bk-btn"
            type="button"
            :disabled="busy"
            @click="closeReview"
          >
            取消
          </button>
          <button
            class="bk-btn"
            :class="
              reviewDialog.decision === 'APPROVE'
                ? 'bk-btn-success'
                : 'bk-btn-danger'
            "
            type="button"
            :disabled="busy"
            @click="submitReview"
          >
            {{
              busy
                ? '提交中…'
                : reviewDialog.decision === 'APPROVE'
                  ? '确认通过'
                  : '确认驳回'
            }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>

    <!-- 流转弹窗 -->
    <OverlayTransition>
      <CleaningPromotionDialog
        v-if="promotionItems.length > 0"
        :items="promotionItems"
        :spaces="spaces"
        :default-name="promotionDefaultName"
        @close="promotionItems = []"
        @promoted="onPromoted"
      />
    </OverlayTransition>
  </div>
</template>

<style scoped>
.notice-action {
  margin-inline-start: var(--space-2);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

.name-filter {
  width: 220px;
}

.status-filter {
  width: 150px;
}

.batch-filter {
  width: 200px;
}

.space-filter {
  width: 170px;
}

.score-filter {
  width: 130px;
}

.cleaning-table {
  min-width: 920px;
}

.check-col {
  width: 44px;
}

.empty-cell {
  text-align: center;
  color: var(--color-text-muted);
  padding: var(--space-8) var(--space-4);
}

.nowrap {
  white-space: nowrap;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}

.link-button {
  padding: 0;
  border: none;
  background: none;
  color: var(--color-action);
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.link-button:hover {
  text-decoration: underline;
}

tr.selected {
  background: color-mix(
    in srgb,
    var(--color-surface-selected) 60%,
    var(--color-surface)
  );
}

.text-danger {
  color: var(--color-text-danger);
}

/* 选中后的操作条：只在有选择时出现，避免空占一行高度。 */
.selection-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding: var(--space-2) var(--space-3);
  background: var(--color-surface-selected);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
}

.selection-count {
  font-size: var(--text-sm);
  font-weight: 600;
}

.batch-summary {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.source-path {
  overflow-wrap: anywhere;
}

.table-in-card {
  border: none;
}
</style>
