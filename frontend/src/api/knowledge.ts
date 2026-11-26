import {
  newIdempotencyKey,
  request,
  requestBlob,
  requestWithMeta,
} from './http'
import { pollUntil } from './polling'
import { listQuery } from './query'

export interface Page<T> {
  items: T[]
  /** null on the last page, as the published contract states. */
  nextCursor: string | null
  /** 筛选后的记录总数；未传 limit 时等于 items 长度。 */
  total: number
}

/** 列表接口的 offset/limit 分页参数，与后端 PageQuery 对应。 */
export interface PageQuery {
  limit: number
  offset: number
}

/** 列表请求选项：分页、取消信号，以及各接口自己的筛选参数。 */
export interface ListOptions {
  limit?: number
  offset?: number
  signal?: AbortSignal
}

/* ---------- 身份 ---------- */

export interface CurrentUser {
  userId: string
  displayName: string
  roles: ('USER' | 'SYSTEM_ADMIN')[]
  canCreateSpace: boolean
}

export type ModelProtocol = 'openai_compatible' | 'anthropic'

export interface ModelConfig {
  id: string
  alias: string
  credentialRef: string
  enabled: boolean
  revision: number
  isDefault: boolean
  protocol: ModelProtocol
  baseUrl: string
  modelName: string
}

export interface ModelConfigInput {
  alias: string
  credentialRef: string
  enabled?: boolean
  protocol: ModelProtocol
  baseUrl: string
  modelName: string
}

export function listModelConfigs(
  options: ListOptions = {},
): Promise<Page<ModelConfig>> {
  return request<Page<ModelConfig>>(
    `/api/v1/model-configs${listQuery({ limit: options.limit, offset: options.offset })}`,
    {
      signal: options.signal,
    },
  )
}

