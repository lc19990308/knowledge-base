<script setup lang="ts">
import PageHeader from '../layouts/PageHeader.vue'
import {
  FORM_LIMITS,
  NON_BLANK_PATTERN,
  USERNAME_PATTERN,
  validNewPassword,
  validUsername,
} from '../formValidation'
import FormSwitch from '../components/FormSwitch.vue'
import FormInput from '../components/FormInput.vue'
import SelectMenu from '../components/SelectMenu.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { usePagedList } from '../pagination'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import {
  createLocalUser,
  createModelConfig,
  deleteModelConfig,
  getGlobalSettings,
  listAnswerTemplates,
  listModelConfigs,
  listSpaces,
  listUserSpaceMemberships,
  listUsers,
  putGlobalSettings,
  replaceUserSpaceMemberships,
  resetUserCredential,
  setUserAccess,
  updateModelConfig,
  type AnswerTemplate,
  type GlobalSettings,
  type ModelConfig,
  type ModelProtocol,
  type Space,
  type SpaceMembership,
  type UserAccess,
} from '../api/knowledge'
import { describeError } from '../api/display'

const {
  usernameMin,
  usernameMax,
  displayName: displayNameLimit,
  passwordMin,
  passwordMax,
} = FORM_LIMITS

type Tab = 'models' | 'members' | 'matrix'

const TABS: { key: Tab; label: string }[] = [
  { key: 'models', label: '全局模型配置' },
  { key: 'members', label: '成员与角色' },
  { key: 'matrix', label: '权限矩阵' },
]

const tab = ref<Tab>('models')
const userQuery = ref('')
const USER_SEARCH_DELAY_MS = 250
let usersTimer: ReturnType<typeof globalThis.setTimeout> | undefined
/** 全局配置弹窗的可选项需要完整列表，与表格分页数据分开保存。 */
const templates = ref<AnswerTemplate[]>([])
const allConfigs = ref<ModelConfig[]>([])
const settings = ref<GlobalSettings | null>(null)
const defaultModelId = ref('')
const templateId = ref('')
const etag = ref('')
const usageReportOn = ref(true)
const contextLimit = ref<number | ''>(10)
const busy = ref(false)
const loading = ref(true)
const error = ref<string | null>(null)
const notice = ref<{ tone: string; text: string } | null>(null)

// 全局配置与模型配置弹窗
const globalModal = ref(false)
const configModal = ref(false)
const configEditingId = ref('')
const configEtag = ref('')
const configAlias = ref('')
const configCredential = ref('')
const configProtocol = ref<ModelProtocol>('openai_compatible')
const configBaseUrl = ref('')
const configModelName = ref('')
const configEnabled = ref(true)
const configBusy = ref(false)

// 成员编辑弹窗：角色、账号状态与空间授权在同一处提交
const memberModal = ref(false)
const memberEditingId = ref('')
const memberEditingName = ref('')
const memberRole = ref<'USER' | 'SYSTEM_ADMIN'>('USER')
const memberCanCreate = ref(false)
const memberEnabled = ref(false)
const memberIsLocal = ref(false)
const memberNewPassword = ref('')
const memberBusy = ref(false)

// 空间授权：一次可分配多个空间，每个空间独立设置读取／维护
const memberSpaces = ref<Space[]>([])
const memberGrants = ref<Record<string, SpaceMembership>>({})
const memberSpacesLoading = ref(false)
const memberSpacesError = ref<string | null>(null)
const memberSpacesLoaded = ref(false)
let memberSpacesRequest: AbortController | undefined

const isAdminTarget = computed(() => memberRole.value === 'SYSTEM_ADMIN')

const memberSpaceRows = computed(() =>
  memberSpaces.value.map((space) => {
    const grant = memberGrants.value[space.spaceId]
    // 系统管理员默认对全部空间可读可维护，列表照常展示但不可修改。
    const admin = isAdminTarget.value
    const owned = grant?.owned ?? false
    return {
      spaceId: space.spaceId,
      name: space.name,
      owned,
      canRead: admin || owned || (grant?.canRead ?? false),
      canMaintain: admin || owned || (grant?.canMaintain ?? false),
    }
  }),
)

// 新建账号弹窗
const createModal = ref(false)
const newUsername = ref('')
const newDisplayName = ref('')
const newPassword = ref('')
const newIsAdmin = ref(false)
const creating = ref(false)

const enabledConfigs = computed(() =>
  allConfigs.value.filter((item) => item.enabled),
)

/** 模型配置与成员两张表各自走服务端分页，条数由分页器决定。 */
const {
  items: configs,
  page: configPage,
  pageSize: configPageSize,
  total: configTotal,
  error: configListError,
  reload: reloadConfigs,
} = usePagedList<ModelConfig>(
  (query, signal) => listModelConfigs({ ...query, signal }),
  { describeError },
)

