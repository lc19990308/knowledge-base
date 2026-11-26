<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import { Copy, Download, Sparkles } from '@lucide/vue'
import { FORM_LIMITS, NON_BLANK_PATTERN } from '../formValidation'
import FormInput from '../components/FormInput.vue'
import FormSwitch from '../components/FormSwitch.vue'
import SelectMenu from '../components/SelectMenu.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createAnswerTemplate,
  deleteAnswerTemplate,
  getAnswerTemplate,
  listAnswerTemplates,
  listAssistants,
  updateAnswerTemplate,
  type AnswerTemplate,
  type Assistant,
} from '../api/knowledge'
import { describeError, formatDate } from '../api/display'
import { isApiError, needsVersionRefresh } from '../api/http'
import DataTable from '../components/DataTable.vue'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import PaginationBar from '../components/PaginationBar.vue'
import TemplateBodyEditor from '../components/TemplateBodyEditor.vue'
import TemplateGenerationDialog from '../components/TemplateGenerationDialog.vue'
import {
  TEMPLATE_EXAMPLES,
  type TemplateExample,
  type TemplateExampleMode,
} from '../answerTemplateExamples'
import FormRadioGroup from '../components/FormRadioGroup.vue'
import { usePagedList } from '../pagination'

/** 模板正文按空行分段，段内 {变量} 高亮，预览区与原型一致。 */
type PreviewSegment = { text: string; isVar: boolean }

const assistants = ref<Assistant[]>([])
const selectedTemplate = ref<AnswerTemplate | null>(null)
const selectedId = ref('')
const name = ref('')
const body = ref('')
const enabled = ref(true)
const etag = ref('')
const latestTemplate = ref<AnswerTemplate | null>(null)
const isNew = ref(false)
const busy = ref(false)
const loading = ref(true)
const error = ref<string | null>(null)
const notice = ref<{ tone: string; text: string } | null>(null)
const editorModal = ref(false)
const exampleModal = ref(false)
const generationModal = ref(false)
/** 格式示例弹窗用单选决定展开哪一份示例。 */
const exampleMode = ref<TemplateExampleMode>('structured')
/** 复制按钮的短暂反馈：idle 常态 / copied 成功 / failed 失败，2 秒后回到常态。 */
const copyState = ref<'idle' | 'copied' | 'failed'>('idle')
let copyTimer: ReturnType<typeof globalThis.setTimeout> | undefined
const testQuestion = ref('员工出差报销需要哪些材料？')
const testScenario = ref<'normal' | 'empty' | 'expired'>('normal')

const selected = computed(() => selectedTemplate.value)
const canDelete = computed(
  () =>
    selected.value !== null &&
    !selected.value.isGlobal &&
    selected.value.assistantIds.length === 0,
)

/**
 * 模板列表走服务端分页：每页条数由分页器决定，翻页或改条数时重新请求。
 * 当前选中的模板单独保存，避免它不在本页时编辑区被清空。
 */
const {
  items: templateItems,
  page: templatePage,
  pageSize: templatePageSize,
  total: templateTotal,
  error: listError,
  reload: reloadTemplates,
} = usePagedList<AnswerTemplate>(
  (query, signal) => listAnswerTemplates({ ...query, signal }),
  { describeError: describe },
)

const testContext = computed<Record<string, string>>(() => {
  if (testScenario.value === 'empty') {
    return {
      question: testQuestion.value,
      references: '（没有检索到可引用资料）',
      validity: '无可验证政策',
    }
  }
  if (testScenario.value === 'expired') {
    return {
      question: testQuestion.value,
      references: '《差旅管理办法》第 8 条（测试资料）',
      validity: '该政策已失效，请勿作为当前依据',
    }
  }
  return {
    question: testQuestion.value,
    references: '《差旅管理办法》第 8 条（测试资料）',
    validity: '当前有效',
  }
})
const testPreview = computed(() =>
  body.value.replace(
    /\{([^{}]+)\}/g,
    (match, key: string) => testContext.value[key] ?? match,
  ),
)

