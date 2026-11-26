<script setup lang="ts">
import { animate } from 'motion-v'
import {
  DIALOG_CLOSED,
  DRAWER_CLOSED,
  EXIT_SETTLE_MS,
  EXIT_TRANSITION,
  MASK_CLOSED,
  prefersReducedMotion,
} from '../motion'

/**
 * 浮层退场包装。
 *
 * 弹窗 / 抽屉的显隐由调用方用 `v-if` 控制（调用方自己的状态一置 false，节点当拍就被移除），
 * 组件内部没有任何机会播退场。把它包一层 `<Transition>` 后，节点被判定移除时会先回调
 * @leave，我们在这里播完退场再放行移除，因此：
 * - 用户点 X / 取消 / 点遮罩 / 按 Esc（组件 emit close → 调用方置 false）有退场；
 * - 保存成功等「程序化关闭」（调用方直接置 false）同样有退场。
 *
 * 只接管退场：进场仍由 ModalShell / DrawerPanel 内部的 motion 组件完成，避免两套动画打架。
 */
const props = withDefaults(
  defineProps<{
    /** dialog = 居中弹窗，drawer = 右侧抽屉；决定面板的选择器与滑出方向。 */
    variant?: 'dialog' | 'drawer'
  }>(),
  { variant: 'dialog' },
)

const PANEL_SELECTOR = {
  dialog: '.modal-card',
  drawer: '.drawer-panel',
} as const

const PANEL_CLOSED = {
  dialog: DIALOG_CLOSED,
  drawer: DRAWER_CLOSED,
} as const

/**
 * 退场：遮罩淡出 + 面板收起，播完后放行节点移除。
 * 收尾用定长定时器（EXIT_SETTLE_MS）而不是动画完成回调：时长是我们自己传的固定 tween，
 * 按时长收尾同样精确，还能兜住「减少动效」以及动画被打断的情况。
 */
function handleLeave(element: Element, done: () => void): void {
  const mask = element as HTMLElement
  // 退场期间不再接受点击，避免遮罩在被移除前又接到一次关闭请求
  mask.style.pointerEvents = 'none'
  const panel = mask.querySelector<HTMLElement>(PANEL_SELECTOR[props.variant])
  if (!prefersReducedMotion()) {
    animate(mask, MASK_CLOSED, EXIT_TRANSITION)
    if (panel) {
      animate(panel, PANEL_CLOSED[props.variant], EXIT_TRANSITION)
    }
  }
  globalThis.setTimeout(done, EXIT_SETTLE_MS)
}
</script>

<template>
  <Transition :css="false" @leave="handleLeave">
    <slot />
  </Transition>
</template>
