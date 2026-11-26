/**
 * 打字机渲染：把模型增量按稳定节奏逐字显示，避免整段回答一次性出现。
 *
 * 速度取「基础节奏 + 按积压追赶」：模型输出慢时不慌不忙，一次性到达一大段
 * （例如本地替身模型或网关忽略流式）时也能在半秒到一秒内追平，不会长时间落后。
 * 关闭动效或页面切到后台时直接显示全部，避免无意义的动画开销。
 */
export interface Typewriter {
  /** 追加一段新文本；重复调用按到达顺序拼接。 */
  push(text: string): void
  /** 增量流结束：剩余文本继续显示完，追上后触发 onIdle。 */
  finish(): void
  /** 立即显示全部文本，用于页面隐藏、减少动效或放弃逐字展示的收尾。 */
  flush(): void
  /** 停止渲染并释放动画帧，不再触发任何回调。 */
  stop(): void
}

/** 基础速度：约每秒 60 个字符，接近中文阅读节奏。 */
const BASE_CHARS_PER_SECOND = 60

/** 积压在这个时长内追平：越小越"等模型"，越大越像整段跳出。 */
const CATCH_UP_WINDOW_MS = 800

/** 文本重绘间隔：逐字显示不必每帧都重新解析 Markdown。 */
const RENDER_INTERVAL_MS = 33

export function createTypewriter(
  render: (text: string) => void,
  onIdle?: () => void,
): Typewriter {
  let target = ''
  let revealed = 0
  let finished = false
  let stopped = false
  let idle = false
  let frame: number | undefined
  let lastFrameAt = 0
  let lastRenderAt = 0

  const reduceMotion =
    typeof globalThis.matchMedia === 'function' &&
    globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches

  /** 页面不可见时不画动画，省掉后台标签页的渲染开销。 */
  const hidden = (): boolean => globalThis.document.visibilityState === 'hidden'

  const draw = (force = false): void => {
    const now = globalThis.performance.now()
    if (!force && now - lastRenderAt < RENDER_INTERVAL_MS) return
    lastRenderAt = now
    render(target.slice(0, revealed))
  }

  const cancelFrame = (): void => {
    if (frame !== undefined) {
      globalThis.cancelAnimationFrame(frame)
      frame = undefined
    }
  }

  /** 已结束且全部显示完：补画一次最终文本，并通知调用方可以替换为正式回答。 */
  const settle = (): void => {
    if (idle || !finished || revealed < target.length) return
    idle = true
    draw(true)
    onIdle?.()
  }

  const schedule = (): void => {
    if (stopped || frame !== undefined) return
    frame = globalThis.requestAnimationFrame(tick)
  }

  function tick(now: number): void {
    frame = undefined
    if (stopped) return
    const elapsed =
      lastFrameAt === 0 ? 16 : Math.min(Math.max(now - lastFrameAt, 1), 100)
    lastFrameAt = now
    const backlog = target.length - revealed
    if (backlog > 0) {
      const rate = Math.max(
        BASE_CHARS_PER_SECOND,
        (backlog / CATCH_UP_WINDOW_MS) * 1000,
      )
      revealed = Math.min(
        target.length,
        revealed + Math.max(1, Math.round((rate * elapsed) / 1000)),
      )
      draw()
    }
    if (revealed < target.length) schedule()
    else settle()
  }

  return {
    push(text: string): void {
      if (stopped || !text) return
      target += text
      if (reduceMotion || hidden()) {
        revealed = target.length
        draw(true)
        settle()
        return
      }
      schedule()
    },

    finish(): void {
      if (stopped) return
      finished = true
      if (reduceMotion || hidden()) {
        revealed = target.length
        draw(true)
      }
      settle()
    },

    flush(): void {
      if (stopped) return
      revealed = target.length
      draw(true)
      settle()
    },

    stop(): void {
      stopped = true
      cancelFrame()
    },
  }
}
