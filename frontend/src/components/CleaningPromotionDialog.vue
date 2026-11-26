<script setup lang="ts">
import { computed, ref } from 'vue'
import ModalShell from './ModalShell.vue'
import FormInput from './FormInput.vue'
import FormRadioGroup from './FormRadioGroup.vue'
import FormTextarea from './FormTextarea.vue'
import SelectMenu from './SelectMenu.vue'
import { FORM_LIMITS, validCalendarDate } from '../formValidation'
import { describeError } from '../api/display'
import { isApiError } from '../api/http'
import {
  promoteCleaningItems,
  type CleaningItem,
  type CleaningPolicyState,
  type CleaningPromotionResult,
} from '../api/cleaning'
import type { Space } from '../api/knowledge'

/**
 * 流转到知识库：把已通过的条目合成一份 Markdown 文档，写入目标空间草稿。
 *
 * 这里只负责收集参数并提交；流转成功后仍需在「发布管理」提交变更审核，
 * 因此弹窗明确写清「不会立即对用户可见」，避免误以为已经上线。
 */
const props = defineProps<{
  items: CleaningItem[]
  spaces: Space[]
  defaultName: string
}>()
const emit = defineEmits<{
  close: []
  promoted: [result: CleaningPromotionResult]
}>()

const POLICY_OPTIONS: {
  label: string
  value: CleaningPolicyState
  hint: string
}[] = [
  { label: '现行有效', value: 'EFFECTIVE', hint: '可被搜索结果直接引用' },
  { label: '已失效', value: 'EXPIRED', hint: '保留归档，回答时提示已失效' },
  { label: '待核实', value: 'UNKNOWN', hint: '回答时标注有效性待核实' },
]

const spaceId = ref(props.items[0]?.targetSpaceId ?? '')
const documentName = ref(props.defaultName)
const policyState = ref<CleaningPolicyState>('EFFECTIVE')
const effectiveFrom = ref('')
const effectiveUntil = ref('')
const reason = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const dateError = ref<string | null>(null)

const spaceOptions = computed(() =>
  props.spaces.map((space) => ({
    label: space.canMaintain ? space.name : `${space.name}（只读）`,
    value: space.spaceId,
  })),
)

const selectedSpace = computed(() =>
  props.spaces.find((space) => space.spaceId === spaceId.value),
)

/** 文档名统一补 `.md` 后缀；用户自己写了就不重复追加。 */
const finalName = computed(() => {
  const trimmed = documentName.value.trim()
  return trimmed.endsWith('.md') ? trimmed : `${trimmed}.md`
})

const canSubmit = computed(
  () =>
    !busy.value &&
    spaceId.value !== '' &&
    documentName.value.trim() !== '' &&
    documentName.value.trim().length <= FORM_LIMITS.documentName &&
    dateError.value === null,
)

/** 生效区间校验：两端都填时要求「起 < 止」，与后端规则一致。 */
function validateDates(): boolean {
  const from = effectiveFrom.value.trim()
  const until = effectiveUntil.value.trim()
  dateError.value = null
  if (from !== '' && !validCalendarDate(from)) {
    dateError.value = '生效日期格式不正确。'
    return false
  }
  if (until !== '' && !validCalendarDate(until)) {
    dateError.value = '失效日期格式不正确。'
    return false
  }
  if (from !== '' && until !== '' && from >= until) {
    dateError.value = '生效日期必须早于失效日期。'
    return false
  }
  return true
}

/** 提交流转：成功后把结果交给页面刷新列表并提示去发布管理。 */
async function submit(): Promise<void> {
  if (!canSubmit.value || !validateDates()) return
  busy.value = true
  error.value = null
  try {
    const result = await promoteCleaningItems({
      itemIds: props.items.map((item) => item.itemId),
      spaceId: spaceId.value,
      documentName: finalName.value,
      policyState: policyState.value,
      effectiveFrom: effectiveFrom.value.trim() || null,
      effectiveUntil: effectiveUntil.value.trim() || null,
      reason: reason.value.trim(),
    })
    emit('promoted', result)
  } catch (failure) {
    error.value = isApiError(failure, 409, 'STATE_CONFLICT')
      ? describeError(failure, '条目状态已变化，请刷新后重试。')
      : describeError(failure)
  } finally {
    busy.value = false
  }
}

/** 提交中不允许关闭，避免参数被改到一半。 */
function close(): void {
  if (busy.value) return
  emit('close')
}
</script>

<template>
  <ModalShell title="流转到知识库" width="640px" @close="close">
    <form id="cleaning-promotion-form" @submit.prevent="submit">
      <p class="bk-notice bk-notice-info">
        <span class="n-icon">ℹ</span>
        <span
          >将 {{ String(items.length) }} 条已通过的条目合成一份 Markdown
          文档，加入「{{ selectedSpace?.name ?? '目标空间' }}」的草稿；
          还需在发布管理提交变更审核，审核通过后才对用户可见。</span
        >
      </p>

      <div class="bk-field">
        <label>目标空间</label>
        <SelectMenu
          v-model="spaceId"
          label="目标空间"
          placeholder="请选择空间"
          :options="spaceOptions"
          :disabled="busy"
        />
      </div>

      <div class="bk-field">
        <label for="cleaning-document-name">文档名称</label>
        <FormInput
          id="cleaning-document-name"
          v-model="documentName"
          :maxlength="FORM_LIMITS.documentName"
          :disabled="busy"
        />
        <span class="bk-text-xs bk-muted"
          >最终文件名：{{ finalName }}（重名时后端会追加序号）</span
        >
      </div>

      <div class="bk-field">
        <FormRadioGroup
          v-model="policyState"
          label="政策状态"
          :options="POLICY_OPTIONS"
          :disabled="busy"
        />
      </div>

      <div class="date-row">
        <div class="bk-field">
          <label for="cleaning-effective-from">生效日期（可选）</label>
          <FormInput
            id="cleaning-effective-from"
            v-model="effectiveFrom"
            type="date"
            :disabled="busy"
            @change="validateDates"
          />
        </div>
        <div class="bk-field">
          <label for="cleaning-effective-until">失效日期（可选）</label>
          <FormInput
            id="cleaning-effective-until"
            v-model="effectiveUntil"
            type="date"
            :disabled="busy"
            @change="validateDates"
          />
        </div>
      </div>
      <p v-if="dateError" class="bk-notice bk-notice-fail" role="alert">
        {{ dateError }}
      </p>

      <div class="bk-field">
        <label for="cleaning-promotion-reason">说明（可选）</label>
        <FormTextarea
          id="cleaning-promotion-reason"
          v-model="reason"
          :maxlength="FORM_LIMITS.reviewReason"
          placeholder="例如：来自客服语料，2026-11 审核通过"
          :disabled="busy"
          compact
        />
      </div>

      <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
        {{ error }}
      </p>
    </form>

    <template #actions>
      <button class="bk-btn" type="button" :disabled="busy" @click="close">
        取消
      </button>
      <button
        class="bk-btn bk-btn-primary"
        type="submit"
        form="cleaning-promotion-form"
        :disabled="!canSubmit"
      >
        {{ busy ? '流转中…' : '流转到知识库' }}
      </button>
    </template>
  </ModalShell>
</template>

<style scoped>
.date-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

@media (max-width: 560px) {
  .date-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
