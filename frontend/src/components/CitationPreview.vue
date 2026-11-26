<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, useTemplateRef } from 'vue'
import {
  downloadCitation,
  getCitation,
  type CitationDocument,
  type CitationLabel,
} from '../api/knowledge'
import { describeError, validityLabel, validityTone } from '../api/display'
import { isApiError } from '../api/http'
import AssistantMessageText from './AssistantMessageText.vue'
import ModalShell from './ModalShell.vue'
import OverlayTransition from './OverlayTransition.vue'

type PreviewState =
  | { status: 'loading'; citation: CitationLabel }
  | { status: 'ready'; citation: CitationLabel; document: CitationDocument }
  | { status: 'error'; citation: CitationLabel; message: string }

const MARKDOWN_EXTENSIONS = ['md', 'markdown']

const state = shallowRef<PreviewState>()
const closeAction = useTemplateRef<HTMLButtonElement>('closeAction')
const preview = computed(() =>
  state.value?.status === 'ready' ? state.value.document : undefined,
)
/** 证据原文按原件格式呈现：Markdown 原件按 Markdown 渲染，其余格式保持纯文本。 */
const markdownExcerpt = computed(() => {
  const name = preview.value?.name ?? ''
  const dot = name.lastIndexOf('.')
  return (
    dot > 0 && MARKDOWN_EXTENSIONS.includes(name.slice(dot + 1).toLowerCase())
  )
})
let request: AbortController | undefined
let downloadRequest: AbortController | undefined
const downloading = ref(false)
const downloadError = ref('')

async function load(citation: CitationLabel): Promise<void> {
  request?.abort()
  resetDownload()
  const controller = new AbortController()
  request = controller
  state.value = { status: 'loading', citation }
  try {
    const document = await getCitation(citation.citationId, controller.signal)
    if (!controller.signal.aborted)
      state.value = { status: 'ready', citation, document }
  } catch (failure) {
    if (controller.signal.aborted) return
    const unavailable =
      isApiError(failure, 404, 'RESOURCE_NOT_FOUND') ||
      isApiError(failure, 403, 'FORBIDDEN')
    const message = describeError(
      failure,
      unavailable ? '该引用已不可用。' : undefined,
    )
    state.value = { status: 'error', citation, message }
  } finally {
    if (request === controller) request = undefined
  }
}

function open(citation: CitationLabel, event: MouseEvent): void {
  if (event.currentTarget instanceof HTMLButtonElement) {
    event.currentTarget.focus({ preventScroll: true })
  }
  void load(citation)
}

function resetDownload(): void {
  downloadRequest?.abort()
  downloadRequest = undefined
  downloading.value = false
  downloadError.value = ''
}

async function download(): Promise<void> {
  const current = state.value
  if (current?.status !== 'ready' || downloading.value) return
  const controller = new AbortController()
  downloadRequest = controller
  downloading.value = true
  downloadError.value = ''
  try {
    const file = await downloadCitation(
      current.citation.citationId,
      controller.signal,
    )
    const url = URL.createObjectURL(file)
    const link = globalThis.document.createElement('a')
    link.href = url
    link.download = current.document.name
    link.click()
    globalThis.setTimeout(() => {
      URL.revokeObjectURL(url)
    }, 1000)
  } catch (failure) {
    if (!controller.signal.aborted) downloadError.value = describeError(failure)
  } finally {
    if (downloadRequest === controller) {
      downloadRequest = undefined
      downloading.value = false
    }
  }
}

function close(): void {
  resetDownload()
  request?.abort()
  state.value = undefined
}

function retry(): void {
  if (state.value?.status !== 'error') return
  closeAction.value?.focus()
  void load(state.value.citation)
}

onBeforeUnmount(close)
defineExpose({ open, close })
</script>

<template>
  <Teleport to="body">
    <OverlayTransition>
      <ModalShell
        v-if="state"
        title="引用预览"
        width="640px"
        manage-focus
        @close="close"
      >
        <h4 class="source-name">{{ preview?.name || state.citation.label }}</h4>
        <p v-if="state.status === 'loading'" class="bk-muted" role="status">
          正在加载引用…
        </p>
        <p
          v-else-if="state.status === 'error'"
          class="bk-notice bk-notice-warn"
          role="alert"
        >
          {{ state.message }}
        </p>
        <template v-if="preview">
          <dl class="metadata bk-text-sm">
            <div>
              <dt>来源位置</dt>
              <dd>{{ preview.sourceLocation || '暂无位置标注' }}</dd>
            </div>
            <div>
              <dt>文档版本</dt>
              <dd>
                {{
                  preview.versionNumber != null
                    ? `第 ${preview.versionNumber} 版`
                    : preview.documentVersionId || '暂无版本信息'
                }}
              </dd>
            </div>
            <div>
              <dt>政策有效性</dt>
              <dd>
                <span
                  v-if="preview.validity"
                  :class="validityTone(preview.validity)"
                >
                  {{ validityLabel(preview.validity) }}
                </span>
                <span v-else>暂无有效性信息</span>
              </dd>
            </div>
          </dl>
          <h4>
            {{
              preview.evidenceGranularity === 'CHUNK' ? '证据原文' : '文档预览'
            }}
          </h4>
          <p
            v-if="preview.evidenceGranularity !== 'CHUNK'"
            class="bk-text-sm bk-muted"
          >
            无原文片段。
          </p>
          <div
            v-if="preview.previewText && markdownExcerpt"
            class="excerpt excerpt-markdown"
            tabindex="0"
          >
            <AssistantMessageText :text="preview.previewText" />
          </div>
          <p v-else-if="preview.previewText" class="excerpt" tabindex="0">
            {{ preview.previewText }}
          </p>
          <p v-else class="bk-muted">暂无可预览文本。</p>
        </template>
        <p v-if="downloadError" class="bk-notice bk-notice-warn" role="alert">
          {{ downloadError }}
        </p>
        <template #actions>
          <button ref="closeAction" class="bk-btn" type="button" @click="close">
            关闭预览
          </button>
          <button
            v-if="state.status === 'error'"
            class="bk-btn bk-btn-ghost"
            type="button"
            @click="retry"
          >
            重试
          </button>
          <button
            v-if="preview"
            class="bk-btn bk-btn-info"
            type="button"
            :disabled="downloading"
            @click="download"
          >
            {{ downloading ? '下载中…' : '下载原文件' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
  </Teleport>
</template>

<style scoped>
.source-name,
.metadata,
.excerpt {
  overflow-wrap: anywhere;
}

.metadata {
  display: grid;
  gap: var(--space-2);
  margin: var(--space-3) 0;
}

.metadata > div {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.metadata dt {
  color: var(--color-text-muted);
}

.metadata dd {
  min-width: 0;
  margin: 0;
}

.excerpt {
  margin: var(--space-2) 0 0;
  padding: var(--space-3);
  border-radius: var(--radius-sm);
  background: var(--color-canvas);
  white-space: pre-wrap;
}

/* Markdown 由消息正文组件自身排版，不再沿用纯文本的保留空白。 */
.excerpt-markdown {
  white-space: normal;
}
</style>