const userQueryError = ref<string | null>(null)
const {
  items: users,
  page: userPage,
  pageSize: userPageSize,
  total: userTotal,
  loading: usersLoading,
  error: usersListError,
  reload: reloadUsers,
  reset: resetUsers,
} = usePagedList<UserAccess>(
  (query, signal) => listUsers(userQuery.value.trim(), { ...query, signal }),
  { describeError },
)
const usersError = computed(() => userQueryError.value ?? usersListError.value)
const MATRIX = [
  {
    action: '搜索 / 问答已发布且有权限的空间',
    user: true,
    maintainer: true,
    admin: true,
  },
  {
    action: '检索未发布 / 已撤回内容（仅检索，不含问答）',
    user: false,
    maintainer: false,
    admin: true,
  },
  {
    action: '文档预览 / 下载（管理员可取最近发布与草稿）',
    user: true,
    maintainer: true,
    admin: true,
  },
  {
    action: '创建空间（非管理员需单独授权）',
    user: false,
    maintainer: false,
    admin: true,
  },
  {
    action: '导入文档（需空间维护权限）',
    user: false,
    maintainer: true,
    admin: true,
  },
  {
    action: '提交发布审核 / 驳回后重提',
    user: false,
    maintainer: true,
    admin: true,
  },
  {
    action: '审核发布 / 驳回 / 撤回发布',
    user: false,
    maintainer: false,
    admin: true,
  },
  {
    action: '开放助手可绑定的空间',
    user: false,
    maintainer: false,
    admin: true,
  },
  {
    action: '回答模板 / 全局模型配置',
    user: false,
    maintainer: false,
    admin: true,
  },
]

const issuerLabel = (issuer: string): string =>
  issuer === 'local' ? '本地账号' : '企业 IdP'

function notify(text: string): void {
  notice.value = { tone: 'bk-notice bk-notice-ok', text }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [settingsResult, templatePage, configPage] = await Promise.all([
      getGlobalSettings(),
      listAnswerTemplates().catch(() => ({ items: [], total: 0 })),
      listModelConfigs().catch(() => ({ items: [], total: 0 })),
    ])
    settings.value = settingsResult.data
    defaultModelId.value = settingsResult.data.defaultModelId ?? ''
    templateId.value = settingsResult.data.templateId ?? ''
    contextLimit.value = settingsResult.data.contextLimit
    usageReportOn.value = settingsResult.data.usageReportEnabled
    etag.value = settingsResult.etag ?? ''
    templates.value = templatePage.items
    allConfigs.value = configPage.items
    await reloadConfigs()
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    loading.value = false
  }
}

function closeGlobal(): void {
  globalModal.value = false
  error.value = null
}

async function saveGlobal(): Promise<void> {
  if (busy.value) return
  const limit = contextLimit.value
  if (
    typeof limit !== 'number' ||
    !Number.isInteger(limit) ||
    limit < FORM_LIMITS.contextMin ||
    limit > FORM_LIMITS.contextMax
  ) {
    error.value = '上下文引用上限须为 1–100 的整数。'
    return
  }
  busy.value = true
  error.value = null
  notice.value = null
  try {
    const updated = await putGlobalSettings(etag.value, {
      defaultModelId: defaultModelId.value === '' ? null : defaultModelId.value,
      templateId: templateId.value === '' ? null : templateId.value,
      contextLimit: limit,
      usageReportEnabled: usageReportOn.value,
    })
    etag.value = updated.etag ?? etag.value
    settings.value = updated.data
    defaultModelId.value = updated.data.defaultModelId ?? ''
    templateId.value = updated.data.templateId ?? ''
    contextLimit.value = updated.data.contextLimit
    usageReportOn.value = updated.data.usageReportEnabled
    await loadConfigs()
    globalModal.value = false
    notify('已保存全局配置。')
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    busy.value = false
  }
}

// ---------- 模型配置（多条，弹窗新增/编辑/删除） ----------
async function loadConfigs(): Promise<void> {
  try {
    await Promise.all([reloadConfigs(), reloadAllConfigs()])
    if (configListError.value !== null) error.value = configListError.value
  } catch (failure) {
    error.value = describeError(failure)
  }
}

async function reloadAllConfigs(): Promise<void> {
  try {
    allConfigs.value = (await listModelConfigs()).items
  } catch (failure) {
    error.value = describeError(failure)
  }
}

function openConfigCreate(): void {
  configEditingId.value = ''
  configEtag.value = ''
  configAlias.value = ''
  configCredential.value = ''
  configProtocol.value = 'openai_compatible'
  configBaseUrl.value = ''
  configModelName.value = ''
  configEnabled.value = true
  error.value = null
  configModal.value = true
}

function openConfigEdit(config: ModelConfig): void {
  configEditingId.value = config.id
  configEtag.value = `"${String(config.revision)}"`
  configAlias.value = config.alias
  configCredential.value = config.credentialRef
  configProtocol.value = config.protocol
  configBaseUrl.value = config.baseUrl
  configModelName.value = config.modelName
  configEnabled.value = config.enabled
  error.value = null
  configModal.value = true
}

