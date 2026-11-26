<script setup lang="ts">
import FormFileInput from './FormFileInput.vue'
import ModalShell from './ModalShell.vue'
import { computed, onBeforeUnmount, reactive, ref, shallowRef } from 'vue'
import {
  getDraft,
  getImportBatch,
  importDocuments,
  replaceDraft,
  retryDocument,
  type DraftEntry,
  type ImportBatch,
  type ImportFile,
  type Space,
} from '../api/knowledge'
import {
  describeError,
  fileCodeHint,
  formatBytes,
  statusLabel,
} from '../api/display'
import { isApiError, newIdempotencyKey } from '../api/http'
import { pollUntil } from '../api/polling'

const props = defineProps<{ space: Space }>()
const emit = defineEmits<{ close: []; imported: [] }>()

const files = shallowRef<File[]>([])
const batch = ref<ImportBatch | null>(null)
const busy = ref(false)
const merging = ref(false)
const polling = ref(false)
const merged = ref(false)
const error = ref<string | null>(null)
const notice = ref<{ tone: string; text: string } | null>(null)
let batchController = new AbortController()
let uploadKey: string | undefined
const retrying = reactive(new Set<string>())
const uploadProgress = ref<number | null>(null)
const previewUrls = new Map<File, string>()
let refreshNeeded = false
let previewGeneration = 0
const pollingPaused = ref(false)

function clearBatch(): void {
  batchController.abort()
  batchController = new AbortController()
  refreshNeeded = false
  polling.value = false
  pollingPaused.value = false
  batch.value = null
  merged.value = false
  error.value = null
  notice.value = null
}

const MAX_FILE_BYTES = 10 * 1024 * 1024
const MAX_FILES = 10
const FILE_EXTENSIONS = ['pdf', 'docx', 'xlsx', 'pptx', 'txt', 'md']
const ACCEPT = FILE_EXTENSIONS.map((extension) => `.${extension}`).join(',')

const totalBytes = computed(() =>
  files.value.reduce((sum, file) => sum + file.size, 0),
)
const fileError = computed(() => {
  if (files.value.length > MAX_FILES) return '每批最多选择 10 个文件。'
  for (const file of files.value) {
    const name = file.name.trim()
    const dot = name.lastIndexOf('.')
    if (
      name.startsWith('.') ||
      name.length > 255 ||
      dot <= 0 ||
      !FILE_EXTENSIONS.includes(name.slice(dot + 1).toLowerCase())
    ) {
      return `「${file.name}」的文件名或格式不受支持，请选择 ${ACCEPT}。`
    }
    if (file.size === 0) return `「${file.name}」是空文件。`
    if (file.size > MAX_FILE_BYTES) return `「${file.name}」超过 10 MiB。`
  }
  return null
})

const summary = computed(() => {
  if (!batch.value) return null
  const items = batch.value.files
  const count = (status: ImportFile['status']) =>
    items.filter((file) => file.status === status).length
  return {
    total: items.length,
    ready: count('READY'),
    skipped: count('SKIPPED_DUPLICATE'),
    noText: count('NO_TEXT'),
    failed: count('FAILED'),
    rejected: count('REJECTED'),
  }
})

const stepState = computed(() => {
  const hasResult = batch.value !== null
  const finished = batch.value !== null && !isProcessing(batch.value)
  return [
    { label: '选择文件', done: files.value.length > 0, now: !hasResult },
    {
      label: '上传并处理',
      done: hasResult && finished,
      now: hasResult && !finished,
    },
    {
      label: '加入空间草稿',
      done: merged.value,
      now: finished && !merged.value,
    },
  ]
})

interface LocalFilePreview {
  id: string
  file: File
  extension: string
  objectUrl?: string | undefined
  text?: string | undefined
  message?: string | undefined
}
const localPreviews = shallowRef<LocalFilePreview[]>([])

function releasePreviews(): void {
  for (const url of previewUrls.values()) URL.revokeObjectURL(url)
  previewUrls.clear()
  localPreviews.value = []
}

