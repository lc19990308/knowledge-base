<script setup lang="ts">
import { FORM_LIMITS, NON_BLANK_PATTERN } from '../formValidation'
import ModalShell from '../components/ModalShell.vue'
import OverlayTransition from '../components/OverlayTransition.vue'
import FormInput from '../components/FormInput.vue'
import { computed, nextTick, onMounted, ref, type Component } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  LogOut,
  Monitor,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  Sun,
} from '@lucide/vue'
import { navigation, platformTitle } from '../navigation'
import { ensureSession, isAdmin, logout, session } from '../auth'
import {
  setThemePreference,
  themePreference,
  type ThemePreference,
} from '../theme'

const route = useRoute()
const router = useRouter()
const quickSearch = ref('')
const signingOut = ref(false)
const confirmOut = ref(false)

/** 侧栏收起状态的存储键；与 theme.ts 的 kb.theme 同前缀，便于整体清理。 */
const SIDEBAR_STORAGE_KEY = 'kb.layout.sidebar-collapsed'

/** 读回上次的侧栏状态：存储不可用（无痕模式）时按展开处理，不阻断渲染。 */
function readSidebarCollapsed(): boolean {
  try {
    return globalThis.localStorage.getItem(SIDEBAR_STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

const sidebarCollapsed = ref(readSidebarCollapsed())
/**
 * 首帧不播动画：进程启动时若上次是收起态，宽度会从 236px 过渡到 64px，
 * 看起来像页面自己抽了一下。挂载并完成一次绘制后再放开过渡。
 */
const shellReady = ref(false)

/** 侧栏开合：写状态并持久化，刷新后保持用户选择。 */
function toggleSidebar(): void {
  sidebarCollapsed.value = !sidebarCollapsed.value
  try {
    globalThis.localStorage.setItem(
      SIDEBAR_STORAGE_KEY,
      sidebarCollapsed.value ? '1' : '0',
    )
  } catch {
    // 存储不可用只影响记忆偏好，本次开合照常生效。
  }
}

const currentTitle = computed(() =>
  typeof route.meta.title === 'string' ? route.meta.title : '工作台',
)
const showSearch = computed(() => route.name !== 'document')
const user = computed(() => session().user)
const initial = computed(() => {
  const name = user.value?.displayName ?? ''
  return name.trim().slice(0, 1) || '问'
})
const roleLabel = computed(() =>
  user.value?.roles.includes('SYSTEM_ADMIN') ? '系统管理员' : '普通用户',
)

const navGroups = computed(() =>
  navigation.filter((group) => group.label !== '管理' || isAdmin()),
)

/** 主题三态：跟随系统是默认值，显式选择才会写进存储。 */
const THEME_OPTIONS: {
  value: ThemePreference
  title: string
  icon: Component
}[] = [
  { value: 'system', title: '跟随系统', icon: Monitor },
  { value: 'light', title: '浅色主题', icon: Sun },
  { value: 'dark', title: '深色主题', icon: Moon },
]

/** 顶栏快捷输入不再落在工作台：整段交给 AI 助手预填，由用户确认后发送。 */
function submitSearch(): void {
  const term = quickSearch.value.trim()
  if (!term || term.length > FORM_LIMITS.search) return
  void router.push({ path: '/assistant', query: { q: term }, force: true })
  // 内容已经交给助手输入框，顶栏不再保留，避免下次输入拼接上一次的提问。
  quickSearch.value = ''
}

function askSignOut(): void {
  confirmOut.value = true
}

async function signOut(): Promise<void> {
  if (signingOut.value) return
  signingOut.value = true
  await logout()
  signingOut.value = false
  confirmOut.value = false
  await router.replace('/login')
}

onMounted(() => {
  // 路由守卫已经拉过会话；这里兜底确保壳层拿到用户（幂等，不重复请求）。
  void ensureSession()
  // 等挂载后的第一帧画完再开启宽度过渡，避免还原收起态时闪一下。
  void nextTick(() => {
    globalThis.requestAnimationFrame(() => {
      shellReady.value = true
    })
  })
})
</script>

<template>
  <div class="app-shell" :class="{ 'is-ready': shellReady }">
    <a class="skip-link" href="#main-content">跳转到主内容</a>

    <aside
      id="app-sidebar"
      class="sidebar"
      :class="{ 'is-collapsed': sidebarCollapsed }"
      aria-label="平台侧栏"
    >
      <RouterLink class="brand" to="/">
        <span class="brand-mark" aria-hidden="true">问</span>
        <span class="brand-name">{{ platformTitle }}</span>
      </RouterLink>

      <template v-for="group in navGroups" :key="group.label">
        <p class="nav-group-label">{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          class="nav-link"
          :to="item.path"
          :title="sidebarCollapsed ? item.label : undefined"
        >
          <component :is="item.icon" :size="16" aria-hidden="true" />
          <span class="nav-label">{{ item.label }}</span>
        </RouterLink>
      </template>

      <div class="sidebar-foot">
        <div class="user-chip">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <span class="user-meta">
            <span class="name">{{ user?.displayName ?? '未登录' }}</span>
            <span class="role">{{ roleLabel }}</span>
          </span>
          <button
            v-if="user"
            class="sign-out"
            type="button"
            title="退出登录"
            :aria-label="`退出登录（当前：${user.displayName}）`"
            :disabled="signingOut"
            :inert="sidebarCollapsed ? true : undefined"
            @click="askSignOut"
          >
            <LogOut :size="14" aria-hidden="true" />
          </button>
        </div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar" aria-label="当前位置">
        <button
          class="sidebar-toggle"
          type="button"
          :aria-expanded="!sidebarCollapsed"
          aria-controls="app-sidebar"
          :aria-label="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
          :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
          @click="toggleSidebar"
        >
          <PanelLeftOpen
            v-if="sidebarCollapsed"
            :size="17"
            aria-hidden="true"
          />
          <PanelLeftClose v-else :size="17" aria-hidden="true" />
        </button>
        <h1>{{ currentTitle }}</h1>
        <form
          v-if="showSearch"
          class="topbar-search"
          role="search"
          @submit.prevent="submitSearch"
        >
          <FormInput
            v-model="quickSearch"
            :maxlength="FORM_LIMITS.search"
            :pattern="NON_BLANK_PATTERN"
            required
            class="topbar-search-field"
            variant="embedded"
            type="search"
            aria-label="快捷提问"
            placeholder="提问或查找文档内容"
          />
          <button
            type="submit"
            title="前往 AI 助手"
            aria-label="前往 AI 助手"
            :disabled="quickSearch.trim() === ''"
          >
            <Search :size="17" aria-hidden="true" />
          </button>
        </form>

        <div class="theme-switch" role="group" aria-label="界面主题">
          <button
            v-for="option in THEME_OPTIONS"
            :key="option.value"
            type="button"
            class="theme-option"
            :class="{ 'is-active': themePreference === option.value }"
            :title="option.title"
            :aria-pressed="themePreference === option.value"
            @click="setThemePreference(option.value)"
          >
            <component :is="option.icon" :size="15" aria-hidden="true" />
            <span class="visually-hidden">{{ option.title }}</span>
          </button>
        </div>

        <div
          v-if="user"
          class="topbar-user"
          :class="{ 'is-shown': sidebarCollapsed }"
        >
          <span class="topbar-user-name">{{ user.displayName }}</span>
          <button
            class="sign-out"
            type="button"
            title="退出登录"
            :aria-label="`退出登录（当前：${user.displayName}）`"
            :disabled="signingOut"
            @click="askSignOut"
          >
            <LogOut :size="14" aria-hidden="true" />
          </button>
        </div>
      </header>

      <main id="main-content" class="content" tabindex="-1">
        <RouterView />
      </main>
    </div>

    <OverlayTransition>
      <ModalShell
        v-if="confirmOut"
        title="退出登录"
        width="400px"
        manage-focus
        @close="confirmOut = false"
      >
        <p class="modal-text">
          确定要退出当前账号「{{ user?.displayName }}」吗？退出后需要重新登录。
        </p>
        <template #actions>
          <button class="bk-btn" type="button" @click="confirmOut = false">
            取消
          </button>
          <button
            class="bk-btn bk-btn-danger"
            type="button"
            :disabled="signingOut"
            @click="signOut"
          >
            {{ signingOut ? '退出中…' : '退出登录' }}
          </button>
        </template>
      </ModalShell>
    </OverlayTransition>
  </div>
</template>

<style scoped>
.app-shell {
  display: grid;
  /* 第一列跟着侧栏自身宽度走：开合只过渡侧栏的 width，主区随列宽平滑让位，
     不必对 grid-template-columns 做插值（各浏览器支持不一致）。 */
  grid-template-columns: auto minmax(0, 1fr);
  min-height: 100dvh;
}

.skip-link {
  position: fixed;
  z-index: 30;
  inset: var(--space-2) auto auto var(--space-2);
  padding: var(--space-2) var(--space-4);
  background: var(--color-surface);
}

.skip-link:not(:focus) {
  clip-path: inset(50%);
}

.sidebar {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  width: var(--sidebar-width);
  height: 100dvh;
  overflow-y: auto;
  /* 收起过程中超出宽度的文字被裁掉，而不是挤出行内滚动条。 */
  overflow-x: hidden;
  padding: var(--space-5) var(--space-3);
  border-inline-end: 1px solid var(--color-border);
  background: var(--color-surface);
  /* 侧栏与主区之间一道极淡的投影，替代通栏边框的“硬切” */
  box-shadow: 1px 0 0 color-mix(in oklab, var(--color-text) 3%, transparent);
  /* 开合用 --motion-base（220ms）：比 hover 的 140ms 慢一档，宽度大幅变化不显急。 */
  transition: width var(--motion-base) var(--ease-standard);
}

/* 侧栏收起态：只把宽度压到 64px，文字靠淡出 + 变窄裁切退场，图标位置全程不动。 */
.sidebar.is-collapsed {
  width: var(--sidebar-width-collapsed);
}

.sidebar.is-collapsed .brand-name,
.sidebar.is-collapsed .nav-label,
.sidebar.is-collapsed .nav-group-label,
.sidebar.is-collapsed .user-meta,
.sidebar.is-collapsed .sidebar-foot .sign-out {
  opacity: 0;
}

/* 收起后账号信息只剩头像：退出入口交给顶栏（见 .topbar-user.is-shown）。 */
.sidebar.is-collapsed .sign-out {
  pointer-events: none;
}

/* 收起态品牌块与账号区向内让 4px，把 32px 的图标 / 头像对到侧栏中线上。 */
.sidebar.is-collapsed .brand,
.sidebar.is-collapsed .user-chip {
  padding-inline: 4px;
}

/* 收起后分组标题不可读，换一条极淡分隔线继续表达分组。 */
.sidebar.is-collapsed .nav-group-label::after {
  content: '';
  position: absolute;
  inset: auto 4px 4px;
  height: 1px;
  background: var(--color-divider);
}

/* 首帧还原收起态时不播过渡，避免页面刚出现就自己抽一下。 */
.app-shell:not(.is-ready) .sidebar,
.app-shell:not(.is-ready) .brand,
.app-shell:not(.is-ready) .user-chip,
.app-shell:not(.is-ready) .brand-name,
.app-shell:not(.is-ready) .nav-label,
.app-shell:not(.is-ready) .nav-group-label,
.app-shell:not(.is-ready) .user-meta,
.app-shell:not(.is-ready) .sign-out {
  transition: none;
}

/* 侧栏开合按钮：与 AI 助手会话栏按钮同一尺寸和 hover，放在标题左侧。 */
.sidebar-toggle {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--color-text-secondary);
  font: inherit;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  cursor: pointer;
  transition:
    background var(--motion-fast) var(--ease-standard),
    color var(--motion-fast) var(--ease-standard);
}

.sidebar-toggle:hover {
  color: var(--color-text);
  background: var(--color-divider);
}

.sidebar-toggle:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  padding: var(--space-2) var(--space-2) var(--space-5);
  text-decoration: none;
  transition: padding-inline var(--motion-base) var(--ease-standard);
}

/* 文字类元素统一用 140ms 淡出：宽度收完前先把字「化掉」，不会留下半步截断的字。 */
.brand-name,
.nav-label,
.nav-group-label,
.user-meta,
.sign-out {
  transition: opacity var(--motion-fast) var(--ease-standard);
}

.brand-name {
  white-space: nowrap;
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  flex: none;
  background: var(--action-gradient, var(--color-action));
  color: var(--color-action-text);
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 15px;
  box-shadow:
    0 2px 6px color-mix(in oklab, var(--color-action) 28%, transparent),
    inset 0 1px 0 rgb(255 255 255 / 18%);
}

.brand-name {
  font-weight: 700;
  font-size: var(--text-base);
  letter-spacing: var(--tracking-display);
  color: var(--color-text);
}

.nav-group-label {
  position: relative;
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  white-space: nowrap;
  padding: var(--space-4) var(--space-2) var(--space-2);
}

.nav-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  padding: 9px var(--space-3);
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  font-weight: 500;
  text-decoration: none;
  transition:
    background var(--motion-fast) var(--ease-standard),
    color var(--motion-fast) var(--ease-standard);
}

.nav-label {
  white-space: nowrap;
}

/* 收起时文字仍占原始宽度（靠侧栏变窄裁切），所以图标必须禁止被 flex 压缩，
   否则 DB 里量到的 svg 宽度会被挤成 0，收起态只剩一个空导航条。 */
.nav-link svg {
  flex: none;
}

.nav-link:hover {
  background: var(--color-divider);
  color: var(--color-text);
}

/* 当前项左侧 3px 主色竖条：比整块底色更安静，层级一眼可辨。 */
.nav-link[aria-current='page']::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 3px;
  height: 18px;
  border-radius: 2px;
  background: var(--color-action);
  transform: translateY(-50%);
}

