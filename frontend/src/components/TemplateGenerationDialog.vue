<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Copy, Sparkles } from '@lucide/vue'
import ModalShell from './ModalShell.vue'
import FormInput from './FormInput.vue'
import FormTextarea from './FormTextarea.vue'
import FormRadioGroup from './FormRadioGroup.vue'
import SelectMenu from './SelectMenu.vue'
import {
  createTemplateGeneration,
  getTemplateGeneration,
  listModelOptions,
  type ModelOption,
  type TemplateGeneration,
} from '../api/knowledge'
import { describeError } from '../api/display'

/**
 * AI 生成模板：独立于新建/编辑模板弹窗的辅助工具。
 * 只负责生成并展示正文，用户复制后自行粘贴到「模板正文」，不直接改写任何模板。
 */
const emit = defineEmits<{ close: [] }>()

type GenerationMode = 'STRUCTURED' | 'ADVANCED'

const MODE_OPTIONS: { value: GenerationMode; label: string; hint: string }[] = [
  { value: 'STRUCTURED', label: '结构化', hint: '按规则区块分段' },
  { value: 'ADVANCED', label: '高级文本', hint: '完整提示词约束' },
]

const mode = ref<GenerationMode>('STRUCTURED')
const scenario = ref('')
const requirements = ref('')
const modelId = ref('')
const models = ref<ModelOption[]>([])
const modelsError = ref('')
const generation = ref<TemplateGeneration | null>(null)
const busy = ref(false)
const error = ref('')
const copyState = ref<'idle' | 'copied' | 'failed'>('idle')

/** 轮询与复制反馈的句柄：弹窗关闭或组件卸载时必须清理。 */
let pollTimer: ReturnType<typeof globalThis.setTimeout> | undefined
let pollAbort: AbortController | undefined
let copyTimer: ReturnType<typeof globalThis.setTimeout> | undefined

const modelOptions = computed(() =>
  models.value.map((item) =>
    item.isDefault
      ? { label: item.alias, value: item.id, detail: '默认模型' }
      : { label: item.alias, value: item.id },
  ),
)
const requirementCount = computed(() => requirements.value.trim().length)
const succeeded = computed(
  () =>
    generation.value?.status === 'SUCCEEDED' &&
    Boolean(generation.value.resultBody),
)
const canSubmit = computed(
  () =>
    !busy.value &&
    requirementCount.value >= 10 &&
    requirementCount.value <= 2000 &&
    models.value.length > 0,
)
const statusText = computed(() => {
  const current = generation.value
  if (!current) return ''
  if (current.status === 'QUEUED') return '已排队，等待生成…'
  if (current.status === 'RUNNING') return '模型正在生成模板…'
  if (current.status === 'SUCCEEDED') return `生成完成 · ${current.modelAlias}`
  return '生成失败'
})

onMounted(async () => {
  try {
    const page = await listModelOptions()
    models.value = page.items
    const preferred = page.items.find((item) => item.isDefault) ?? page.items[0]
    modelId.value = preferred?.id ?? ''
    if (page.items.length === 0) {
      modelsError.value = '尚未配置可用模型，请先在系统设置中启用一条模型配置。'
    }
  } catch (failure) {
    modelsError.value = describeError(failure)
  }
})

onBeforeUnmount(() => {
  stopPolling()
  resetCopyState()
})

function stopPolling(): void {
  if (pollTimer !== undefined) {
    globalThis.clearTimeout(pollTimer)
    pollTimer = undefined
  }
  pollAbort?.abort()
  pollAbort = undefined
}

/** 失败码 → 中文提示；未知码保留原始值便于排查。 */
function failureText(code: string | null): string {
  switch (code) {
    case 'MODEL_TIMEOUT':
      return '模型响应超时，可稍后重新生成。'
    case 'MODEL_UNAVAILABLE':
      return '模型网关不可用，请检查模型配置。'
    case 'MODEL_RATE_LIMITED':
      return '模型网关限流，请稍后再试。'
    case 'INVALID_MODEL_OUTPUT':
      return '模型返回内容不符合模板格式，请重新生成。'
    default:
      return code ?? '未知错误'
  }
}

async function submit(): Promise<void> {
  if (busy.value) return
  if (requirementCount.value < 10) {
    error.value = '请至少填写 10 个字符的需求描述。'
    return
  }
  if (models.value.length === 0) return
  stopPolling()
  resetCopyState()
  error.value = ''
  generation.value = null
  busy.value = true
  try {
    const created = await createTemplateGeneration({
      mode: mode.value,
      scenario: scenario.value.trim(),
      requirements: requirements.value.trim(),
      modelConfigId: modelId.value || null,
    })
    generation.value = created
    if (created.status === 'SUCCEEDED' || created.status === 'FAILED') {
      busy.value = false
      return
    }
    schedulePoll(created.generationId, Date.now())
  } catch (failure) {
    busy.value = false
    error.value = describeError(failure)
  }
}

function schedulePoll(id: string, startedAt: number): void {
  pollTimer = globalThis.setTimeout(() => {
    void pollOnce(id, startedAt)
  }, 1500)
}

async function pollOnce(id: string, startedAt: number): Promise<void> {
  const controller = new AbortController()
  pollAbort = controller
  try {
    const current = await getTemplateGeneration(id, controller.signal)
    generation.value = current
    if (current.status === 'SUCCEEDED' || current.status === 'FAILED') {
      busy.value = false
      return
    }
    if (Date.now() - startedAt > 120000) {
      busy.value = false
      error.value = '生成超时，请稍后重试。'
      return
    }
    schedulePoll(id, startedAt)
  } catch (failure) {
    if (controller.signal.aborted) return
    busy.value = false
    error.value = describeError(failure)
  }
}

