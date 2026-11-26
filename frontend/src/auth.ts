import { reactive } from 'vue'
import { getCurrentUser, type CurrentUser } from './api/knowledge'
import { clearCsrfToken, request } from './api/http'

/**
 * 会话模块：全站唯一的登录状态来源。
 * - `ensureSession` 幂等：路由守卫与 App 壳都会调用，只请求一次 /auth/me。
 * - 登录必须带一次性图形验证码（GET /auth/captcha），服务端校验通过才进入密码验证。
 * - 企业登录走后端 OIDC 重定向（/oauth2/authorization/enterprise），前端不做任何凭据处理。
 */
interface SessionState {
  user: CurrentUser | null
  loaded: boolean
}

const state = reactive<SessionState>({ user: null, loaded: false })
let pending: Promise<CurrentUser | null> | null = null

export function session(): { user: CurrentUser | null; loaded: boolean } {
  return state
}

export function isAdmin(): boolean {
  return state.user?.roles.includes('SYSTEM_ADMIN') ?? false
}

/** 首次调用拉取 /auth/me；401 视为未登录（返回 null），不抛错。 */
export function ensureSession(): Promise<CurrentUser | null> {
  if (state.loaded) {
    return Promise.resolve(state.user)
  }
  if (!pending) {
    pending = getCurrentUser(false)
      .then((user) => {
        state.user = user
        return user
      })
      .catch(() => {
        state.user = null
        return null
      })
      .finally(() => {
        state.loaded = true
        pending = null
      })
  }
  return pending
}

/** 登录图形验证码：captchaId 是服务端一次性题目标识，image 可直接作为 img 的 src。 */
export interface CaptchaChallenge {
  captchaId: string
  image: string
}

/** 取一枚登录验证码；一次性使用，登录失败或过期后都要重新获取。 */
export function fetchCaptcha(): Promise<CaptchaChallenge> {
  return request<CaptchaChallenge>('/api/v1/auth/captcha')
}

/** 企业单点登录：交给后端与 IdP，成功后回到前端根路径。 */
export function loginViaSso(): void {
  window.location.assign('/oauth2/authorization/enterprise')
}

/** 本地账号密码登录：验证码由服务端校验，成功后写入全局会话状态。 */
export async function login(
  username: string,
  password: string,
  captcha: { captchaId: string; captchaCode: string },
): Promise<CurrentUser> {
  const payload = await request<CurrentUser>('/api/v1/auth/login', {
    method: 'POST',
    body: { username, password, ...captcha },
  })
  state.user = payload
  state.loaded = true
  return payload
}

/** 登出：清会话与开发身份，随后由调用方跳转登录页。 */
export async function logout(): Promise<void> {
  await request('/api/v1/auth/logout', { method: 'POST' }).catch(() => {
    // 会话已失效也继续本地清理，保证界面回到登录页。
  })
  clearCsrfToken()
  localStorage.removeItem('aiqa.devSubject')
  state.user = null
  state.loaded = false
}