/** 模板正文按空行分段，段内 {变量} 高亮；验证预览与格式示例共用。 */
function parsePreviewSegments(value: string): PreviewSegment[][] {
  return value
    .split(/\n\s*\n/)
    .map((paragraph) => paragraph.trim())
    .filter((paragraph) => paragraph !== '')
    .map((paragraph) =>
      paragraph
        .split(/(\{[^{}\s][^{}]*\})/g)
        .filter((segment) => segment !== '')
        .map((segment) => ({ text: segment, isVar: segment.startsWith('{') })),
    )
}

const previewParagraphs = computed<PreviewSegment[][]>(() =>
  parsePreviewSegments(body.value),
)

/** 单选卡片的选项与当前展开的示例，一一对应。 */
const exampleOptions = TEMPLATE_EXAMPLES.map((example) => ({
  value: example.mode,
  label: example.label,
  hint: example.hint,
}))

const activeExample = computed(
  () =>
    TEMPLATE_EXAMPLES.find((example) => example.mode === exampleMode.value) ??
    null,
)

const activeParagraphs = computed<PreviewSegment[][]>(() =>
  activeExample.value ? parsePreviewSegments(activeExample.value.body) : [],
)

/** 每次打开都回到第一份示例，避免上次的选择让用户以为只有一份。 */
function openExamples(): void {
  exampleMode.value = TEMPLATE_EXAMPLES[0]?.mode ?? 'structured'
  resetCopyState()
  exampleModal.value = true
}

/** 复位复制反馈与定时器；切换示例、重新打开弹窗时都会调用。 */
function resetCopyState(): void {
  copyState.value = 'idle'
  if (copyTimer !== undefined) {
    globalThis.clearTimeout(copyTimer)
    copyTimer = undefined
  }
}

/** 复制当前示例正文到剪贴板，按钮短暂显示结果；失败时提示检查剪贴板权限。 */
async function copyExample(example: TemplateExample): Promise<void> {
  resetCopyState()
  try {
    await globalThis.navigator.clipboard.writeText(example.body)
    copyState.value = 'copied'
  } catch {
    copyState.value = 'failed'
  }
  copyTimer = globalThis.setTimeout(resetCopyState, 2000)
}

// 切换示例后，上一次的「已复制」提示不应留在新内容上。
watch(exampleMode, resetCopyState)

onBeforeUnmount(resetCopyState)

