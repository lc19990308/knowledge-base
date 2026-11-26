import { isApiError } from './http'

interface PollOptions<T> {
  signal?: AbortSignal | undefined
  delayMs: number
  timeoutMs: number
  attempts?: number
  waitBeforeFirst?: boolean
  isComplete: (value: T) => boolean
  onValue?: (value: T) => void
}

const MAX_POLL_DELAY_MS = 5000

function wait(delayMs: number, signal: AbortSignal): Promise<void> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const timer = globalThis.setTimeout(() => {
      signal.removeEventListener('abort', cancel)
      resolve()
    }, delayMs)
    function cancel(): void {
      globalThis.clearTimeout(timer)
      const reason: unknown = signal.reason
      reject(
        reason instanceof Error
          ? reason
          : new DOMException('轮询已取消', 'AbortError'),
      )
    }
    signal.addEventListener('abort', cancel, { once: true })
  })
}

export function canRetryWrite(failure: unknown): boolean {
  return (
    failure instanceof TypeError ||
    isApiError(failure, 500, 'INTERNAL_ERROR') ||
    isApiError(failure, 503, 'DEPENDENCY_UNAVAILABLE') ||
    isApiError(failure, 429, 'RATE_LIMITED') ||
    isApiError(failure, 409, 'IDEMPOTENCY_IN_PROGRESS')
  )
}

/** undefined 表示达到本次等待预算。调用方可持有业务 ID，稍后继续读取。 */
export async function pollUntil<T>(
  read: (signal: AbortSignal) => Promise<T>,
  options: PollOptions<T>,
): Promise<T | undefined> {
  const controller = new AbortController()
  const signal = controller.signal
  const timeout = new DOMException('轮询等待超时', 'TimeoutError')
  const cancel = () => {
    controller.abort(options.signal?.reason)
  }
  options.signal?.throwIfAborted()
  options.signal?.addEventListener('abort', cancel, { once: true })
  const deadline = globalThis.setTimeout(() => {
    controller.abort(timeout)
  }, options.timeoutMs)
  let delay = options.delayMs
  try {
    if (options.waitBeforeFirst) await wait(delay, signal)
    for (let index = 0; index < (options.attempts ?? Infinity); index++) {
      signal.throwIfAborted()
      let retryDelay = delay
      try {
        const value = await read(signal)
        signal.throwIfAborted()
        options.onValue?.(value)
        if (options.isComplete(value)) return value
      } catch (failure) {
        signal.throwIfAborted()
        if (
          isApiError(failure, 429, 'RATE_LIMITED') ||
          isApiError(failure, 503, 'DEPENDENCY_UNAVAILABLE')
        ) {
          retryDelay = Math.max(delay, failure.retryAfterMs ?? 0)
        } else if (!(failure instanceof TypeError)) {
          throw failure
        }
      }
      if (index + 1 < (options.attempts ?? Infinity))
        await wait(retryDelay, signal)
      delay = Math.min(MAX_POLL_DELAY_MS, Math.ceil(delay * 1.5))
    }
  } catch (failure) {
    if (failure !== timeout) throw failure
  } finally {
    globalThis.clearTimeout(deadline)
    options.signal?.removeEventListener('abort', cancel)
  }
  return undefined
}
