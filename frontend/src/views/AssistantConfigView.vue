<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import { FORM_LIMITS, NON_BLANK_PATTERN } from '../formValidation'
import FormInput from '../components/FormInput.vue'
import SelectMenu from '../components/SelectMenu.vue'
import { computed, onMounted, ref, watch } from 'vue'
import {
  createAssistant,
  getAssistant,
  listAnswerTemplates,
  listAssistants,
  listSpaces,
  updateAssistant,
  type AnswerTemplate,
  type Assistant,
  type AssistantAdmin,
  type AssistantBinding,
  type Space,
} from '../api/knowledge'
import { describeError } from '../api/display'
import DataTable from '../components/DataTable.vue'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import PaginationBar from '../components/PaginationBar.vue'
import SpaceBindingPicker from '../components/SpaceBindingPicker.vue'
import { usePagedList } from '../pagination'

const spaces = ref<Space[]>([])
const templates = ref<AnswerTemplate[]>([])
const editing = ref<{ data: AssistantAdmin; etag: string } | null>(null)
const name = ref('')
const templateId = ref('')
const boundSpaceIds = ref<string[]>([])
const busy = ref(false)
const loading = ref(true)
const error = ref<string | null>(null)
const notice = ref<{ tone: string; text: string } | null>(null)
const editorModal = ref(false)

const isNew = computed(() => editing.value === null)
const editorError = ref<string | null>(null)

const nameFilter = ref('')
/** 助手名称筛选去抖间隔，与知识库页面保持一致。 */
const FILTER_DELAY_MS = 250
let filterTimer: ReturnType<typeof globalThis.setTimeout> | undefined

/** 助手列表走服务端分页；管理模式下包含已停用的助手，名称筛选下推到接口。 */
const {
  items: assistants,
  page: assistantPage,
  pageSize: assistantPageSize,
  total: assistantTotal,
  error: listError,
  reload: reloadAssistants,
  reset: resetAssistants,
} = usePagedList<Assistant>(
  (query, signal) =>
    listAssistants({
      ...query,
      management: true,
      q: nameFilter.value.trim(),
      signal,
    }),
  { describeError },
)

function cancelFilterTimer(): void {
  globalThis.clearTimeout(filterTimer)
}

/** 输入按关键字去抖；清空筛选时立即回到不筛选的第一页。 */
watch(nameFilter, () => {
  cancelFilterTimer()
  if (nameFilter.value.trim() === '') {
    void resetAssistants()
    return
  }
  filterTimer = globalThis.setTimeout(
    () => void resetAssistants(),
    FILTER_DELAY_MS,
  )
})

/** 接口已回填空间名；旧响应缺名称时回退到已加载的空间列表。 */
const spaceNameById = computed(
  () => new Map(spaces.value.map((space) => [space.spaceId, space.name])),
)

function bindingName(binding: AssistantBinding): string | undefined {
  return binding.spaceName ?? spaceNameById.value.get(binding.spaceId)
}

/** 列表列显示前两个空间名，其余折叠为 +N，完整名单放在 title 里。 */
function bindingSummary(assistant: Assistant): string {
  const total = assistant.bindings.length
  if (total === 0) return '未绑定空间'
  const names = assistant.bindings
    .map((binding) => bindingName(binding))
    .filter((name): name is string => typeof name === 'string')
  if (names.length === 0) return `${String(total)} 个空间`
  const head = names.slice(0, 2).join('、')
  const rest = total - Math.min(names.length, 2)
  return rest > 0 ? `${head} +${String(rest)}` : head
}

function bindingTitle(assistant: Assistant): string {
  const names = assistant.bindings.map(
    (binding) => bindingName(binding) ?? '未知空间',
  )
  if (names.length === 0) return ''
  return names.slice(0, 10).join('、') + (names.length > 10 ? ' …' : '')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [spacePage, templatePage] = await Promise.all([
      listSpaces(),
      listAnswerTemplates().catch(() => ({
        items: [],
        nextCursor: null,
        total: 0,
      })),
    ])
    spaces.value = spacePage.items
    templates.value = templatePage.items
    await reloadAssistants()
    if (listError.value !== null) error.value = listError.value
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    loading.value = false
  }
}

async function openEditor(assistantId: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  notice.value = null
  editorError.value = null
  try {
    const detail = await getAssistant(assistantId)
    editing.value = { data: detail.data, etag: detail.etag ?? '' }
    name.value = detail.data.name
    templateId.value = detail.data.templateId ?? ''
    boundSpaceIds.value = detail.data.bindings.map((binding) => binding.spaceId)
    editorModal.value = true
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    busy.value = false
  }
}

