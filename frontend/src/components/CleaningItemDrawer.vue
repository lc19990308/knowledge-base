<script setup lang="ts">
import { computed } from 'vue'
import DrawerPanel from './DrawerPanel.vue'
import ScoreMeter from './ScoreMeter.vue'
import {
  cleaningItemLabel,
  cleaningItemTone,
  cleaningScoreStateLabel,
  formatDateTime,
} from '../api/display'
import type {
  CleaningItemDetail,
  CleaningReviewDecision,
  CleaningScoreDetail,
} from '../api/cleaning'

/**
 * 清洗条目详情：清洗前后对照、评分明细、审核与流转记录，底部给审核动作。
 *
 * 只负责展示与转发操作，取数与状态由页面持有，避免抽屉自己再维护一份条目状态。
 */
const props = defineProps<{
  item: CleaningItemDetail | null
  loading: boolean
  error: string | null
  busy: boolean
}>()
const emit = defineEmits<{
  close: []
  review: [decision: CleaningReviewDecision]
  promote: []
}>()

const SCORE_DIMENSIONS: {
  key: keyof CleaningScoreDetail
  label: string
  max: number
}[] = [
  { key: 'length', label: '长度', max: 20 },
  { key: 'completeness', label: '完整性', max: 20 },
  { key: 'noise', label: '噪声', max: 20 },
  { key: 'information', label: '信息量', max: 20 },
  { key: 'structure', label: '结构', max: 20 },
]

const dimensions = computed(() =>
  SCORE_DIMENSIONS.map((dimension) => {
    const value = props.item?.scoreDetail[dimension.key] ?? 0
    return {
      ...dimension,
      value,
      percent: Math.min(100, Math.max(0, (value / dimension.max) * 100)),
    }
  }),
)

const canReview = computed(() => props.item?.status === 'PENDING_REVIEW')
const canPromote = computed(() => props.item?.status === 'APPROVED')

/** 抽屉标题随条目变化；未加载完成时用占位文案。 */
const drawerTitle = computed(() =>
  props.item ? `「${props.item.title}」· 条目详情` : '条目详情',
)

const hint = computed(() => {
  const item = props.item
  if (!item) return ''
  const parts = [item.batchName]
  if (item.sourcePath) parts.push(item.sourcePath)
  parts.push(formatDateTime(item.createdAt))
  return parts.join(' · ')
})
</script>

