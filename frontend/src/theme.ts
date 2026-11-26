import { readonly, ref } from 'vue'

/**
 * 主题模块：全站唯一的主题状态来源。
 *
 * - 偏好三态 `light | dark | system` 只存在于存储与切换器里；DOM 上永远只写
 *   `light` / `dark` 两个实际值（system 在启动时解析成具体值）。这样 CSS 侧
 *   只需要 `:root` 与 `[data-theme='dark']` 两块，不必再维护一份媒体查询里的同名取值。
 * - 首屏防闪烁靠 index.html 里的内联脚本（早于任何样式加载执行），
 *   那段逻辑必须与本文件保持一致，改这里就要同步改那里。
 */
export type ThemePreference = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'kb.theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'
/** 与 theme.css 的 --color-canvas 同值，样式表尚未生效时作为兜底。 */
const FALLBACK_THEME_COLORS: Record<ResolvedTheme, string> = {
  light: '#f6f7f9',
  dark: '#0e1116',
}

function isPreference(value: unknown): value is ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system'
}

function readStoredPreference(): ThemePreference {
  try {
    const stored = globalThis.localStorage.getItem(STORAGE_KEY)
    return isPreference(stored) ? stored : 'system'
  } catch {
    // 无痕模式下读存储会抛错：当作跟随系统，不阻断启动。
    return 'system'
  }
}

function systemTheme(): ResolvedTheme {
  return globalThis.matchMedia(DARK_QUERY).matches ? 'dark' : 'light'
}

function resolvePreference(preference: ThemePreference): ResolvedTheme {
  return preference === 'system' ? systemTheme() : preference
}

/** 首屏脚本已经写过 data-theme，这里读回来，保证 JS 状态与页面一致。 */
function readDomTheme(): ResolvedTheme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

const preferenceState = ref<ThemePreference>(readStoredPreference())
const resolvedState = ref<ResolvedTheme>(readDomTheme())

/** 用户偏好（三态）：切换器用它表达当前选中项。 */
export const themePreference = readonly(preferenceState)
/** 已生效的主题（两态）：引用它的 computed 会在切主题后重新求值。 */
export const resolvedTheme = readonly(resolvedState)

function applyResolved(resolved: ResolvedTheme): void {
  // 先改属性再改 ref：响应式回调里读 getComputedStyle 才能拿到新颜色。
  document.documentElement.dataset.theme = resolved
  resolvedState.value = resolved
  // 移动端地址栏与原生控件跟随主题，避免出现「半暗色」；取值直接跟随 token。
  const canvas =
    getComputedStyle(document.documentElement)
      .getPropertyValue('--color-canvas')
      .trim() || FALLBACK_THEME_COLORS[resolved]
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', canvas)
}

/** 用户显式选择主题：只有这条路径会写存储。 */
export function setThemePreference(preference: ThemePreference): void {
  preferenceState.value = preference
  try {
    globalThis.localStorage.setItem(STORAGE_KEY, preference)
  } catch {
    // 写不进去时本次切换仍然生效，只是刷新后回到跟随系统。
  }
  applyResolved(resolvePreference(preference))
}

/** 系统主题变化只在「跟随系统」时生效：用户显式选过就不能被系统覆盖。 */
function handleSystemChange(): void {
  if (preferenceState.value === 'system') {
    applyResolved(systemTheme())
  }
}

/** 多标签页同步：另一标签改了偏好时跟随；这里不回写存储，避免互相触发。 */
function handleStorageChange(event: StorageEvent): void {
  if (event.key !== STORAGE_KEY) return
  const next = isPreference(event.newValue) ? event.newValue : 'system'
  preferenceState.value = next
  applyResolved(resolvePreference(next))
}

/** 启动时对齐一次：内联脚本已经设过属性，这里补上 meta 与响应式状态。 */
applyResolved(resolvePreference(preferenceState.value))
globalThis.matchMedia(DARK_QUERY).addEventListener('change', handleSystemChange)
globalThis.addEventListener('storage', handleStorageChange)
