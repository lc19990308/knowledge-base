import type { MotionProps } from 'motion-v'
import type { Ref } from 'vue'
import { onBeforeUnmount, ref } from 'vue'

/**
 * 全局动效预设：把 motion-v 的时长与曲线收敛到 styles/theme.css 的
 * --motion-fast / --motion-base / --ease-standard，保证 JS 动画（弹窗、抽屉、下拉）
 * 和 CSS 过渡（按钮 hover 等）是同一套节奏，调速度时两边一起改，不会一处快一处慢。
 *
 * 时长在模块加载时按系统「减少动效」设置取一次值，与 CSS 侧变量一样不随会话中途切换系统设置而变。
 */

/** motion 的 transition 类型，直接从组件 props 反推，避免额外引 framer-motion 的类型入口。 */
export type MotionTransition = NonNullable<MotionProps['transition']>

/** 与 --ease-standard 完全相同的三次贝塞尔曲线。 */
const EASE_STANDARD: [number, number, number, number] = [0.2, 0, 0, 1]

/** 与 --motion-fast（140ms）对应；motion 的时长单位是秒。 */
const MOTION_FAST_SECOND = 0.14
/** 与 --motion-base（220ms）对应。 */
const MOTION_BASE_SECOND = 0.22

/** 是否开启了系统的「减少动效」。 */
export function prefersReducedMotion(): boolean {
  return globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/**
 * 按时长换算实际动画时长：系统开启「减少动效」时压到 0，动画立即结束，
 * 对应的收尾定时器也按 0 处理，不会出现「关不掉」的等待。
 */
function motionDuration(second: number): number {
  return prefersReducedMotion() ? 0 : second
}

/** 进场：慢一点，让用户看清弹层从哪儿出来。 */
export const ENTER_TRANSITION: MotionTransition = {
  duration: motionDuration(MOTION_BASE_SECOND),
  ease: EASE_STANDARD,
}

/** 退场：比进场快，关闭操作要「立刻有回应」才不会觉得拖沓。 */
export const EXIT_TRANSITION: MotionTransition = {
  duration: motionDuration(MOTION_FAST_SECOND),
  ease: EASE_STANDARD,
}

/** 退场时长（毫秒），供浮层包装按定长 tween 收尾使用。 */
export const EXIT_DURATION_MS = Math.round(
  motionDuration(MOTION_FAST_SECOND) * 1000,
)

/** 退场收尾缓冲：多留一点余量，避免节点在动画最后一帧就被移除，看起来像「闪没」。 */
const EXIT_SETTLE_BUFFER_MS = 40
export const EXIT_SETTLE_MS =
  EXIT_DURATION_MS === 0 ? 0 : EXIT_DURATION_MS + EXIT_SETTLE_BUFFER_MS

/** 遮罩只做透明度淡入淡出，不再叠加位移，避免和卡片缩放一起抖。 */
export const MASK_OPEN = { opacity: 1 }
export const MASK_CLOSED = { opacity: 0 }

/** 弹窗卡片：轻微下沉 + 缩放，控制在中后台可接受的克制范围内。 */
export const DIALOG_OPEN = { opacity: 1, y: 0, scale: 1 }
export const DIALOG_CLOSED = { opacity: 0, y: 8, scale: 0.985 }

/** 抽屉：从右侧滑入，位移控制在 40px，不做整屏横移。 */
export const DRAWER_OPEN = { opacity: 1, x: 0 }
export const DRAWER_CLOSED = { opacity: 0, x: 40 }

/** 下拉面板：以触发器那条边为原点纵向展开。 */
export const POPOVER_OPEN = { opacity: 1, scaleY: 1, y: 0 }
export const POPOVER_CLOSED = { opacity: 0, scaleY: 0.92, y: -4 }

export interface ExitAnimation {
  /** 是否正在播退场动画；模板用它切成关闭态并临时禁用指针事件。 */
  leaving: Ref<boolean>
  /** 请求关闭：先播退场动画，动画结束后才调用 notify 真正收尾。重复调用安全。 */
  requestClose: () => void
}

/**
 * 退场动画协调器：给「自己管理显隐、不会随父级卸载」的浮层用（当前只有下拉菜单）。
 *
 * 弹窗和抽屉的显隐由调用方 `v-if` 控制，走 OverlayTransition；下拉菜单的面板是组件内部
 * `v-if="open"`、同一个实例反复开合，没法借父级过渡，所以在这里把「请求关闭」和
 * 「真正收起」拆成两步：leaving = true 播动画 → 播完再 hidePopover + 卸载。
 */
export function useExitAnimation(
  notify: () => void,
  exitDurationMs: number = EXIT_DURATION_MS,
): ExitAnimation {
  const leaving = ref(false)
  let timer: ReturnType<typeof globalThis.setTimeout> | undefined
  let settled = false

  /** 收尾只做一次：清掉定时器后通知外部，再把内部状态复位，供下一次开合复用。 */
  function settle(): void {
    if (settled) return
    settled = true
    if (timer !== undefined) {
      globalThis.clearTimeout(timer)
      timer = undefined
    }
    notify()
    leaving.value = false
    settled = false
  }

  function requestClose(): void {
    if (leaving.value || settled) return
    leaving.value = true
    const delay =
      exitDurationMs === 0 ? 0 : exitDurationMs + EXIT_SETTLE_BUFFER_MS
    timer = globalThis.setTimeout(settle, delay)
  }

  onBeforeUnmount(() => {
    if (timer !== undefined) globalThis.clearTimeout(timer)
  })

  return { leaving, requestClose }
}
