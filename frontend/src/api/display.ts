import { ApiError } from './http'

const STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  PROCESSING: '处理中',
  READY: '可检索',
  NO_TEXT: '无可提取正文',
  FAILED: '处理失败',
  SKIPPED_DUPLICATE: '同名已跳过',
  REJECTED: '已拒绝',
  PENDING_REVIEW: '待审核',
  APPROVED_WAITING_INDEX: '待索引完成',
  PUBLISHED: '已发布',
  CANCELLED: '已撤回',
  EFFECTIVE: '现行',
  EXPIRED: '已失效',
  UNKNOWN: '有效性待核实',
  NO_MATCH: '无匹配',
  SUCCEEDED: '已回答',
  RUNNING: '生成中',
  VISIBLE: '可见',
  MASKED: '已屏蔽',
  ANSWER: '回答',
  NO_EVIDENCE: '无依据',
  UNCERTAIN: '有效性待核实',
  CONFLICT: '政策冲突',
  COMPLETED: '已完成',
}

const FILE_CODES: Record<string, string> = {
  NO_EXTRACTABLE_TEXT: '纯扫描件，无法提取正文。已存档，不计入可检索文档数。',
  PROCESSING_FAILED: '解析失败，属于技术性失败，可重试。',
  DUPLICATE_NAME: '与空间内已有文件同名。原文件不覆盖；上传新版前必须改名。',
  FILE_TOO_LARGE: '超过单文件体积上限。',
  UNSUPPORTED_FILE: '暂不支持该格式。',
  INVALID_FILE: '文件内容与扩展名不匹配，或格式已损坏。',
  SUSPICIOUS_FILE: '文件未通过内容安全检查，已被拒绝。',
}

export function statusLabel(value: string | undefined): string {
  if (!value) return '—'
  return STATUS_LABELS[value] ?? value
}

export function fileCodeHint(code: string | undefined): string {
  if (!code) return ''
  return FILE_CODES[code] ?? code
}

const STATUS_TONE: Record<string, string> = {
  READY: 'status-success',
  PUBLISHED: 'status-success',
  SUCCEEDED: 'status-success',
  EFFECTIVE: 'status-success',
  COMPLETED: 'status-success',
  FAILED: 'status-danger',
  REJECTED: 'status-danger',
  CANCELLED: 'status-danger',
  NO_TEXT: 'status-warning',
  PROCESSING: 'status-warning',
  QUEUED: 'status-warning',
  PENDING_REVIEW: 'status-warning',
  APPROVED_WAITING_INDEX: 'status-warning',
  UNKNOWN: 'status-warning',
  EXPIRED: 'status-warning',
  NO_EVIDENCE: 'status-warning',
  CONFLICT: 'status-warning',
  MASKED: 'status-warning',
}

/** Maps a backend enum to the shared status badge tone; unknown values stay neutral. */
export function statusTone(value: string | undefined): string {
  if (!value) return 'status'
  return STATUS_TONE[value] ?? 'status'
}

const API_ERROR_HINTS: Record<string, string> = {
  '400 INVALID_REQUEST': '请求无法处理，请检查输入后重试。',
  '400 INVALID_CAPTCHA': '验证码不正确或已失效，请按图片重新输入。',
  '400 IDEMPOTENCY_KEY_REQUIRED': '请求无法处理，请重新发起操作。',
  '400 INVALID_CURSOR': '分页状态已失效，请重新加载列表。',
  '401 UNAUTHENTICATED': '会话已失效，请重新登录。',
  '401 INVALID_CREDENTIALS': '用户名或密码错误。',
  '403 FORBIDDEN': '你没有权限执行此操作。',
  '403 CSRF_INVALID': '会话校验已失效，请重试。',
  '403 ACCOUNT_DISABLED': '该账号已停用，请联系管理员。',
  '404 RESOURCE_NOT_FOUND': '该资源当前不可用。',
  '409 STATE_CONFLICT': '资源状态已变化或存在冲突，请读取最新状态后重试。',
  '409 PUBLICATION_CHANGED': '当前发布版本已变化，请读取最新状态后重试。',
  '409 PUBLISH_REQUEST_OPEN': '已有待处理的发布申请，请刷新查看。',
  '409 DOCUMENT_NOT_RETRYABLE': '该文档当前无法重试，请刷新查看。',
  '409 IDEMPOTENCY_KEY_REUSED': '该操作与之前的请求冲突，请检查结果后再试。',
  '409 IDEMPOTENCY_IN_PROGRESS': '此操作仍在处理，请稍后查看结果。',
  '409 USERNAME_TAKEN': '该用户名已被使用。',
  '412 ETAG_MISMATCH': '版本已变化，请读取最新版本并确认后再提交。',
  '428 IF_MATCH_REQUIRED': '缺少版本标识，请先读取最新版本后再提交。',
  '413 PAYLOAD_TOO_LARGE': '上传内容超出大小限制。',
  '415 UNSUPPORTED_MEDIA_TYPE': '暂不支持该内容类型。',
  '422 VALIDATION_FAILED': '输入未通过校验，请检查后重试。',
  '429 RATE_LIMITED': '操作过于频繁，请稍后重试。',
  '500 INTERNAL_ERROR': '服务发生异常，请稍后重试。',
  '503 DEPENDENCY_UNAVAILABLE': '依赖服务暂时不可用，请稍后重试。',
  '503 AUTH_VALIDATION_UNAVAILABLE': '登录校验暂时不可用，请稍后重试。',
}