function filePreviewUrl(file: File): string {
  const existing = previewUrls.get(file)
  if (existing) return existing
  const url = URL.createObjectURL(file.slice(0, file.size, 'application/pdf'))
  previewUrls.set(file, url)
  return url
}

function fileCode(file: ImportFile): string {
  const name = file.name
  const dot = name.lastIndexOf('.')
  return dot > 0
    ? name
        .slice(dot + 1)
        .toUpperCase()
        .slice(0, 3)
    : 'FILE'
}

function badgeFor(status: ImportFile['status']): string {
  switch (status) {
    case 'READY':
      return 'bk-badge bk-badge-ok'
    case 'SKIPPED_DUPLICATE':
      return 'bk-badge bk-badge-warn'
    case 'NO_TEXT':
      return 'bk-badge bk-badge-draft'
    case 'FAILED':
    case 'REJECTED':
      return 'bk-badge bk-badge-fail'
    default:
      return 'bk-badge bk-badge-info'
  }
}

function describe(failure: unknown): string {
  if (isApiError(failure, 413, 'PAYLOAD_TOO_LARGE')) {
    return '本批次超过体积上限，请拆分后重试。'
  }
  return describeError(failure)
}

async function onPick(selectedFiles: File[]): Promise<void> {
  const generation = ++previewGeneration
  releasePreviews()
  uploadKey = undefined
  clearBatch()
  files.value = selectedFiles
  const rows = selectedFiles.map((file, index) => ({
    id: `${String(generation)}-${String(index)}`,
    file,
    extension: file.name.split('.').pop()?.toLowerCase() ?? '',
  }))
  localPreviews.value = rows
  if (fileError.value) return
  const previews = await Promise.all(
    rows.map(async (row): Promise<LocalFilePreview> => {
      const { file, extension } = row
      try {
        if (extension === 'pdf') {
          const signature = await file.slice(0, 5).text()
          if (generation !== previewGeneration) return row
          return signature === '%PDF-'
            ? { ...row, objectUrl: filePreviewUrl(file) }
            : { ...row, message: '无法读取 PDF 预览。' }
        }
        if (extension === 'txt' || extension === 'md') {
          const limit = 512 * 1024
          const text = await file.slice(0, limit).text()
          return {
            ...row,
            text,
            message: file.size > limit ? '仅显示前 512 KiB。' : undefined,
          }
        }
        return { ...row, message: '上传并解析后，可在文档列表查看正文预览。' }
      } catch {
        return { ...row, message: '无法读取预览。' }
      }
    }),
  )
  if (generation === previewGeneration) {
    localPreviews.value = previews.filter((row) =>
      files.value.includes(row.file),
    )
  }
}

function removeFile(file: File): void {
  void onPick(files.value.filter((selected) => selected !== file))
}

async function upload(): Promise<void> {
  if (
    !props.space.canMaintain ||
    files.value.length === 0 ||
    busy.value ||
    fileError.value
  )
    return
  clearBatch()
  const signal = batchController.signal
  busy.value = true
  uploadProgress.value = 0
  try {
    uploadKey ??= newIdempotencyKey()
    const accepted = await importDocuments(
      props.space.spaceId,
      files.value,
      uploadKey,
      (percent) => {
        uploadProgress.value = percent
      },
      signal,
    )
    if (signal.aborted) return
    uploadKey = undefined
    uploadProgress.value = 100
    batch.value = accepted
    if (isProcessing(accepted)) void refreshBatch(false)
    else await mergeIntoDraft()
  } catch (failure) {
    if (!signal.aborted) error.value = describe(failure)
  } finally {
    busy.value = false
  }
}

function isProcessing(value: ImportBatch): boolean {
  // 批次状态可能仍为 PROCESSING，逐文件状态才是处理进度的来源。
  return value.files.some(
    (file) => file.status === 'QUEUED' || file.status === 'PROCESSING',
  )
}