async function submitConfig(): Promise<void> {
  if (configBusy.value) return
  if (
    configAlias.value.trim() === '' ||
    configCredential.value.trim() === '' ||
    configBaseUrl.value.trim() === '' ||
    configModelName.value.trim() === ''
  ) {
    error.value = '别名、凭据引用、网关地址和模型 ID 都不能为空。'
    return
  }
  if (
    configAlias.value.trim().length > FORM_LIMITS.name ||
    configModelName.value.trim().length > FORM_LIMITS.name
  ) {
    error.value = '模型别名和模型 ID 最多 120 个字符。'
    return
  }
  if (
    configCredential.value.trim().length > FORM_LIMITS.credentialRef ||
    configBaseUrl.value.trim().length > FORM_LIMITS.modelAddress
  ) {
    error.value = '凭据引用和网关地址最多 255 个字符。'
    return
  }
  try {
    const address = configBaseUrl.value.trim()
    if (!/^https?:\/\//i.test(address) || /[\s\\]/.test(address)) {
      error.value = '请输入完整的网关地址，地址中不能包含空白或反斜杠。'
      return
    }
    const baseUrl = new URL(address)
    const localMagpie =
      configProtocol.value === 'openai_compatible' &&
      address === 'http://127.0.0.1:3425/v1'
    if (
      (baseUrl.protocol !== 'https:' && !localMagpie) ||
      baseUrl.username ||
      baseUrl.password ||
      address.includes('?') ||
      address.includes('#')
    ) {
      error.value =
        '请输入不含凭据、查询参数或片段的 HTTPS 地址，或本机 Magpie 的 OpenAI 兼容地址。'
      return
    }
  } catch {
    error.value = '网关地址格式无效。'
    return
  }
  configBusy.value = true
  error.value = null
  try {
    const payload = {
      alias: configAlias.value.trim(),
      credentialRef: configCredential.value.trim(),
      enabled: configEnabled.value,
      protocol: configProtocol.value,
      baseUrl: configBaseUrl.value.trim(),
      modelName: configModelName.value.trim(),
    }
    const isCreatingConfig = configEditingId.value === ''
    if (isCreatingConfig) {
      await createModelConfig(payload)
      notify('已新增模型配置。')
    } else {
      await updateModelConfig(configEditingId.value, configEtag.value, payload)
      notify('已保存模型配置。')
    }
    configModal.value = false
    await loadConfigs()
    if (isCreatingConfig) {
      const lastPage = Math.max(
        1,
        Math.ceil(configTotal.value / configPageSize.value),
      )
      if (lastPage !== configPage.value) configPage.value = lastPage
    }
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    configBusy.value = false
  }
}

async function removeConfig(config: ModelConfig): Promise<void> {
  if (configBusy.value) return
  configBusy.value = true
  error.value = null
  try {
    await deleteModelConfig(config.id, String(config.revision))
    notify('已删除模型配置。')
    await loadConfigs()
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    configBusy.value = false
  }
}

// ---------- 成员（表格 + 弹窗编辑） ----------
function cancelUsersSearch(): void {
  globalThis.clearTimeout(usersTimer)
}

/** 搜索词变化后去抖；切换页签时立即重读，保证成员表拿到当前页。 */
watch(
  [tab, userQuery],
  ([currentTab, currentQuery], [previousTab, previousQuery]) => {
    cancelUsersSearch()
    if (currentTab !== 'members') {
      users.value = []
      return
    }
    if (currentQuery.trim().length > FORM_LIMITS.search) {
      userQueryError.value = '搜索词最多 200 个字符。'
      return
    }
    userQueryError.value = null
    if (previousTab !== 'members' || currentQuery === previousQuery) {
      void resetUsers()
      return
    }
    usersTimer = globalThis.setTimeout(
      () => void resetUsers(),
      USER_SEARCH_DELAY_MS,
    )
  },
  { flush: 'sync' },
)
onUnmounted(cancelUsersSearch)

/** 读取全部空间与该成员已有的授权，供一次给多个空间分配权限。 */
async function loadMemberSpaces(userId: string): Promise<void> {
  memberSpacesRequest?.abort()
  const request = new AbortController()
  memberSpacesRequest = request
  memberSpacesLoading.value = true
  memberSpacesError.value = null
  memberSpacesLoaded.value = false
  try {
    const [spacePage, grantPage] = await Promise.all([
      listSpaces({ signal: request.signal }),
      listUserSpaceMemberships(userId, request.signal),
    ])
    if (request.signal.aborted) return
    memberSpaces.value = spacePage.items
    const grants: Record<string, SpaceMembership> = {}
    for (const grant of grantPage.items) {
      grants[grant.spaceId] = grant
    }
    memberGrants.value = grants
    memberSpacesLoaded.value = true
  } catch (failure) {
    if (!request.signal.aborted)
      memberSpacesError.value = describeError(failure)
  } finally {
    if (!request.signal.aborted) memberSpacesLoading.value = false
  }
}

function grantOf(spaceId: string): SpaceMembership {
  return (
    memberGrants.value[spaceId] ?? {
      spaceId,
      spaceName:
        memberSpaces.value.find((space) => space.spaceId === spaceId)?.name ??
        spaceId,
      canRead: false,
      canMaintain: false,
      owned: false,
    }
  )
}

function setSpaceRead(spaceId: string, canRead: boolean): void {
  const grant = grantOf(spaceId)
  if (grant.owned) return
  memberGrants.value[spaceId] = {
    ...grant,
    canRead,
    canMaintain: canRead ? grant.canMaintain : false,
  }
}

function setSpaceMaintain(spaceId: string, canMaintain: boolean): void {
  const grant = grantOf(spaceId)
  if (grant.owned) return
  memberGrants.value[spaceId] = {
    ...grant,
    canRead: canMaintain ? true : grant.canRead,
    canMaintain,
  }
}

