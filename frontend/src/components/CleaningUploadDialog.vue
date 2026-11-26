<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import ModalShell from './ModalShell.vue'
import FormInput from './FormInput.vue'
import FormFileInput from './FormFileInput.vue'
import SelectMenu from './SelectMenu.vue'
import { FORM_LIMITS } from '../formValidation'
import { describeError, formatBytes } from '../api/display'
import { uploadCleaningBatch, type CleaningBatch } from '../api/cleaning'
import type { Space } from '../api/knowledge'

/**
 * 上传原始数据集：文件夹（保留相对路径）与压缩包可以同时提交。
 *
 * 体积与数量限制在前端先拦一次，避免把注定被拒的批次传上去；
 * 真正的安全校验（解包路径、条目数、压缩比）仍在后端完成。
 */
const props = defineProps<{ spaces: Space[] }>()
const emit = defineEmits<{ close: []; accepted: [batch: CleaningBatch] }>()

const MAX_FILES = 500
const MAX_FILE_BYTES = 10 * 1024 * 1024
const MAX_BATCH_BYTES = 200 * 1024 * 1024
const MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
const ACCEPT_FILES =
  '.txt,.md,.csv,.tsv,.json,.jsonl,.html,.htm,.xlsx,.pdf,.docx,.pptx'
const ACCEPT_ARCHIVE = '.zip'

interface PickedFile {
  file: File
  path: string
}

const files = ref<PickedFile[]>([])
const archive = ref<File | null>(null)
const name = ref('')
const targetSpaceId = ref('')
const busy = ref(false)
const progress = ref<number | null>(null)
const error = ref<string | null>(null)
const notice = ref<string | null>(null)
let upload: AbortController | undefined

const spaceOptions = computed(() => [
  { label: '不指定（流转时再选）', value: '' },
  ...props.spaces.map((space) => ({
    label: space.canMaintain ? space.name : `${space.name}（只读）`,
    value: space.spaceId,
  })),
])

const totalBytes = computed(
  () =>
    files.value.reduce((sum, entry) => sum + entry.file.size, 0) +
    (archive.value?.size ?? 0),
)

const canSubmit = computed(
  () =>
    !busy.value &&
    (files.value.length > 0 || archive.value !== null) &&
    totalBytes.value <= MAX_BATCH_BYTES,
)

/** 关闭弹窗：上传中不允许关闭，避免请求被悄悄中断。 */
function close(): void {
  if (busy.value) return
  upload?.abort()
  emit('close')
}

/** 选择文件夹里的文件：与已选的合并，按相对路径去重。 */
function onPickFiles(picked: File[], paths: string[]): void {
  error.value = null
  notice.value = null
  const merged = new Map(files.value.map((entry) => [entry.path, entry]))
  for (const [index, file] of picked.entries()) {
    if (file.size > MAX_FILE_BYTES) {
      error.value = `${file.name} 超过单文件 10 MB，请拆分后再上传。`
      continue
    }
    const path = paths[index] ?? file.name
    merged.set(path, { file, path })
  }
  const next = [...merged.values()]
  if (next.length > MAX_FILES) {
    error.value = `单批最多 ${String(MAX_FILES)} 个文件，请分批上传。`
    return
  }
  const bytes =
    next.reduce((sum, entry) => sum + entry.file.size, 0) +
    (archive.value?.size ?? 0)
  if (bytes > MAX_BATCH_BYTES) {
    error.value = '整批超过 200 MB，请分批上传。'
    return
  }
  files.value = next
}

/** 选择压缩包：只接受 zip，大小按压缩前体积限制。 */
function onPickArchive(picked: File[]): void {
  error.value = null
  notice.value = null
  const file = picked[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    error.value = '压缩包仅支持 .zip 格式。'
    return
  }
  if (file.size > MAX_ARCHIVE_BYTES) {
    error.value = '压缩包超过 64 MB，请拆分后再上传。'
    return
  }
  const filesBytes = files.value.reduce(
    (sum, entry) => sum + entry.file.size,
    0,
  )
  if (filesBytes + file.size > MAX_BATCH_BYTES) {
    error.value = '文件夹与压缩包合计超过 200 MB，请分批上传。'
    return
  }
  archive.value = file
}

function removeFile(path: string): void {
  files.value = files.value.filter((entry) => entry.path !== path)
}

function removeArchive(): void {
  archive.value = null
}

/** 提交批次：成功后把结果交给页面继续轮询，不在弹窗里等待清洗完成。 */
async function submit(): Promise<void> {
  if (!canSubmit.value) return
  busy.value = true
  error.value = null
  notice.value = null
  progress.value = 0
  upload = new AbortController()
  try {
    const batch = await uploadCleaningBatch(
      {
        files: files.value,
        archive: archive.value,
        name: name.value,
        targetSpaceId: targetSpaceId.value === '' ? null : targetSpaceId.value,
      },
      {
        onUploadProgress: (percent) => {
          progress.value = percent
        },
        signal: upload.signal,
      },
    )
    emit('accepted', batch)
  } catch (failure) {
    if (upload.signal.aborted) return
    error.value = describeError(failure)
  } finally {
    busy.value = false
    progress.value = null
  }
}

onBeforeUnmount(() => {
  upload?.abort()
})
</script>

