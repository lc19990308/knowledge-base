<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import DrawerPanel from '../components/DrawerPanel.vue'
import ImportDialog from '../components/ImportDialog.vue'
import { FORM_LIMITS, NON_BLANK_PATTERN } from '../formValidation'
import FormInput from '../components/FormInput.vue'
import FormFileInput from '../components/FormFileInput.vue'
import SelectMenu from '../components/SelectMenu.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { usePagedList } from '../pagination'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { RefreshCw } from '@lucide/vue'
import {
  createSpace,
  getDraft,
  getSpace,
  getDraftDocument,
  listAllPublishRequests,
  listSpaces,
  submitPublishRequest,
  updateDocumentVersion,
  replaceDraft,
  type DraftDocumentPreview,
  type DraftEntry,
  type PublishRequest,
  type Space,
} from '../api/knowledge'
import {
  describeError,
  formatBytes,
  formatDate,
  formatDateTime,
  statusLabel,
  statusTone,
} from '../api/display'
import { isApiError, needsVersionRefresh } from '../api/http'

const route = useRoute()
const router = useRouter()

const selectedId = ref('')
const selectedSpace = ref<Space | null>(null)
const draftEntries = ref<DraftEntry[]>([])
const draftEtag = ref('')
const draftState = ref<'idle' | 'loading' | 'loaded' | 'forbidden'>('idle')
const preview = ref<DraftDocumentPreview | null>(null)
/** 正在更新的草稿条目；配合隐藏文件选择框，一次只更新一份文档。 */
const updateTarget = ref<DraftEntry | null>(null)
const updating = ref(false)
const previewError = ref<string | null>(null)

const nameFilter = ref('')
const statusFilter = ref<'' | 'published' | 'draft' | 'reviewing'>('')
/** 最近一轮审核申请：区分线上是否可读和审核进度，避免同名为「已发布」。 */
const openRequests = ref<Map<string, PublishRequest>>(new Map())
const FILTER_DELAY_MS = 250
let filterTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let disposed = false
/** 用函数读取，避免 TypeScript 把 `disposed` 收窄成常量 false。 */
const isDisposed = (): boolean => disposed
const newSpaceName = ref('')
const newSpaceDepartment = ref('')
const creating = ref(false)
const submitting = ref(false)
const importSpace = ref<Space | null>(null)
const showCreate = ref(false)
const createError = ref<string | null>(null)
const notice = ref<{ tone: string; text: string } | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)
let draftRequest: AbortController | undefined
let previewRequest: AbortController | undefined

/** 页面筛选值到接口 status 参数的映射。 */
const statusQuery = computed<'PUBLISHED' | 'DRAFT' | 'REVIEWING' | ''>(() => {
  switch (statusFilter.value) {
    case 'published':
      return 'PUBLISHED'
    case 'draft':
      return 'DRAFT'
    case 'reviewing':
      return 'REVIEWING'
    default:
      return ''
  }
})

/** 空间列表按名称与状态在服务端筛选、分页，total 为筛选后的总数。 */
const {
  items: spaces,
  page: spacePage,
  pageSize: spacePageSize,
  total: spaceTotal,
  error: listError,
  reload: reloadSpaces,
  reset: resetSpaces,
} = usePagedList<Space>(
  (query, signal) =>
    listSpaces({
      ...query,
      q: nameFilter.value.trim(),
      status: statusQuery.value,
      signal,
    }),
  { describeError: describe },
)

function cancelFilterTimer(): void {
  globalThis.clearTimeout(filterTimer)
}

/** 状态筛选立即生效；名称输入按关键字去抖，避免每个字符都发一次请求。 */
watch(statusFilter, () => {
  cancelFilterTimer()
  void resetSpaces()
})
watch(nameFilter, () => {
  cancelFilterTimer()
  filterTimer = globalThis.setTimeout(() => void resetSpaces(), FILTER_DELAY_MS)
})