export function describeError(error: unknown, contextHint?: string): string {
  if (error instanceof ApiError) {
    const hint = API_ERROR_HINTS[`${String(error.status)} ${error.code}`]
    const trace = error.requestId ? `（请求 ${error.requestId}）` : ''
    if (!hint && !contextHint) {
      if (error.code !== 'INVALID_RESPONSE') {
        console.warn('未识别的 API 错误', {
          status: error.status,
          code: error.code,
          requestId: error.requestId,
        })
      }
      return `${error.code === 'INVALID_RESPONSE' ? '服务响应格式异常，请稍后重试。' : '请求失败，请稍后重试。'}${trace}`
    }
    const detail =
      !contextHint &&
      error.message !== error.code &&
      error.status >= 400 &&
      error.status < 500
        ? ` ${error.message}`
        : ''
    const retry =
      (error.status === 429 || error.status === 503) &&
      error.retryAfterMs !== undefined
        ? ` 请等待 ${String(Math.ceil(error.retryAfterMs / 1000))} 秒后重试。`
        : ''
    return `${contextHint ?? hint ?? ''}${detail}${retry}${trace}`
  }
  return error instanceof Error ? error.message : '请求失败'
}

const beijingFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  dateStyle: 'medium',
  timeStyle: 'short',
  hour12: false,
})

const beijingDateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/** All user-facing timestamps are Beijing time, matching the usage statistics boundary. */
/** 北京时间日期（不含时分），用于「编辑于 …」这类展示。 */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  return beijingDateFormatter.format(
    new Date(value.length === 10 ? value + 'T00:00:00Z' : value),
  )
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime())
    ? value
    : beijingFormatter.format(parsed)
}

export function beijingToday(): string {
  return beijingDateFormatter.format(new Date())
}

