import { computed, ref, shallowRef, type ComputedRef, type Ref } from 'vue'
import type { Page, PageQuery } from './api/knowledge'

/** 列表默认每页条数；公共分页器与各列表页共用这一份定义。 */
export const DEFAULT_PAGE_SIZE = 10

/** 每页条数可选项，与接口的 limit 参数一一对应。 */
export const PAGE_SIZE_OPTIONS = [10, 20, 50] as const

/** 后端 PageQuery.MAX_LIMIT 是 200，追加模式一次重取已加载区间时不能超过它。 */
const MAX_PAGE_SPAN = 200

export interface PagedListOptions<T> {
  initialPageSize?: number
  /** 把异常转成界面文案，默认取 message。 */
  describeError?: (error: unknown) => string
  /**
   * 追加模式：翻页时把新一页接在已有数据后面，供「滚动到底部加载下一页」的列表使用。
   * 默认关闭，翻页即替换当前页，与分页器配合。
   */
  append?: boolean
  /**
   * 追加模式下判断同一行记录的业务主键。分页之间若在列表头部新增了记录，
   * 下一页会重复返回上一页末尾的那一行，靠它去重，避免渲染重复节点。
   */
  keyOf?: (item: T) => string
}

/**
 * 服务端分页：页码、每页条数、总数与请求参数集中在这里，翻页或改条数都会重新取数，
 * 并取消上一次未完成的请求，避免旧响应覆盖新页面。
 */
export interface PagedList<T> {
  items: Ref<T[]>
  page: Ref<number>
  pageSize: Ref<number>
  total: Ref<number>
  limit: ComputedRef<number>
  offset: ComputedRef<number>
  loading: Ref<boolean>
  error: Ref<string | null>
  /** 已加载的数据之外是否还有下一页；滚动加载靠它决定还要不要请求。 */
  hasMore: ComputedRef<boolean>
  /** 读取下一页；追加模式下拼到已有数据后面，已在加载或没有更多时直接跳过。 */
  loadMore: () => Promise<void>
  /**
   * 重新读取当前列表：普通模式重取当前页；追加模式重取已加载的整段，
   * 尽量保持用户滚动到的位置，也让删除 / 归档后的列表与后端对齐。
   */
  reload: () => Promise<void>
  /** 回到第一页再读取（筛选条件变化时使用）。 */
  reset: () => Promise<void>
}

export function usePagedList<T>(
  fetchPage: (query: PageQuery, signal: AbortSignal) => Promise<Page<T>>,
  options: PagedListOptions<T> = {},
): PagedList<T> {
  const pageRef = ref(1)
  const sizeRef = ref(
    Math.max(1, Math.floor(options.initialPageSize ?? DEFAULT_PAGE_SIZE)),
  )
  const items = shallowRef<T[]>([])
  const total = ref(0)
  /** 追加模式下取回空页说明已到末尾，置位后不再重复请求同一段数据。 */
  const exhausted = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  let current: AbortController | undefined
  let generation = 0

  const limit = computed(() => sizeRef.value)
  const offset = computed(() => (pageRef.value - 1) * sizeRef.value)

  /** 追加模式下新旧两页按主键合并；没配 keyOf 时直接拼，调用方需自行保证记录不重复。 */
  function mergeItems(previous: T[], next: T[]): T[] {
    const keyOf = options.keyOf
    if (!keyOf) return [...previous, ...next]
    const seen = new Set(previous.map((item) => keyOf(item)))
    const merged = [...previous]
    for (const item of next) {
      const key = keyOf(item)
      if (seen.has(key)) continue
      seen.add(key)
      merged.push(item)
    }
    return merged
  }

  /**
   * 真正发一次请求：mode 决定结果替换还是追加。
   * 每次请求都换一代编号，旧请求即使晚到也会被丢弃。
   */
  async function run(
    requestLimit: number,
    requestOffset: number,
    mode: 'replace' | 'append',
  ): Promise<boolean> {
    current?.abort()
    const controller = new AbortController()
    current = controller
    const runId = ++generation
    loading.value = true
    error.value = null
    try {
      const result = await fetchPage(
        { limit: requestLimit, offset: requestOffset },
        controller.signal,
      )
      if (controller.signal.aborted || runId !== generation) return false
      items.value =
        mode === 'append' ? mergeItems(items.value, result.items) : result.items
      total.value = result.total
      return true
    } catch (failure) {
      if (controller.signal.aborted || runId !== generation) return false
      error.value = describe(failure)
      return false
    } finally {
      if (runId === generation) loading.value = false
    }
  }

  /** 取当前页（页码 × 每页条数换 offset），结果替换列表。 */
  async function loadCurrentPage(): Promise<void> {
    exhausted.value = false
    await run(sizeRef.value, offset.value, 'replace')
  }

  function describe(failure: unknown): string {
    if (options.describeError) return options.describeError(failure)
    return failure instanceof Error ? failure.message : '列表加载失败'
  }

  /**
   * 追加模式下重取已加载的整段数据：offset 固定从 0 开始，
   * limit 取「已加载页数 × 每页条数」并压到后端上限内。
   */
  async function reloadSpan(): Promise<void> {
    exhausted.value = false
    const span = pageRef.value * sizeRef.value
    const applied = await run(Math.min(span, MAX_PAGE_SPAN), 0, 'replace')
    // 请求失败时保留原数据与原页码，别让页码被旧条数带偏。
    if (!applied) return
    // 整段重取后条数可能变少（末尾几条被删掉），按实际条数校正页码，
    // 下一次滚动加载才不会跳过或重复记录。
    pageRef.value = Math.max(1, Math.ceil(items.value.length / sizeRef.value))
  }

  /** 已加载条数之外还有记录才需要继续取；追加到末尾后自然变成 false。 */
  const hasMore = computed(
    () => !exhausted.value && items.value.length < total.value,
  )

  /** 读取下一页：追加模式接在后面，普通模式按分页器语义切到该页。 */
  async function loadMore(): Promise<void> {
    if (loading.value || !hasMore.value) return
    const before = items.value.length
    pageRef.value += 1
    if (!options.append) {
      await loadCurrentPage()
      return
    }
    const applied = await run(sizeRef.value, offset.value, 'append')
    // 后端返回空页却仍报有总数时，说明确实没有下一页了；置位避免反复请求同一段。
    if (applied && items.value.length === before) exhausted.value = true
  }

  /**
   * 页码与每页条数用可写计算属性暴露：分页器改条数时会同时把页码设回 1，
   * 这里合并成一次请求，不会先按旧页码取一次再按新页码取一次。
   */
  const page = computed({
    get: () => pageRef.value,
    set: (value: number) => {
      const next = Math.max(1, Math.floor(value))
      if (next === pageRef.value) return
      pageRef.value = next
      void loadCurrentPage()
    },
  })

  const pageSize = computed({
    get: () => sizeRef.value,
    set: (value: number) => {
      const next = Math.max(1, Math.floor(value))
      if (next === sizeRef.value) return
      sizeRef.value = next
      pageRef.value = 1
      void loadCurrentPage()
    },
  })

  async function reload(): Promise<void> {
    if (!options.append) return loadCurrentPage()
    return reloadSpan()
  }

  async function reset(): Promise<void> {
    pageRef.value = 1
    await loadCurrentPage()
  }

  return {
    items,
    page,
    pageSize,
    total,
    limit,
    offset,
    loading,
    error,
    hasMore: computed(
      () => !exhausted.value && items.value.length < total.value,
    ),
    loadMore,
    reload,
    reset,
  }
}