export function createModelConfig(
  input: ModelConfigInput,
): Promise<WithEtag<ModelConfig>> {
  return requestWithMeta<ModelConfig>('/api/v1/model-configs', {
    method: 'POST',
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

export function updateModelConfig(
  id: string,
  etag: string,
  input: ModelConfigInput,
): Promise<WithEtag<ModelConfig>> {
  return requestWithMeta<ModelConfig>(`/api/v1/model-configs/${id}`, {
    method: 'PUT',
    ifMatch: etag,
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

export function deleteModelConfig(
  id: string,
  etag: string,
): Promise<undefined> {
  return request<undefined>(`/api/v1/model-configs/${id}`, {
    method: 'DELETE',
    ifMatch: etag,
    idempotencyKey: newIdempotencyKey(),
  })
}

export interface LoginProvider {
  id: string
  type: 'SSO'
}

/** 公开接口：后端接入了哪些登录方式；未接 IdP 时为空，登录页据此隐藏 SSO 按钮。 */
export function listLoginProviders(): Promise<{ items: LoginProvider[] }> {
  return request<{ items: LoginProvider[] }>('/api/v1/auth/providers')
}

export async function getCurrentUser(
  redirectOnUnauthorized?: false,
): Promise<CurrentUser> {
  return request<CurrentUser>(
    '/api/v1/auth/me',
    redirectOnUnauthorized === false ? { redirectOnUnauthorized } : {},
  )
}

/* ---------- 空间 ---------- */

export type PublicationStatus =
  'NONE' | 'PENDING_REVIEW' | 'PUBLISHED' | 'REJECTED'

export interface Space {
  spaceId: string
  name: string
  epoch: number
  currentPublicationId: string | null
  canRead: boolean
  canMaintain: boolean
  documentCount: number
  searchableCount: number
  updatedAt: string | null
  department: string | null
}

export interface DraftEntry {
  documentVersionId: string
  effectiveFrom?: string | null
  effectiveUntil?: string | null
  policyState: 'EFFECTIVE' | 'EXPIRED' | 'UNKNOWN'
  replacesDocumentVersionId?: string | null
  name: string | null
  parseState: string | null
  sizeBytes: number | null
  mediaType: string | null
  versionNumber: number | null
  createdAt: string | null
}

export interface Draft {
  spaceId: string
  revision: number
  entries: DraftEntry[]
}

export interface DocumentPreview {
  documentVersionId: string
  name: string
  status: 'QUEUED' | 'PROCESSING' | 'READY' | 'NO_TEXT' | 'FAILED'
  previewText: string | null
  sizeBytes: number
  mediaType: string | null
  versionNumber: number
  errorCode: string | null
}

/** 空间列表的发布状态筛选值，与接口 status 参数一致。 */
export type SpaceListStatus = 'PUBLISHED' | 'DRAFT' | 'REVIEWING'

export interface SpaceListOptions extends ListOptions {
  /** 按空间名称包含匹配。 */
  q?: string
  status?: SpaceListStatus | ''
}

export function listSpaces(
  options: SpaceListOptions = {},
): Promise<Page<Space>> {
  return request<Page<Space>>(
    `/api/v1/spaces${listQuery({
      limit: options.limit,
      offset: options.offset,
      q: options.q,
      status: options.status,
    })}`,
    {
      signal: options.signal,
    },
  )
}

export function createSpace(name: string, department?: string): Promise<Space> {
  return request<Space>('/api/v1/spaces', {
    method: 'POST',
    body: {
      name,
      department: department && department !== '' ? department : null,
    },
    idempotencyKey: newIdempotencyKey(),
  })
}

export function getSpace(
  spaceId: string,
  signal?: AbortSignal,
): Promise<Space> {
  return request<Space>(`/api/v1/spaces/${spaceId}`, { signal })
}

export type PolicyValidity = 'EFFECTIVE' | 'EXPIRED' | 'UNCERTAIN'

export interface SearchHit {
  documentVersionId: string
  name: string
  status: DocumentPreview['status']
  snippet?: string | null
  match?: 'NAME' | 'TEXT'
  validity: PolicyValidity
  versionNumber: number
}

/** 检索指向的内容来源：auto 由后端按 当前发布 → 最近发布 → 草稿 依次回退。 */
export type SearchSource = 'auto' | 'published' | 'latest' | 'draft'

/** 本次命中实际来自哪里，用于在界面上区分已发布与未发布内容。 */
export type SearchContentSource = 'PUBLISHED' | 'WITHDRAWN' | 'DRAFT'

export interface SearchResult {
  status: 'READY' | 'PROCESSING' | 'NO_MATCH'
  items: SearchHit[]
  nextCursor: string | null
  contentSource: SearchContentSource
}

export function searchSpace(
  spaceId: string,
  query: string,
  field: 'NAME' | 'TEXT' | 'ALL' = 'ALL',
  signal?: AbortSignal,
  source: SearchSource = 'auto',
): Promise<SearchResult> {
  const params = new URLSearchParams({ q: query, field, source })
  return request<SearchResult>(
    `/api/v1/spaces/${spaceId}/search?${params.toString()}`,
    { signal },
  )
}

export function getDocument(
  spaceId: string,
  documentId: string,
  signal?: AbortSignal,
): Promise<DocumentPreview> {
  return request<DocumentPreview>(
    `/api/v1/spaces/${spaceId}/documents/${documentId}`,
    { signal },
  )
}

export function downloadDocumentUrl(
  spaceId: string,
  documentId: string,
): string {
  return `/api/v1/spaces/${spaceId}/documents/${documentId}/content`
}

/* ---------- 导入 ---------- */

export type ImportFileStatus =
  | 'QUEUED'
  | 'PROCESSING'
  | 'READY'
  | 'NO_TEXT'
  | 'FAILED'
  | 'SKIPPED_DUPLICATE'
  | 'REJECTED'

export type ImportFileCode =
  | 'NO_EXTRACTABLE_TEXT'
  | 'PROCESSING_FAILED'
  | 'DUPLICATE_NAME'
  | 'FILE_TOO_LARGE'
  | 'UNSUPPORTED_FILE'
  | 'INVALID_FILE'
  | 'SUSPICIOUS_FILE'

export interface ImportFile {
  name: string
  status: ImportFileStatus
  documentVersionId?: string
  code?: ImportFileCode
  sizeBytes: number | null
  mediaType: string | null
  versionNumber: number | null
}

export interface ImportBatch {
  batchId: string
  spaceId: string
  status: 'PROCESSING' | 'COMPLETED'
  files: ImportFile[]
}

export function importDocuments(
  spaceId: string,
  files: File[],
  idempotencyKey = newIdempotencyKey(),
  onUploadProgress?: (percent: number) => void,
  signal?: AbortSignal,
): Promise<ImportBatch> {
  const formData = new FormData()
  for (const file of files) {
    formData.append('files', file, file.name)
  }
  return request<ImportBatch>(`/api/v1/spaces/${spaceId}/imports`, {
    method: 'POST',
    formData,
    idempotencyKey,
    onUploadProgress,
    signal,
  })
}

export interface UpdatedDocument {
  documentVersionId: string
  versionNumber: number
  status: string
}

/** 更新已入库文档：同文件名上传新内容，服务端生成下一版本（v2、v3…）。 */
export function updateDocumentVersion(
  spaceId: string,
  documentVersionId: string,
  file: File,
  signal?: AbortSignal,
): Promise<UpdatedDocument> {
  const formData = new FormData()
  formData.append('file', file, file.name)
  return request<UpdatedDocument>(
    `/api/v1/spaces/${spaceId}/documents/${documentVersionId}/versions`,
    {
      method: 'POST',
      formData,
      idempotencyKey: newIdempotencyKey(),
      signal,
    },
  )
}

export function getImportBatch(
  batchId: string,
  signal?: AbortSignal,
): Promise<ImportBatch> {
  return request<ImportBatch>(`/api/v1/imports/${batchId}`, { signal })
}

export function retryDocument(
  spaceId: string,
  documentId: string,
): Promise<DocumentPreview> {
  return request<DocumentPreview>(
    `/api/v1/spaces/${spaceId}/documents/${documentId}/retry`,
    {
      method: 'POST',
      idempotencyKey: newIdempotencyKey(),
    },
  )
}

/* ---------- 草稿与发布 ---------- */

export interface WithEtag<T> {
  data: T
  etag: string | undefined
}

export interface DraftDocumentPreview {
  documentVersionId: string
  name: string
  parseState: string
  previewText: string | null
}

export function getDraft(
  spaceId: string,
  signal?: AbortSignal,
): Promise<WithEtag<Draft>> {
  return requestWithMeta<Draft>(`/api/v1/spaces/${spaceId}/draft`, { signal })
}

export function getDraftDocument(
  spaceId: string,
  documentId: string,
  signal?: AbortSignal,
): Promise<DraftDocumentPreview> {
  return request<DraftDocumentPreview>(
    `/api/v1/spaces/${spaceId}/draft/documents/${documentId}`,
    { signal },
  )
}

export function replaceDraft(
  spaceId: string,
  etag: string,
  entries: DraftEntry[],
): Promise<Draft> {
  return request<Draft>(`/api/v1/spaces/${spaceId}/draft`, {
    method: 'PUT',
    ifMatch: etag,
    body: { entries },
  })
}

export type RequestStatus =
  | 'PENDING_REVIEW'
  | 'APPROVED_WAITING_INDEX'
  | 'PUBLISHED'
  | 'REJECTED'
  | 'CANCELLED'

export interface PublishRequest {
  requestId: string
  spaceId: string
  snapshotId: string
  status: RequestStatus
  submittedBy: string
  submittedAt: string
  submissionReason: string | null
  decisionReason: string | null
  decisionRequired: boolean
  draftRevision: number
  publicationId: string | null
  snapshot: {
    documentVersionId: string
    name: string | null
    policyState: 'EFFECTIVE' | 'EXPIRED' | 'UNKNOWN'
    effectiveFrom: string | null
    effectiveUntil: string | null
    replacesDocumentVersionId: string | null
    parseState: string | null
  }[]
}

export interface PublishRequestListOptions extends ListOptions {
  status?: PublishRequest['status'] | ''
}

export function listPublishRequests(
  spaceId: string,
  options: PublishRequestListOptions = {},
): Promise<Page<PublishRequest>> {
  return request<Page<PublishRequest>>(
    `/api/v1/spaces/${spaceId}/publish-requests${listQuery({
      limit: options.limit,
      offset: options.offset,
      status: options.status,
    })}`,
    { signal: options.signal },
  )
}

export function listAllPublishRequests(
  options: PublishRequestListOptions = {},
): Promise<Page<PublishRequest>> {
  return request<Page<PublishRequest>>(
    `/api/v1/publish-requests${listQuery({
      limit: options.limit,
      offset: options.offset,
      status: options.status,
    })}`,
    { signal: options.signal },
  )
}

export function getPublishRequest(
  requestId: string,
  signal?: AbortSignal,
): Promise<WithEtag<PublishRequest>> {
  return requestWithMeta<PublishRequest>(
    `/api/v1/publish-requests/${requestId}`,
    { signal },
  )
}

export function submitPublishRequest(
  spaceId: string,
  etag: string,
  reason: string,
): Promise<PublishRequest> {
  return request<PublishRequest>(`/api/v1/spaces/${spaceId}/publish-requests`, {
    method: 'POST',
    ifMatch: etag,
    body: { reason },
    idempotencyKey: newIdempotencyKey(),
  })
}

export function decidePublishRequest(
  requestId: string,
  etag: string,
  decision: 'APPROVE' | 'REJECT',
  reason: string,
): Promise<PublishRequest> {
  return request<PublishRequest>(
    `/api/v1/publish-requests/${requestId}/decision`,
    {
      method: 'POST',
      ifMatch: etag,
      body: { decision, reason },
      idempotencyKey: newIdempotencyKey(),
    },
  )
}

export function getReviewDocument(
  requestId: string,
  documentId: string,
): Promise<DocumentPreview> {
  return request<DocumentPreview>(
    `/api/v1/publish-requests/${requestId}/documents/${documentId}`,
  )
}

export function withdrawPublication(
  spaceId: string,
  expectedPublicationId: string,
  reason: string,
): Promise<{
  spaceId: string
  epoch: number
  currentPublicationId: string | null
}> {
  return request(`/api/v1/spaces/${spaceId}/withdrawals`, {
    method: 'POST',
    body: { expectedPublicationId, reason },
    idempotencyKey: newIdempotencyKey(),
  })
}

export function reindexSpace(
  spaceId: string,
): Promise<{ spaceId: string; enqueued: number }> {
  return request(`/api/v1/spaces/${spaceId}/reindex`, {
    method: 'POST',
    idempotencyKey: newIdempotencyKey(),
  })
}

/* ---------- 成员与用户 ---------- */

export interface SpaceMember {
  userId: string
  displayName: string
  canRead: boolean
  canMaintain: boolean
}

export interface UserAccess {
  userId: string
  displayName: string
  enabled: boolean
  roles: ('USER' | 'SYSTEM_ADMIN')[]
  canCreateSpace: boolean
  issuer: string
}

export function listMembers(
  spaceId: string,
  signal?: AbortSignal,
): Promise<Page<SpaceMember>> {
  return request<Page<SpaceMember>>(`/api/v1/spaces/${spaceId}/members`, {
    signal,
  })
}

export function setMember(
  spaceId: string,
  userId: string,
  canRead: boolean,
  canMaintain: boolean,
): Promise<SpaceMember> {
  return request<SpaceMember>(`/api/v1/spaces/${spaceId}/members/${userId}`, {
    method: 'PUT',
    body: { canRead, canMaintain },
  })
}

export interface LocalUserInput {
  username: string
  displayName: string
  password: string
  roles?: string[]
}

export interface SpaceMembership {
  spaceId: string
  spaceName: string
  canRead: boolean
  canMaintain: boolean
  /** 空间由该成员创建时为 true；系统管理员也不能收回所有者权限。 */
  owned: boolean
}

export interface SpaceMembershipInput {
  spaceId: string
  canRead: boolean
  canMaintain: boolean
}

/** 管理员读取某成员在各空间的授权（只含仍有授权的空间）。 */
export function listUserSpaceMemberships(
  userId: string,
  signal?: AbortSignal,
): Promise<Page<SpaceMembership>> {
  return request<Page<SpaceMembership>>(
    `/api/v1/users/${userId}/space-memberships`,
    { signal },
  )
}

/** 管理员一次提交成员的全部空间授权；未列出的空间撤销授权。 */
export function replaceUserSpaceMemberships(
  userId: string,
  memberships: SpaceMembershipInput[],
): Promise<Page<SpaceMembership>> {
  return request<Page<SpaceMembership>>(
    `/api/v1/users/${userId}/space-memberships`,
    { method: 'PUT', body: { memberships } },
  )
}

/** 管理员创建本地账号；成功后该账号即可在登录页登录。 */
export function createLocalUser(input: LocalUserInput): Promise<unknown> {
  return request<unknown>('/api/v1/users', {
    method: 'POST',
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

/** 管理员重置本地账号密码；企业 IdP 账号不适用。 */
export function resetUserCredential(
  userId: string,
  password: string,
): Promise<undefined> {
  return request<undefined>(`/api/v1/users/${userId}/credential`, {
    method: 'POST',
    body: { password },
    idempotencyKey: newIdempotencyKey(),
  })
}

export function listUsers(
  query = '',
  options: ListOptions = {},
): Promise<Page<UserAccess>> {
  return request<Page<UserAccess>>(
    `/api/v1/users${listQuery({
      limit: options.limit,
      offset: options.offset,
      q: query,
    })}`,
    { signal: options.signal },
  )
}

export function setUserAccess(
  userId: string,
  access: { enabled: boolean; roles: string[]; canCreateSpace: boolean },
): Promise<UserAccess> {
  return request<UserAccess>(`/api/v1/users/${userId}/access`, {
    method: 'PUT',
    body: access,
  })
}

/* ---------- 助手与问答 ---------- */

export interface AssistantBinding {
  spaceId: string
  role: 'RETRIEVAL'
  /** 接口回填的空间名称；新建或更新时不传。 */
  spaceName?: string | undefined
}

export interface Assistant {
  assistantId: string
  name: string
  bindings: AssistantBinding[]
  enabled: boolean
}

export interface AssistantAdmin extends Assistant {
  templateId: string | null
  template: string | null
}

export interface Conversation {
  conversationId: string
  assistantId: string
  createdAt: string
  archived: boolean
  title?: string | null
}

export interface CitationLabel {
  citationId: string
  label: string
  /** 正文里的引用编号（[n] 的 n），由服务端按证据序号给出。 */
  marker: number
}

export interface Paragraph {
  id: string
  status: 'VISIBLE' | 'MASKED'
  kind?: 'ANSWER' | 'NO_EVIDENCE' | 'EXPIRED' | 'UNCERTAIN' | 'CONFLICT'
  text?: string
  citations?: CitationLabel[]
  code?: 'SOURCE_UNAVAILABLE'
}

export type QuestionStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface Question {
  questionId: string
  status: QuestionStatus
  paragraphs?: Paragraph[]
  failureCode?: 'QUESTION_FAILED' | 'CONTEXT_CHANGED' | 'MODEL_UNAVAILABLE'
}

export interface QuestionAccepted {
  questionId: string
  status: 'QUEUED'
}

/** 首个问题由后端在同一事务内建会话，客户端不先创建空会话。 */
export interface NewQuestionAccepted extends QuestionAccepted {
  conversation: Conversation
}

export interface Message {
  messageId: string
  questionId: string
  role: 'USER' | 'ASSISTANT'
  createdAt: string
  text?: string
  paragraphs?: Paragraph[]
  status?: QuestionStatus
  failureCode?: Question['failureCode'] | null
}

export function listAssistants(
  options: ListOptions & { management?: boolean; q?: string } = {},
): Promise<Page<Assistant>> {
  return request<Page<Assistant>>(
    `/api/v1/assistants${listQuery({
      limit: options.limit,
      offset: options.offset,
      management: options.management === true ? 'true' : undefined,
      q: options.q,
    })}`,
    { signal: options.signal },
  )
}

export function getAssistant(
  assistantId: string,
): Promise<WithEtag<AssistantAdmin>> {
  return requestWithMeta<AssistantAdmin>(`/api/v1/assistants/${assistantId}`)
}

export function createAssistant(input: {
  name: string
  bindings: AssistantBinding[]
  templateId: string | null
}): Promise<Assistant> {
  return request<Assistant>('/api/v1/assistants', {
    method: 'POST',
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

export function updateAssistant(
  assistantId: string,
  etag: string,
  input: {
    name: string
    bindings: AssistantBinding[]
    templateId: string | null
    enabled: boolean
  },
): Promise<Assistant> {
  return request<Assistant>(`/api/v1/assistants/${assistantId}`, {
    method: 'PUT',
    ifMatch: etag,
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

export function listConversations(
  options: ListOptions & { archived?: boolean } = {},
): Promise<Page<Conversation>> {
  return request<Page<Conversation>>(
    `/api/v1/conversations${listQuery({
      limit: options.limit,
      offset: options.offset,
      archived: options.archived === true ? 'true' : undefined,
    })}`,
    { signal: options.signal },
  )
}

/** 会话列表分页后，用它补齐不在当前页的会话（标题、归属助手、归档状态）。 */
export function getConversation(
  conversationId: string,
  signal?: AbortSignal,
): Promise<Conversation> {
  return request<Conversation>(`/api/v1/conversations/${conversationId}`, {
    signal,
  })
}

export function setConversationArchived(
  id: string,
  archived: boolean,
): Promise<Conversation> {
  return request<Conversation>(`/api/v1/conversations/${id}/archive`, {
    method: 'PUT',
    body: { archived },
  })
}

export function deleteConversation(id: string): Promise<void> {
  return request<undefined>(`/api/v1/conversations/${id}`, { method: 'DELETE' })
}

export function createConversation(
  assistantId: string,
  title?: string,
  idempotencyKey = newIdempotencyKey(),
): Promise<Conversation> {
  return request<Conversation>('/api/v1/conversations', {
    method: 'POST',
    body: { assistantId, title },
    idempotencyKey,
  })
}

export function listMessages(
  conversationId: string,
  signal?: AbortSignal,
): Promise<Page<Message>> {
  return request<Page<Message>>(
    `/api/v1/conversations/${conversationId}/messages`,
    { signal },
  )
}

export interface ModelOption {
  id: string
  alias: string
  isDefault: boolean
}

export function listModelOptions(
  signal?: AbortSignal,
): Promise<Page<ModelOption>> {
  return request<Page<ModelOption>>('/api/v1/model-configs/options', { signal })
}

export function ask(
  conversationId: string,
  text: string,
  requestedSpaceIds?: string[],
  idempotencyKey = newIdempotencyKey(),
  modelId?: string,
): Promise<QuestionAccepted> {
  return request<QuestionAccepted>(
    `/api/v1/conversations/${conversationId}/questions`,
    {
      method: 'POST',
      body: { text, requestedSpaceIds, modelId },
      idempotencyKey,
    },
  )
}

/** 新会话的首个问题：后端自动建会话并按问题提炼标题。 */
export function startQuestion(
  text: string,
  options: {
    assistantId?: string | undefined
    requestedSpaceIds?: string[] | undefined
    modelId?: string | undefined
    idempotencyKey?: string
  } = {},
): Promise<NewQuestionAccepted> {
  return request<NewQuestionAccepted>('/api/v1/questions', {
    method: 'POST',
    body: {
      assistantId: options.assistantId,
      text,
      requestedSpaceIds: options.requestedSpaceIds,
      modelId: options.modelId,
    },
    idempotencyKey: options.idempotencyKey ?? newIdempotencyKey(),
  })
}

export function getQuestion(
  questionId: string,
  signal?: AbortSignal,
): Promise<Question> {
  return request<Question>(`/api/v1/questions/${questionId}`, { signal })
}

export class AnswerPollingTimeout extends Error {
  constructor(readonly questionId: string) {
    super('回答仍在处理，可稍后继续查询。')
    this.name = 'AnswerPollingTimeout'
  }
}

export async function waitForAnswer(
  questionId: string,
  options: {
    signal?: AbortSignal
    timeoutMs?: number
    attempts?: number
    delayMs?: number
  } = {},
): Promise<Question> {
  const answer = await pollUntil((signal) => getQuestion(questionId, signal), {
    signal: options.signal,
    timeoutMs: options.timeoutMs ?? 60_000,
    attempts: options.attempts ?? 40,
    delayMs: options.delayMs ?? 700,
    isComplete: (view) =>
      view.status === 'SUCCEEDED' || view.status === 'FAILED',
  })
  if (!answer) throw new AnswerPollingTimeout(questionId)
  return answer
}

export interface CitationDocument {
  citationId: string
  name: string
  previewText: string
  sourceLocation?: string | null
  documentVersionId?: string | null
  versionNumber?: number | null
  validity?: PolicyValidity | null
  evidenceGranularity?: 'CHUNK' | 'DOCUMENT' | null
}

function citationUrl(citationId: string): string {
  return `/api/v1/citations/${encodeURIComponent(citationId)}`
}

export function getCitation(
  citationId: string,
  signal?: AbortSignal,
): Promise<CitationDocument> {
  return request<CitationDocument>(citationUrl(citationId), { signal })
}

export function downloadCitation(
  citationId: string,
  signal?: AbortSignal,
): Promise<Blob> {
  return requestBlob(`${citationUrl(citationId)}/content`, signal)
}

/* ---------- 用量与设置 ---------- */

export interface UsageRankingRow {
  userId: string
  displayName: string
  conversationCount: number
  questionCount: number
  failedQuestionCount: number
  reportedTokenCount: number | null
  unreportedModelCallCount: number
}

export interface UsageSpaceRow {
  spaceId: string
  name: string
  citationCount: number
  questionCount: number
}

export interface UsageQuestionRow {
  questionId: string
  userId: string
  acceptedAt: string
  status: string
  text: string
  tokenTotal: number | null
}

export interface Usage {
  userId: string | null
  period: 'DAY' | 'WEEK' | 'MONTH'
  periodStart: string
  periodEndExclusive: string
  timeZone: 'Asia/Shanghai'
  conversationCount: number
  questionCount: number
  failedQuestionCount: number
  reportedTokenCount: number | null
  unreportedModelCallCount: number
}

export interface AnswerTemplate {
  templateId: string
  name: string
  body: string
  enabled: boolean
  revision: number
  isGlobal: boolean
  assistantIds: string[]
  updatedAt: string | null
}

export function listAnswerTemplates(
  options: ListOptions = {},
): Promise<Page<AnswerTemplate>> {
  return request<Page<AnswerTemplate>>(
    `/api/v1/answer-templates${listQuery({ limit: options.limit, offset: options.offset })}`,
    { signal: options.signal },
  )
}

export function getAnswerTemplate(
  templateId: string,
): Promise<WithEtag<AnswerTemplate>> {
  return requestWithMeta<AnswerTemplate>(
    `/api/v1/answer-templates/${templateId}`,
  )
}

export function createAnswerTemplate(input: {
  name: string
  body: string
  enabled?: boolean
}): Promise<WithEtag<AnswerTemplate>> {
  return requestWithMeta<AnswerTemplate>('/api/v1/answer-templates', {
    method: 'POST',
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

export function updateAnswerTemplate(
  templateId: string,
  etag: string,
  input: { name: string; body: string; enabled: boolean },
): Promise<WithEtag<AnswerTemplate>> {
  return requestWithMeta<AnswerTemplate>(
    `/api/v1/answer-templates/${templateId}`,
    {
      method: 'PUT',
      ifMatch: etag,
      body: input,
      idempotencyKey: newIdempotencyKey(),
    },
  )
}

export async function deleteAnswerTemplate(
  templateId: string,
  etag: string,
): Promise<void> {
  // 204 has no body; request() resolves undefined for it.
  await request<undefined>(`/api/v1/answer-templates/${templateId}`, {
    method: 'DELETE',
    ifMatch: etag,
  })
}

/** AI 辅助生成的模板正文：异步任务，前端轮询状态直到 SUCCEEDED / FAILED。 */
export interface TemplateGeneration {
  generationId: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  mode: 'STRUCTURED' | 'ADVANCED'
  scenario: string
  resultBody: string | null
  failureCode: string | null
  inputTokens: number | null
  outputTokens: number | null
  modelAlias: string
  createdAt: string
  finishedAt: string | null
}

export interface TemplateGenerationInput {
  mode: 'STRUCTURED' | 'ADVANCED'
  scenario: string
  requirements: string
  referenceTemplateId?: string | null
  modelConfigId?: string | null
}

export function createTemplateGeneration(
  input: TemplateGenerationInput,
): Promise<TemplateGeneration> {
  return request<TemplateGeneration>('/api/v1/answer-templates/generations', {
    method: 'POST',
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}

export function getTemplateGeneration(
  generationId: string,
  signal?: AbortSignal,
): Promise<TemplateGeneration> {
  return request<TemplateGeneration>(
    `/api/v1/answer-templates/generations/${generationId}`,
    { signal },
  )
}

export interface UsageBucket {
  label: string
  bucketStart: string
  questionCount: number
  failedQuestionCount: number
}

export interface UsageSeries {
  period: 'DAY' | 'WEEK' | 'MONTH'
  date: string
  timeZone: 'Asia/Shanghai'
  buckets: UsageBucket[]
}

export function getUsageSeries(
  period: 'DAY' | 'WEEK' | 'MONTH',
  date: string,
  userId?: string,
  signal?: AbortSignal,
): Promise<UsageSeries> {
  const params = new URLSearchParams({ period, date })
  if (userId === 'all') params.set('allUsers', 'true')
  else if (userId) params.set('userId', userId)
  return request<UsageSeries>(`/api/v1/usage/series?${params.toString()}`, {
    signal,
  })
}

export function getUsageRanking(
  period: 'DAY' | 'WEEK' | 'MONTH',
  date: string,
  signal?: AbortSignal,
): Promise<Page<UsageRankingRow>> {
  return request<Page<UsageRankingRow>>(
    `/api/v1/usage/ranking?period=${period}&date=${date}`,
    { signal },
  )
}

export function getUsageSpaces(
  period: 'DAY' | 'WEEK' | 'MONTH',
  date: string,
  signal?: AbortSignal,
): Promise<Page<UsageSpaceRow>> {
  return request<Page<UsageSpaceRow>>(
    `/api/v1/usage/spaces?period=${period}&date=${date}`,
    { signal },
  )
}

export function getUsageQuestions(
  period: 'DAY' | 'WEEK' | 'MONTH',
  date: string,
  userId?: string,
  signal?: AbortSignal,
): Promise<Page<UsageQuestionRow>> {
  const params = new URLSearchParams({ period, date })
  if (userId === 'all') params.set('allUsers', 'true')
  else if (userId) params.set('userId', userId)
  return request<Page<UsageQuestionRow>>(
    `/api/v1/usage/questions?${params.toString()}`,
    { signal },
  )
}

export function getUsage(
  period: 'DAY' | 'WEEK' | 'MONTH',
  date: string,
  userId?: string,
  signal?: AbortSignal,
): Promise<Usage> {
  const params = new URLSearchParams({ period, date })
  if (userId === 'all') params.set('allUsers', 'true')
  else if (userId) params.set('userId', userId)
  return request<Usage>(`/api/v1/usage?${params.toString()}`, { signal })
}

export interface GlobalSettings {
  defaultModelId: string | null
  /** 默认模型的别名（只读解析结果）；未配置默认模型时为 null。 */
  modelAlias: string | null
  templateId: string | null
  template: string | null
  contextLimit: number
  usageReportEnabled: boolean
  revision?: number
}

export function getGlobalSettings(): Promise<WithEtag<GlobalSettings>> {
  return requestWithMeta<GlobalSettings>('/api/v1/settings/global')
}

export interface GlobalSettingsInput {
  defaultModelId: string | null
  templateId: string | null
  contextLimit: number
  usageReportEnabled: boolean
}

export function putGlobalSettings(
  etag: string,
  input: GlobalSettingsInput,
): Promise<WithEtag<GlobalSettings>> {
  return requestWithMeta<GlobalSettings>('/api/v1/settings/global', {
    method: 'PUT',
    ifMatch: etag,
    body: input,
    idempotencyKey: newIdempotencyKey(),
  })
}
