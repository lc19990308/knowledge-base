<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import { FORM_LIMITS } from '../formValidation'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import FormTextarea from '../components/FormTextarea.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { usePagedList } from '../pagination'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  decidePublishRequest,
  getGlobalSettings,
  getPublishRequest,
  getSpace,
  listAllPublishRequests,
  listSpaces,
  withdrawPublication,
  type GlobalSettings,
  type PublishRequest,
  type Space,
} from '../api/knowledge'
import { describeError, formatDateTime, statusLabel } from '../api/display'
import { isApiError, needsVersionRefresh } from '../api/http'
import { getCurrentUser, type CurrentUser } from '../api/knowledge'

type TabKey = PublishRequest['status']

const TABS: { key: TabKey; label: string }[] = [
  { key: 'PENDING_REVIEW', label: '待审核' },
  { key: 'APPROVED_WAITING_INDEX', label: '等待索引' },
  { key: 'REJECTED', label: '已驳回' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'CANCELLED', label: '已撤回' },
]

const spaces = ref<Space[]>([])
const me = ref<CurrentUser | null>(null)
const settings = ref<GlobalSettings | null>(null)
const tab = ref<TabKey>('PENDING_REVIEW')
const selected = ref<PublishRequest | null>(null)
const selectedEtag = ref('')
const decisionReason = ref('')
let detailRequest: AbortController | undefined
const busy = ref(false)
const loading = ref(true)
const error = ref<string | null>(null)
const notice = ref<{ tone: string; text: string } | null>(null)

const isAdmin = computed(
  () => me.value?.roles.includes('SYSTEM_ADMIN') ?? false,
)
/**
 * 发布申请按状态页签在服务端筛选和分页：切页签回到第一页重新请求，
 * total 由接口返回，前端不再拿全量申请自己切片。
 */
const {
  items: requests,
  page: requestPage,
  pageSize: requestPageSize,
  total: requestTotal,
  error: listError,
  reload: reloadRequests,
  reset: resetRequests,
} = usePagedList<PublishRequest>(
  (query, signal) =>
    listAllPublishRequests({ ...query, status: tab.value, signal }),
  { describeError: describe },
)
const currentPublicationId = computed(
  () =>
    spaces.value.find((space) => space.spaceId === selected.value?.spaceId)
      ?.currentPublicationId,
)
const canDecide = computed(
  () =>
    isAdmin.value &&
    selected.value?.decisionRequired === true &&
    selectedEtag.value !== '',
)
const spaceName = (spaceId: string): string =>
  spaces.value.find((space) => space.spaceId === spaceId)?.name ??
  spaceId.slice(0, 8)

function policyTone(state: string): string {
  if (state === 'EFFECTIVE') return 'bk-badge bk-badge-ok'
  if (state === 'EXPIRED') return 'bk-badge bk-badge-warn'
  return 'bk-badge bk-badge-draft'
}

function badgeClass(status: PublishRequest['status']): string {
  switch (status) {
    case 'PUBLISHED':
      return 'bk-badge bk-badge-published'
    case 'PENDING_REVIEW':
    case 'APPROVED_WAITING_INDEX':
      return 'bk-badge bk-badge-review'
    case 'REJECTED':
      return 'bk-badge bk-badge-rejected'
    default:
      return 'bk-badge bk-badge-draft'
  }
}

function describe(failure: unknown): string {
  if (isApiError(failure, 409, 'STATE_CONFLICT', 'PUBLICATION_CHANGED')) {
    return describeError(failure, '申请或发布状态已变化，请刷新后重试。')
  }
  return describeError(failure)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [user, spacePage, globalSettings] = await Promise.all([
      getCurrentUser().catch(() => null),
      listSpaces(),
      getGlobalSettings().catch(() => null),
    ])
    me.value = user
    spaces.value = spacePage.items
    settings.value = globalSettings?.data ?? null
    await reloadRequests()
    if (listError.value !== null) error.value = listError.value
  } catch (failure) {
    error.value = describe(failure)
  } finally {
    loading.value = false
  }
}

async function open(request: PublishRequest): Promise<void> {
  if (busy.value || loading.value) return
  closeDrawer()
  const controller = new AbortController()
  detailRequest = controller
  const { signal } = controller
  error.value = null
  notice.value = null
  decisionReason.value = ''
  try {
    const detail = await getPublishRequest(request.requestId, signal)
    if (signal.aborted) return
    selected.value = detail.data
    selectedEtag.value = detail.etag ?? ''
  } catch (failure) {
    if (!signal.aborted) error.value = describe(failure)
  } finally {
    if (detailRequest === controller) detailRequest = undefined
  }
}

function closeDrawer(): void {
  detailRequest?.abort()
  detailRequest = undefined
  selected.value = null
  selectedEtag.value = ''
}

