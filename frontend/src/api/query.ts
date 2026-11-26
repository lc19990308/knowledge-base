/** 列表查询参数的取值类型：空值（undefined / 空串）不参与拼接。 */
export type QueryValue = string | number | boolean | undefined

/**
 * 把筛选与分页参数拼成查询串。
 *
 * 空值不发送，省略 limit 时接口返回全部记录；知识库与数据清洗等列表接口共用这一份实现，
 * 避免每个 API 模块各写一遍同样的 URLSearchParams 逻辑。
 */
export function listQuery(entries: Record<string, QueryValue>): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(entries)) {
    if (value === undefined || value === '') continue
    params.set(key, String(value))
  }
  const query = params.toString()
  return query === '' ? '' : `?${query}`
}