<template>
  <ModalShell title="上传原始数据" width="680px" @close="close">
    <form id="cleaning-upload-form" @submit.prevent="submit">
      <p class="bk-notice bk-notice-info">
        <span class="n-icon">ℹ</span>
        <span
          >支持 TXT、MD、CSV、TSV、JSON、JSONL、HTML、XLSX、PDF、DOCX、PPTX；
          压缩包仅支持 .zip，其余文件会被忽略并计入「已忽略」。</span
        >
      </p>

      <div class="dropzone">
        <b>选择要清洗的原始数据</b>
        <p class="bk-text-sm bk-muted">
          单批最多 {{ String(MAX_FILES) }} 个文件，单文件 10 MB，整批 200 MB；
          压缩包 64 MB。
        </p>
        <div class="e-actions">
          <FormFileInput
            :accept="ACCEPT_FILES"
            directory
            :disabled="busy"
            @change="onPickFiles"
            >选择文件夹</FormFileInput
          >
          <FormFileInput
            :accept="ACCEPT_ARCHIVE"
            secondary
            :disabled="busy"
            @change="onPickArchive"
            >选择压缩包</FormFileInput
          >
        </div>
      </div>

      <p v-if="files.length || archive" class="bk-text-sm bk-muted">
        已选 {{ String(files.length) }} 个文件<template v-if="archive"
          >、1 个压缩包</template
        >
        · {{ formatBytes(totalBytes) }}
      </p>

      <ul v-if="files.length || archive" class="picked-list">
        <li v-if="archive" class="picked-row">
          <span class="picked-name">{{ archive.name }}</span>
          <span class="picked-meta">{{ formatBytes(archive.size) }}</span>
          <button
            class="bk-btn bk-btn-sm bk-btn-ghost bk-btn-danger"
            type="button"
            :aria-label="`移除压缩包：${archive.name}`"
            :disabled="busy"
            @click="removeArchive"
          >
            ×
          </button>
        </li>
        <li
          v-for="entry in files.slice(0, 8)"
          :key="entry.path"
          class="picked-row"
        >
          <span class="picked-name" :title="entry.path">{{ entry.path }}</span>
          <span class="picked-meta">{{ formatBytes(entry.file.size) }}</span>
          <button
            class="bk-btn bk-btn-sm bk-btn-ghost bk-btn-danger"
            type="button"
            :aria-label="`移除文件：${entry.path}`"
            :disabled="busy"
            @click="removeFile(entry.path)"
          >
            ×
          </button>
        </li>
        <li v-if="files.length > 8" class="bk-text-xs bk-muted">
          还有 {{ String(files.length - 8) }} 个文件未展开显示。
        </li>
      </ul>

      <div class="bk-field">
        <label for="cleaning-batch-name">批次名</label>
        <FormInput
          id="cleaning-batch-name"
          v-model="name"
          :maxlength="FORM_LIMITS.batchName"
          placeholder="留空则按压缩包名或上传时间自动命名"
          :disabled="busy"
        />
      </div>
      <div class="bk-field">
        <label>目标空间（可选）</label>
        <SelectMenu
          v-model="targetSpaceId"
          label="目标空间"
          :options="spaceOptions"
          :disabled="busy"
        />
        <span class="bk-text-xs bk-muted"
          >留空时，审核通过后再选择流转到哪个空间。</span
        >
      </div>

      <div
        v-if="busy && progress !== null"
        class="upload-progress"
        role="status"
        aria-live="polite"
      >
        <span>{{
          progress === 100
            ? '已发送，等待服务端登记批次…'
            : `上传中 ${String(progress)}%`
        }}</span>
        <progress
          :value="progress"
          max="100"
          :aria-label="`上传进度 ${String(progress)}%`"
        ></progress>
      </div>

      <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
        {{ error }}
      </p>
      <p v-if="notice" class="bk-notice bk-notice-ok" role="status">
        {{ notice }}
      </p>
    </form>

    <template #actions>
      <button class="bk-btn" type="button" :disabled="busy" @click="close">
        取消
      </button>
      <button
        class="bk-btn bk-btn-primary"
        type="submit"
        form="cleaning-upload-form"
        :disabled="!canSubmit"
      >
        {{ busy ? '上传中…' : '开始清洗' }}
      </button>
    </template>
  </ModalShell>
</template>

<style scoped>
.dropzone {
  margin: var(--space-4) 0;
  padding: var(--space-5);
  text-align: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-panel);
  background: var(--color-canvas);
}

.dropzone b {
  display: block;
  margin-bottom: var(--space-2);
  font-size: var(--text-sm);
}

.dropzone .e-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.picked-list {
  max-height: 220px;
  margin: 0 0 var(--space-4);
  padding: 0;
  overflow-y: auto;
  list-style: none;
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-control);
}

.picked-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px var(--space-3);
  font-size: var(--text-sm);
  border-bottom: 1px solid var(--color-divider);
}

.picked-row:last-child {
  border-bottom: 0;
}

.picked-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picked-meta {
  flex: none;
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.upload-progress {
  display: grid;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
  font-size: var(--text-sm);
  color: var(--color-text-muted);
}

.upload-progress progress {
  width: 100%;
  height: 6px;
  accent-color: var(--color-action);
}
</style>