watch(
  tab,
  () => {
    closeDrawer()
    void resetRequests()
  },
  { flush: 'sync' },
)
onUnmounted(closeDrawer)

async function refreshSpace(spaceId: string): Promise<void> {
  // Disable withdrawal until the server confirms the current pointer.
  spaces.value = spaces.value.map((space) =>
    space.spaceId === spaceId
      ? { ...space, currentPublicationId: null }
      : space,
  )
  try {
    const updated = await getSpace(spaceId)
    spaces.value = spaces.value.map((space) =>
      space.spaceId === spaceId ? updated : space,
    )
  } catch (failure) {
    error.value = `申请已审核，但无法刷新当前发布状态。请刷新页面后重试。${describe(failure)}`
  }
}

async function decide(decision: 'APPROVE' | 'REJECT'): Promise<void> {
  const request = selected.value
  if (!request || !canDecide.value || busy.value || loading.value) return
  if (decisionReason.value.trim().length > FORM_LIMITS.reviewReason) {
    error.value = '审核意见最多 2000 个字符。'
    return
  }
  if (decision === 'REJECT' && decisionReason.value.trim() === '') {
    error.value = '驳回必须填写审核意见。'
    return
  }
  busy.value = true
  error.value = null
  try {
    const updated = await decidePublishRequest(
      request.requestId,
      selectedEtag.value,
      decision,
      decisionReason.value.trim() || '通过并发布',
    )
    if (selected.value?.requestId === updated.requestId) {
      selected.value = updated
      selectedEtag.value = ''
    }
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text:
        updated.status === 'APPROVED_WAITING_INDEX'
          ? '已通过，索引完成前不会切换发布；完成后自动生效。'
          : `审核结果：${statusLabel(updated.status)}`,
    }
    await Promise.all([refreshSpace(updated.spaceId), reloadRequests()])
  } catch (failure) {
    error.value = describe(failure)
    if (
      selected.value?.requestId === request.requestId &&
      (needsVersionRefresh(failure) ||
        isApiError(failure, 409, 'STATE_CONFLICT'))
    ) {
      selectedEtag.value = ''
      try {
        const fresh = await getPublishRequest(request.requestId)
        if (!Object.is(selected.value, request)) return
        selected.value = fresh.data
        selectedEtag.value = fresh.etag ?? ''
        requests.value = requests.value.map((item) =>
          item.requestId === fresh.data.requestId ? fresh.data : item,
        )
        error.value = describeError(
          failure,
          '已读取最新申请并保留审核意见，请核对后再提交。',
        )
      } catch (refreshFailure) {
        error.value = `${describe(failure)} ${describeError(refreshFailure)}`
      }
    }
  } finally {
    busy.value = false
  }
}

