export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId: string | undefined
  readonly retryAfterMs: number | undefined

  constructor(
    status: number,
    code: string,
    detail?: string,
    requestId?: string,
    retryAfterMs?: number,
  ) {
    super(detail ?? code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.requestId = requestId
    this.retryAfterMs = retryAfterMs
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  formData?: FormData
  idempotencyKey?: string
  ifMatch?: string
  signal?: AbortSignal | undefined
  onUploadProgress?: ((percent: number) => void) | undefined
  /** 路由守卫探测会话时自行决定重定向目标。 */
  redirectOnUnauthorized?: false
}

export function isApiError<S extends number, C extends string>(
  error: unknown,
  status: S,
  ...codes: C[]
): error is ApiError & { status: S; code: C } {
  return (
    error instanceof ApiError &&
    error.status === status &&
    codes.some((code) => code === error.code)
  )
}

export function needsVersionRefresh(error: unknown): error is ApiError {
  return (
    isApiError(error, 412, 'ETAG_MISMATCH') ||
    isApiError(error, 428, 'IF_MATCH_REQUIRED')
  )
}

let csrfToken: string | undefined
let csrfGeneration = 0
let redirecting401 = false

/** 401 统一处理：跳登录页并带上当前地址；登录页自身的探测不重复跳转。 */
function handleUnauthorized(): void {
  clearCsrfToken()
  if (redirecting401) return
  if (window.location.pathname === '/login') return
  redirecting401 = true
  const current = window.location.pathname + window.location.search
  window.location.assign('/login?redirect=' + encodeURIComponent(current))
}

/** 会话重建（登出/过期）后调用：旧令牌随旧会话失效，必须重新获取。 */
export function clearCsrfToken(): void {
  csrfToken = undefined
  csrfGeneration++
}

async function fetchCsrfToken(signal?: AbortSignal): Promise<string> {
  const generation = csrfGeneration
  const { data: payload, requestId } = await requestWithMeta<unknown>(
    '/api/v1/auth/csrf',
    {
      signal,
    },
  )
  if (
    !isRecord(payload) ||
    typeof payload.token !== 'string' ||
    !payload.token
  ) {
    console.warn('CSRF 响应缺少令牌', { requestId })
    throw new ApiError(200, 'INVALID_RESPONSE', undefined, requestId)
  }
  signal?.throwIfAborted()
  if (generation !== csrfGeneration) return fetchCsrfToken(signal)
  csrfToken = payload.token
  return payload.token
}

/**
 * Local development only: the backend trusts this header solely when DEV_HEADER_AUTH=true.
 * 未选择身份时不发送头，使 /auth/me 返回 401，登录页因此真实生效。
 */
function developmentHeaders(): Record<string, string> {
  if (!import.meta.env.DEV) return {}
  const subject = localStorage.getItem('aiqa.devSubject')
  return subject ? { 'X-Dev-Subject': subject, 'X-Dev-Name': subject } : {}
}

function retryAfterMs(value: string | null): number | undefined {
  if (!value) return undefined
  if (/^\d+$/.test(value)) {
    const delay = Number(value) * 1000
    return Number.isFinite(delay) ? delay : undefined
  }
  const date = Date.parse(value)
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : undefined
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function mediaType(response: Response): string | undefined {
  return response.headers
    .get('Content-Type')
    ?.split(';', 1)[0]
    ?.trim()
    .toLowerCase()
}

function isNonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

function invalidResponse(response: Response, requestId?: string): ApiError {
  console.warn('API 响应不符合契约', { status: response.status, requestId })
  return new ApiError(response.status, 'INVALID_RESPONSE', undefined, requestId)
}

async function toApiError(response: Response): Promise<ApiError> {
  const headerId = response.headers.get('X-Request-Id') ?? undefined
  const payload: unknown =
    mediaType(response) === 'application/problem+json'
      ? await response.json().catch(() => undefined)
      : undefined
  const problem = isRecord(payload) ? payload : {}
  const requestId = isNonBlank(problem.requestId)
    ? problem.requestId
    : undefined
  // 旧版 Spring Security 的 401/403 Problem Details 没有正文请求 ID。
  const legacySecurity =
    problem.requestId === undefined &&
    problem.type === 'about:blank' &&
    ((response.status === 401 &&
      problem.code === 'UNAUTHENTICATED' &&
      problem.title === 'Unauthorized') ||
      (response.status === 403 &&
        problem.code === 'FORBIDDEN' &&
        problem.title === 'Forbidden'))
  if (
    problem.status !== response.status ||
    !isNonBlank(problem.type) ||
    !isNonBlank(problem.title) ||
    !isNonBlank(problem.code) ||
    (!legacySecurity &&
      (!requestId || (headerId !== undefined && headerId !== requestId)))
  ) {
    return invalidResponse(response, headerId ?? requestId)
  }
  return new ApiError(
    response.status,
    problem.code,
    typeof problem.detail === 'string' ? problem.detail : undefined,
    headerId ?? requestId,
    retryAfterMs(response.headers.get('Retry-After')),
  )
}

export interface ApiResponse<T> {
  data: T
  etag: string | undefined
  location: string | undefined
  requestId: string | undefined
}

/** Keeps the strong ETag the backend returned so a later write can send If-Match. */
export async function requestWithMeta<T>(
  path: string,
  options: RequestOptions = {},
): Promise<ApiResponse<T>> {
  return send<T>(path, options)
}

export async function request<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const response = await send<T>(path, options)
  return response.data
}

/** 所有 JSON、CSRF 和原件下载共用请求拦截器。 */
async function interceptRequest(
  options: RequestOptions,
  accept: string,
): Promise<RequestInit> {
  options.signal?.throwIfAborted()
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: accept }
  Object.assign(headers, developmentHeaders())
  if (options.ifMatch) headers['If-Match'] = options.ifMatch
  if (options.idempotencyKey)
    headers['Idempotency-Key'] = options.idempotencyKey
  if (method !== 'GET') {
    headers['X-CSRF-TOKEN'] =
      csrfToken ?? (await fetchCsrfToken(options.signal))
  }

  let body: BodyInit | undefined
  if (options.formData) {
    body = options.formData
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const init: RequestInit = {
    method,
    credentials: 'include',
    headers,
    signal: options.signal ?? null,
  }
  if (body !== undefined) init.body = body
  return init
}

/** 统一处理会话失效、CSRF 缓存和 Problem Details。 */
async function interceptResponse(
  response: Response,
  options: RequestOptions,
): Promise<Response> {
  options.signal?.throwIfAborted()
  if (response.ok) return response

  const error = await toApiError(response)
  options.signal?.throwIfAborted()
  if (isApiError(error, 401, 'UNAUTHENTICATED')) {
    if (options.redirectOnUnauthorized === false) clearCsrfToken()
    else handleUnauthorized()
  }
  // 后端明确拒绝 CSRF 时只清缓存，由用户决定是否重试写入。
  if (isApiError(error, 403, 'CSRF_INVALID')) clearCsrfToken()
  throw error
}

async function execute(
  path: string,
  options: RequestOptions,
  accept: string,
): Promise<Response> {
  if (options.formData && options.onUploadProgress) {
    return interceptResponse(await executeUpload(path, options), options)
  }
  const init = await interceptRequest(options, accept)
  return interceptResponse(await fetch(path, init), options)
}

async function executeUpload(
  path: string,
  options: RequestOptions,
): Promise<Response> {
  const init = await interceptRequest(options, 'application/json')
  const { signal } = options
  signal?.throwIfAborted()

  return new Promise<Response>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    let settled = false
    const finish = (callback: () => void): void => {
      if (settled) return
      settled = true
      signal?.removeEventListener('abort', abort)
      callback()
    }
    const abort = (): void => {
      xhr.abort()
      finish(() => {
        reject(
          signal?.reason instanceof Error
            ? signal.reason
            : new DOMException('请求已取消', 'AbortError'),
        )
      })
    }

    if (signal?.aborted) {
      abort()
      return
    }
    signal?.addEventListener('abort', abort, { once: true })
    xhr.open(init.method ?? 'POST', path)
    xhr.withCredentials = true
    const requestHeaders = init.headers
    if (requestHeaders instanceof Headers) {
      requestHeaders.forEach((value, name) => {
        xhr.setRequestHeader(name, value)
      })
    } else if (Array.isArray(requestHeaders)) {
      for (const [name, value] of requestHeaders)
        xhr.setRequestHeader(name, value)
    } else {
      for (const [name, value] of Object.entries(requestHeaders ?? {})) {
        xhr.setRequestHeader(name, value)
      }
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) {
        options.onUploadProgress?.(
          Math.min(100, Math.round((event.loaded / event.total) * 100)),
        )
      }
    }
    xhr.onerror = () => {
      finish(() => {
        reject(new TypeError('网络请求失败'))
      })
    }
    xhr.onabort = () => {
      finish(() => {
        reject(new DOMException('请求已取消', 'AbortError'))
      })
    }
    xhr.onload = () => {
      if (xhr.status < 200 || xhr.status > 599) {
        finish(() => {
          reject(new TypeError('网络请求失败'))
        })
        return
      }
      const responseHeaders = new Headers()
      for (const line of xhr
        .getAllResponseHeaders()
        .trim()
        .split(/[\r\n]+/)) {
        const separator = line.indexOf(':')
        if (separator > 0) {
          responseHeaders.append(
            line.slice(0, separator).trim(),
            line.slice(separator + 1).trim(),
          )
        }
      }
      const noBody =
        xhr.status === 204 || xhr.status === 205 || xhr.status === 304
      const response = new Response(noBody ? null : xhr.responseText, {
        status: xhr.status,
        statusText: xhr.statusText,
        headers: responseHeaders,
      })
      finish(() => {
        resolve(response)
      })
    }
    xhr.send(options.formData ?? null)
  })
}

