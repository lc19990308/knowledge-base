<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getDocument, type DocumentPreview } from '../api/knowledge'
import { isApiError } from '../api/http'
import { describeError, statusLabel, statusTone } from '../api/display'

const props = defineProps<{ id: string }>()
const route = useRoute()
const doc = ref<DocumentPreview | null>(null)
const error = ref<string | null>(null)
const loading = ref(true)

const spaceId = computed(() => {
  const value = route.query.space
  return typeof value === 'string' ? value : ''
})

function formatSize(bytes: number): string {
  return bytes >= 1024 * 1024
    ? `${(bytes / (1024 * 1024)).toFixed(1)} MiB`
    : `${String(Math.max(1, Math.round(bytes / 1024)))} KiB`
}

function describe(failure: unknown): string {
  return describeError(
    failure,
    isApiError(failure, 404, 'RESOURCE_NOT_FOUND')
      ? '该文档不存在、来源已撤回，或你没有访问权限。'
      : undefined,
  )
}

watch(
  [spaceId, () => props.id],
  async ([space, id], _previous, onCleanup) => {
    doc.value = null
    error.value = null
    loading.value = space !== ''
    if (space === '') {
      error.value = '缺少空间上下文，请从工作台搜索进入。'
      return
    }

    const controller = new AbortController()
    const { signal } = controller
    onCleanup(() => {
      controller.abort()
    })
    const isCurrent = () =>
      !signal.aborted && spaceId.value === space && props.id === id
    try {
      const result = await getDocument(space, id, signal)
      if (isCurrent()) doc.value = result
    } catch (failure) {
      if (isCurrent()) error.value = describe(failure)
    } finally {
      if (isCurrent()) loading.value = false
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="document">
    <PageHeader :title="doc?.name ?? '文档预览'">
      <template #actions>
        <RouterLink class="bk-btn" to="/">返回工作台</RouterLink>
        <RouterLink class="bk-btn" to="/assistant">返回助手</RouterLink>
      </template>
    </PageHeader>

    <section class="bk-card" aria-live="polite">
      <div class="bk-card-head">
        <h3>文档内容</h3>
        <span v-if="doc" class="doc-meta">
          <span v-if="doc.versionNumber" class="bk-badge bk-badge-draft"
            >v{{ doc.versionNumber }}</span
          >
          <span :class="statusTone(doc.status)">{{
            statusLabel(doc.status)
          }}</span>
        </span>
      </div>
      <p v-if="doc" class="bk-text-xs bk-muted doc-sub">
        文档标识 {{ doc.documentVersionId.slice(0, 8) }}
        <template v-if="doc.sizeBytes">
          · {{ formatSize(doc.sizeBytes) }}
        </template>
        <template v-if="doc.errorCode">
          · 失败原因 {{ statusLabel(doc.errorCode) }}
        </template>
      </p>

      <p v-if="loading" class="bk-text-sm bk-muted">正在加载…</p>
      <div v-else-if="error" class="bk-notice bk-notice-fail" role="alert">
        <span class="n-icon">⛔</span>
        <span>{{ error }}</span>
      </div>
      <template v-else-if="doc">
        <p v-if="doc.previewText" class="bk-text-sm content-text">
          {{ doc.previewText }}
        </p>
        <p v-else class="bk-text-sm bk-muted">
          该文档没有可提取的正文（例如纯扫描件）。
        </p>
      </template>
    </section>
  </div>
</template>

<style scoped>
.document {
  display: grid;
  gap: var(--space-4);
}

.doc-meta {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.doc-sub {
  margin-bottom: var(--space-3);
  overflow-wrap: anywhere;
}

.content-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
  color: var(--color-text-secondary);
}
</style>
