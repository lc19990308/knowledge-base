import { openEventStream } from './http'

/**
 * 一条回答增量流的回调约定：done / error / timeout 三者最多触发一次。
 * 服务端在 done 之后不再发增量，客户端要再读一次问题详情拿段落与引用。
 */
export interface AnswerStreamHandlers {
  onDelta: (text: string) => void
  onDone: (status: string, failureCode: string | null) => void
  onFailure: (code: string) => void
  onTimeout: () => void
}

interface Frame {
  event: string
  data: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 事件载荷按 JSON 解析；解析失败当作空对象，不让一个坏帧打断整条流。 */
function payloadOf(data: string): Record<string, unknown> {
  try {
    const parsed: unknown = JSON.parse(data) as unknown
    return isRecord(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

/**
 * 把一段 SSE 文本解析成一帧：空行分隔帧，data 多行按规范用换行拼接，注释行忽略。
 * 没有 data 的块（例如心跳注释）不产生事件。
 */
function frameOf(block: string): Frame | undefined {
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return data.length > 0 ? { event, data: data.join('\n') } : undefined
}

/**
 * 订阅一条问题的回答增量（SSE）。
 *
 * 正常结束回调 onDone；服务端报错或连接中途断开回调 onFailure，等待过久回调 onTimeout。
 * 这三种情况之后都由调用方回退轮询，因此这里不抛业务异常。
 */
export async function streamAnswer(
  questionId: string,
  handlers: AnswerStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await openEventStream(
    `/api/v1/questions/${encodeURIComponent(questionId)}/stream`,
    { signal },
  )
  const reader = response.body?.getReader()
  if (!reader) throw new Error('当前浏览器不支持流式读取')

  const decoder = new TextDecoder()
  let buffered = ''
  // 用对象包一层：闭包里改字段，读的地方不会被 TypeScript 当成常量 false。
  const state = { settled: false }

  const dispatch = (frame: Frame): void => {
    if (state.settled) return
    const payload = payloadOf(frame.data)
    switch (frame.event) {
      case 'delta':
        if (typeof payload.text === 'string') handlers.onDelta(payload.text)
        return
      case 'done':
        state.settled = true
        handlers.onDone(
          typeof payload.status === 'string' ? payload.status : 'FAILED',
          typeof payload.failureCode === 'string' ? payload.failureCode : null,
        )
        return
      case 'error':
        state.settled = true
        handlers.onFailure(
          typeof payload.code === 'string' ? payload.code : 'INTERNAL_ERROR',
        )
        return
      case 'timeout':
        state.settled = true
        handlers.onTimeout()
        return
      default:
      // 未知事件类型（服务端扩展）直接忽略。
    }
  }

  /** 追加一段字节解码结果；按空行切帧，跨块的半帧留在缓冲里。 */
  const consume = (chunk: string, flush: boolean): void => {
    buffered += chunk.replace(/\r\n?/g, '\n')
    let boundary = buffered.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffered.slice(0, boundary)
      buffered = buffered.slice(boundary + 2)
      const frame = frameOf(block)
      if (frame) dispatch(frame)
      boundary = buffered.indexOf('\n\n')
    }
    if (flush && buffered.trim()) {
      const frame = frameOf(buffered)
      buffered = ''
      if (frame) dispatch(frame)
    }
  }

  try {
    while (!state.settled) {
      const { done, value } = await reader.read()
      if (done) break
      consume(decoder.decode(value, { stream: true }), false)
    }
    if (!state.settled) consume(decoder.decode(), true)
  } finally {
    void reader.cancel().catch(() => undefined)
  }
  if (!state.settled) handlers.onFailure('STREAM_CLOSED')
}
