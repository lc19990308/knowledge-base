import { listQuery } from './query'
import { newIdempotencyKey, request } from './http'
import type { Page } from './knowledge'

/** 清洗批次状态：排队 → 提取中 → 打分中 → 已完成 / 部分成功 / 失败。 */
export type CleaningBatchStatus =
  'QUEUED' | 'EXTRACTING' | 'SCORING' | 'READY' | 'PARTIAL' | 'FAILED'

/** 清洗条目状态：待审核 → 已通过 / 已驳回，已通过的可以流转。 */
export type CleaningItemStatus =
  'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'PROMOTED'

/** 打分状态：模型分未产出时保持 PENDING，失败或未启用时是 UNAVAILABLE。 */
export type CleaningScoreState = 'PENDING' | 'SCORED' | 'UNAVAILABLE'

/** 单个来源文件的处理状态。 */
export type CleaningSourceState = 'PENDING' | 'READY' | 'FAILED'

/** 政策状态沿用发布链路的取值，流转时由管理员选择。 */
export type CleaningPolicyState = 'EFFECTIVE' | 'EXPIRED' | 'UNKNOWN'

/** 规则分各维度明细，与后端 score_detail 一一对应。 */
export interface CleaningScoreDetail {
  length: number
  completeness: number
  noise: number
  information: number
  structure: number
}

export interface CleaningBatch {
  batchId: string
  name: string
  targetSpaceId: string | null
  status: CleaningBatchStatus
  modelScoring: boolean
  fileCount: number
  skippedCount: number
  failedFileCount: number
  itemCount: number
  duplicateCount: number
  /** 已打分条目的平均总分；没有可打分条目时为空。 */
  averageScore: number | null
  errorCode: string | null
  createdAt: string
  finishedAt: string | null
}

export interface CleaningSourceFile {
  sourceFileId: string
  relativePath: string
  displayName: string
  sizeBytes: number
  state: CleaningSourceState
  recordCount: number
  errorCode: string | null
}

export interface CleaningBatchDetail extends CleaningBatch {
  sourceFiles: CleaningSourceFile[]
}

export interface CleaningItem {
  itemId: string
  batchId: string
  batchName: string
  title: string
  /** 列表用的正文摘要，避免把整段正文塞进表格。 */
  excerpt: string
  sourcePath: string | null
  ruleScore: number
  modelScore: number | null
  totalScore: number
  scoreState: CleaningScoreState
  modelReason: string | null
  status: CleaningItemStatus
  targetSpaceId: string | null
  targetSpaceName: string | null
  promotedDocumentVersionId: string | null
  createdAt: string
  reviewedAt: string | null
  reviewReason: string | null
}

export interface CleaningItemDetail extends CleaningItem {
  body: string
  rawExcerpt: string | null
  scoreDetail: CleaningScoreDetail
  reviewedBy: string | null
  promotedSpaceId: string | null
  promotedAt: string | null
}

export interface CleaningReviewResult {
  reviewed: number
  items: { itemId: string; status: CleaningItemStatus }[]
}

export interface CleaningPromotionResult {
  documentVersionId: string
  documentName: string
  versionNumber: number
  draftRevision: number
  promoted: number
}

export type CleaningReviewDecision = 'APPROVE' | 'REJECT'

export interface CleaningItemFilters {
  status?: CleaningItemStatus | ''
  batchId?: string
  targetSpaceId?: string
  /** 最低总分；0 表示不限。 */
  minScore?: number
  q?: string
}

export interface CleaningBatchFilters {
  status?: CleaningBatchStatus | ''
}

export interface CleaningListOptions {
  limit?: number
  offset?: number
  signal?: AbortSignal
}

/** 长期停留在这些状态的批次需要界面继续轮询，直到出结果。 */
export function isCleaningBatchActive(status: CleaningBatchStatus): boolean {
  return status === 'QUEUED' || status === 'EXTRACTING' || status === 'SCORING'
}

/** 上传原始数据：文件夹里的多个文件（带相对路径）与可选的压缩包可以一起提交。 */
export interface CleaningUploadInput {
  files: { file: File; path: string }[]
  archive: File | null
  name: string
  targetSpaceId: string | null
}

export interface CleaningUploadOptions {
  idempotencyKey?: string
  onUploadProgress?: (percent: number) => void
  signal?: AbortSignal
}