export async function requestBlob(
  path: string,
  signal?: AbortSignal,
): Promise<Blob> {
  const response = await execute(path, { signal }, 'application/octet-stream')
  if (mediaType(response) !== 'application/octet-stream') {
    throw invalidResponse(
      response,
      response.headers.get('X-Request-Id') ?? undefined,
    )
  }
  const blob = await response.blob()
  signal?.throwIfAborted()
  return blob
}

/**
 * 打开一条 SSE 长连接：复用统一的鉴权、CSRF 缓存和 Problem Details 处理。
 *
 * 返回未读取的 Response，由调用方逐块解析事件；服务端不支持流式（返回普通 JSON）时
 * 按契约错误抛出，调用方据此回退轮询。
 */
export async function openEventStream(
  path: string,
  options: RequestOptions = {},
): Promise<Response> {
  const response = await execute(path, options, 'text/event-stream')
  if (mediaType(response) !== 'text/event-stream') {
    throw invalidResponse(
      response,
      response.headers.get('X-Request-Id') ?? undefined,
    )
  }
  return response
}

async function send<T>(
  path: string,
  options: RequestOptions,
): Promise<ApiResponse<T>> {
  const response = await execute(path, options, 'application/json')
  const etag = response.headers.get('ETag') ?? undefined
  const location = response.headers.get('Location') ?? undefined
  const headerId = response.headers.get('X-Request-Id') ?? undefined
  if (response.status === 204) {
    return { data: undefined as T, etag, location, requestId: headerId }
  }
  const payload: unknown =
    mediaType(response) === 'application/json'
      ? await response.json().catch(() => undefined)
      : undefined
  options.signal?.throwIfAborted()
  const requestId =
    isRecord(payload) && isNonBlank(payload.requestId)
      ? payload.requestId
      : undefined
  if (
    !isRecord(payload) ||
    payload.code !== 'OK' ||
    !Object.hasOwn(payload, 'data') ||
    !requestId ||
    (headerId !== undefined && headerId !== requestId)
  ) {
    throw invalidResponse(response, headerId ?? requestId)
  }
  return { data: payload.data as T, etag, location, requestId }
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
