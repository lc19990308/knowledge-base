<script setup lang="ts">
import { X } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import FormInput from './FormInput.vue'
import { FORM_LIMITS } from '../formValidation'
import { describeError } from '../api/display'
import { listSpaces, type Space } from '../api/knowledge'

const props = defineProps<{
  modelValue: string[]
  /** 父页面已加载的空间，用于解析已选空间的名称；搜索结果会覆盖同 id 的旧记录。 */
  knownSpaces?: Space[]
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

/** 结果列表每次取 20 条，翻到底再加载下一页。 */
const PAGE_SIZE = 20
/** 名称筛选去抖间隔，与知识库页面保持一致。 */
const FILTER_DELAY_MS = 250
/** 已选区超过这个数量就折叠，避免把弹窗撑高。 */
const COLLAPSE_AFTER = 8
/** 「全选筛选结果」一次取到接口上限。 */
const BULK_LIMIT = 200

const query = ref('')
const results = shallowRef<Space[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)
const expanded = ref(false)
const bulkBusy = ref(false)

const spaceById = ref<Map<string, Space>>(new Map())
let controller: AbortController | undefined
let filterTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let generation = 0

function badgeOf(space: Space): { cls: string; text: string } {
  if (space.currentPublicationId)
    return { cls: 'bk-badge bk-badge-published', text: '已发布' }
  if (space.documentCount > 0)
    return { cls: 'bk-badge bk-badge-review', text: '未发布' }
  return { cls: 'bk-badge bk-badge-draft', text: '草稿' }
}

function metaOf(space: Space): string {
  return `${String(space.documentCount)} 份文档 · ${String(space.searchableCount)} 份可检索`
}

function placeholderName(spaceId: string): string {
  return `空间 ${spaceId.slice(0, 8)}`
}

interface SelectedView {
  spaceId: string
  name: string
  meta: string
  badgeClass: string
  badgeText: string
}

const selectedSpaces = computed<SelectedView[]>(() =>
  props.modelValue.map((spaceId) => {
    const space = spaceById.value.get(spaceId)
    if (!space) {
      return {
        spaceId,
        name: placeholderName(spaceId),
        meta: '名称待加载',
        badgeClass: 'bk-badge bk-badge-draft',
        badgeText: '未解析',
      }
    }
    const badge = badgeOf(space)
    return {
      spaceId,
      name: space.name,
      meta: metaOf(space),
      badgeClass: badge.cls,
      badgeText: badge.text,
    }
  }),
)

const visibleSelected = computed(() =>
  expanded.value || selectedSpaces.value.length <= COLLAPSE_AFTER
    ? selectedSpaces.value
    : selectedSpaces.value.slice(0, COLLAPSE_AFTER),
)
const hiddenCount = computed(() =>
  Math.max(0, selectedSpaces.value.length - COLLAPSE_AFTER),
)

function mergeSpaces(items: Space[] | undefined): void {
  if (!items || items.length === 0) return
  const next = new Map(spaceById.value)
  for (const item of items) next.set(item.spaceId, item)
  spaceById.value = next
}

function isSelected(spaceId: string): boolean {
  return props.modelValue.includes(spaceId)
}

function add(space: Space): void {
  if (props.disabled || isSelected(space.spaceId)) return
  emit('update:modelValue', [...props.modelValue, space.spaceId])
}

function remove(spaceId: string): void {
  if (props.disabled) return
  emit(
    'update:modelValue',
    props.modelValue.filter((value) => value !== spaceId),
  )
}

async function load(reset: boolean): Promise<void> {
  if (reset) {
    controller?.abort()
    loading.value = true
    error.value = null
    results.value = []
    total.value = 0
  } else if (loading.value) {
    return
  }
  const run = reset ? ++generation : generation
  const request = new AbortController()
  if (reset) controller = request
  const offset = reset ? 0 : results.value.length
  try {
    const page = await listSpaces({
      q: query.value.trim(),
      limit: PAGE_SIZE,
      offset,
      signal: request.signal,
    })
    if (request.signal.aborted || run !== generation) return
    results.value = reset ? page.items : [...results.value, ...page.items]
    total.value = page.total
    mergeSpaces(page.items)
  } catch (failure) {
    if (request.signal.aborted) return
    error.value = describeError(failure)
  } finally {
    if (run === generation) loading.value = false
  }
}

function loadMore(): void {
  void load(false)
}

/** 把当前筛选条件命中的空间一次全部加入，用接口上限兜底。 */
async function addAllFiltered(): Promise<void> {
  if (props.disabled || bulkBusy.value) return
  bulkBusy.value = true
  error.value = null
  try {
    const page = await listSpaces({
      q: query.value.trim(),
      limit: BULK_LIMIT,
    })
    mergeSpaces(page.items)
    const merged = new Set(props.modelValue)
    for (const item of page.items) merged.add(item.spaceId)
    emit('update:modelValue', [...merged])
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    bulkBusy.value = false
  }
}

function scheduleSearch(): void {
  globalThis.clearTimeout(filterTimer)
  filterTimer = globalThis.setTimeout(() => void load(true), FILTER_DELAY_MS)
}

watch(query, scheduleSearch)

watch(
  () => props.knownSpaces,
  (spaces) => {
    mergeSpaces(spaces)
  },
  { immediate: true },
)

watch(
  () => props.modelValue.length,
  () => {
    if (props.modelValue.length <= COLLAPSE_AFTER) expanded.value = false
  },
)

void load(true)

onBeforeUnmount(() => {
  globalThis.clearTimeout(filterTimer)
  controller?.abort()
})
</script>

<template>
  <section class="space-binding" aria-label="绑定空间">
    <div class="binding-head">
      <span class="binding-title">已选 {{ modelValue.length }} 个空间</span>
      <button
        v-if="hiddenCount > 0"
        class="link-button"
        type="button"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起' : `展开其余 ${hiddenCount} 个` }}
      </button>
    </div>

    <div v-if="selectedSpaces.length > 0" class="chip-list" role="list">
      <span
        v-for="space in visibleSelected"
        :key="space.spaceId"
        class="binding-chip"
        role="listitem"
        :title="`${space.name} · ${space.meta}`"
      >
        <span class="chip-name">{{ space.name }}</span>
        <span :class="space.badgeClass">{{ space.badgeText }}</span>
        <button
          class="chip-remove"
          type="button"
          :disabled="disabled"
          :aria-label="`移除 ${space.name}`"
          @click="remove(space.spaceId)"
        >
          <X aria-hidden="true" />
        </button>
      </span>
    </div>
    <p v-else class="binding-empty">
      还没有绑定空间。用户的提问不会检索任何内容。
    </p>

    <div class="search-row">
      <FormInput
        v-model="query"
        type="search"
        class="search-input"
        :maxlength="FORM_LIMITS.search"
        :disabled="disabled"
        aria-label="搜索空间名称"
        placeholder="搜索空间名称…"
      />
      <button
        class="bk-btn bk-btn-sm bk-btn-ghost"
        type="button"
        :disabled="disabled || bulkBusy || total === 0"
        @click="void addAllFiltered()"
      >
        {{
          bulkBusy
            ? '添加中…'
            : `全选${query.trim() ? '筛选' : ''}结果 ${String(total)} 个`
        }}
      </button>
    </div>

    <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>

    <ul class="result-list">
      <li
        v-for="space in results"
        :key="space.spaceId"
        class="result-row"
        :class="{ 'result-picked': isSelected(space.spaceId) }"
      >
        <span class="result-copy">
          <span class="result-name">{{ space.name }}</span>
          <span class="result-meta">{{ metaOf(space) }}</span>
        </span>
        <span :class="badgeOf(space).cls">{{ badgeOf(space).text }}</span>
        <span v-if="isSelected(space.spaceId)" class="picked-tag">已添加</span>
        <button
          v-else
          class="bk-btn bk-btn-sm bk-btn-success"
          type="button"
          :disabled="disabled"
          @click="add(space)"
        >
          添加
        </button>
      </li>
      <li v-if="loading && results.length === 0" class="result-empty">
        正在加载空间…
      </li>
      <li
        v-else-if="!loading && results.length === 0 && !error"
        class="result-empty"
      >
        {{ query.trim() ? '没有匹配的空间。' : '没有可绑定的空间。' }}
      </li>
    </ul>

    <button
      v-if="results.length < total"
      class="more-button"
      type="button"
      :disabled="loading"
      @click="loadMore"
    >
      {{
        loading
          ? '加载中…'
          : `加载更多（已显示 ${String(results.length)} / ${String(total)}）`
      }}
    </button>
  </section>
</template>

<style scoped>
.space-binding {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}

.binding-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.binding-title {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-text-secondary);
}