.nav-link[aria-current='page'] {
  background: var(--color-surface-selected);
  color: var(--color-action);
  font-weight: 600;
}

.sidebar-foot {
  margin-top: auto;
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-divider);
}

.modal-text {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.sign-out {
  flex: none;
  margin-left: auto;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
}

.sign-out:hover {
  background: var(--color-surface-selected);
  color: var(--color-action);
}

.sign-out[disabled] {
  opacity: 0.5;
  cursor: default;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  padding: var(--space-2);
  border-radius: var(--radius-sm);
  transition: padding-inline var(--motion-base) var(--ease-standard);
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  flex: none;
  background: var(--color-surface-selected);
  color: var(--color-action);
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  box-shadow: inset 0 0 0 1px
    color-mix(in oklab, var(--color-action) 22%, transparent);
}

.user-meta {
  display: grid;
  min-width: 0;
}

.user-meta .name {
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--color-text);
  white-space: nowrap;
}

.user-meta .role {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  white-space: nowrap;
}

.main {
  min-width: 0;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-6);
  border-bottom: 1px solid var(--color-border);
  background: color-mix(in srgb, var(--color-surface) 88%, transparent);
  backdrop-filter: blur(10px);
  /* 顶栏自带毛玻璃，本身就住在独立图层的渲染路径上。侧栏开合时主区每帧变宽，
     不给这层提示的话 Chrome 会跟着逐帧重建背景光栅，实测把 220ms 的过渡拖成掉帧；
     提层后光栅复用，宽度动画稳定在 16ms 一帧。 */
  will-change: transform;
  /* 毛玻璃下的细投影，让顶栏与内容有一层可感知的分界 */
  box-shadow: 0 1px 0 color-mix(in oklab, var(--color-text) 4%, transparent);
}