function spaceBadge(space: Space): { cls: string; text: string } {
  const request = openRequests.value.get(space.spaceId)
  const review =
    request?.status === 'APPROVED_WAITING_INDEX'
      ? '待索引生效'
      : request?.status === 'PENDING_REVIEW'
        ? '审核中'
        : ''
  if (space.currentPublicationId)
    return {
      cls: 'bk-badge bk-badge-published',
      text: review ? `已发布 · 新变更${review}` : '已发布',
    }
  if (review) return { cls: 'bk-badge bk-badge-review', text: review }
  if (space.documentCount > 0)
    return { cls: 'bk-badge bk-badge-review', text: '待发布' }
  return { cls: 'bk-badge bk-badge-draft', text: '草稿' }
}

function describe(failure: unknown): string {
  if (isApiError(failure, 404, 'RESOURCE_NOT_FOUND')) {
    return '该空间不存在，或你没有访问权限。'
  }
  return describeError(failure)
}

/**
 * 常见文档 MIME → 表格短标签。
 * 草稿表只展示扩展名，像 docx 的完整子类型会把「格式」列撑到一整行。
 */
const MEDIA_TYPE_LABELS: Record<string, string> = {
  'application/pdf': 'PDF',
  'application/msword': 'DOC',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
    'DOCX',
  'application/vnd.ms-excel': 'XLS',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'XLSX',
  'application/vnd.ms-powerpoint': 'PPT',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation':
    'PPTX',
  'application/rtf': 'RTF',
  'application/json': 'JSON',
  'text/plain': 'TXT',
  'text/markdown': 'MD',
  'text/html': 'HTML',
  'text/csv': 'CSV',
}

/** 把 MIME 类型压成短标签；未登记的类型只保留简洁子类型，长串统一显示「其他」。 */
function mediaTypeLabel(mediaType: string | null): string {
  if (!mediaType) return '—'
  const mime = mediaType.split(';')[0]?.trim().toLowerCase() ?? ''
  const known = MEDIA_TYPE_LABELS[mime]
  if (known) return known
  const subtype = mime.split('/').at(-1) ?? ''
  return /^[a-z0-9]+$/.test(subtype) ? subtype.toUpperCase() : '其他'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const requests = await listAllPublishRequests().catch(() => ({
      items: [] as PublishRequest[],
      nextCursor: null,
      total: 0,
    }))
    if (isDisposed()) return
    openRequests.value = new Map(
      requests.items
        .filter((request) =>
          ['PENDING_REVIEW', 'APPROVED_WAITING_INDEX'].includes(request.status),
        )
        .map((request) => [request.spaceId, request]),
    )
    await reloadSpaces()
    if (isDisposed()) return
    if (listError.value !== null) error.value = listError.value
    if (!selectedId.value && spaces.value.length > 0) {
      const preferred =
        spaces.value.find(
          (space) =>
            space.canMaintain &&
            space.documentCount > 0 &&
            space.currentPublicationId,
        ) ??
        spaces.value.find((space) => space.documentCount > 0) ??
        spaces.value[0]
      if (preferred) await selectSpace(preferred.spaceId, false)
    }
  } catch (failure) {
    error.value = describe(failure)
  } finally {
    loading.value = false
  }
}

async function loadDraft(spaceId: string): Promise<void> {
  draftRequest?.abort()
  previewRequest?.abort()
  const request = new AbortController()
  draftRequest = request
  draftState.value = 'loading'
  draftEntries.value = []
  draftEtag.value = ''
  preview.value = null
  previewError.value = null
  const space = selectedSpace.value
  if (!space || space.spaceId !== spaceId || !space.canMaintain) {
    draftState.value = 'forbidden'
    draftRequest = undefined
    return
  }
  try {
    const draft = await getDraft(spaceId, request.signal)
    if (request.signal.aborted) return
    draftEntries.value = draft.data.entries
    draftEtag.value = draft.etag ?? ''
    draftState.value = 'loaded'
  } catch (failure) {
    if (request.signal.aborted) return
    draftState.value = isApiError(failure, 404, 'RESOURCE_NOT_FOUND')
      ? 'forbidden'
      : 'loaded'
    if (!isApiError(failure, 404, 'RESOURCE_NOT_FOUND')) {
      error.value = describe(failure)
    }
  } finally {
    if (draftRequest === request) draftRequest = undefined
  }
}

