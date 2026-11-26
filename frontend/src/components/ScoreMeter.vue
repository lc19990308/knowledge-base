<script setup lang="ts">
import { computed } from 'vue'

/**
 * 分数展示：总分 + 规则/模型小字 + 细进度条。
 *
 * 表格、抽屉与批次平均分共用同一套配色和排版，避免三处各写一份阈值判断。
 * 只做展示，不读取接口、不碰 store。
 */
const props = withDefaults(
  defineProps<{
    score: number
    ruleScore?: number | null
    modelScore?: number | null
    /** sm 用于表格行内，md 用于抽屉里的重点展示。 */
    size?: 'sm' | 'md'
    /** 是否显示「规则 / 模型」拆解。 */
    showBreakdown?: boolean
    /** 是否显示细进度条。 */
    showBar?: boolean
  }>(),
  {
    ruleScore: null,
    modelScore: null,
    size: 'sm',
    showBreakdown: true,
    showBar: true,
  },
)

/** 分数分档阈值：与后端规则分的「及格/良好」口径一致。 */
const SCORE_HIGH = 80
const SCORE_MID = 60

const level = computed<'high' | 'mid' | 'low'>(() => {
  if (props.score >= SCORE_HIGH) return 'high'
  if (props.score >= SCORE_MID) return 'mid'
  return 'low'
})

/** 进度条宽度，限制在 0–100，防止异常数据把条撑出容器。 */
const barStyle = computed(() => ({
  width: `${String(Math.min(100, Math.max(0, props.score)))}%`,
}))

/** 模型分缺失时明确写「未评分」，不用 0 冒充真实分数。 */
const modelText = computed(() =>
  props.modelScore === null ? '模型未评分' : `模型 ${String(props.modelScore)}`,
)

const ruleText = computed(() =>
  props.ruleScore === null ? '' : `规则 ${String(props.ruleScore)}`,
)
</script>

<template>
  <span class="score" :class="[`score-${level}`, `score-${size}`]">
    <span class="score-value">{{ score }}</span>
    <span v-if="showBreakdown" class="score-sub">
      {{ ruleText }}
      <template v-if="ruleText">·</template>
      {{ modelText }}
    </span>
    <span v-if="showBar" class="score-bar" aria-hidden="true">
      <span class="score-bar-fill" :style="barStyle"></span>
    </span>
  </span>
</template>

<style scoped>
.score {
  display: inline-flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-variant-numeric: tabular-nums;
}

.score-value {
  font-weight: 700;
  color: var(--color-text);
}

.score-sm .score-value {
  font-size: var(--text-base);
  line-height: 1.2;
}

.score-md .score-value {
  font-size: var(--text-xl);
  line-height: 1.1;
}

.score-sub {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  white-space: nowrap;
}

.score-bar {
  display: block;
  height: 4px;
  margin-top: 2px;
  overflow: hidden;
  background: var(--color-divider);
  border-radius: var(--radius-pill);
}

.score-bar-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--color-warning);
  transition: width var(--motion-base) var(--ease-standard);
}

.score-high .score-value {
  color: var(--color-text-success);
}

.score-high .score-bar-fill {
  background: var(--color-success);
}

.score-mid .score-bar-fill {
  background: var(--color-warning);
}

.score-low .score-value {
  color: var(--color-text-danger);
}

.score-low .score-bar-fill {
  background: var(--color-danger);
}

@media (prefers-reduced-motion: reduce) {
  .score-bar-fill {
    transition: none;
  }
}
</style>
