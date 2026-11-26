export const FORM_LIMITS = {
  name: 120,
  department: 60,
  displayName: 60,
  usernameMin: 3,
  usernameMax: 64,
  passwordMin: 8,
  passwordMax: 72,
  modelAddress: 255,
  credentialRef: 255,
  question: 2000,
  reviewReason: 2000,
  search: 200,
  /** 登录图形验证码位数：与服务端下发的四位字符一致。 */
  captcha: 4,
  /** 清洗批次名与流转生成的文档名。 */
  batchName: 200,
  documentName: 120,
  contextMin: 1,
  contextMax: 100,
} as const

export const USERNAME_PATTERN = '[A-Za-z0-9][A-Za-z0-9_.\\-]{2,63}'
export const NON_BLANK_PATTERN = String.raw`.*\S.*`
const localUsername = new RegExp(`^(?:${USERNAME_PATTERN})$`)

export function validUsername(value: string): boolean {
  return localUsername.test(value)
}

export function validNewPassword(value: string): boolean {
  return (
    value.length >= FORM_LIMITS.passwordMin &&
    value.length <= FORM_LIMITS.passwordMax &&
    new TextEncoder().encode(value).length <= FORM_LIMITS.passwordMax
  )
}

export function validCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000-'))
    return false
  const date = new Date(`${value}T00:00:00Z`)
  return (
    Number.isFinite(date.getTime()) &&
    date.toISOString().startsWith(`${value}T`)
  )
}