const drawerOpen = ref(false)

async function selectSpace(spaceId: string, openDrawer = true): Promise<void> {
  selectedId.value = spaceId
  drawerOpen.value = openDrawer
  error.value = null
  notice.value = null
  const known = spaces.value.find((space) => space.spaceId === spaceId)
  if (known) {
    selectedSpace.value = known
  } else {
    try {
      selectedSpace.value = await getSpace(spaceId)
    } catch {
      selectedSpace.value = null
    }
  }
  if (isDisposed() || selectedId.value !== spaceId) return
  await loadDraft(spaceId)
}

/** 空间按名称排序，新建后所在页会变；读全量定位到它那一页再选中。 */
async function focusSpace(spaceId: string): Promise<void> {
  try {
    const all = (await listSpaces()).items
    const index = all.findIndex((space) => space.spaceId === spaceId)
    if (index >= 0) {
      const target = Math.floor(index / spacePageSize.value) + 1
      if (target !== spacePage.value) {
        spacePage.value = target
        return
      }
    }
    await reloadSpaces()
  } catch {
    // 定位失败不影响创建结果，用户仍可翻页找到该空间。
  }
}

function closeDrawer(): void {
  drawerOpen.value = false
}

function openImport(space: Space): void {
  if (!space.canMaintain) {
    error.value = '你没有该空间的维护权限。'
    return
  }
  importSpace.value = space
}

function closeImport(): void {
  importSpace.value = null
}

watch(
  () => route.query.uploadSpace,
  async (spaceId, _, onCleanup) => {
    if (typeof spaceId !== 'string' || !spaceId) return
    const request = new AbortController()
    onCleanup(() => {
      request.abort()
    })
    try {
      const space = await getSpace(spaceId, request.signal)
      if (!request.signal.aborted) openImport(space)
    } catch (failure) {
      if (!request.signal.aborted) error.value = describe(failure)
    } finally {
      if (!request.signal.aborted) {
        const query = { ...route.query }
        delete query.uploadSpace
        // 保留错误提示和目标空间，清理一次性入口参数。
        await router.replace({ query })
      }
    }
  },
  { immediate: true },
)

async function handleImported(): Promise<void> {
  const spaceId = importSpace.value?.spaceId
  if (!spaceId) return
  await reloadSpaces()
  if (!isDisposed() && selectedId.value === spaceId) await loadDraft(spaceId)
}

function openCreate(): void {
  newSpaceName.value = ''
  newSpaceDepartment.value = ''
  createError.value = null
  showCreate.value = true
}

function closeCreate(): void {
  if (!creating.value) showCreate.value = false
}

