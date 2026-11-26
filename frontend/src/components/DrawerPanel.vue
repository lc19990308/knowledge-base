<script setup lang="ts">
import { Motion } from 'motion-v'
import { onBeforeUnmount, onMounted, useTemplateRef } from 'vue'
import {
  DRAWER_CLOSED,
  DRAWER_OPEN,
  ENTER_TRANSITION,
  MASK_CLOSED,
  MASK_OPEN,
} from '../motion'

/**
 * 公共右侧抽屉：遮罩 + 面板 + 标题/操作区。
 * 默认接管焦点、限制 Tab 循环、Esc 关闭，并在卸载时把焦点还给触发元素。
 */
const props = withDefaults(
  defineProps<{
    title: string
    width?: string
    /** 默认聚焦关闭按钮；破坏性操作可改为第一个底部操作。 */
    initialFocus?: 'close' | 'first-action'
  }>(),
  { width: '560px', initialFocus: 'close' },
)
const emit = defineEmits<{ close: [] }>()
const panel = useTemplateRef<HTMLElement>('panel')
const closeButton = useTemplateRef<HTMLButtonElement>('closeButton')
let previousFocus: HTMLElement | undefined

onMounted(() => {
  if (globalThis.document.activeElement instanceof HTMLElement) {
    previousFocus = globalThis.document.activeElement
  }
  const actionButton =
    props.initialFocus === 'first-action'
      ? panel.value?.querySelector<HTMLElement>(
          '.drawer-actions button:not(:disabled), .drawer-actions a[href]',
        )
      : null
  ;(actionButton ?? closeButton.value)?.focus()
})

onBeforeUnmount(() => {
  if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
})

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    emit('close')
    return
  }
  if (event.key !== 'Tab' || !panel.value) return
  const focusable = Array.from(
    panel.value.querySelectorAll<HTMLElement>(
      'a[href], button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]',
    ),
  ).filter((element) => element.getClientRects().length > 0)
  const first = focusable[0]
  const last = focusable.at(-1)
  if (event.shiftKey && globalThis.document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && globalThis.document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
</script>

<template>
  <!-- as-child：动画直接落在 div / section 上，DOM 结构与 ref 保持原样，事件也不用挂在 motion 组件上 -->
  <Motion
    as-child
    :initial="MASK_CLOSED"
    :animate="MASK_OPEN"
    :transition="ENTER_TRANSITION"
  >
    <div class="drawer-mask" role="presentation" @click="emit('close')">
      <Motion
        as-child
        :initial="DRAWER_CLOSED"
        :animate="DRAWER_OPEN"
        :transition="ENTER_TRANSITION"
      >
        <section
          ref="panel"
          class="drawer-panel"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          :style="{ '--drawer-width': width }"
          @click.stop
          @keydown="handleKeydown"
        >
          <header class="drawer-head">
            <div class="drawer-titles">
              <h3>{{ title }}</h3>
              <p v-if="$slots.hint" class="drawer-hint"><slot name="hint" /></p>
            </div>
            <button
              ref="closeButton"
              class="drawer-close"
              type="button"
              aria-label="关闭"
              @click="emit('close')"
            >
              ✕
            </button>
          </header>
          <div class="drawer-body">
            <slot />
          </div>
          <footer v-if="$slots.actions" class="drawer-actions">
            <slot name="actions" />
          </footer>
        </section>
      </Motion>
    </div>
  </Motion>
</template>

<style scoped>
.drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  justify-content: flex-end;
  background: var(--color-mask);
}

.drawer-panel {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  width: min(var(--drawer-width), calc(100vw - var(--space-4)));
  height: 100%;
  min-width: 0;
  background: var(--color-surface-raised);
  border-inline-start: 1px solid var(--color-border);
  box-shadow: var(--elev-pop, var(--elev-raised));
}

.drawer-head {
  display: flex;
  flex: 0 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-5) var(--space-5) var(--space-3);
  border-bottom: 1px solid var(--color-divider);
}

.drawer-titles {
  min-width: 0;
}

.drawer-titles h3 {
  font-size: var(--dialog-title-size);
  line-height: var(--dialog-title-line-height);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.drawer-hint {
  margin-top: 4px;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.drawer-close {
  flex: none;
  padding: 2px 6px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  cursor: pointer;
}

.drawer-close:hover {
  background: var(--color-divider);
  color: var(--color-text);
}

.drawer-body {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  padding: var(--space-4) var(--space-5);
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.drawer-actions {
  display: flex;
  flex: 0 0 auto;
  flex-flow: row wrap;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-5);
  border-top: 1px solid var(--color-divider);
  background: var(--color-surface-raised);
}

@media (max-width: 640px) {
  .drawer-panel {
    width: 100%;
    max-width: 100%;
    border-inline-start: 0;
  }
}
</style>