/** 全选只授予读取权限；维护权限逐空间单独开启。所有者空间不受影响。 */
function selectAllSpaces(): void {
  const next = { ...memberGrants.value }
  for (const row of memberSpaceRows.value) {
    if (row.owned) continue
    next[row.spaceId] = { ...grantOf(row.spaceId), canRead: true }
  }
  memberGrants.value = next
}

function clearSpaces(): void {
  const next = { ...memberGrants.value }
  for (const row of memberSpaceRows.value) {
    if (row.owned) continue
    next[row.spaceId] = {
      ...grantOf(row.spaceId),
      canRead: false,
      canMaintain: false,
    }
  }
  memberGrants.value = next
}

function closeMemberModal(): void {
  if (memberBusy.value) return
  memberSpacesRequest?.abort()
  memberModal.value = false
  error.value = null
}

function openMemberCreate(): void {
  newUsername.value = ''
  newDisplayName.value = ''
  newPassword.value = ''
  newIsAdmin.value = false
  error.value = null
  createModal.value = true
}

async function submitMemberCreate(): Promise<void> {
  const name = newUsername.value.trim()
  const display = newDisplayName.value.trim()
  if (creating.value) return
  if (!validUsername(name)) {
    error.value =
      '用户名须为 3–64 位，以英文字母或数字开头，其余可用英文字母、数字、点、下划线和连字符。'
    return
  }
  if (!display || display.length > FORM_LIMITS.displayName) {
    error.value = '请填写显示名，最多 60 个字符。'
    return
  }
  if (!validNewPassword(newPassword.value)) {
    error.value = '密码须为 8–72 个字符，且 UTF-8 编码后最多 72 字节。'
    return
  }
  creating.value = true
  error.value = null
  try {
    await createLocalUser({
      username: name,
      displayName: display,
      password: newPassword.value,
      roles: newIsAdmin.value ? ['USER', 'SYSTEM_ADMIN'] : ['USER'],
    })
    createModal.value = false
    await reloadUsers()
    notify(`已创建本地账号「${name}」，该账号现在可以在登录页登录。`)
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    creating.value = false
  }
}

function openMemberEdit(user: UserAccess): void {
  memberEditingId.value = user.userId
  memberEditingName.value = user.displayName
  memberRole.value = user.roles.includes('SYSTEM_ADMIN')
    ? 'SYSTEM_ADMIN'
    : 'USER'
  memberCanCreate.value = user.canCreateSpace
  memberEnabled.value = user.enabled
  memberIsLocal.value = user.issuer === 'local'
  memberNewPassword.value = ''
  memberSpaces.value = []
  memberGrants.value = {}
  memberSpacesLoaded.value = false
  memberSpacesError.value = null
  error.value = null
  memberModal.value = true
  void loadMemberSpaces(user.userId)
}

async function submitMemberEdit(): Promise<void> {
  if (memberBusy.value) return
  if (
    memberIsLocal.value &&
    memberNewPassword.value !== '' &&
    !validNewPassword(memberNewPassword.value)
  ) {
    error.value =
      '新密码须为 8–72 个字符，且 UTF-8 编码后最多 72 字节。留空表示不改密。'
    return
  }
  memberBusy.value = true
  error.value = null
  try {
    await setUserAccess(memberEditingId.value, {
      enabled: memberEnabled.value,
      roles:
        memberRole.value === 'SYSTEM_ADMIN'
          ? ['USER', 'SYSTEM_ADMIN']
          : ['USER'],
      canCreateSpace: memberCanCreate.value,
    })
    if (memberIsLocal.value && memberNewPassword.value !== '') {
      await resetUserCredential(memberEditingId.value, memberNewPassword.value)
    }
    let grantNote = '（未改动空间授权）'
    if (!isAdminTarget.value && memberSpacesLoaded.value) {
      const memberships = memberSpaceRows.value
        .filter((row) => row.canRead || row.canMaintain)
        .map((row) => ({
          spaceId: row.spaceId,
          canRead: row.canRead,
          canMaintain: row.canMaintain,
        }))
      await replaceUserSpaceMemberships(memberEditingId.value, memberships)
      grantNote = `，空间授权 ${String(memberships.length)} 项`
    }
    memberModal.value = false
    await reloadUsers()
    notify(`已保存成员设置${grantNote}。`)
  } catch (failure) {
    error.value = describeError(failure)
  } finally {
    memberBusy.value = false
  }
}

// 从系统管理员改回普通用户时，补读空间授权，避免保存时看不到可分配的空间。
watch(memberRole, (role) => {
  if (!memberModal.value || role === 'SYSTEM_ADMIN') return
  if (memberSpacesLoaded.value || memberSpacesLoading.value) return
  void loadMemberSpaces(memberEditingId.value)
})

onMounted(() => {
  void load()
})

onUnmounted(() => memberSpacesRequest?.abort())
</script>

