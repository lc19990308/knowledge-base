<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed, watch } from 'vue'
import SelectMenu from './SelectMenu.vue'
import { DEFAULT_PAGE_SIZE, PAGE_SIZE_OPTIONS } from '../pagination'

type PageItem =
  { kind: 'page'; value: number; key: string } | { kind: 'gap'; key: string }

const props = withDefaults(
  defineProps<{
    /** 当前页码，从 1 开始。 */
    page: number
    /** 筛选后的记录总数，由列表接口的 total 提供。 */
    total: number
    pageSize?: number
    /** 可选的每页条数；不在列表里的当前值也会补进选项。 */
    pageSizes?: readonly number[]
    /** 侧栏等窄容器使用：只显示上一页 / 页码 / 下一页，不显示条数设置。 */
    compact?: boolean
    disabled?: boolean
    /** 汇总文案的量词，例如「个空间」「条申请」。 */
    unit?: string
    label?: string
  }>(),
  {
    pageSize: DEFAULT_PAGE_SIZE,
    pageSizes: () => [...PAGE_SIZE_OPTIONS],
    compact: false,
    disabled: false,
    unit: '条',
    label: '分页',
  },
)

const emit = defineEmits<{
  'update:page': [value: number]
  'update:pageSize': [value: number]
}>()

const pageCount = computed(() =>
  Math.max(
    1,
    Math.ceil(Math.max(0, props.total) / Math.max(1, props.pageSize)),
  ),
)
const current = computed(() =>
  Math.min(Math.max(1, props.page), pageCount.value),
)
const rangeStart = computed(() =>
  props.total === 0 ? 0 : (current.value - 1) * props.pageSize + 1,
)
const rangeEnd = computed(() =>
  Math.min(props.total, current.value * props.pageSize),
)

const sizeOptions = computed(() => {
  const sizes = [...props.pageSizes]
  if (!sizes.includes(props.pageSize)) sizes.push(props.pageSize)
  return sizes
    .sort((left, right) => left - right)
    .map((size) => ({ label: `${String(size)} 条/页`, value: String(size) }))
})

/**
 * 有数据就显示分页条：条数设置是公共能力，单页列表也要能调整每页条数，
 * 否则用户把条数调大后（例如 9 条数据选了 20 条/页）无法再调回来。
 * 侧栏（compact）宽度不足，仍只在有多页时显示。
 */
const showBar = computed(() =>
  props.compact ? pageCount.value > 1 : props.total > 0,
)

const pages = computed<PageItem[]>(() => {
  const count = pageCount.value
  const value = current.value
  const numbers: (number | 'gap')[] = []
  if (count <= 7) {
    for (let index = 1; index <= count; index += 1) numbers.push(index)
  } else if (value <= 4) {
    numbers.push(1, 2, 3, 4, 5, 'gap', count)
  } else if (value >= count - 3) {
    numbers.push(1, 'gap', count - 4, count - 3, count - 2, count - 1, count)
  } else {
    numbers.push(1, 'gap', value - 1, value, value + 1, 'gap', count)
  }
  return numbers.map((entry, index) =>
    entry === 'gap'
      ? { kind: 'gap', key: `gap-${String(index)}` }
      : { kind: 'page', value: entry, key: `page-${String(entry)}` },
  )
})

function go(target: number): void {
  if (props.disabled) return
  const next = Math.min(Math.max(1, target), pageCount.value)
  if (next !== current.value) emit('update:page', next)
}

/** 切换每页条数后回到第一页，避免停留在已不存在的页码上。 */
function changePageSize(value: string): void {
  const size = Number(value)
  if (
    props.disabled ||
    !Number.isInteger(size) ||
    size < 1 ||
    size === props.pageSize
  ) {
    return
  }
  emit('update:pageSize', size)
  if (props.page !== 1) emit('update:page', 1)
}

// 数据变少导致当前页越界时，把页码收回到最后一页。
watch([() => props.page, pageCount], () => {
  if (props.page !== current.value) emit('update:page', current.value)
})
</script>

<template>
  <nav
    v-if="showBar"
    class="pagination"
    :class="{ 'pagination-compact': compact }"
    :aria-label="label"
  >
    <p v-if="!compact" class="pagination-summary">
      共 {{ total }} {{ unit
      }}<span class="pagination-range"> · {{ rangeStart }}–{{ rangeEnd }}</span>
    </p>
    <div class="pagination-controls">
      <div v-if="!compact" class="pagination-size">
        <SelectMenu
          :model-value="String(pageSize)"
          :options="sizeOptions"
          label="每页条数"
          :disabled="disabled"
          @update:model-value="changePageSize"
        />
      </div>
      <button
        class="bk-btn bk-btn-sm pagination-step"
        type="button"
        aria-label="上一页"
        :disabled="disabled || current <= 1"
        @click="go(current - 1)"
      >
        <ChevronLeft :size="14" aria-hidden="true" />
      </button>

      <span v-if="compact" class="pagination-status">
        {{ current }} / {{ pageCount }}
      </span>
      <template v-else>
        <template v-for="item in pages" :key="item.key">
          <span
            v-if="item.kind === 'gap'"
            class="pagination-gap"
            aria-hidden="true"
            >…</span
          >
          <button
            v-else
            class="bk-btn bk-btn-sm pagination-page"
            :class="{ 'bk-btn-primary': item.value === current }"
            type="button"
            :aria-label="`第 ${item.value} 页`"
            :aria-current="item.value === current ? 'page' : undefined"
            :disabled="disabled"
            @click="go(item.value)"
          >
            {{ item.value }}
          </button>
        </template>
      </template>

      <button
        class="bk-btn bk-btn-sm pagination-step"
        type="button"
        aria-label="下一页"
        :disabled="disabled || current >= pageCount"
        @click="go(current + 1)"
      >
        <ChevronRight :size="14" aria-hidden="true" />
      </button>
    </div>
  </nav>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
  flex-wrap: wrap;
}

.pagination-summary {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.pagination-range {
  color: var(--color-text-secondary);
}

.pagination-controls {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  margin-inline-start: auto;
}

.pagination-compact .pagination-controls {
  margin-inline-start: 0;
  justify-content: center;
  width: 100%;
}

/* 条数设置沿用公共下拉，但压到与小尺寸分页按钮同一高度。 */
.pagination-size {
  width: 118px;
  margin-inline-end: var(--space-2);
  flex: none;
}

.pagination-size :deep(.bk-select-trigger) {
  min-height: var(--button-small-height);
  padding-block: 0;
  font-size: var(--button-small-text-size);
}

.pagination-step {
  width: var(--button-small-height);
  padding: 0;
  flex: none;
}

.pagination-page {
  min-width: var(--button-small-height);
  padding: 0 var(--space-2);
  font-variant-numeric: tabular-nums;
}

.pagination-gap {
  padding: 0 var(--space-1);
  color: var(--color-text-muted);
  line-height: 1;
}

.pagination-status {
  min-width: 48px;
  text-align: center;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.pagination-compact {
  margin-top: var(--space-2);
}
</style>
