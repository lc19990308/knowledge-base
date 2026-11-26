<script setup lang="ts">
import { Motion } from 'motion-v'
import { onBeforeUnmount, onMounted, useTemplateRef } from 'vue'
import {
  DIALOG_CLOSED,
  DIALOG_OPEN,
  ENTER_TRANSITION,
  MASK_CLOSED,
  MASK_OPEN,
} from '../motion'

const props = withDefaults(
  defineProps<{
    title: string
    width?: string
    manageFocus?: boolean
    /** 默认聚焦关闭按钮；危险确认可改为第一个底部操作（通常是「取消」）。 */
    initialFocus?: 'close' | 'first-action'
  }>(),
  { manageFocus: true, initialFocus: 'close', width: '' },
)
const emit = defineEmits<{ close: [] }>()
const panel = useTemplateRef<HTMLElement>('panel')
const closeButton = useTemplateRef<HTMLButtonElement>('closeButton')
let previousFocus: HTMLElement | undefined

onMounted(() => {
  if (!props.manageFocus) return
  if (globalThis.document.activeElement instanceof HTMLElement) {
    previousFocus = globalThis.document.activeElement
  }
  const actionButton =
    props.initialFocus === 'first-action'
      ? panel.value?.querySelector<HTMLElement>(
          '.modal-actions button:not(:disabled), .modal-actions a[href]',
        )
      : null
  ;(actionButton ?? closeButton.value)?.focus()
})

onBeforeUnmount(() => {
  if (previousFocus?.isConnected) previousFocus.focus({ preventScroll: true })
})

function handleKeydown(event: KeyboardEvent): void {
  if (!props.manageFocus) return
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
    <div class="modal-mask" role="presentation" @click="emit('close')">
      <Motion
        as-child
        :initial="DIALOG_CLOSED"
        :animate="DIALOG_OPEN"
        :transition="ENTER_TRANSITION"
      >
        <section
          ref="panel"
          class="modal-card"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          :style="{ width }"
          @click.stop
          @keydown="handleKeydown"
        >
          <div class="modal-head">
            <h3>{{ title }}</h3>
            <button
              ref="closeButton"
              class="modal-close"
              type="button"
              aria-label="关闭"
              @click="emit('close')"
            >
              ✕
            </button>
          </div>
          <div class="modal-body">
            <div class="modal-body-scroll"><slot /></div>
          </div>
          <div v-if="$slots.actions" class="modal-actions">
            <slot name="actions" />
          </div>
        </section>
      </Motion>
    </div>
  </Motion>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: var(--color-mask);
  display: grid;
  place-items: center;
  padding: var(--space-4);
}

.modal-card {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  min-width: 0;
  max-width: calc(100vw - 2 * var(--space-4));
  max-height: calc(100vh - 2 * var(--space-4));
  overflow: hidden;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--elev-pop, var(--elev-raised));
  padding: var(--space-5);
}

.modal-head {
  position: relative;
  flex: 0 0 auto;
  padding-inline-end: var(--space-8);
  margin-bottom: var(--space-4);
}

.modal-head h3 {
  font-size: var(--dialog-title-size);
  line-height: var(--dialog-title-line-height);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.modal-close {
  position: absolute;
  inset-block-start: 0;
  inset-inline-end: 0;
  border: none;
  background: none;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: var(--text-sm);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
}

.modal-close:hover {
  background: var(--color-divider);
  color: var(--color-text);
}

.modal-body {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  max-width: 100%;
  overflow: hidden;
}

.modal-body-scroll {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  max-width: 100%;
  padding-inline-end: var(--space-3);
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.modal-body-scroll > :slotted(*) {
  min-width: 0;
  max-width: 100%;
}

.modal-actions {
  display: flex;
  flex: 0 0 auto;
  flex-flow: row wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-divider);
  background: var(--color-surface-raised);
}

.modal-actions > :slotted(.bk-btn) {
  height: var(--button-small-height);
  padding: 0 var(--button-small-padding-inline);
  font-size: var(--button-small-text-size);
}
</style>
