<script setup lang="ts">
import { computed } from 'vue'

/**
 * 公共表格容器：负责统一外框、固定默认高度与缺省状态。
 * - 高度由 `--table-height` 提供，默认容纳 10 条数据行（分页每页条数默认值），
 *   行数增减不改变页面骨架，超出时表内滚动；卡片内嵌表格用 `table-compact` 保持紧凑。
 * - 传入 `rows` 后，为 0 时渲染统一缺省状态；未传则完全由调用方渲染 `tbody`。
 */
const props = defineProps<{
  label?: string
  /** 数据行数；传 0 时渲染缺省状态，传 undefined 表示加载中、不要空态。 */
  rows?: number | undefined
  emptyText?: string
  emptyHint?: string
  /** 覆盖默认高度，例如 '420px' 或 'auto'。 */
  height?: string
}>()

const isEmpty = computed(() => props.rows === 0)
const style = computed(() =>
  props.height === undefined ? undefined : { height: props.height },
)
</script>

<template>
  <div
    class="bk-table-wrap"
    :class="{ 'is-empty': isEmpty }"
    :style="style"
    :aria-label="label"
  >
    <slot />
    <div v-if="isEmpty" class="bk-table-empty" role="status">
      <p class="e-title">{{ emptyText ?? '暂无数据' }}</p>
      <p v-if="emptyHint" class="e-hint">{{ emptyHint }}</p>
      <div v-if="$slots['empty-actions']" class="e-actions">
        <slot name="empty-actions" />
      </div>
    </div>
  </div>
</template>