export function formatBytes(size: number): string {
  if (size < 1024) return `${String(size)} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

/** 政策有效性：EFFECTIVE 现行、EXPIRED 已失效、UNCERTAIN 待核实。 */
export function validityLabel(
  validity: 'EFFECTIVE' | 'EXPIRED' | 'UNCERTAIN',
): string {
  switch (validity) {
    case 'EFFECTIVE':
      return '现行有效'
    case 'EXPIRED':
      return '已失效'
    default:
      return '有效性待核实'
  }
}

export function validityTone(
  validity: 'EFFECTIVE' | 'EXPIRED' | 'UNCERTAIN',
): string {
  switch (validity) {
    case 'EFFECTIVE':
      return 'bk-badge bk-badge-ok'
    case 'EXPIRED':
      return 'bk-badge bk-badge-warn'
    default:
      return 'bk-badge bk-badge-draft'
  }
}

export interface AnswerFlag {
  tone: 'masked' | 'none' | 'expired' | 'uncertain' | 'conflict'
  text: string
}

/** 回答段落的状态提示；工作台与助手共用同一套语义。 */
export function answerFlag(paragraph: {
  status: string
  kind?: string | undefined
}): AnswerFlag | null {
  if (paragraph.status === 'MASKED')
    return { tone: 'masked', text: '⛔ 来源已不可用' }
  switch (paragraph.kind) {
    case 'NO_EVIDENCE':
      return { tone: 'none', text: '◌ 无依据' }
    case 'EXPIRED':
      return { tone: 'expired', text: '⛔ 政策已失效' }
    case 'UNCERTAIN':
      return { tone: 'uncertain', text: '有效性待核实' }
    case 'CONFLICT':
      return { tone: 'conflict', text: '⚡ 政策冲突 · 逐项列出' }
    default:
      return null
  }
}

/** 清洗批次状态：与清洗草稿箱接口的 status 一一对应。 */
const CLEANING_BATCH_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  EXTRACTING: '提取中',
  SCORING: '打分中',
  READY: '已完成',
  PARTIAL: '部分成功',
  FAILED: '处理失败',
}

export function cleaningBatchLabel(value: string | undefined): string {
  if (!value) return '—'
  return CLEANING_BATCH_LABELS[value] ?? value
}

/** 批次状态徽章：完成绿、进行中琥珀、部分成功琥珀、失败红。 */
export function cleaningBatchTone(value: string | undefined): string {
  switch (value) {
    case 'READY':
      return 'bk-badge bk-badge-ok'
    case 'PARTIAL':
      return 'bk-badge bk-badge-warn'
    case 'FAILED':
      return 'bk-badge bk-badge-fail'
    case 'QUEUED':
    case 'EXTRACTING':
    case 'SCORING':
      return 'bk-badge bk-badge-review'
    default:
      return 'bk-badge bk-badge-draft'
  }
}

/** 清洗条目状态：待审核 → 已通过 / 已驳回 / 已流转。 */
const CLEANING_ITEM_LABELS: Record<string, string> = {
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  PROMOTED: '已流转',
}

export function cleaningItemLabel(value: string | undefined): string {
  if (!value) return '—'
  return CLEANING_ITEM_LABELS[value] ?? value
}

/** 条目状态徽章：通过绿、待审蓝、驳回红、已流转灰。 */
export function cleaningItemTone(value: string | undefined): string {
  switch (value) {
    case 'APPROVED':
      return 'bk-badge bk-badge-ok'
    case 'PENDING_REVIEW':
      return 'bk-badge bk-badge-review'
    case 'REJECTED':
      return 'bk-badge bk-badge-rejected'
    default:
      return 'bk-badge bk-badge-published'
  }
}

/** 打分状态：模型分缺失时界面要说明原因，而不是显示成 0 分。 */
export function cleaningScoreStateLabel(value: string | undefined): string {
  switch (value) {
    case 'SCORED':
      return '规则分 + 模型分'
    case 'UNAVAILABLE':
      return '仅规则分'
    default:
      return '模型打分中'
  }
}

/** 清洗来源文件的状态：与批次的「已完成」区分开，避免把单个文件也读成批次结论。 */
const CLEANING_SOURCE_LABELS: Record<string, string> = {
  PENDING: '等待处理',
  READY: '已处理',
  FAILED: '处理失败',
}

export function cleaningSourceStateLabel(value: string | undefined): string {
  if (!value) return '—'
  return CLEANING_SOURCE_LABELS[value] ?? value
}

/** 清洗过程中按文件归集的失败原因；未登记的码原样展示，便于排查。 */
const CLEANING_FILE_ERRORS: Record<string, string> = {
  ENCODING_UNSUPPORTED: '文件不是 UTF-8 编码，请另存为 UTF-8 后重试。',
  UNSUPPORTED_CLEANING_FORMAT: '暂不支持该格式，已忽略。',
  EXTRACT_FAILED: '解压或读取失败。',
  ARCHIVE_ENTRY_TOO_LARGE: '压缩包内单个条目超过大小上限。',
  ARCHIVE_TOO_MANY_ENTRIES: '压缩包条目数超过上限。',
  ARCHIVE_RATIO_ANOMALY: '压缩比异常，已拒绝以防压缩炸弹。',
  PROCESSING_FAILED: '处理失败，可重试。',
  TOO_MANY_RECORDS: '单文件记录数超过上限。',
}

export function cleaningFileErrorHint(code: string | null | undefined): string {
  if (!code) return ''
  return CLEANING_FILE_ERRORS[code] ?? code
}