/** 复制生成结果，用户粘贴到自己的模板正文即可；2 秒后回到常态。 */
async function copyResult(): Promise<void> {
  const body = generation.value?.resultBody
  if (!body) return
  resetCopyState()
  try {
    await globalThis.navigator.clipboard.writeText(body)
    copyState.value = 'copied'
  } catch {
    copyState.value = 'failed'
  }
  copyTimer = globalThis.setTimeout(resetCopyState, 2000)
}

function resetCopyState(): void {
  copyState.value = 'idle'
  if (copyTimer !== undefined) {
    globalThis.clearTimeout(copyTimer)
    copyTimer = undefined
  }
}

function close(): void {
  stopPolling()
  resetCopyState()
  emit('close')
}
</script>

<template>
  <ModalShell title="AI 生成模板" width="720px" @close="close">
    <p class="bk-text-sm bk-muted generation-intro">
      描述业务场景与要求，模型会生成一份可直接使用的模板正文。生成结果不会保存，复制后粘贴到「模板正文」即可。
    </p>

    <FormRadioGroup v-model="mode" :options="MODE_OPTIONS" label="目标格式" />

    <div class="bk-field generation-field">
      <label for="generation-scenario">使用场景</label>
      <FormInput
        id="generation-scenario"
        v-model="scenario"
        :maxlength="240"
        placeholder="例如：员工差旅报销咨询"
      />
    </div>

    <div class="bk-field">
      <label for="generation-requirements">需求描述</label>
      <FormTextarea
        id="generation-requirements"
        v-model="requirements"
        :maxlength="2000"
        rows="5"
        placeholder="例如：先给结论；引用资料标注来源；资料不足时说明缺少什么；涉及政策说明有效性。"
      />
      <span class="bk-text-xs bk-muted generation-count">
        {{ requirementCount }}/2000，至少 10 个字符
      </span>
    </div>

    <div class="bk-field generation-field">
      <span>生成模型</span>
      <SelectMenu
        v-model="modelId"
        :options="modelOptions"
        placeholder="选择模型…"
        label="生成模型"
        :disabled="busy || models.length === 0"
      />
    </div>

    <p v-if="modelsError" class="bk-notice bk-notice-fail" role="alert">
      {{ modelsError }}
    </p>
    <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>

    <div v-if="generation" class="generation-result" aria-live="polite">
      <div class="generation-status">
        <span
          class="bk-badge"
          :class="
            generation.status === 'SUCCEEDED'
              ? 'bk-badge-ok'
              : generation.status === 'FAILED'
                ? 'bk-badge-danger'
                : 'bk-badge-review'
          "
        >
          {{ statusText }}
        </span>
        <span v-if="succeeded" class="bk-text-xs bk-muted">
          {{ generation.inputTokens ?? '—' }} 输入 tokens ·
          {{ generation.outputTokens ?? '—' }} 输出 tokens
        </span>
      </div>
      <pre v-if="succeeded" class="generation-preview">{{
        generation.resultBody
      }}</pre>
      <p
        v-else-if="generation.status === 'FAILED'"
        class="bk-text-sm generation-failure"
      >
        {{ failureText(generation.failureCode) }}
      </p>
      <p v-else class="bk-text-sm bk-muted generation-hint">
        生成完成后可一键复制，粘贴到任意模板正文中。
      </p>
    </div>

    <p class="visually-hidden" role="status">
      {{
        copyState === 'copied'
          ? '模板内容已复制到剪贴板'
          : copyState === 'failed'
            ? '复制失败，请检查浏览器剪贴板权限'
            : ''
      }}
    </p>

    <template #actions>
      <button class="bk-btn" type="button" @click="close">关闭</button>
      <button
        v-if="!succeeded"
        class="bk-btn bk-btn-primary"
        type="button"
        :disabled="!canSubmit"
        @click="submit"
      >
        <Sparkles :size="15" aria-hidden="true" />
        {{ busy ? '生成中…' : '生成模板' }}
      </button>
      <template v-else>
        <button class="bk-btn" type="button" :disabled="busy" @click="submit">
          重新生成
        </button>
        <button
          class="bk-btn"
          :class="
            copyState === 'copied'
              ? 'bk-btn-success'
              : copyState === 'failed'
                ? 'bk-btn-danger'
                : 'bk-btn-primary'
          "
          type="button"
          @click="copyResult"
        >
          <Copy :size="15" aria-hidden="true" />
          {{
            copyState === 'copied'
              ? '已复制'
              : copyState === 'failed'
                ? '复制失败'
                : '复制内容'
          }}
        </button>
      </template>
    </template>
  </ModalShell>
</template>

<style scoped>
.generation-intro {
  margin: 0 0 var(--space-4);
}

.generation-field {
  min-width: 0;
}

.generation-field label,
.generation-field > span {
  font-size: var(--form-text-size);
  font-weight: var(--form-label-weight);
}

.generation-count {
  align-self: flex-end;
}

.generation-result {
  margin-top: var(--space-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-3);
  background: var(--color-canvas);
}

.generation-status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.generation-preview {
  margin: var(--space-3) 0 0;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.7;
  color: var(--color-text-secondary);
}

.generation-failure {
  margin: var(--space-2) 0 0;
  color: var(--color-text-danger);
}

.generation-hint {
  margin: var(--space-2) 0 0;
}
</style>