async function refreshBatch(immediate = true): Promise<void> {
  const id = batch.value?.batchId
  if (!id || polling.value) return
  const signal = batchController.signal
  polling.value = true
  pollingPaused.value = false
  try {
    const result = await pollUntil(
      (requestSignal) => {
        refreshNeeded = false
        return getImportBatch(id, requestSignal)
      },
      {
        signal,
        delayMs: 1500,
        timeoutMs: 120_000,
        waitBeforeFirst: !immediate,
        isComplete: (value) => !isProcessing(value) && !refreshNeeded,
        onValue: (value) => {
          batch.value = value
        },
      },
    )
    if (!signal.aborted) {
      pollingPaused.value = !result
      if (result && batch.value && !isProcessing(batch.value)) {
        void mergeIntoDraft()
      }
    }
  } catch (failure) {
    if (!signal.aborted) {
      error.value = describe(failure)
      pollingPaused.value = true
    }
  } finally {
    if (!signal.aborted) {
      polling.value = false
      if (refreshNeeded && !pollingPaused.value) void refreshBatch()
    }
  }
}

async function retry(file: ImportFile): Promise<void> {
  const prior = batch.value
  const documentId = file.documentVersionId
  if (!documentId || !prior || retrying.has(documentId)) return
  retrying.add(documentId)
  const signal = batchController.signal
  error.value = null
  try {
    await retryDocument(prior.spaceId, documentId)
    if (signal.aborted) return
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已重新排队：${file.name}`,
    }
    const current = batch.value
    if (current) {
      batch.value = {
        ...current,
        files: current.files.map((item) =>
          item.documentVersionId === documentId && item.status === 'FAILED'
            ? { ...item, status: 'QUEUED' }
            : item,
        ),
      }
    }
    merged.value = false
    refreshNeeded = true
    void refreshBatch()
  } catch (failure) {
    if (!signal.aborted) error.value = describe(failure)
  } finally {
    retrying.delete(documentId)
  }
}

/** 导入的版本进入草稿后才可提审，未加入草稿时明确阻止。 */
async function mergeIntoDraft(): Promise<void> {
  const current = batch.value
  if (!current || merging.value || merged.value || isProcessing(current)) return
  if (
    !current.files.some(
      (file) =>
        file.documentVersionId &&
        (file.status === 'READY' || file.status === 'NO_TEXT'),
    )
  )
    return
  const { signal } = batchController
  merging.value = true
  error.value = null
  notice.value = null
  try {
    const draft = await getDraft(current.spaceId, signal)
    if (signal.aborted) return
    const existing = new Set(
      draft.data.entries.map((entry) => entry.documentVersionId),
    )
    const additions: DraftEntry[] = current.files
      .filter(
        (file) =>
          file.documentVersionId !== undefined &&
          (file.status === 'READY' || file.status === 'NO_TEXT') &&
          !existing.has(file.documentVersionId),
      )
      .map((file) => ({
        documentVersionId: file.documentVersionId ?? '',
        effectiveFrom: null,
        effectiveUntil: null,
        policyState: 'EFFECTIVE' as const,
        replacesDocumentVersionId: null,
        name: file.name,
        parseState: null,
        sizeBytes: file.sizeBytes,
        mediaType: file.mediaType,
        versionNumber: file.versionNumber,
        createdAt: null,
      }))
    if (additions.length > 0) {
      if (!draft.etag) throw new Error('无法获取草稿版本，请重试加入草稿。')
      await replaceDraft(current.spaceId, draft.etag, [
        ...draft.data.entries,
        ...additions,
      ])
    }
    signal.throwIfAborted()
    merged.value = true
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text:
        additions.length > 0
          ? `已将 ${String(additions.length)} 份文档加入草稿。`
          : '文档已在草稿中。',
    }
    emit('imported')
  } catch (failure) {
    if (!signal.aborted) error.value = describe(failure)
  } finally {
    merging.value = false
  }
}

function closeDialog(): void {
  if (busy.value || merging.value || polling.value || retrying.size > 0) return
  emit('close')
}

onBeforeUnmount(() => {
  previewGeneration++
  batchController.abort()
  releasePreviews()
})
</script>

<template>
  <ModalShell
    :title="`导入文档 · ${space.name}`"
    width="720px"
    @close="closeDialog"
  >
    <div class="import-dialog">
      <div class="bk-steps" aria-label="导入流程">
        <template v-for="(step, index) in stepState" :key="step.label">
          <span class="bk-step" :class="{ done: step.done, now: step.now }">
            <span class="s-idx">{{ step.done ? '✓' : index + 1 }}</span>{{
              step.label
            }}
          </span>
          <span v-if="index < stepState.length - 1" class="bk-step-arrow"
            >→</span
          >
        </template>
      </div>
      <p class="bk-notice bk-notice-info">
        <span class="n-icon">ℹ</span>
        <span
          >每批最多 10 个文件，单文件不超过 10
          MiB。同名文件跳过，不覆盖原文件。</span
        >
      </p>
      <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
        {{ error }}
      </p>
      <p v-if="notice" :class="notice.tone" role="status">{{ notice.text }}</p>

      <section v-if="!batch" aria-label="选择上传文件">
        <div class="dropzone">
          <b>选择文件</b>
          支持 PDF、DOCX、XLSX、PPTX、TXT 和 MD，最大 10 MiB。
          <div class="e-actions">
            <FormFileInput
              :accept="ACCEPT"
              multiple
              :disabled="busy"
              @change="onPick"
              >选择文件</FormFileInput
            >
          </div>
        </div>
        <p v-if="files.length" class="bk-text-sm bk-muted">
          已选 {{ files.length }} 个文件 · {{ formatBytes(totalBytes) }}
        </p>
        <div
          v-if="files.length"
          class="selected-files"
          aria-label="已选文件预览"
        >
          <article
            v-for="preview in localPreviews"
            :key="preview.id"
            class="file-preview"
          >
            <div class="file-preview-head">
              <div class="f-main">
                <div class="f-name">{{ preview.file.name }}</div>
                <div class="f-sub">
                  {{ formatBytes(preview.file.size) }} ·
                  {{ preview.extension.toUpperCase() }}
                </div>
              </div>
              <a
                v-if="preview.objectUrl"
                class="bk-btn bk-btn-sm bk-btn-info"
                :href="preview.objectUrl"
                target="_blank"
                rel="noopener"
                :aria-label="`打开文件预览：${preview.file.name}`"
                >预览</a
              >
              <button
                class="bk-btn bk-btn-sm bk-btn-ghost bk-btn-danger"
                type="button"
                :disabled="busy"
                :aria-label="`移除文件：${preview.file.name}`"
                @click="removeFile(preview.file)"
              >
                ×
              </button>
            </div>
            <pre v-if="preview.text !== undefined" class="preview-text">{{
              preview.text
            }}</pre>
            <p v-if="preview.message" class="bk-text-xs bk-muted">
              {{ preview.message }}
            </p>
          </article>
        </div>
        <p v-if="fileError" class="bk-notice bk-notice-fail" role="alert">
          {{ fileError }}
        </p>

        <div
          v-if="busy && uploadProgress !== null"
          class="upload-progress"
          role="status"
          aria-live="polite"
        >
          <span>{{
            uploadProgress === 100
              ? '已发送，等待服务端确认…'
              : `上传中 ${uploadProgress}%`
          }}</span>
          <progress
            :value="uploadProgress"
            max="100"
            :aria-label="`上传进度 ${uploadProgress}%`"
          ></progress>
        </div>
      </section>

      <template v-if="batch && summary">
        <div class="summary-bar" role="status">
          本次 {{ summary.total }} 个文件：<b>{{ summary.ready }}</b> 可检索 ·
          <b>{{ summary.skipped }}</b> 同名已跳过 ·
          <b>{{ summary.noText }}</b> 无可提取正文 ·
          <b>{{ summary.failed }}</b> 处理失败（可重试）
          <template v-if="summary.rejected > 0"
            >· <b>{{ summary.rejected }}</b> 被拒绝</template
          >
          <template v-if="polling">· 正在刷新状态…</template>
          <template v-else-if="pollingPaused">
            · 状态查询已暂停
            <button
              class="bk-btn bk-btn-sm bk-btn-ghost"
              type="button"
              @click="refreshBatch()"
            >
              继续查询
            </button>
          </template>
        </div>
        <div
          v-for="(file, index) in batch.files"
          :key="`${batch.batchId}-${String(index)}`"
          class="import-row"
        >
          <span class="f-icon">{{ fileCode(file) }}</span>
          <div class="f-main">
            <div class="f-name">{{ file.name }}</div>
            <div class="f-sub">
              {{ formatBytes(file.sizeBytes ?? 0) }}
              <template v-if="file.versionNumber"
                >· v{{ file.versionNumber }}</template
              >
              · {{ statusLabel(file.status) }}
              <template v-if="fileCodeHint(file.code)"
                >· {{ fileCodeHint(file.code) }}</template
              >
            </div>
          </div>
          <div class="f-status">
            <span :class="badgeFor(file.status)"
              ><span class="bk-dot"></span>{{ statusLabel(file.status) }}</span
            >
            <button
              v-if="file.status === 'FAILED' && file.documentVersionId"
              class="bk-btn bk-btn-sm bk-btn-ghost"
              type="button"
              :disabled="retrying.has(file.documentVersionId)"
              @click="retry(file)"
            >
              {{ retrying.has(file.documentVersionId) ? '重试中…' : '重试' }}
            </button>
          </div>
        </div>
        <button
          v-if="
            error &&
            !pollingPaused &&
            !merged &&
            batch.files.some(
              (file) => file.status === 'READY' || file.status === 'NO_TEXT',
            )
          "
          class="bk-btn bk-btn-sm bk-btn-neutral"
          type="button"
          :disabled="merging || retrying.size > 0"
          @click="mergeIntoDraft"
        >
          重试加入草稿
        </button>
      </template>
    </div>
    <template #actions>
      <button
        class="bk-btn"
        type="button"
        :disabled="busy || merging || polling || retrying.size > 0"
        @click="closeDialog"
      >
        关闭
      </button>
      <button
        v-if="!batch"
        class="bk-btn bk-btn-primary"
        type="button"
        :disabled="
          busy || files.length === 0 || !space.canMaintain || fileError !== null
        "
        @click="upload"
      >
        {{
          busy
            ? uploadProgress === 100
              ? '处理中…'
              : `上传中 ${uploadProgress ?? 0}%`
            : '开始导入'
        }}
      </button>
    </template>
  </ModalShell>
</template>

<style scoped>
.dropzone {
  border: 1.5px dashed var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-8);
  text-align: center;
  color: var(--color-text-muted);
  background: var(--color-canvas);
  margin-bottom: var(--space-5);
}

.dropzone b {
  display: block;
  font-size: var(--text-base);
  color: var(--color-text);
  margin-bottom: 4px;
}

.dropzone .e-actions {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}

.import-dialog,
.selected-files,
.upload-progress {
  display: grid;
  gap: var(--space-3);
}

.file-preview {
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.file-preview-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.preview-text {
  max-height: 12rem;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: var(--text-xs) var(--font-mono);
}

.upload-progress progress {
  width: 100%;
  accent-color: var(--color-action);
}

.summary-bar {
  display: flex;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--color-canvas);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  margin-bottom: var(--space-2);
}

.summary-bar b {
  font-variant-numeric: tabular-nums;
}

.import-row {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-4) 0;
  border-bottom: 1px solid var(--color-divider);
}

.import-row:last-of-type {
  border-bottom: none;
}

.f-icon {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-sm);
  flex: none;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  background: var(--color-divider);
  color: var(--color-text-secondary);
}

.f-main {
  flex: 1;
  min-width: 0;
}

.f-name {
  font-weight: 600;
  font-size: var(--text-sm);
  overflow-wrap: anywhere;
}

.f-sub {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  margin-top: 2px;
  line-height: 1.5;
}

.f-status {
  flex: none;
  width: 200px;
  text-align: right;
}

@media (max-width: 720px) {
  .f-status {
    width: auto;
  }
}
</style>