function startNew(): void {
  if (busy.value || loading.value) return
  editing.value = null
  name.value = ''
  templateId.value = ''
  boundSpaceIds.value = []
  editorError.value = null
  editorModal.value = true
  notice.value = null
  error.value = null
}

function closeEditor(): void {
  if (!busy.value) editorModal.value = false
}

function replaceRow(updated: Assistant): void {
  assistants.value = assistants.value.map((assistant) =>
    assistant.assistantId === updated.assistantId ? updated : assistant,
  )
}

/** 助手按名称排序，新建或改名后所在页会变，读全量定位到它那一页。 */
async function focusAssistant(assistantId: string): Promise<void> {
  if (nameFilter.value.trim() !== '') {
    // 筛选后的列表与全量顺序不同，清空筛选回到第一页，由用户自行翻页。
    nameFilter.value = ''
    return
  }
  try {
    const all = (await listAssistants({ management: true })).items
    const index = all.findIndex((item) => item.assistantId === assistantId)
    if (index < 0) return
    const target = Math.floor(index / assistantPageSize.value) + 1
    if (target !== assistantPage.value) assistantPage.value = target
  } catch {
    // 定位失败不影响保存结果，用户仍可翻页找到该助手。
  }
}

async function save(): Promise<void> {
  if (busy.value) return
  const trimmed = name.value.trim()
  if (!trimmed || trimmed.length > FORM_LIMITS.name) {
    editorError.value = '请输入助手名称，最多 120 个字符。'
    return
  }
  if (boundSpaceIds.value.length === 0) {
    editorError.value =
      '至少绑定 1 个空间，未绑定空间的助手不会出现在用户的助手列表里。'
    return
  }
  busy.value = true
  editorError.value = null
  const current = editing.value
  const payload = {
    name: trimmed,
    bindings: boundSpaceIds.value.map((spaceId) => ({
      spaceId,
      role: 'RETRIEVAL' as const,
    })),
    templateId: templateId.value === '' ? null : templateId.value,
  }
  try {
    if (current) {
      const updated = await updateAssistant(
        current.data.assistantId,
        current.etag,
        {
          ...payload,
          enabled: current.data.enabled,
        },
      )
      replaceRow(updated)
      await focusAssistant(updated.assistantId)
    } else {
      const created = await createAssistant(payload)
      await reloadAssistants()
      await focusAssistant(created.assistantId)
    }
    editorModal.value = false
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: current ? '已保存助手配置。' : '已创建助手。',
    }
  } catch (failure) {
    editorError.value = describeError(failure)
  } finally {
    busy.value = false
  }
}