/** 下载示例为 UTF-8 纯文本，用户可保存后粘贴回「模板正文」。 */
function downloadExample(example: TemplateExample): void {
  const blob = new Blob([example.body], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = globalThis.document.createElement('a')
  link.href = url
  link.download = example.fileName
  globalThis.document.body.append(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/** 列表副标题：优先级语义与原型文案一致。 */
function subTitle(template: AnswerTemplate): string {
  const edited = template.updatedAt
    ? ` · 编辑于 ${formatDate(template.updatedAt)}`
    : ''
  if (!template.enabled) {
    return template.assistantIds.length > 0
      ? '关联助手已停用'
      : `已停用，未关联助手${edited}`
  }
  if (template.isGlobal) {
    return `未配置助手级模板的助手使用${edited}`
  }
  if (template.assistantIds.length > 0) {
    return `用于「${assistantNames(template).join('」「')}」${edited}`
  }
  return `未被引用${edited}`
}

function assistantNames(template: AnswerTemplate): string[] {
  return template.assistantIds
    .map(
      (id) =>
        assistants.value.find((assistant) => assistant.assistantId === id)
          ?.name,
    )
    .filter((value): value is string => typeof value === 'string')
}

function statusBadge(template: AnswerTemplate): { cls: string; text: string } {
  if (template.isGlobal) return { cls: 'bk-badge bk-badge-draft', text: '兜底' }
  if (template.enabled) return { cls: 'bk-badge bk-badge-ok', text: '使用中' }
  return { cls: 'bk-badge bk-badge-draft', text: '未启用' }
}

function describe(failure: unknown): string {
  if (isApiError(failure, 409, 'STATE_CONFLICT')) {
    return describeError(
      failure,
      '该模板仍被全局配置或助手引用，不能删除。请先解除引用。',
    )
  }
  return describeError(failure)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    await Promise.all([reloadTemplates(), loadAssistants()])
    if (listError.value !== null) {
      error.value = listError.value
      return
    }
    if (!isNew.value) {
      const current = selected.value
      if (current) {
        select(
          templateItems.value.find(
            (item) => item.templateId === current.templateId,
          ) ?? current,
          false,
        )
      } else if (templateItems.value.length > 0) {
        select(templateItems.value[0] as AnswerTemplate, false)
      } else {
        startNew(false)
      }
    }
  } catch (failure) {
    error.value = describe(failure)
  } finally {
    loading.value = false
  }
}

async function loadAssistants(): Promise<void> {
  try {
    assistants.value = (await listAssistants()).items
  } catch {
    assistants.value = []
  }
}

function select(template: AnswerTemplate, openModal = true): void {
  isNew.value = false
  selectedTemplate.value = template
  selectedId.value = template.templateId
  name.value = template.name
  body.value = template.body
  enabled.value = template.enabled
  latestTemplate.value = null
  etag.value = `"${String(template.revision)}"`
  error.value = null
  notice.value = null
  if (openModal) editorModal.value = true
}

/**
 * 保存后重新读取当前页；模板按名称排序，目标不一定落在本页，
 * 不在本页时按 id 单独读取，保证编辑区仍指向刚保存的那条。
 */
async function reloadSelection(templateId: string): Promise<void> {
  await load()
  const fresh = templateItems.value.find(
    (item) => item.templateId === templateId,
  )
  if (fresh) {
    select(fresh, false)
    return
  }
  const fetched = await getAnswerTemplate(templateId)
  select(fetched.data, false)
}

async function refreshVersion(): Promise<AnswerTemplate> {
  const fresh = await getAnswerTemplate(selectedId.value)
  if (!fresh.etag) throw new Error('未读取到模板版本标识。')
  templateItems.value = templateItems.value.map((item) =>
    item.templateId === fresh.data.templateId ? fresh.data : item,
  )
  if (selectedTemplate.value?.templateId === fresh.data.templateId) {
    selectedTemplate.value = fresh.data
  }
  etag.value = fresh.etag
  return fresh.data
}

async function recoverVersion(failure: unknown): Promise<void> {
  error.value = describe(failure)
  if (!needsVersionRefresh(failure)) return
  etag.value = ''
  latestTemplate.value = null
  try {
    latestTemplate.value = await refreshVersion()
    error.value = describeError(
      failure,
      '已读取最新模板。请核对下方版本，确认后再操作；本地输入已保留。',
    )
  } catch (refreshFailure) {
    error.value = `${error.value} 读取最新版本失败。${describeError(refreshFailure)}`
  }
}

async function retryVersion(): Promise<void> {
  busy.value = true
  try {
    latestTemplate.value = await refreshVersion()
    error.value =
      '已读取最新模板。请核对下方版本，确认后再操作；本地输入已保留。'
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    busy.value = false
  }
}

function startNew(openModal = true): void {
  isNew.value = true
  selectedTemplate.value = null
  selectedId.value = ''
  name.value = ''
  body.value = ''
  enabled.value = true
  etag.value = ''
  latestTemplate.value = null
  error.value = null
  notice.value = null
  if (openModal) editorModal.value = true
}

async function save(): Promise<void> {
  if (busy.value || (!isNew.value && !etag.value)) return
  if (!name.value.trim() || name.value.trim().length > FORM_LIMITS.name) {
    error.value = '请输入模板名称，最多 120 个字符。'
    return
  }
  if (body.value.trim() === '') {
    error.value = '模板正文不能为空。'
    return
  }
  busy.value = true
  error.value = null
  notice.value = null
  try {
    if (isNew.value) {
      const created = await createAnswerTemplate({
        name: name.value.trim(),
        body: body.value,
        enabled: enabled.value,
      })
      isNew.value = false
      await reloadSelection(created.data.templateId)
      editorModal.value = false
      notice.value = { tone: 'bk-notice bk-notice-ok', text: '已创建模板。' }
    } else {
      const updated = await updateAnswerTemplate(selectedId.value, etag.value, {
        name: name.value.trim(),
        body: body.value,
        enabled: enabled.value,
      })
      await reloadSelection(updated.data.templateId)
      editorModal.value = false
      notice.value = { tone: 'bk-notice bk-notice-ok', text: '已保存模板。' }
    }
  } catch (failure) {
    await recoverVersion(failure)
  } finally {
    busy.value = false
  }
}

async function remove(): Promise<void> {
  if (!selected.value || busy.value || !etag.value) return
  busy.value = true
  error.value = null
  try {
    await deleteAnswerTemplate(selected.value.templateId, etag.value)
    notice.value = { tone: 'bk-notice bk-notice-ok', text: '已删除模板。' }
    selectedTemplate.value = null
    selectedId.value = ''
    editorModal.value = false
    await load()
  } catch (failure) {
    await recoverVersion(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="templates">
    <PageHeader title="回答模板">
      <template #actions>
        <button class="bk-btn bk-btn-info" type="button" @click="openExamples">
          格式示例
        </button>
        <button class="bk-btn" type="button" @click="generationModal = true">
          <Sparkles :size="15" aria-hidden="true" />
          AI 生成模板
        </button>
        <button class="bk-btn bk-btn-primary" type="button" @click="startNew()">
          ＋ 新建模板
        </button>
      </template>
    </PageHeader>

    <p
      v-if="error && !editorModal"
      class="bk-notice bk-notice-fail"
      role="alert"
    >
      {{ error }}
    </p>
    <p v-if="notice" :class="notice.tone" role="status">{{ notice.text }}</p>
    <p v-if="loading" class="bk-text-sm bk-muted">正在加载…</p>

    <div class="template-list-layout">
      <section class="bk-card" aria-label="模板列表">
        <div class="bk-card-head">
          <h3>模板列表</h3>
        </div>
        <p v-if="!loading && templateTotal === 0" class="bk-text-sm bk-muted">
          还没有模板。
        </p>
        <DataTable label="回答模板列表">
          <table class="bk-table">
            <thead>
              <tr>
                <th>模板</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="templateTotal === 0">
                <td colspan="3" class="empty-cell">还没有模板。</td>
              </tr>
              <tr v-for="template in templateItems" :key="template.templateId">
                <td>
                  <div class="t-name">
                    {{ template.name }}
                    <span
                      v-if="!template.isGlobal"
                      class="bk-badge bk-badge-info"
                      >助手级</span
                    >
                  </div>
                  <div class="t-sub">{{ subTitle(template) }}</div>
                </td>
                <td>
                  <span :class="statusBadge(template).cls">{{
                    statusBadge(template).text
                  }}</span>
                </td>
                <td>
                  <button
                    class="bk-btn bk-btn-sm bk-btn-neutral"
                    type="button"
                    :aria-pressed="template.templateId === selectedId"
                    @click="select(template)"
                  >
                    编辑
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </DataTable>
        <PaginationBar
          v-model:page="templatePage"
          v-model:page-size="templatePageSize"
          :total="templateTotal"
          unit="个模板"
          label="回答模板分页"
        />
      </section>

      <OverlayTransition>
        <ModalShell
          v-if="editorModal"
          :title="isNew ? '新建模板' : `编辑：${name || '未命名'}`"
          width="720px"
          @close="editorModal = false"
        >
          <div v-if="!isNew" class="bk-field template-scope">
            <span
              v-if="!isNew && selected && !selected.isGlobal"
              class="bk-badge bk-badge-info"
            >
              助手级
            </span>
            <span
              v-else-if="!isNew && selected?.isGlobal"
              class="bk-badge bk-badge-draft"
              >全局</span
            >
          </div>

          <form id="template-editor-form" @submit.prevent="save">
            <p
              v-if="error"
              class="bk-notice bk-notice-fail bk-mb-4"
              role="alert"
            >
              {{ error }}
            </p>
            <button
              v-if="!isNew && !etag"
              class="bk-btn bk-btn-ghost"
              type="button"
              :disabled="busy"
              @click="retryVersion"
            >
              读取最新版本
            </button>
            <details
              v-if="latestTemplate"
              class="bk-notice bk-notice-warn"
              open
            >
              <summary>
                服务端最新模板 ·
                {{ latestTemplate.enabled ? '已启用' : '已停用' }}
              </summary>
              <pre class="template-test-output">{{ latestTemplate.body }}</pre>
            </details>
            <label class="bk-field bk-switch-field">
              <span>启用模板</span>
              <FormSwitch
                v-model="enabled"
                title="停用后不参与解析，回退到其他模板"
              />
            </label>
            <div v-if="isNew" class="bk-field">
              <label for="tpl-name">模板名称</label>
              <FormInput
                id="tpl-name"
                v-model="name"
                :maxlength="FORM_LIMITS.name"
                :pattern="NON_BLANK_PATTERN"
                required
                placeholder="例如：差旅专用模板"
              />
            </div>

            <div class="bk-field">
              <label>模板正文</label>
              <TemplateBodyEditor v-model="body" />
            </div>

            <div class="bk-card-head preview-head">
              <h3>本地验证预览</h3>
            </div>
            <div class="template-test-controls">
              <label class="bk-field">
                <span>测试问题</span>
                <FormInput
                  v-model="testQuestion"
                  :maxlength="FORM_LIMITS.question"
                  placeholder="输入一个真实问题"
                />
              </label>
              <div class="bk-field">
                <span>测试场景</span>
                <SelectMenu
                  v-model="testScenario"
                  label="测试场景"
                  :options="[
                    { value: 'normal', label: '有正常引用' },
                    { value: 'empty', label: '没有引用' },
                    { value: 'expired', label: '包含失效政策' },
                  ]"
                />
              </div>
            </div>
            <div class="tpl-preview">
              <p class="bk-text-xs bk-muted">最终提示词（变量已替换）</p>
              <pre class="template-test-output">{{
                testPreview || '（模板为空）'
              }}</pre>
            </div>
            <div class="bk-card-head preview-head">
              <h3>结构预览</h3>
            </div>
            <div class="tpl-preview">
              <p v-if="previewParagraphs.length === 0" class="bk-muted">
                （模板为空）
              </p>
              <template
                v-for="(paragraph, pIndex) in previewParagraphs"
                :key="pIndex"
              >
                <p>
                  <template
                    v-for="(segment, sIndex) in paragraph"
                    :key="sIndex"
                  >
                    <span v-if="segment.isVar" class="ph">{{
                      segment.text
                    }}</span>
                    <template v-else>{{ segment.text }}</template>
                  </template>
                </p>
              </template>
            </div>
          </form>
          <template #actions>
            <button
              v-if="!isNew"
              class="bk-btn bk-btn-danger"
              type="button"
              :disabled="busy || !canDelete || !etag"
              :title="canDelete ? '删除模板' : '仍被引用或为全局模板，无法删除'"
              @click="remove"
            >
              删除
            </button>
            <button class="bk-btn" type="button" @click="editorModal = false">
              取消
            </button>
            <button
              class="bk-btn bk-btn-primary"
              type="submit"
              form="template-editor-form"
              :disabled="busy || (!isNew && !etag)"
            >
              {{ busy ? '保存中…' : '保存模板' }}
            </button>
          </template>
        </ModalShell>
      </OverlayTransition>

      <OverlayTransition>
        <ModalShell
          v-if="exampleModal"
          title="模板格式示例"
          width="780px"
          @close="exampleModal = false"
        >
          <p class="bk-text-sm bk-muted example-intro">
            选择一份示例查看格式；下载为 .txt
            后可粘贴到「模板正文」中按需修改。变量
            {question}、{references}、{validity} 会在生成回答时自动替换。
          </p>
          <FormRadioGroup
            v-model="exampleMode"
            :options="exampleOptions"
            label="示例类型"
          />
          <article v-if="activeExample" class="example-card">
            <header class="example-head">
              <div class="example-heading">
                <h4>{{ activeExample.title }}</h4>
                <p class="bk-text-xs bk-muted">
                  {{ activeExample.description }}
                </p>
              </div>
              <div class="example-actions">
                <button
                  class="bk-btn bk-btn-sm"
                  :class="
                    copyState === 'copied'
                      ? 'bk-btn-success'
                      : copyState === 'failed'
                        ? 'bk-btn-danger'
                        : ''
                  "
                  type="button"
                  @click="copyExample(activeExample)"
                >
                  <Copy :size="14" aria-hidden="true" />
                  {{
                    copyState === 'copied'
                      ? '已复制'
                      : copyState === 'failed'
                        ? '复制失败'
                        : '复制内容'
                  }}
                </button>
                <button
                  class="bk-btn bk-btn-sm bk-btn-info"
                  type="button"
                  @click="downloadExample(activeExample)"
                >
                  <Download :size="14" aria-hidden="true" />
                  下载 .txt
                </button>
              </div>
            </header>
            <p class="visually-hidden" role="status">
              {{
                copyState === 'copied'
                  ? '模板内容已复制到剪贴板'
                  : copyState === 'failed'
                    ? '复制失败，请检查浏览器剪贴板权限'
                    : ''
              }}
            </p>
            <div v-if="activeExample.mode === 'structured'" class="tpl-preview">
              <p v-for="(paragraph, pIndex) in activeParagraphs" :key="pIndex">
                <template v-for="(segment, sIndex) in paragraph" :key="sIndex">
                  <span v-if="segment.isVar" class="ph">{{
                    segment.text
                  }}</span>
                  <template v-else>{{ segment.text }}</template>
                </template>
              </p>
            </div>
            <pre v-else class="example-body">{{ activeExample.body }}</pre>
          </article>
          <template #actions>
            <button class="bk-btn" type="button" @click="exampleModal = false">
              关闭
            </button>
          </template>
        </ModalShell>
      </OverlayTransition>

      <OverlayTransition>
        <TemplateGenerationDialog
          v-if="generationModal"
          @close="generationModal = false"
        />
      </OverlayTransition>
    </div>
  </div>
</template>

<style scoped>
.templates {
  display: grid;
  gap: var(--space-4);
}

.template-list-layout {
  display: grid;
  gap: var(--space-4);
}

.tpl-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-divider);
}

.tpl-item:last-of-type {
  border-bottom: none;
}

.t-main {
  flex: 1;
  min-width: 0;
}

.t-name {
  font-weight: 600;
  font-size: var(--text-sm);
}

.t-sub {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  margin-top: 2px;
}

.template-scope {
  align-items: flex-start;
}

.preview-head {
  flex-direction: column;
  align-items: flex-start;
  margin-top: var(--space-6);
}

.tpl-preview {
  background: var(--color-canvas);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  line-height: 1.7;
}

.tpl-preview p {
  margin: 0;
  white-space: pre-wrap;
}

.tpl-preview p + p {
  margin-top: var(--space-2);
}

.tpl-preview .ph {
  color: var(--color-action);
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--color-surface-selected);
  border-radius: 4px;
  padding: 1px 5px;
  white-space: nowrap;
}

.example-intro {
  margin: 0 0 var(--space-4);
}

.example-card {
  margin-top: var(--space-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.example-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  margin-bottom: var(--space-3);
}

.example-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.example-heading {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

.example-heading h4 {
  margin: 0;
  font-size: var(--text-sm);
}

.example-heading p {
  margin: 0;
}

pre.example-body {
  margin: 0;
  padding: var(--space-3);
  background: var(--color-canvas);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