<template>
  <DrawerPanel :title="drawerTitle" width="880px" @close="emit('close')">
    <template v-if="hint" #hint>{{ hint }}</template>

    <p v-if="loading" class="bk-text-sm bk-muted">正在读取条目…</p>
    <p v-else-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>

    <template v-else-if="item">
      <section class="score-summary" aria-label="评分结果">
        <ScoreMeter
          :score="item.totalScore"
          :rule-score="item.ruleScore"
          :model-score="item.modelScore"
          size="md"
        />
        <div class="score-meta">
          <span :class="cleaningItemTone(item.status)">{{
            cleaningItemLabel(item.status)
          }}</span>
          <span class="bk-text-xs bk-muted">{{
            cleaningScoreStateLabel(item.scoreState)
          }}</span>
        </div>
        <p v-if="item.modelReason" class="score-reason">
          模型意见：{{ item.modelReason }}
        </p>
      </section>

      <section aria-label="评分明细">
        <h4 class="section-title">规则分明细</h4>
        <ul class="dimension-list">
          <li v-for="dimension in dimensions" :key="dimension.key">
            <span class="dimension-label">{{ dimension.label }}</span>
            <span class="dimension-bar" aria-hidden="true">
              <span
                class="dimension-fill"
                :style="{ width: `${String(dimension.percent)}%` }"
              ></span>
            </span>
            <span class="dimension-value"
              >{{ String(dimension.value) }} / {{ String(dimension.max) }}</span
            >
          </li>
        </ul>
      </section>

      <section aria-label="清洗前后对照">
        <h4 class="section-title">清洗前后对照</h4>
        <div class="compare-grid">
          <div class="compare-pane">
            <div class="compare-head">清洗前原文摘录</div>
            <pre class="body-text">{{
              item.rawExcerpt ?? '（未保留原文摘录）'
            }}</pre>
          </div>
          <div class="compare-pane">
            <div class="compare-head">清洗后正文</div>
            <pre class="body-text body-text-clean">{{ item.body }}</pre>
          </div>
        </div>
      </section>

      <section aria-label="审核与流转记录">
        <h4 class="section-title">审核与流转</h4>
        <dl class="record-list">
          <div class="record-row">
            <dt>审核</dt>
            <dd>
              <template v-if="item.reviewedAt">
                {{ formatDateTime(item.reviewedAt) }}
                <template v-if="item.reviewedBy">
                  · {{ item.reviewedBy }}</template
                >
                <span v-if="item.reviewReason" class="record-reason"
                  >：{{ item.reviewReason }}</span
                >
              </template>
              <span v-else class="bk-muted">未审核</span>
            </dd>
          </div>
          <div class="record-row">
            <dt>流转</dt>
            <dd>
              <template v-if="item.promotedAt">
                {{ formatDateTime(item.promotedAt) }} →
                {{ item.targetSpaceName ?? '目标空间' }}
                <span class="record-reason">
                  （文档版本
                  {{ item.promotedDocumentVersionId?.slice(0, 8) ?? '—' }}）
                </span>
              </template>
              <span v-else class="bk-muted">未流转</span>
            </dd>
          </div>
        </dl>
      </section>
    </template>

    <template #actions>
      <button
        v-if="canReview"
        class="bk-btn bk-btn-danger"
        type="button"
        :disabled="busy"
        @click="emit('review', 'REJECT')"
      >
        驳回
      </button>
      <button
        v-if="canReview"
        class="bk-btn bk-btn-success"
        type="button"
        :disabled="busy"
        @click="emit('review', 'APPROVE')"
      >
        通过
      </button>
      <button
        v-if="canPromote"
        class="bk-btn bk-btn-primary"
        type="button"
        :disabled="busy"
        @click="emit('promote')"
      >
        流转到知识库
      </button>
      <button
        class="bk-btn"
        type="button"
        :disabled="busy"
        @click="emit('close')"
      >
        关闭
      </button>
    </template>
  </DrawerPanel>
</template>

<style scoped>
.score-summary {
  display: grid;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
  padding: var(--space-4);
  background: var(--color-canvas);
  border: 1px solid var(--color-divider);
  border-radius: var(--radius-panel);
}

.score-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.score-reason {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  overflow-wrap: anywhere;
}

.section-title {
  margin-bottom: var(--space-3);
  font-size: var(--text-sm);
  font-weight: 700;
}

.dimension-list {
  display: grid;
  gap: var(--space-2);
  margin: 0 0 var(--space-6);
  padding: 0;
  list-style: none;
}

.dimension-list li {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr) 72px;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--text-sm);
}

.dimension-label {
  color: var(--color-text-secondary);
}

.dimension-bar {
  display: block;
  height: 6px;
  overflow: hidden;
  background: var(--color-divider);
  border-radius: var(--radius-pill);
}

.dimension-fill {
  display: block;
  height: 100%;
  background: var(--color-action);
  border-radius: inherit;
}

.dimension-value {
  text-align: end;
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.compare-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.compare-pane {
  min-width: 0;
}

.compare-head {
  margin-bottom: var(--space-2);
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-text-muted);
}

.body-text {
  max-height: 320px;
  margin: 0;
  padding: var(--space-3);
  overflow: auto;
  font: inherit;
  font-size: var(--text-sm);
  line-height: var(--leading-body);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
}

.body-text-clean {
  background: var(--color-surface-selected);
}

.record-list {
  display: grid;
  gap: var(--space-2);
  margin: 0 0 var(--space-2);
  font-size: var(--text-sm);
}

.record-row {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: var(--space-3);
}

.record-row dt {
  color: var(--color-text-muted);
}

.record-row dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.record-reason {
  color: var(--color-text-secondary);
}

@media (max-width: 900px) {
  .compare-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