.link-button {
  padding: 0;
  color: var(--color-action);
  font: inherit;
  font-size: var(--text-xs);
  background: none;
  border: 0;
  cursor: pointer;
}

.link-button:focus-visible {
  outline: 2px solid var(--color-action);
  outline-offset: 2px;
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-width: 0;
}

.binding-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  max-width: 100%;
  padding: var(--space-1) var(--space-1) var(--space-1) var(--space-3);
  background: var(--color-surface-selected);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
}

.chip-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-text);
}

.chip-remove {
  display: inline-flex;
  flex: none;
  padding: 2px;
  color: var(--color-text-muted);
  background: none;
  border: 0;
  border-radius: var(--radius-pill);
  cursor: pointer;
}

.chip-remove:hover:not(:disabled) {
  color: var(--color-text-danger);
  background: var(--color-surface);
}

.chip-remove:disabled {
  cursor: not-allowed;
  opacity: var(--form-disabled-opacity);
}

.chip-remove svg {
  width: var(--space-3);
  height: var(--space-3);
}

.binding-empty {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.search-row {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  flex-wrap: wrap;
}

.search-input {
  flex: 1 1 200px;
  min-width: 0;
}

.result-list {
  display: grid;
  gap: 2px;
  max-height: 260px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  list-style: none;
}

.result-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-control);
}

.result-row:hover {
  background: var(--color-surface-selected);
}

.result-picked {
  background: var(--color-surface-selected);
}

.result-copy {
  display: grid;
  flex: 1;
  gap: var(--form-menu-detail-gap);
  min-width: 0;
}

.result-name {
  font-size: var(--form-text-size);
  line-height: var(--form-line-height);
  overflow-wrap: anywhere;
}

.result-meta {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.picked-tag {
  flex: none;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.result-empty {
  padding: var(--space-3);
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.more-button {
  justify-self: start;
  padding: var(--space-1) 0;
  color: var(--color-action);
  font: inherit;
  font-size: var(--text-xs);
  background: none;
  border: 0;
  cursor: pointer;
}

.more-button:disabled {
  color: var(--color-text-muted);
  cursor: not-allowed;
}
</style>
