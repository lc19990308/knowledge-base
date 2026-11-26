<script setup lang="ts" generic="T extends string = string">
import { ChevronDown } from '@lucide/vue'
import { Motion } from 'motion-v'
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  useId,
  watch,
} from 'vue'
import {
  ENTER_TRANSITION,
  EXIT_TRANSITION,
  POPOVER_CLOSED,
  POPOVER_OPEN,
  useExitAnimation,
} from '../motion'

const props = defineProps<{
  id?: string
  modelValue?: T
  options: readonly { label: string; value: NoInfer<T>; detail?: string }[]
  placeholder?: string
  disabled?: boolean
  label?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()
const open = ref(false)
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const panelId = useId()
/** 面板向上展开时为 true：缩放原点换到底边，避免下拉「从头部长出来」。 */
const opensAbove = ref(false)
/** 收起时若由键盘操作触发，收尾后把焦点还给触发器。 */
let pendingFocusRestore = false
const selected = computed(() =>
  props.options.find((option) => option.value === props.modelValue),
)

// 收起先播动画，动画结束才真正隐藏并卸载面板；隐藏时浏览器原生弹层不能提前隐藏，否则动画看不到。
const { leaving, requestClose } = useExitAnimation(finishClose)

/** 动画收尾：退出顶层弹层、卸载节点、按需还焦点。 */
function finishClose(): void {
  panel.value?.hidePopover()
  open.value = false
  if (pendingFocusRestore) {
    pendingFocusRestore = false
    trigger.value?.focus({ preventScroll: true })
  }
}

function close(restoreFocus = false): void {
  if (!open.value) return
  pendingFocusRestore ||= restoreFocus
  requestClose()
}

function isOpen(): boolean {
  return open.value && !leaving.value
}

/**
 * 计算并写入面板位置、可用高度与展开方向。
 * 展开、页面滚动、窗口尺寸变化都复用它，保证面板始终贴着触发器，
 * 不会出现「触发器动了、面板留在原处」的脱节。
 */
function positionMenu(): void {
  const anchor = trigger.value
  const menu = panel.value
  if (!anchor || !menu) return
  const rect = anchor.getBoundingClientRect()
  const gap = 6
  const edge = 8
  const above = rect.top - gap - edge
  const below = globalThis.innerHeight - rect.bottom - gap - edge
  const useAbove = below < 260 && above > below
  const width = Math.min(
    Math.max(rect.width, 170),
    globalThis.innerWidth - edge * 2,
  )
  opensAbove.value = useAbove
  menu.style.width = `${String(width)}px`
  menu.style.left = `${String(Math.max(edge, Math.min(rect.left, globalThis.innerWidth - width - edge)))}px`
  menu.style.maxHeight = `${String(Math.min(260, Math.max(0, useAbove ? above : below)))}px`
  // 上下两侧只留一侧的定位，避免换方向时旧的 top/bottom 残留
  if (useAbove) {
    menu.style.bottom = `${String(globalThis.innerHeight - rect.top + gap)}px`
    menu.style.top = 'auto'
  } else {
    menu.style.top = `${String(rect.bottom + gap)}px`
    menu.style.bottom = 'auto'
  }
}

async function expand(fromEnd = false): Promise<void> {
  if (props.disabled || props.options.length === 0 || open.value) return
  open.value = true
  await nextTick()
  const menu = panel.value
  if (!menu || !isOpen()) return
  positionMenu()
  menu.popover = 'manual'
  menu.showPopover()
  const options = menu.querySelectorAll<HTMLButtonElement>('[role="option"]')
  const index = props.options.findIndex(
    (option) => option.value === props.modelValue,
  )
  focusOption(options[index < 0 ? (fromEnd ? options.length - 1 : 0) : index])
}

function focusOption(option: HTMLButtonElement | undefined): void {
  const menu = panel.value
  if (!menu || !option) return
  option.focus({ preventScroll: true })
  const optionRect = option.getBoundingClientRect()
  const menuRect = menu.getBoundingClientRect()
  if (optionRect.top < menuRect.top) {
    menu.scrollTop -= menuRect.top - optionRect.top
  } else if (optionRect.bottom > menuRect.bottom) {
    menu.scrollTop += optionRect.bottom - menuRect.bottom
  }
}

function toggle(): void {
  if (open.value) close(true)
  else void expand()
}

function select(value: T): void {
  close(true)
  emit('update:modelValue', value)
}

function handleOutside(event: PointerEvent): void {
  if (event.composedPath().includes(trigger.value as EventTarget)) return
  if (panel.value && event.composedPath().includes(panel.value)) return
  close()
}

/**
 * 页面滚动时让面板跟着触发器走，而不是直接收起：
 * 浏览器把触发器滚入视野、键盘聚焦、动画引起的滚动都不该把刚打开的面板关掉。
 * 只有触发器整个离开视口才收起，避免面板贴着看不见的锚点漂在屏幕边。
 */
function handleScroll(event: Event): void {
  // 面板自身滚动（内部滚动一条长列表）不参与定位
  if (event.target === panel.value || !isOpen()) return
  const rect = trigger.value?.getBoundingClientRect()
  if (!rect) return
  if (rect.bottom <= 0 || rect.top >= globalThis.innerHeight) {
    close(panel.value?.contains(globalThis.document.activeElement))
    return
  }
  positionMenu()
}

/** 窗口尺寸变化同样只是重新贴合；触发器消失交给方向键/外部点击处理。 */
function handleResize(): void {
  if (!isOpen()) return
  positionMenu()
}

function handleKeydown(event: KeyboardEvent): void {
  const { key } = event
  if (!open.value) {
    if (key === 'ArrowDown' || key === 'ArrowUp') {
      event.preventDefault()
      void expand(key === 'ArrowUp')
    }
    return
  }
  if (key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close(true)
    return
  }
  if (key === 'Tab') {
    close(true)
    return
  }
  if (
    key !== 'ArrowDown' &&
    key !== 'ArrowUp' &&
    key !== 'Home' &&
    key !== 'End'
  )
    return
  const options = Array.from(
    panel.value?.querySelectorAll<HTMLButtonElement>('[role="option"]') ?? [],
  )
  if (options.length === 0) return
  event.preventDefault()
  const current = options.findIndex((option) => option === event.target)
  const next =
    key === 'Home'
      ? 0
      : key === 'End'
        ? options.length - 1
        : Math.min(
            options.length - 1,
            Math.max(0, current + (key === 'ArrowDown' ? 1 : -1)),
          )
  focusOption(options[next])
}

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) close()
  },
)