async function submitCreate(): Promise<void> {
  const name = newSpaceName.value.trim()
  if (creating.value) return
  if (
    !name ||
    name.length > FORM_LIMITS.name ||
    newSpaceDepartment.value.trim().length > FORM_LIMITS.department
  ) {
    createError.value =
      '请输入空间名称（最多 120 个字符），所属部门最多 60 个字符。'
    return
  }
  creating.value = true
  createError.value = null
  error.value = null
  try {
    const created = await createSpace(name, newSpaceDepartment.value.trim())
    showCreate.value = false
    await focusSpace(created.spaceId)
    await selectSpace(created.spaceId, false)
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已创建草稿空间「${created.name}」，尚未发布。`,
    }
  } catch (failure) {
    createError.value = describe(failure)
  } finally {
    creating.value = false
  }
}

/** 轮询草稿内新版本的解析状态：只有进草稿的版本才能用草稿预览接口读到。 */
async function waitForParse(
  spaceId: string,
  documentId: string,
): Promise<DraftDocumentPreview | null> {
  for (let attempt = 0; attempt < 40; attempt++) {
    const document = await getDraftDocument(spaceId, documentId)
    if (
      document.parseState !== 'QUEUED' &&
      document.parseState !== 'PROCESSING'
    ) {
      return document
    }
    await new Promise((resolve) => globalThis.setTimeout(resolve, 1500))
  }
  return null
}

/** 把新版本写进草稿替换旧条目；返回用于提交审核的新草稿条目。 */
async function replaceDraftEntry(
  entry: DraftEntry,
  next: DraftEntry,
): Promise<void> {
  const entries = draftEntries.value.map((existing) =>
    existing.documentVersionId === entry.documentVersionId ? next : existing,
  )
  if (!draftEtag.value) {
    throw new Error('无法获取草稿版本，请刷新后重试。')
  }
  await replaceDraft(selectedId.value, draftEtag.value, entries)
}

/**
 * 上传新版本：先入草稿再轮询解析（草稿接口才能读到新版本），
 * 解析失败时把旧条目放回去，避免把坏文件留在待审核清单里。
 */
async function onUpdateFile(entry: DraftEntry, files: File[]): Promise<void> {
  const file = files[0]
  if (!file || updating.value) return
  updateTarget.value = entry
  updating.value = true
  error.value = null
  notice.value = null
  try {
    const updated = await updateDocumentVersion(
      selectedId.value,
      entry.documentVersionId,
      file,
    )
    const next: DraftEntry = {
      ...entry,
      documentVersionId: updated.documentVersionId,
      replacesDocumentVersionId: entry.documentVersionId,
      name: file.name,
      sizeBytes: file.size,
      mediaType: file.type || entry.mediaType,
      versionNumber: updated.versionNumber,
      parseState: 'QUEUED',
      createdAt: new Date().toISOString(),
    }
    await replaceDraftEntry(entry, next)
    const parsed = await waitForParse(
      selectedId.value,
      updated.documentVersionId,
    )
    if (!parsed) {
      throw new Error(
        '解析时间较长，新版本已入草稿，请稍后点「刷新草稿」查看。',
      )
    }
    if (parsed.parseState === 'FAILED' || parsed.parseState === 'NO_TEXT') {
      await replaceDraftEntry(next, entry)
      throw new Error(
        parsed.parseState === 'NO_TEXT'
          ? '新版本没有可提取的正文，已回退到原版本。'
          : '新版本解析失败，已回退到原版本。',
      )
    }
    await loadDraft(selectedId.value)
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已生成 v${String(updated.versionNumber)} 并替换草稿条目，提交审核后生效。`,
    }
  } catch (failure) {
    error.value = describe(failure)
    await loadDraft(selectedId.value).catch(() => undefined)
  } finally {
    updating.value = false
    updateTarget.value = null
  }
}

async function openPreview(documentId: string): Promise<void> {
  previewRequest?.abort()
  const request = new AbortController()
  previewRequest = request
  preview.value = null
  previewError.value = null
  try {
    const document = await getDraftDocument(
      selectedId.value,
      documentId,
      request.signal,
    )
    if (!request.signal.aborted) preview.value = document
  } catch (failure) {
    if (!request.signal.aborted) previewError.value = describe(failure)
  }
}