async function withdraw(): Promise<void> {
  const request = selected.value
  if (!request || !isAdmin.value || busy.value || loading.value) return
  const publicationId = currentPublicationId.value
  if (!publicationId) {
    error.value = '该空间当前没有发布内容可撤回。'
    return
  }
  if (
    !decisionReason.value.trim() ||
    decisionReason.value.trim().length > FORM_LIMITS.reviewReason
  ) {
    error.value = '请填写撤回原因，最多 2000 个字符。'
    return
  }
  busy.value = true
  error.value = null
  try {
    await withdrawPublication(
      request.spaceId,
      publicationId,
      decisionReason.value.trim(),
    )
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: '已撤回。',
    }
    closeDrawer()
    await load()
  } catch (failure) {
    error.value = describe(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="publishing">
    <PageHeader title="发布管理">
      <template #actions>
        <button
          class="bk-btn bk-btn-neutral"
          type="button"
          :disabled="busy || loading"
          @click="load"
        >
          刷新
        </button>
      </template>
    </PageHeader>

    <p v-if="error && !selected" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>
    <p v-if="notice" :class="notice.tone" role="status">{{ notice.text }}</p>

    <div class="bk-tabs" role="tablist" aria-label="发布状态">
      <button
        v-for="item in TABS"
        :key="item.key"
        class="bk-tab"
        type="button"
        role="tab"
        :aria-selected="tab === item.key"
        @click="tab = item.key"
      >
        {{ item.label }}
      </button>
    </div>

    <p class="bk-text-sm bk-muted bk-mb-4" role="status">
      {{ loading ? '正在加载…' : `当前状态下 ${requestTotal} 条申请` }}
    </p>

    <div class="bk-table-wrap" role="tabpanel">
      <table class="bk-table">
        <thead>
          <tr>
            <th>空间 / 变更</th>
            <th>提交人</th>
            <th>提交时间</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="requestTotal === 0">
            <td colspan="5" class="empty-cell">该状态下没有申请。</td>
          </tr>
          <tr v-for="request in requests" :key="request.requestId">
            <td>
              <div class="bk-row-title">{{ spaceName(request.spaceId) }}</div>
              <div class="bk-row-sub">
                {{ request.submissionReason ?? '未填写提交说明' }}
              </div>
            </td>
            <td class="bk-mono">{{ request.submittedBy.slice(0, 8) }}</td>
            <td>{{ formatDateTime(request.submittedAt) }}</td>
            <td>
              <span :class="badgeClass(request.status)">{{
                statusLabel(request.status)
              }}</span>
            </td>
            <td>
              <button
                class="bk-btn bk-btn-sm bk-btn-info"
                type="button"
                :disabled="busy || loading"
                @click="open(request)"
              >
                查看
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <PaginationBar
      v-model:page="requestPage"
      v-model:page-size="requestPageSize"
      :total="requestTotal"
      unit="条申请"
      label="发布申请分页"
    />

    <div class="bk-notice bk-notice-info bk-mt-4">
      <span class="n-icon">ℹ</span>
      <span>审核通过后才对用户可见。</span>
    </div>

    <OverlayTransition>
      <ModalShell
        v-if="selected"
        :title="spaceName(selected.spaceId)"
        width="620px"
        manage-focus
        @close="closeDrawer"
      >
        <div class="bk-steps review-steps bk-mb-4">
          <span class="bk-step done"
            ><span class="s-idx">✓</span
            >{{ formatDateTime(selected.submittedAt) }} 提交</span
          >
          <span class="bk-step" :class="{ now: selected.decisionRequired }">
            <span class="s-idx">2</span>管理员审核
          </span>
          <span
            class="bk-step"
            :class="{ done: selected.status === 'PUBLISHED' }"
          >
            <span class="s-idx">3</span>通过后生效
          </span>
        </div>

        <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
          {{ error }}
        </p>
        <p v-if="notice" :class="notice.tone" role="status">
          {{ notice.text }}
        </p>

        <div class="bk-card-head review-heading">
          <h3>变更内容</h3>
          <span class="hint"
            >草稿版本 {{ selected.draftRevision }} · 快照
            {{ selected.snapshotId.slice(0, 8) }} · 共
            {{ selected.snapshot.length }} 份文档</span
          >
        </div>

        <div class="bk-table-wrap bk-mb-4 table-compact">
          <table class="bk-table">
            <thead>
              <tr>
                <th>文档 / 变更</th>
                <th>政策状态</th>
                <th>生效区间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="selected.snapshot.length === 0">
                <td colspan="3" class="empty-cell">该申请没有冻结文档。</td>
              </tr>
              <tr
                v-for="entry in selected.snapshot"
                :key="entry.documentVersionId"
              >
                <td>
                  <div class="bk-row-title">
                    {{ entry.name ?? entry.documentVersionId.slice(0, 8) }}
                  </div>
                  <div class="bk-row-sub">
                    {{ entry.documentVersionId.slice(0, 8) }}
                  </div>
                </td>
                <td>
                  <span :class="policyTone(entry.policyState)">{{
                    statusLabel(entry.policyState)
                  }}</span>
                </td>
                <td class="num">
                  {{ entry.effectiveFrom ?? '未填写' }} —
                  {{ entry.effectiveUntil ?? '未填写' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <p v-if="selected.decisionReason" class="bk-text-sm">
          审核意见：{{ selected.decisionReason }}
        </p>

        <p class="bk-notice bk-notice-info">
          <span class="n-icon">ℹ</span>
          <span>当前全局模板：{{ settings?.template || '未配置' }}</span>
        </p>

        <form class="drawer-form" @submit.prevent>
          <div class="bk-field">
            <label for="review-reason">审核意见</label>
            <FormTextarea
              id="review-reason"
              v-model="decisionReason"
              :maxlength="FORM_LIMITS.reviewReason"
              placeholder="通过可留空；驳回时请填写具体原因…"
            />
          </div>
          <p v-if="!isAdmin" class="bk-text-sm bk-muted">仅管理员可操作。</p>
        </form>
        <template v-if="isAdmin" #actions>
          <button
            class="bk-btn bk-btn-danger"
            type="button"
            :disabled="busy || loading || !canDecide"
            @click="decide('REJECT')"
          >
            驳回
          </button>
          <button
            class="bk-btn bk-btn-success"
            type="button"
            :disabled="busy || loading || !canDecide"
            @click="decide('APPROVE')"
          >
            通过并发布
          </button>
          <button
            class="bk-btn bk-btn-warning"
            type="button"
            :disabled="busy || loading || !currentPublicationId"
            title="撤回当前发布"
            @click="withdraw"
          >
            撤回发布
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
  </div>
</template>

<style scoped>
.review-steps,
.review-heading {
  flex-direction: column;
  align-items: flex-start;
}

.drawer-form {
  margin-top: var(--space-4);
}

.empty-cell {
  text-align: center;
  color: var(--color-text-muted);
  padding: var(--space-8) var(--space-4);
}
</style>