onMounted(() => {
  globalThis.document.addEventListener('pointerdown', handleOutside)
  globalThis.addEventListener('scroll', handleScroll, true)
  globalThis.addEventListener('resize', handleResize)
})
onBeforeUnmount(() => {
  globalThis.document.removeEventListener('pointerdown', handleOutside)
  globalThis.removeEventListener('scroll', handleScroll, true)
  globalThis.removeEventListener('resize', handleResize)
})
</script>

<template>
  <div class="select-menu" @keydown="handleKeydown">
    <button
      :id="id"
      ref="trigger"
      class="bk-select-trigger"
      type="button"
      :disabled="disabled || options.length === 0"
      :aria-label="
        label
          ? `${label}：${selected?.label ?? placeholder ?? '请选择'}`
          : undefined
      "
      :aria-expanded="open && !leaving"
      :aria-controls="open ? panelId : undefined"
      :title="selected?.label ?? placeholder"
      aria-haspopup="listbox"
      @click="toggle"
    >
      <span
        class="select-trigger-text"
        :class="{ 'select-placeholder': !selected }"
      >
        {{ selected?.label ?? placeholder ?? '请选择' }}
      </span>
      <ChevronDown
        class="select-chevron"
        :class="{ 'is-open': open && !leaving }"
        aria-hidden="true"
      />
    </button>
    <!-- as-child：面板是真实 DOM，定位逻辑直接写它的 style，动画走 motion 的展开/收起 -->
    <Motion
      v-if="open"
      as-child
      :initial="POPOVER_CLOSED"
      :animate="leaving ? POPOVER_CLOSED : POPOVER_OPEN"
      :transition="leaving ? EXIT_TRANSITION : ENTER_TRANSITION"
    >
      <div
        :id="panelId"
        ref="panel"
        class="select-popover"
        :class="{ 'is-leaving': leaving, 'is-above': opensAbove }"
        role="listbox"
        :aria-label="label"
      >
        <button
          v-for="option in options"
          :key="option.value"
          class="select-option"
          :class="{ selected: option.value === modelValue }"
          type="button"
          role="option"
          tabindex="-1"
          :aria-selected="option.value === modelValue"
          :title="
            option.detail ? `${option.label} · ${option.detail}` : option.label
          "
          @click="select(option.value)"
        >
          <span class="select-check" aria-hidden="true">{{
            option.value === modelValue ? '✓' : ''
          }}</span>
          <span class="select-option-copy">
            <strong>{{ option.label }}</strong>
            <small v-if="option.detail">{{ option.detail }}</small>
          </span>
        </button>
      </div>
    </Motion>
  </div>