.topbar h1 {
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: var(--tracking-display);
}

.topbar-search {
  margin-inline-start: auto;
  display: flex;
  align-items: stretch;
  width: min(100%, 320px);
  min-width: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.topbar-search:focus-within {
  border-color: var(--color-action);
  box-shadow: var(--shadow-focus);
}

.topbar-search-field {
  flex: 1;
}

.topbar-search button {
  display: grid;
  place-items: center;
  flex: none;
  min-inline-size: 40px;
  min-block-size: 40px;
  border: 0;
  border-inline-start: 1px solid var(--color-border);
  border-start-end-radius: var(--radius-md);
  border-end-end-radius: var(--radius-md);
  background: var(--color-canvas);
  color: var(--color-action);
  cursor: pointer;
}

.topbar-search button:disabled {
  color: var(--color-text-muted);
  cursor: default;
}

.topbar-search button:hover:not(:disabled) {
  background: var(--color-surface-selected);
}

/* 主题切换器：图标三态，选中项用表层面板 + 1px 环表达，不抢主操作的强调。 */
.theme-switch {
  display: flex;
  flex: none;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-canvas);
}

.theme-option {
  display: grid;
  place-items: center;
  width: 30px;
  height: 26px;
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.theme-option:hover {
  color: var(--color-text);
}

.theme-option {
  transition:
    background var(--motion-fast) var(--ease-standard),
    color var(--motion-fast) var(--ease-standard),
    box-shadow var(--motion-fast) var(--ease-standard);
}

.theme-option.is-active {
  background: var(--color-surface);
  color: var(--color-action);
  box-shadow: var(--elev-ring);
}

/* 窄屏隐藏侧栏底部账号区，这里保留可见的身份与退出入口。 */
.topbar-user {
  display: none;
  align-items: center;
  gap: var(--space-2);
  flex: none;
}

/* 桌面端侧栏收起后账号区也收成头像，退出登录改由顶栏承接，功能不丢。 */
@media (min-width: 861px) {
  .topbar-user.is-shown {
    display: flex;
    margin-inline-start: var(--space-2);
  }
}

.topbar-user-name {
  max-width: 8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

/* 用户决定：内容铺满窗口，两侧只留 20px，不再限宽居中（覆盖原型的 1280px 上限）。 */
.content {
  padding: var(--space-6) 20px;
}

@media (max-width: 860px) {
  .app-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  /* 窄屏侧栏是一条横排导航，没有「收起」概念：忽略收起态，回到自适应宽度。 */
  .sidebar,
  .sidebar.is-collapsed {
    width: auto;
    position: static;
    height: auto;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-3);
    border-inline-end: 0;
    border-bottom: 1px solid var(--color-border);
    transition: none;
  }

  .sidebar.is-collapsed .brand,
  .sidebar.is-collapsed .user-chip {
    padding-inline: var(--space-2);
  }

  .sidebar.is-collapsed .brand-name,
  .sidebar.is-collapsed .nav-label,
  .sidebar.is-collapsed .user-meta {
    opacity: 1;
  }

  .sidebar.is-collapsed .nav-group-label::after {
    content: none;
  }

  .sidebar-toggle {
    display: none;
  }

  .brand {
    padding: var(--space-2);
  }

  .nav-group-label,
  .sidebar-foot {
    display: none;
  }

  .topbar {
    position: static;
    flex-wrap: wrap;
    padding: var(--space-3) var(--space-4);
  }

  .topbar-search {
    order: 2;
    flex: 1;
    min-width: 0;
    margin-left: 0;
  }

  .topbar-user {
    display: flex;
    order: 1;
    margin-inline-start: auto;
  }

  .sign-out {
    border: 1px solid var(--color-border);
    background: var(--color-surface);
  }

  .content {
    padding: var(--space-4);
  }
}

/* 系统开启「减少动效」时直接切到目标宽度，不做过渡。 */
@media (prefers-reduced-motion: reduce) {
  .sidebar,
  .brand,
  .user-chip,
  .brand-name,
  .nav-label,
  .nav-group-label,
  .user-meta,
  .sign-out,
  .sidebar-toggle {
    transition: none;
  }
}
</style>