/**
 * 提交清洗批次。
 *
 * `paths` 与 `files` 按下标一一对应，用于还原文件夹层级；压缩包由后端解包，
 * 前端只负责原样上传，不读取其中的内容。
 */
export function uploadCleaningBatch(
  input: CleaningUploadInput,
  options: CleaningUploadOptions = {},
): Promise<CleaningBatch> {
  const formData = new FormData()
  for (const entry of input.files) {
    formData.append('files', entry.file, entry.file.name)
    formData.append('paths', entry.path)
  }
  if (input.archive) {
    formData.append('archive', input.archive, input.archive.name)
  }
  if (input.name.trim() !== '') {
    formData.append('name', input.name.trim())
  }
  if (input.targetSpaceId) {
    formData.append('targetSpaceId', input.targetSpaceId)
  }
  return request<CleaningBatch>('/api/v1/cleaning/batches', {
    method: 'POST',
    formData,
    idempotencyKey: options.idempotencyKey ?? newIdempotencyKey(),
    onUploadProgress: options.onUploadProgress,
    signal: options.signal,
  })
}

/** 批次列表：按创建时间倒序，服务端分页。 */
export function listCleaningBatches(
  filters: CleaningBatchFilters = {},
  options: CleaningListOptions = {},
): Promise<Page<CleaningBatch>> {
  return request<Page<CleaningBatch>>(
    `/api/v1/cleaning/batches${listQuery({
      status: filters.status,
      limit: options.limit,
      offset: options.offset,
    })}`,
    { signal: options.signal },
  )
}

/** 批次详情：含每个来源文件的处理结果与失败原因。 */
export function getCleaningBatch(
  batchId: string,
  signal?: AbortSignal,
): Promise<CleaningBatchDetail> {
  return request<CleaningBatchDetail>(`/api/v1/cleaning/batches/${batchId}`, {
    signal,
  })
}

/** 重新对未成功打分的条目跑一次模型打分，用于网关恢复后的补偿。 */
export function rescoreCleaningBatch(
  batchId: string,
): Promise<{ enqueued: number }> {
  return request<{ enqueued: number }>(
    `/api/v1/cleaning/batches/${batchId}/rescore`,
    { method: 'POST', idempotencyKey: newIdempotencyKey() },
  )
}

/** 清洗草稿箱列表：全局收件箱，支持状态、批次、目标空间、最低分与关键词筛选。 */
export function listCleaningItems(
  filters: CleaningItemFilters = {},
  options: CleaningListOptions = {},
): Promise<Page<CleaningItem>> {
  return request<Page<CleaningItem>>(
    `/api/v1/cleaning/items${listQuery({
      status: filters.status,
      batchId: filters.batchId,
      targetSpaceId: filters.targetSpaceId,
      minScore: filters.minScore === 0 ? undefined : filters.minScore,
      q: filters.q,
      limit: options.limit,
      offset: options.offset,
    })}`,
    { signal: options.signal },
  )
}

/** 条目详情：正文、清洗前摘录、评分明细与审核/流转记录。 */
export function getCleaningItem(
  itemId: string,
  signal?: AbortSignal,
): Promise<CleaningItemDetail> {
  return request<CleaningItemDetail>(`/api/v1/cleaning/items/${itemId}`, {
    signal,
  })
}

/** 批量审核：通过为轻确认，驳回必须带原因（由后端与前端共同校验）。 */
export function reviewCleaningItems(
  itemIds: string[],
  decision: CleaningReviewDecision,
  reason: string,
): Promise<CleaningReviewResult> {
  return request<CleaningReviewResult>('/api/v1/cleaning/items/review', {
    method: 'POST',
    body: { itemIds, decision, reason: reason.trim() },
    idempotencyKey: newIdempotencyKey(),
  })
}

export interface CleaningPromotionInput {
  itemIds: string[]
  spaceId: string
  documentName: string
  policyState: CleaningPolicyState
  effectiveFrom: string | null
  effectiveUntil: string | null
  reason: string
}

/**
 * 流转到知识库：把已通过的条目合成一份 Markdown 文档，写入目标空间草稿。
 * 发布仍需走「发布管理」的提交与审核，不会直接对用户可见。
 */
export function promoteCleaningItems(
  input: CleaningPromotionInput,
): Promise<CleaningPromotionResult> {
  return request<CleaningPromotionResult>('/api/v1/cleaning/promotions', {
    method: 'POST',
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}