</template>

<style scoped>
.select-menu {
  position: relative;
  min-width: 0;
}

.bk-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  text-align: left;
  cursor: pointer;
}

/* 触发器宽度有限，长名称单行省略号收尾，避免换行把控件撑高、挤压同排的搜索框。 */
.select-trigger-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.select-placeholder {
  color: var(--color-text-muted);
}

.select-chevron {
  color: var(--color-text-muted);
  flex: none;
  width: var(--space-4);
  height: var(--space-4);
  transition: transform var(--motion-fast) var(--ease-standard);
}

.select-chevron.is-open {
  transform: rotate(180deg);
}

.select-popover {
  position: fixed;
  inset: auto;
  box-sizing: border-box;
  margin: 0;
  overscroll-behavior: contain;
  overflow-y: auto;
  padding: var(--form-menu-padding);
  background: var(--color-surface-raised, var(--color-surface));
  border: 1px solid var(--color-border);
  border-radius: var(--radius-panel);
  box-shadow: var(--elev-pop, var(--elev-raised));
  /* 缩放以贴着触发器的那条边为原点，视觉上是「从按钮下沿展开」 */
  transform-origin: center top;
}

.select-popover.is-above {
  transform-origin: center bottom;
}

/* 收起动画播放中：面板已经透明，再接受点击会误触选项。 */
.select-popover.is-leaving {
  pointer-events: none;
}

.select-option {
  display: flex;
  align-items: flex-start;
  gap: var(--form-menu-gap);
  width: 100%;
  padding: var(--form-padding-block);
  color: var(--color-text);
  font: inherit;
  font-size: var(--form-text-size);
  line-height: var(--form-line-height);
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: var(--radius-control);
  cursor: pointer;
}

.select-option:hover,
.select-option:focus-visible,
.select-option.selected {
  background: var(--color-surface-selected);
  color: var(--color-action);
}

.select-check {
  flex: 0 0 var(--space-4);
  color: var(--color-action);
  font-weight: 700;
}

.select-option-copy {
  display: grid;
  gap: var(--form-menu-detail-gap);
  min-width: 0;
  flex: 1 1 auto;
}

/* 选项弹层宽度与触发器同源，长名称同样单行省略，完整文字靠 title 悬浮查看。 */
.select-option-copy strong,
.select-option-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.select-option-copy strong {
  font-weight: var(--form-label-weight);
}

.select-option-copy small {
  color: var(--color-text-muted);
  font-size: var(--form-detail-size);
}
</style>