async function submitReview(): Promise<void> {
  if (submitting.value || draftState.value === 'loading') return
  const spaceId = selectedId.value
  submitting.value = true
  error.value = null
  notice.value = null
  try {
    const request = await submitPublishRequest(
      spaceId,
      draftEtag.value,
      '知识库页提交审核',
    )
    if (isDisposed() || selectedId.value !== spaceId) return
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已提交审核（${statusLabel(request.status)}）。`,
    }
  } catch (failure) {
    if (isDisposed() || selectedId.value !== spaceId) return
    if (
      needsVersionRefresh(failure) ||
      isApiError(failure, 409, 'ETAG_MISMATCH')
    ) {
      error.value = describeError(failure, '请核对最新草稿，确认后再提交审核。')
      await loadDraft(spaceId)
    } else {
      error.value = describe(failure)
    }
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  disposed = true
  cancelFilterTimer()
  draftRequest?.abort()
  previewRequest?.abort()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="knowledge">
    <PageHeader title="知识空间">
      <template #actions>
        <button
          class="bk-btn bk-btn-primary"
          type="button"
          :disabled="loading || creating"
          @click="openCreate"
        >
          ＋ 创建空间
        </button>
      </template>
    </PageHeader>

    <OverlayTransition>
      <ModalShell
        v-if="showCreate"
        title="创建空间"
        width="520px"
        @close="closeCreate"
      >
        <form id="create-space-form" @submit.prevent="submitCreate">
          <p v-if="createError" class="bk-notice bk-notice-fail" role="alert">
            {{ createError }}
          </p>
          <div class="bk-field">
            <label for="space-department">所属部门</label>
            <FormInput
              id="space-department"
              v-model="newSpaceDepartment"
              :maxlength="FORM_LIMITS.department"
              placeholder="可选，例如：人力资源部"
              :disabled="creating"
            />
          </div>
          <div class="bk-field">
            <label for="space-name">空间名称</label>
            <FormInput
              id="space-name"
              v-model="newSpaceName"
              :maxlength="FORM_LIMITS.name"
              :pattern="NON_BLANK_PATTERN"
              required
              placeholder="例如：差旅报销制度"
              :disabled="creating"
            />
          </div>
        </form>
        <template #actions>
          <button
            class="bk-btn"
            type="button"
            :disabled="creating"
            @click="closeCreate"
          >
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="submit"
            form="create-space-form"
            :disabled="creating"
          >
            {{ creating ? '创建中…' : '创建草稿' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>

    <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>
    <p v-if="notice" :class="notice.tone" role="status">{{ notice.text }}</p>

    <div class="filter-bar">
      <FormInput
        v-model="nameFilter"
        :maxlength="FORM_LIMITS.search"
        class="name-filter"
        type="search"
        aria-label="筛选空间名称"
        placeholder="筛选空间名称…"
      />
      <SelectMenu
        v-model="statusFilter"
        class="status-filter"
        label="筛选空间状态"
        :options="[
          { value: '', label: '全部状态' },
          { value: 'published', label: '已发布' },
          { value: 'reviewing', label: '审核中' },
          { value: 'draft', label: '未发布' },
        ]"
      />
      <span class="bk-grow"></span>
      <span class="bk-text-xs bk-muted">
        {{
          loading
            ? '正在加载…'
            : `${spaceTotal} 个空间${nameFilter.trim() || statusFilter ? '（已筛选）' : ''}`
        }}
      </span>
    </div>

    <div class="bk-table-wrap">
      <table class="bk-table">
        <thead>
          <tr>
            <th>空间</th>
            <th>状态</th>
            <th class="num">文档 / 可检索</th>
            <th>维护权限</th>
            <th>最近变更</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="spaceTotal === 0">
            <td colspan="6" class="empty-cell">没有符合筛选条件的空间。</td>
          </tr>
          <tr
            v-for="space in spaces"
            :key="space.spaceId"
            :class="{ selected: space.spaceId === selectedId }"
          >
            <td>
              <button
                class="link-button"
                type="button"
                @click="selectSpace(space.spaceId)"
              >
                <span class="bk-row-title">{{ space.name }}</span>
              </button>
              <div class="bk-row-sub">
                {{ space.department ?? '未设置部门' }} ·
                {{
                  space.updatedAt
                    ? `最近变更 ${formatDateTime(space.updatedAt)}`
                    : '暂无变更'
                }}
              </div>
            </td>
            <td>
              <span :class="spaceBadge(space).cls">{{
                spaceBadge(space).text
              }}</span>
            </td>
            <td class="num">
              {{ space.documentCount }} / {{ space.searchableCount }}
            </td>
            <td>{{ space.canMaintain ? '可维护' : '只读' }}</td>
            <td class="num">{{ formatDateTime(space.updatedAt) }}</td>
            <td>
              <button
                class="bk-btn bk-btn-sm bk-btn-info"
                type="button"
                @click="selectSpace(space.spaceId)"
              >
                查看
              </button>
              <button
                class="bk-btn bk-btn-sm bk-btn-primary"
                type="button"
                :disabled="!space.canMaintain"
                @click="openImport(space)"
              >
                导入
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <PaginationBar
      v-model:page="spacePage"
      v-model:page-size="spacePageSize"
      :total="spaceTotal"
      unit="个空间"
      label="知识库分页"
    />

    <OverlayTransition variant="drawer">
      <DrawerPanel
        v-if="selectedSpace && drawerOpen"
        :title="`「${selectedSpace.name}」· 文档列表`"
        width="960px"
        @close="closeDrawer"
      >
        <template #hint> 同名文件可直接更新为新版本 </template>

        <p v-if="draftState === 'loading'" class="bk-text-sm bk-muted">
          正在读取草稿…
        </p>
        <div
          v-else-if="draftState === 'forbidden'"
          class="bk-notice bk-notice-warn"
        >
          <span class="n-icon">⚠</span>
          <span>你没有该空间的维护权限。</span>
        </div>
        <template v-else>
          <div class="bk-table-wrap table-in-card table-compact">
            <table class="bk-table">
              <thead>
                <tr>
                  <th>文档名称</th>
                  <th>格式 · 大小</th>
                  <th>可检索</th>
                  <th>状态</th>
                  <th>导入时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="draftEntries.length === 0">
                  <td colspan="6" class="empty-cell">
                    草稿清单为空。先导入文档，再提交审核。
                  </td>
                </tr>
                <tr
                  v-for="entry in draftEntries"
                  :key="entry.documentVersionId"
                >
                  <td class="doc-name">
                    <div class="bk-row-title">
                      {{ entry.name ?? entry.documentVersionId.slice(0, 8) }}
                    </div>
                    <div class="bk-row-sub">
                      第 {{ entry.versionNumber ?? 1 }} 版
                    </div>
                  </td>
                  <td class="doc-meta">
                    <span class="ver">{{
                      mediaTypeLabel(entry.mediaType)
                    }}</span>
                    <span class="doc-size">{{
                      formatBytes(entry.sizeBytes ?? 0)
                    }}</span>
                  </td>
                  <td class="nowrap">
                    <span
                      :class="
                        entry.parseState === 'READY'
                          ? 'bk-badge bk-badge-ok'
                          : 'bk-badge bk-badge-draft'
                      "
                    >
                      {{ entry.parseState === 'READY' ? '可检索' : '待处理' }}
                    </span>
                  </td>
                  <td class="nowrap">
                    <span :class="statusTone(entry.policyState)">{{
                      statusLabel(entry.policyState)
                    }}</span>
                  </td>
                  <td class="num nowrap">{{ formatDate(entry.createdAt) }}</td>
                  <td>
                    <div class="row-actions">
                      <button
                        class="bk-btn bk-btn-sm bk-btn-info"
                        type="button"
                        @click="openPreview(entry.documentVersionId)"
                      >
                        预览
                      </button>
                      <FormFileInput
                        class="bk-btn-sm"
                        secondary
                        :disabled="updating"
                        @change="(files) => onUpdateFile(entry, files)"
                      >
                        {{
                          updating &&
                          updateTarget?.documentVersionId ===
                            entry.documentVersionId
                            ? '更新中…'
                            : '更新'
                        }}
                      </FormFileInput>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <p v-if="previewError" class="bk-notice bk-notice-fail" role="alert">
            {{ previewError }}
          </p>
          <article v-if="preview" class="bk-card preview-card">
            <div class="bk-card-head">
              <h3>{{ preview.name }}</h3>
              <span :class="statusTone(preview.parseState)">{{
                statusLabel(preview.parseState)
              }}</span>
            </div>
            <p v-if="preview.previewText">{{ preview.previewText }}</p>
            <p v-else class="bk-text-sm bk-muted">
              该文档没有可提取的正文（例如纯扫描件）。
            </p>
          </article>
        </template>
        <template #actions>
          <span class="bk-text-xs bk-muted drawer-draft-version">
            草稿版本 {{ draftEtag === '' ? '未知' : draftEtag }}
          </span>
          <div class="drawer-action-group">
            <button
              class="bk-btn bk-btn-sm bk-btn-ghost icon-btn"
              :class="{ 'is-loading': draftState === 'loading' }"
              type="button"
              aria-label="刷新草稿"
              title="刷新草稿"
              :disabled="draftState === 'loading'"
              @click="loadDraft(selectedId)"
            >
              <RefreshCw :size="15" aria-hidden="true" />
            </button>
            <button
              class="bk-btn bk-btn-neutral drawer-import"
              type="button"
              :disabled="!selectedSpace.canMaintain"
              @click="openImport(selectedSpace)"
            >
              导入新文档
            </button>
            <button
              class="bk-btn bk-btn-primary"
              type="button"
              :disabled="
                submitting || draftEntries.length === 0 || draftEtag === ''
              "
              @click="submitReview"
            >
              {{ submitting ? '提交中…' : '提交变更审核' }}
            </button>
          </div>
        </template>
      </DrawerPanel>
    </OverlayTransition>

    <OverlayTransition>
      <ImportDialog
        v-if="importSpace"
        :space="importSpace"
        @close="closeImport"
        @imported="handleImported"
      />
    </OverlayTransition>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  margin-bottom: var(--space-4);
  flex-wrap: wrap;
}

.name-filter {
  width: 220px;
}

.status-filter {
  width: 140px;
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

.empty-cell {
  text-align: center;
  color: var(--color-text-muted);
  padding: var(--space-8) var(--space-4);
}

.ver {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-muted);
}

/* 文档名可能很长（中文文件名无空格），允许在单元格内换行，避免把相邻列挤窄。 */
.doc-name {
  min-width: 200px;
  overflow-wrap: anywhere;
}

/* 徽章、状态、日期这类短内容不换行，否则一列会被压成两行高。 */
.nowrap {
  white-space: nowrap;
}

/* 行内操作并排且同高；换行会把「预览 / 更新」拆成上下两块，点错概率变高。 */
.row-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}

/* 「格式 · 大小」同格分两行排布：格式在上、体积在下，省出一整列宽度。 */
.doc-meta {
  white-space: nowrap;
}

.doc-size {
  display: block;
  margin-top: 2px;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.table-in-card {
  border: none;
}

:deep(.bk-card-head .more) {
  margin-left: auto;
  font-size: var(--text-sm);
  color: var(--color-action);
  text-decoration: none;
}

:deep(.bk-card-head .more):hover {
  text-decoration: underline;
}

.draft-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.preview-card {
  margin-top: var(--space-3);
}

/* 底栏：左侧草稿版本说明，右侧一组操作，两者靠 auto 外边距左右分开。 */
.drawer-draft-version {
  min-width: 0;
  overflow-wrap: anywhere;
  font-variant-numeric: tabular-nums;
}

.drawer-action-group {
  display: flex;
  flex-wrap: wrap;
  flex: none;
  align-items: center;
  gap: var(--space-2);
  margin-inline-start: auto;
}

/* 纯图标按钮：复用 .bk-btn-sm 的高度与描边规则，只把左右内边距收成正方形。 */
.icon-btn {
  width: var(--button-small-height);
  padding-inline: 0;
}

.icon-btn svg {
  display: block;
}

/* 刷新进行中让图标匀速转一圈，作为「已在请求」的轻量反馈。 */
.icon-btn.is-loading svg {
  animation: icon-spin 900ms linear infinite;
}

@keyframes icon-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .icon-btn.is-loading svg {
    animation: none;
  }
}

.drawer-import {
  text-decoration: none;
}
</style>