async function toggleEnabled(assistant: Assistant): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  notice.value = null
  try {
    const { data, etag } = await getAssistant(assistant.assistantId)
    const updated = await updateAssistant(data.assistantId, etag ?? '', {
      name: data.name,
      bindings: data.bindings,
      templateId: data.templateId,
      enabled: !assistant.enabled,
    })
    replaceRow(updated)
    notice.value = {
      tone: 'bk-notice bk-notice-ok',
      text: `已${updated.enabled ? '启用' : '停用'}「${updated.name}」。`,
    }
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="assistant-config">
    <PageHeader title="助手配置">
      <template #actions>
        <button
          class="bk-btn bk-btn-primary"
          type="button"
          :disabled="loading || busy"
          @click="startNew"
        >
          ＋ 新建助手
        </button>
      </template>
    </PageHeader>

    <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
      {{ error }}
    </p>
    <p v-if="notice" :class="notice.tone" role="status">{{ notice.text }}</p>

    <div class="filter-bar">
      <FormInput
        v-model="nameFilter"
        :maxlength="FORM_LIMITS.search"
        class="name-filter"
        type="search"
        aria-label="筛选助手名称"
        placeholder="筛选助手名称…"
      />
      <span class="bk-grow"></span>
      <span class="bk-text-xs bk-muted">
        {{
          loading
            ? '正在加载…'
            : `${String(assistantTotal)} 个助手${nameFilter.trim() ? '（已筛选）' : ''}`
        }}
      </span>
    </div>

    <DataTable label="助手列表">
      <table class="bk-table assistant-table" aria-label="助手列表">
        <thead>
          <tr>
            <th scope="col">助手名称</th>
            <th scope="col">绑定空间</th>
            <th scope="col">状态</th>
            <th scope="col">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading || assistantTotal === 0">
            <td colspan="4" class="bk-muted">
              {{
                loading
                  ? '正在加载…'
                  : error
                    ? '助手列表加载失败。'
                    : '还没有助手。'
              }}
            </td>
          </tr>
          <tr v-for="assistant in assistants" :key="assistant.assistantId">
            <td class="bk-row-title assistant-name">{{ assistant.name }}</td>
            <td
              class="assistant-meta assistant-bindings"
              :title="bindingTitle(assistant)"
            >
              {{ bindingSummary(assistant) }}
            </td>
            <td class="assistant-meta">
              <span
                :class="
                  assistant.enabled
                    ? 'bk-badge bk-badge-ok'
                    : 'bk-badge bk-badge-draft'
                "
              >
                {{ assistant.enabled ? '启用中' : '已停用' }}
              </span>
            </td>
            <td class="assistant-actions">
              <button
                class="bk-btn bk-btn-sm bk-btn-neutral"
                type="button"
                :disabled="busy"
                @click="openEditor(assistant.assistantId)"
              >
                编辑
              </button>
              <button
                class="bk-btn bk-btn-sm"
                :class="assistant.enabled ? 'bk-btn-warning' : 'bk-btn-success'"
                type="button"
                :disabled="busy"
                @click="toggleEnabled(assistant)"
              >
                {{ assistant.enabled ? '停用' : '启用' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </DataTable>

    <PaginationBar
      v-model:page="assistantPage"
      v-model:page-size="assistantPageSize"
      :total="assistantTotal"
      unit="个助手"
      label="助手配置分页"
    />

    <OverlayTransition>
      <ModalShell
        v-if="editorModal"
        :title="isNew ? '新建助手' : `编辑助手：${editing?.data.name ?? name}`"
        width="760px"
        @close="closeEditor"
      >
        <section aria-label="助手详情">
          <form id="assistant-editor-form" @submit.prevent="save">
            <p v-if="editorError" class="bk-notice bk-notice-fail" role="alert">
              {{ editorError }}
            </p>
            <div class="bk-field">
              <label for="assistant-name">助手名称</label>
              <FormInput
                id="assistant-name"
                v-model="name"
                :maxlength="FORM_LIMITS.name"
                :pattern="NON_BLANK_PATTERN"
                required
                placeholder="例如：差旅政策助手"
              />
            </div>
            <div class="bk-field">
              <label for="assistant-template">回答模板</label>
              <SelectMenu
                id="assistant-template"
                v-model="templateId"
                label="回答模板"
                :options="[
                  { value: '', label: '继承全局模板' },
                  ...templates.map((item) => ({
                    value: item.templateId,
                    label:
                      (item.isGlobal
                        ? '全局默认模板'
                        : `助手级：${item.name}（优先于全局）`) +
                      (item.enabled ? '' : '（已停用）'),
                  })),
                ]"
              />
            </div>
            <div class="bk-field">
              <label for="assistant-model">模型</label>
              <FormInput
                id="assistant-model"
                model-value="继承全局配置"
                disabled
              />
            </div>

            <section
              class="binding-section"
              aria-labelledby="assistant-bindings-heading"
            >
              <div class="bk-card-head bind-head">
                <h3 id="assistant-bindings-heading">绑定空间</h3>
              </div>
              <p v-if="spaces.length === 0" class="bk-text-sm bk-muted">
                没有可绑定的空间。
              </p>
              <SpaceBindingPicker
                v-else
                v-model="boundSpaceIds"
                :known-spaces="spaces"
                :disabled="busy"
              />
            </section>
          </form>
        </section>
        <template #actions>
          <button
            class="bk-btn"
            type="button"
            :disabled="busy"
            @click="closeEditor"
          >
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="submit"
            form="assistant-editor-form"
            :disabled="busy"
          >
            {{ busy ? '保存中…' : '保存配置' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
  </div>
</template>

<style scoped>
.assistant-config {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
  grid-template-columns: minmax(0, 1fr);
}

.filter-bar {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  flex-wrap: wrap;
}

.name-filter {
  width: 220px;
}

.assistant-table td {
  vertical-align: middle;
}

.assistant-name {
  overflow-wrap: anywhere;
}

.assistant-meta,
.assistant-actions {
  white-space: nowrap;
}

.assistant-bindings {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.assistant-actions {
  width: 1%;
}

@media (max-width: 600px) {
  .assistant-table th,
  .assistant-table td {
    padding-inline: var(--space-2);
  }
}

.binding-section {
  margin-block-start: var(--space-4);
}

.bind-head {
  margin-bottom: var(--space-2);
}

.bind-head h3 {
  font-size: var(--text-sm);
}

.notice-sm {
  font-size: var(--text-xs);
  margin-top: var(--space-4);
}
</style>