<template>
  <div class="settings">
    <PageHeader title="系统设置" />

    <p
      v-if="
        error && !globalModal && !configModal && !createModal && !memberModal
      "
      class="bk-notice bk-notice-fail"
      role="alert"
    >
      {{ error }}
    </p>
    <p v-if="notice" :class="notice.tone" role="status">{{ notice.text }}</p>
    <p v-if="loading" class="bk-text-sm bk-muted">正在加载…</p>

    <div class="bk-tabs" role="tablist" aria-label="设置模块">
      <button
        v-for="item in TABS"
        :key="item.key"
        class="bk-tab"
        type="button"
        role="tab"
        :aria-selected="tab === item.key"
        @click="tab = item.key"
      >
        {{ item.label }}
      </button>
    </div>

    <!-- Tab 1：全局模型配置（多条） -->
    <section v-if="tab === 'models'" class="settings-tab">
      <section class="bk-card bk-mb-4" aria-label="全局默认">
        <div class="bk-card-head">
          <div>
            <h3>全局设置</h3>
          </div>
          <span class="bk-grow"></span>
          <button
            class="bk-btn bk-btn-sm bk-btn-neutral"
            type="button"
            @click="globalModal = true"
          >
            编辑全局配置
          </button>
        </div>
        <div class="global-summary">
          <div>
            <span class="summary-label">默认模型</span>
            <strong>{{
              enabledConfigs.find((item) => item.id === defaultModelId)
                ?.alias || '未指定'
            }}</strong>
          </div>
          <div>
            <span class="summary-label">回答模板</span>
            <strong>{{
              templates.find((item) => item.templateId === templateId)?.name ||
              '未指定'
            }}</strong>
          </div>
          <div>
            <span class="summary-label">引用上限</span>
            <strong>{{ contextLimit }} 段 / 次</strong>
          </div>
          <div>
            <span class="summary-label">用量上报</span>
            <strong>{{ usageReportOn ? '已启用' : '已关闭' }}</strong>
          </div>
        </div>
      </section>

      <section class="bk-card" aria-label="模型配置列表">
        <div class="bk-card-head">
          <h3>模型配置（{{ configTotal }}）</h3>
          <span class="bk-grow"></span>
          <button
            class="bk-btn bk-btn-sm bk-btn-primary"
            type="button"
            @click="openConfigCreate"
          >
            ＋ 新建模型配置
          </button>
        </div>
        <div class="bk-table-wrap">
          <table class="bk-table">
            <thead>
              <tr>
                <th>别名</th>
                <th>协议 / 模型</th>
                <th>网关地址</th>
                <th>凭据引用</th>
                <th>启用</th>
                <th>默认</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="configTotal === 0">
                <td colspan="7" class="empty-cell">
                  还没有模型配置，先新建一条。
                </td>
              </tr>
              <tr v-for="config in configs" :key="config.id">
                <td>
                  <div class="bk-row-title">{{ config.alias }}</div>
                </td>
                <td>
                  <div class="bk-row-title">{{ config.protocol }}</div>
                  <div class="bk-row-sub">
                    {{ config.modelName || '未填写模型 ID' }}
                  </div>
                </td>
                <td class="bk-mono">{{ config.baseUrl || '未填写' }}</td>
                <td class="bk-mono">{{ config.credentialRef }}</td>
                <td>
                  <span
                    :class="
                      config.enabled
                        ? 'bk-badge bk-badge-ok'
                        : 'bk-badge bk-badge-draft'
                    "
                    >{{ config.enabled ? '启用' : '停用' }}</span
                  >
                </td>
                <td>
                  <span
                    v-if="config.isDefault"
                    class="bk-badge bk-badge-published"
                    >默认</span
                  >
                  <span v-else class="bk-text-xs bk-muted">—</span>
                </td>
                <td>
                  <button
                    class="bk-btn bk-btn-sm bk-btn-neutral"
                    type="button"
                    @click="openConfigEdit(config)"
                  >
                    编辑
                  </button>
                  <button
                    class="bk-btn bk-btn-sm bk-btn-danger"
                    type="button"
                    :disabled="config.isDefault"
                    :title="
                      config.isDefault
                        ? '默认模型不可删除，请先切换默认'
                        : '删除该配置'
                    "
                    @click="removeConfig(config)"
                  >
                    删除
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <PaginationBar
          v-model:page="configPage"
          v-model:page-size="configPageSize"
          :total="configTotal"
          unit="条配置"
          label="模型配置分页"
        />
      </section>
    </section>

    <!-- Tab 2：成员与角色（表格 + 弹窗） -->
    <section v-else-if="tab === 'members'" class="settings-tab">
      <section class="bk-card" aria-label="成员与角色">
        <div class="bk-card-head">
          <h3>成员（{{ userTotal }}）</h3>
          <span class="bk-grow"></span>
          <button
            class="bk-btn bk-btn-sm bk-btn-primary"
            type="button"
            @click="openMemberCreate"
          >
            ＋ 新建账号
          </button>
        </div>
        <div class="bk-field bk-mb-4">
          <label for="settings-user-search">搜索成员</label>
          <FormInput
            id="settings-user-search"
            v-model="userQuery"
            :maxlength="FORM_LIMITS.search"
            type="search"
            placeholder="输入显示名或完整用户 ID"
          />
        </div>
        <p v-if="usersError" class="bk-notice bk-notice-fail" role="alert">
          {{ usersError }}
        </p>
        <div class="bk-table-wrap" :aria-busy="usersLoading">
          <table class="bk-table">
            <thead>
              <tr>
                <th>成员</th>
                <th>来源</th>
                <th>状态</th>
                <th>可创建空间</th>
                <th>角色</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="usersLoading">
                <td colspan="6" class="empty-cell">正在加载成员…</td>
              </tr>
              <tr v-else-if="!usersError && userTotal === 0">
                <td colspan="6" class="empty-cell">
                  {{
                    userQuery.trim() ? '没有匹配的成员。' : '没有可管理的用户。'
                  }}
                </td>
              </tr>
              <tr
                v-for="user in usersLoading || usersError ? [] : users"
                :key="user.userId"
              >
                <td>
                  <div class="bk-row-title">{{ user.displayName }}</div>
                  <div class="bk-row-sub">{{ user.userId.slice(0, 8) }}</div>
                </td>
                <td>{{ issuerLabel(user.issuer) }}</td>
                <td>
                  <span
                    :class="
                      user.enabled
                        ? 'bk-badge bk-badge-ok'
                        : 'bk-badge bk-badge-draft'
                    "
                    >{{ user.enabled ? '启用' : '停用' }}</span
                  >
                </td>
                <td>{{ user.canCreateSpace ? '是' : '否' }}</td>
                <td>
                  <span
                    :class="
                      user.roles.includes('SYSTEM_ADMIN')
                        ? 'bk-badge bk-badge-review'
                        : 'bk-badge bk-badge-draft'
                    "
                    >{{
                      user.roles.includes('SYSTEM_ADMIN')
                        ? '系统管理员'
                        : '普通用户'
                    }}</span
                  >
                </td>
                <td class="member-actions">
                  <button
                    class="bk-btn bk-btn-sm bk-btn-neutral"
                    type="button"
                    @click="openMemberEdit(user)"
                  >
                    编辑
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <PaginationBar
          v-model:page="userPage"
          v-model:page-size="userPageSize"
          :total="userTotal"
          unit="个成员"
          label="成员分页"
          :disabled="usersLoading"
        />
      </section>
    </section>

    <!-- Tab 3：权限矩阵（表格） -->
    <section v-else class="settings-tab">
      <section class="bk-card" aria-label="权限矩阵">
        <div class="bk-card-head">
          <h3>权限矩阵</h3>
        </div>
        <div class="bk-table-wrap table-compact">
          <table class="bk-table perm-matrix">
            <thead>
              <tr>
                <th>操作</th>
                <th>普通用户</th>
                <th>提交用户（获维护权限）</th>
                <th>系统管理员</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in MATRIX" :key="row.action">
                <td class="bk-row-title">{{ row.action }}</td>
                <td :class="row.user ? 'perm-yes' : 'perm-no'">
                  {{ row.user ? '✓' : '—' }}
                </td>
                <td :class="row.maintainer ? 'perm-yes' : 'perm-no'">
                  {{ row.maintainer ? '✓' : '—' }}
                </td>
                <td :class="row.admin ? 'perm-yes' : 'perm-no'">
                  {{ row.admin ? '✓' : '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </section>

    <!-- 全局配置弹窗 -->
    <OverlayTransition>
      <ModalShell
        v-if="globalModal"
        title="全局配置"
        width="720px"
        @close="closeGlobal"
      >
        <form id="global-settings-form" @submit.prevent="saveGlobal">
          <p v-if="error" class="bk-notice bk-notice-fail bk-mb-4" role="alert">
            {{ error }}
          </p>
          <div class="bk-field">
            <label for="default-model">默认模型</label>
            <SelectMenu
              id="default-model"
              v-model="defaultModelId"
              label="默认模型"
              :options="[
                { value: '', label: '未指定' },
                ...enabledConfigs.map((config) => ({
                  value: config.id,
                  label: config.alias,
                })),
              ]"
            />
          </div>
          <div class="bk-field">
            <label for="global-template">全局回答模板</label>
            <SelectMenu
              id="global-template"
              v-model="templateId"
              label="全局回答模板"
              :options="[
                { value: '', label: '未指定' },
                ...templates.map((item) => ({
                  value: item.templateId,
                  label:
                    item.name +
                    (item.enabled ? '' : '（已停用）') +
                    (item.isGlobal ? '（当前全局）' : ''),
                })),
              ]"
            />
          </div>
          <div class="bk-field">
            <label for="context-limit">上下文引用上限（每次提问）</label>
            <FormInput
              id="context-limit"
              v-model="contextLimit"
              required
              step="1"
              type="number"
              :min="FORM_LIMITS.contextMin"
              :max="FORM_LIMITS.contextMax"
              placeholder="例如：12"
            />
          </div>
          <label class="bk-field bk-switch-field">
            <span>用量上报</span>
            <FormSwitch v-model="usageReportOn" />
          </label>
          <div class="bk-field">
            <label for="statistics-timezone">统计时区</label>
            <FormInput
              id="statistics-timezone"
              model-value="Asia/Shanghai（北京时间）"
              disabled
            />
          </div>
        </form>
        <template #actions>
          <button
            class="bk-btn"
            type="button"
            :disabled="busy"
            @click="closeGlobal"
          >
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="submit"
            form="global-settings-form"
            :disabled="busy"
          >
            {{ busy ? '保存中…' : '保存' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>

    <!-- 模型配置弹窗 -->
    <OverlayTransition>
      <ModalShell
        v-if="configModal"
        :title="configEditingId === '' ? '新建模型配置' : '编辑模型配置'"
        width="480px"
        @close="configModal = false"
      >
        <form id="model-config-form" @submit.prevent="submitConfig">
          <p v-if="error" class="bk-notice bk-notice-fail bk-mb-4" role="alert">
            {{ error }}
          </p>
          <div class="bk-field">
            <label for="config-alias">模型别名</label>
            <FormInput
              id="config-alias"
              v-model="configAlias"
              :pattern="NON_BLANK_PATTERN"
              required
              :maxlength="FORM_LIMITS.name"
              placeholder="例如：GLM-5.3"
              :disabled="configBusy"
            />
          </div>
          <div class="bk-field">
            <label for="config-protocol">协议</label>
            <SelectMenu
              id="config-protocol"
              v-model="configProtocol"
              :disabled="configBusy"
              label="协议"
              :options="[
                {
                  value: 'openai_compatible',
                  label: 'OpenAI 兼容协议（OpenAI / DeepSeek / 智谱 / 通义等）',
                },
                { value: 'anthropic', label: 'Anthropic Messages 原生协议' },
              ]"
            />
          </div>
          <div class="bk-field">
            <label for="config-base-url">网关地址</label>
            <FormInput
              id="config-base-url"
              v-model="configBaseUrl"
              :maxlength="FORM_LIMITS.modelAddress"
              required
              type="url"
              placeholder="例如：https://api.openai.com/v1"
              :disabled="configBusy"
            />
          </div>
          <div class="bk-field">
            <label for="config-model-name">模型 ID</label>
            <FormInput
              id="config-model-name"
              v-model="configModelName"
              :maxlength="FORM_LIMITS.name"
              :pattern="NON_BLANK_PATTERN"
              required
              placeholder="例如：gpt-4o-mini / claude-3-5-sonnet"
              :disabled="configBusy"
            />
          </div>
          <div class="bk-field">
            <label for="config-credential">凭据引用</label>
            <FormInput
              id="config-credential"
              v-model="configCredential"
              :maxlength="FORM_LIMITS.credentialRef"
              :pattern="NON_BLANK_PATTERN"
              required
              placeholder="例如：vault:glm53"
              :disabled="configBusy"
            />
          </div>
          <label class="bk-field bk-switch-field">
            <span>启用</span>
            <FormSwitch v-model="configEnabled" />
          </label>
        </form>
        <template #actions>
          <button class="bk-btn" type="button" @click="configModal = false">
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="submit"
            form="model-config-form"
            :disabled="configBusy"
          >
            {{ configBusy ? '保存中…' : '保存' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>

    <!-- 新建账号弹窗 -->
    <OverlayTransition>
      <ModalShell
        v-if="createModal"
        title="新建账号"
        width="480px"
        @close="createModal = false"
      >
        <form id="member-create-form" @submit.prevent="submitMemberCreate">
          <p v-if="error" class="bk-notice bk-notice-fail bk-mb-4" role="alert">
            {{ error }}
          </p>
          <div class="bk-field">
            <label for="new-username">用户名</label>
            <FormInput
              id="new-username"
              v-model="newUsername"
              :pattern="USERNAME_PATTERN"
              :minlength="usernameMin"
              required
              title="以英文字母或数字开头，3–64 位，可包含点、下划线和连字符"
              :maxlength="usernameMax"
              placeholder="3–64 位，以英文字母或数字开头"
              :disabled="creating"
            />
          </div>
          <div class="bk-field">
            <label for="new-display">显示名</label>
            <FormInput
              id="new-display"
              v-model="newDisplayName"
              :pattern="NON_BLANK_PATTERN"
              required
              :maxlength="displayNameLimit"
              placeholder="例如：张三"
              :disabled="creating"
            />
          </div>
          <div class="bk-field">
            <label for="new-password">初始密码</label>
            <FormInput
              id="new-password"
              v-model="newPassword"
              :minlength="passwordMin"
              :maxlength="passwordMax"
              required
              autocomplete="new-password"
              type="password"
              placeholder="8–72 个字符"
              :disabled="creating"
            />
          </div>
          <label class="bk-field bk-switch-field">
            <span>授予系统管理员</span>
            <FormSwitch v-model="newIsAdmin" :disabled="creating" />
          </label>
        </form>
        <template #actions>
          <button class="bk-btn" type="button" @click="createModal = false">
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="submit"
            form="member-create-form"
            :disabled="creating"
          >
            {{ creating ? '创建中…' : '创建账号' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>

    <!-- 编辑成员弹窗：账号设置与空间授权在同一处提交 -->
    <OverlayTransition>
      <ModalShell
        v-if="memberModal"
        :title="`编辑成员 · ${memberEditingName}`"
        width="640px"
        @close="closeMemberModal"
      >
        <form id="member-edit-form" @submit.prevent="submitMemberEdit">
          <p v-if="error" class="bk-notice bk-notice-fail bk-mb-4" role="alert">
            {{ error }}
          </p>
          <div class="bk-field">
            <label for="member-role">角色</label>
            <SelectMenu
              id="member-role"
              v-model="memberRole"
              label="角色"
              :options="[
                { value: 'USER', label: '普通用户（按空间授权）' },
                {
                  value: 'SYSTEM_ADMIN',
                  label: '系统管理员（全部空间默认最高权限）',
                },
              ]"
            />
          </div>
          <label class="bk-field bk-switch-field">
            <span>可创建空间</span>
            <FormSwitch v-model="memberCanCreate" />
          </label>
          <label class="bk-field bk-switch-field">
            <span>启用账号</span>
            <FormSwitch v-model="memberEnabled" />
          </label>
          <div v-if="memberIsLocal" class="bk-field">
            <label for="member-password">重置密码（可选）</label>
            <FormInput
              id="member-password"
              v-model="memberNewPassword"
              :minlength="passwordMin"
              :maxlength="passwordMax"
              autocomplete="new-password"
              type="password"
              placeholder="留空表示不修改；填写则8–72 个字符"
            />
          </div>

          <section class="grant-section" aria-label="空间授权">
            <div class="grant-head">
              <div class="grant-head-text">
                <span class="grant-title">知识空间</span>
              </div>
              <span class="bk-grow"></span>
              <template v-if="!isAdminTarget">
                <button
                  class="bk-btn bk-btn-sm bk-btn-ghost"
                  type="button"
                  :disabled="memberSpacesLoading || memberBusy"
                  @click="selectAllSpaces"
                >
                  全选
                </button>
                <button
                  class="bk-btn bk-btn-sm bk-btn-warning"
                  type="button"
                  :disabled="memberSpacesLoading || memberBusy"
                  @click="clearSpaces"
                >
                  清空
                </button>
              </template>
            </div>

            <p
              v-if="isAdminTarget"
              class="bk-notice bk-notice-info notice-sm grant-notice"
            >
              管理员已默认全量授权。
            </p>
            <p
              v-if="memberSpacesLoading"
              class="bk-text-sm bk-muted grant-notice"
            >
              正在读取空间与授权…
            </p>
            <p
              v-else-if="memberSpacesError"
              class="bk-notice bk-notice-fail notice-sm grant-notice"
              role="alert"
            >
              空间授权读取失败：{{
                memberSpacesError
              }}本次保存只更新账号设置，不改动空间授权。
            </p>
            <p
              v-else-if="memberSpaceRows.length === 0"
              class="bk-text-sm bk-muted grant-notice"
            >
              没有可授权的知识空间。
            </p>
            <ul v-else class="grant-list">
              <li
                v-for="row in memberSpaceRows"
                :key="row.spaceId"
                class="grant-row"
              >
                <span class="grant-name" :title="row.name">{{ row.name }}</span>
                <span v-if="row.owned" class="bk-badge bk-badge-draft"
                  >所有者</span
                >
                <span class="bk-grow"></span>
                <span class="grant-toggle">
                  <FormSwitch
                    :model-value="row.canRead"
                    :disabled="row.owned || isAdminTarget || memberBusy"
                    :aria-label="`${row.name} 读取权限`"
                    @update:model-value="setSpaceRead(row.spaceId, $event)"
                  />
                  <span>可读取</span>
                </span>
                <span class="grant-toggle">
                  <FormSwitch
                    :model-value="row.canMaintain"
                    :disabled="
                      row.owned || isAdminTarget || !row.canRead || memberBusy
                    "
                    :aria-label="`${row.name} 维护权限`"
                    @update:model-value="setSpaceMaintain(row.spaceId, $event)"
                  />
                  <span>可维护</span>
                </span>
              </li>
            </ul>
          </section>
        </form>
        <template #actions>
          <button class="bk-btn" type="button" @click="closeMemberModal">
            取消
          </button>
          <button
            class="bk-btn bk-btn-primary"
            type="submit"
            form="member-edit-form"
            :disabled="memberBusy || (!isAdminTarget && memberSpacesLoading)"
          >
            {{ memberBusy ? '保存中…' : '保存' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
  </div>
</template>

<style scoped>
.settings {
  display: grid;
  gap: var(--space-4);
}

.global-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-4);
}

.global-summary > div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.summary-label {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.global-summary strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-tab {
  display: grid;
  gap: var(--space-4);
}

.notice-sm {
  font-size: var(--text-xs);
}

/* 编辑成员弹窗内的空间授权：多选列表，每行独立设置读取与维护 */
.grant-section {
  margin-top: var(--space-1);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-border);
}

.grant-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.grant-head-text {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.grant-title {
  font-size: var(--form-text-size);
  font-weight: var(--form-label-weight);
}

.grant-notice {
  margin: 0;
}

.grant-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
}

.grant-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--color-border);
}

.grant-row:last-child {
  border-bottom: 0;
}

.grant-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.grant-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: none;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  cursor: pointer;
}

.empty-cell {
  text-align: center;
  color: var(--color-text-muted);
  padding: var(--space-8) var(--space-4);
}

.perm-matrix td {
  font-size: var(--text-xs);
}

.perm-yes {
  color: var(--color-success);
  font-weight: 700;
}

.perm-no {
  color: var(--color-text-muted);
}

@media (max-width: 860px) {
  .member-actions {
    display: flex;
    gap: var(--space-2);
    flex-wrap: wrap;
  }

  .member-row {
    flex-wrap: wrap;
  }

  .global-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .global-summary {
    grid-template-columns: 1fr;
  }
}
</style>
