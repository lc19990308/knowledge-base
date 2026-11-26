<script setup lang="ts">
import { FORM_LIMITS, NON_BLANK_PATTERN } from '../formValidation'
import FormInput from '../components/FormInput.vue'
import { computed, onMounted, ref } from 'vue'
import { Eye, EyeOff, RefreshCw } from '@lucide/vue'
import { useRoute, useRouter } from 'vue-router'
import { ensureSession, fetchCaptcha, login } from '../auth'
import { isApiError } from '../api/http'
import { describeError } from '../api/display'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const captchaCode = ref('')
/** 当前这枚验证码的服务端标识；每次失败都会换新，旧题随即作废。 */
const captchaId = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

/** 密码框是否显示明文，由眼睛按钮切换；默认保持密文。 */
const showPassword = ref(false)
/** 由开关派生输入框类型，避免再用 watch 手工同步两份状态。 */
const passwordType = computed<'password' | 'text'>(() =>
  showPassword.value ? 'text' : 'password',
)

function redirectTarget(): string {
  return typeof route.query.redirect === 'string' ? route.query.redirect : '/'
}

function describe(failure: unknown): string {
  if (isApiError(failure, 429, 'RATE_LIMITED')) {
    return describeError(failure, '失败次数过多，请稍后再试。')
  }
  return describeError(failure)
}

/** 取一枚新验证码并清空输入；失败时抛出，由调用方决定提示方式。 */
async function loadCaptcha(): Promise<void> {
  captchaLoading.value = true
  try {
    const challenge = await fetchCaptcha()
    captchaId.value = challenge.captchaId
    captchaImage.value = challenge.image
    captchaCode.value = ''
  } finally {
    captchaLoading.value = false
  }
}

/** 用户主动换一张（点图或按钮）；此时没有别的错误要保留，失败直接提示。 */
async function refreshCaptcha(): Promise<void> {
  error.value = null
  try {
    await loadCaptcha()
  } catch (failure) {
    captchaId.value = ''
    captchaImage.value = ''
    error.value = describe(failure)
  }
}

onMounted(async () => {
  const user = await ensureSession()
  if (user) {
    await router.replace(redirectTarget())
    return
  }
  await refreshCaptcha()
})

async function submitLogin(): Promise<void> {
  const name = username.value.trim()
  const secret = password.value
  const code = captchaCode.value.trim()
  if (busy.value) return
  if (
    !name ||
    name.length > FORM_LIMITS.usernameMax ||
    !secret ||
    secret.length > FORM_LIMITS.passwordMax
  ) {
    error.value =
      '请填写用户名和密码，用户名最多 64 个字符，密码最多 72 个字符。'
    return
  }
  if (!captchaId.value || code.length !== FORM_LIMITS.captcha) {
    error.value = '请输入图片中的 4 位验证码。'
    return
  }
  busy.value = true
  error.value = null
  try {
    await login(name, secret, {
      captchaId: captchaId.value,
      captchaCode: code,
    })
    await router.replace(redirectTarget())
  } catch (failure) {
    error.value = describe(failure)
    // 验证码一次有效：失败后必须换一张，否则下一次提交必然还是失效码。
    await loadCaptcha().catch(() => {
      captchaId.value = ''
      captchaImage.value = ''
    })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <aside class="brand-panel" aria-hidden="true">
      <div class="brand-top">
        <span class="brand-tile">问</span>
        <span class="brand-name">AI 智能问答平台</span>
      </div>

      <div class="brand-hero">
        <h1>基于已审核的<br />政策资料提问</h1>
        <p class="hero-sub">
          答案标注依据与政策有效性；引用可定位到文档；未审与已撤回内容不出现在回答中。
        </p>
        <ul class="feature-list">
          <li>知识空间审核发布，撤回即失效</li>
          <li>回答逐段标注依据，引用可溯源</li>
          <li>权限按空间授权，管理员统一管控</li>
          <li>用量按用户统计，北京时间对账</li>
        </ul>
      </div>

      <p class="brand-foot">企业知识 · 审核发布 · 溯源问答</p>
      <span class="watermark">问</span>
    </aside>

    <main class="form-panel">
      <div class="form-box">
        <div class="mobile-brand">
          <span class="brand-tile small">问</span>
          <span class="brand-name">AI 智能问答平台</span>
        </div>

        <h2 class="form-title">登录账号</h2>
        <p class="form-sub">使用管理员分配的账号密码登录平台</p>

        <p v-if="error" class="bk-notice bk-notice-fail" role="alert">
          {{ error }}
        </p>
        <form class="login-form" @submit.prevent="submitLogin">
          <div class="bk-field">
            <label for="login-username">用户名</label>
            <FormInput
              id="login-username"
              v-model="username"
              :pattern="NON_BLANK_PATTERN"
              :maxlength="FORM_LIMITS.usernameMax"
              required
              autocomplete="username"
              placeholder="例如：admin"
              :disabled="busy"
            />
          </div>
          <div class="bk-field">
            <label for="login-password">密码</label>
            <div class="password-field">
              <FormInput
                id="login-password"
                v-model="password"
                class="password-input"
                :maxlength="FORM_LIMITS.passwordMax"
                required
                :type="passwordType"
                autocomplete="current-password"
                placeholder="请输入密码"
                :disabled="busy"
              />
              <button
                class="password-toggle"
                type="button"
                :disabled="busy"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" :size="18" aria-hidden="true" />
                <Eye v-else :size="18" aria-hidden="true" />
              </button>
            </div>
          </div>
          <div class="bk-field">
            <label for="login-captcha">验证码</label>
            <div class="captcha-field">
              <FormInput
                id="login-captcha"
                v-model="captchaCode"
                class="captcha-input"
                :maxlength="FORM_LIMITS.captcha"
                required
                autocomplete="off"
                autocapitalize="characters"
                spellcheck="false"
                placeholder="输入图中 4 位字符"
                :disabled="busy"
              />
              <button
                class="captcha-image"
                type="button"
                :disabled="busy || captchaLoading"
                title="看不清？点击换一张"
                aria-label="换一张验证码"
                @click="refreshCaptcha"
              >
                <img
                  v-if="captchaImage"
                  :src="captchaImage"
                  alt="图形验证码"
                  width="132"
                  height="44"
                />
                <span v-else class="captcha-placeholder">
                  <RefreshCw
                    v-if="!captchaLoading"
                    :size="16"
                    aria-hidden="true"
                  />
                  {{ captchaLoading ? '加载中…' : '点此加载' }}
                </span>
              </button>
            </div>
          </div>
          <button
            class="bk-btn bk-btn-primary login-btn"
            type="submit"
            :disabled="busy || !captchaImage"
          >
            {{ busy ? '登录中…' : '登 录' }}
          </button>
        </form>
      </div>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100dvh;
  display: grid;
  grid-template-columns: minmax(420px, 46%) 1fr;
}

/* ---------- 左侧品牌侧板：主色由 token 派生，不用新裸色值 ---------- */
.brand-panel {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 48px 56px;
  color: var(--color-surface);
  background:
    radial-gradient(
      circle at 18% 12%,
      color-mix(in oklab, var(--color-surface), transparent 90%),
      transparent 46%
    ),
    radial-gradient(
      circle at 88% 86%,
      color-mix(in oklab, var(--color-surface), transparent 88%),
      transparent 42%
    ),
    linear-gradient(
      160deg,
      color-mix(in oklab, var(--color-action), black 26%) 0%,
      var(--color-action) 58%,
      color-mix(in oklab, var(--color-action), white 14%) 100%
    );
}

.brand-top {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-tile {
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: color-mix(in oklab, var(--color-surface), transparent 88%);
  border: 1px solid color-mix(in oklab, var(--color-surface), transparent 72%);
  display: grid;
  place-items: center;
  font-size: 22px;
  font-weight: 700;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 22%);
}

.brand-name {
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: var(--tracking-display);
}

.brand-hero {
  margin-top: auto;
  margin-bottom: auto;
  max-width: 30rem;
}

.brand-hero h1 {
  font-family: var(--font-display);
  font-size: var(--text-3xl);
  line-height: 1.25;
  font-weight: 700;
  letter-spacing: var(--tracking-display);
}

.hero-sub {
  margin-top: var(--space-4);
  font-size: var(--text-sm);
  line-height: 1.8;
  color: color-mix(in oklab, var(--color-surface), transparent 22%);
}

.feature-list {
  margin: var(--space-6) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--space-3);
}

.feature-list li {
  position: relative;
  padding-left: 22px;
  font-size: var(--text-sm);
  color: color-mix(in oklab, var(--color-surface), transparent 14%);
}

.feature-list li::before {
  content: '';
  position: absolute;
  left: 0;
  top: 7px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: color-mix(in oklab, var(--color-surface), transparent 40%);
  box-shadow: 0 0 0 3px
    color-mix(in oklab, var(--color-surface), transparent 88%);
}

.brand-foot {
  font-size: var(--text-xs);
  letter-spacing: 0.08em;
  color: color-mix(in oklab, var(--color-surface), transparent 42%);
}

.watermark {
  position: absolute;
  right: -36px;
  bottom: -72px;
  font-family: var(--font-display);
  font-size: 280px;
  line-height: 1;
  font-weight: 700;
  color: color-mix(in oklab, var(--color-surface), transparent 94%);
  pointer-events: none;
  user-select: none;
}

/* ---------- 右侧登录表单 ---------- */
.form-panel {
  display: grid;
  place-items: center;
  padding: var(--space-8) 20px;
  background: var(--color-surface);
}

.form-box {
  width: min(100%, 400px);
  display: grid;
  gap: var(--space-4);
}

.mobile-brand {
  display: none;
  align-items: center;
  gap: 12px;
  margin-bottom: var(--space-2);
}

.form-title {
  font-family: var(--font-display);
  font-size: var(--text-2xl);
  font-weight: 700;
  letter-spacing: var(--tracking-display);
}

.form-sub {
  margin-top: -8px;
  font-size: var(--text-sm);
  color: var(--color-text-muted);
}

.login-form {
  display: grid;
  gap: var(--space-3);
}

/* ---------- 密码框：右侧眼睛按钮切换明文 ---------- */
.password-field {
  position: relative;
  display: block;
  /* 按钮直径与输入框右侧留位同源，避免两处裸值各自漂移 */
  --password-toggle-size: 30px;
}

.password-input {
  padding-right: calc(var(--password-toggle-size) + var(--space-3));
}

.password-toggle {
  position: absolute;
  top: 50%;
  right: 5px;
  display: grid;
  place-items: center;
  width: var(--password-toggle-size);
  height: var(--password-toggle-size);
  padding: 0;
  border: 0;
  border-radius: var(--radius-control);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transform: translateY(-50%);
  transition: color var(--motion-fast) var(--ease-standard);
}

.password-toggle:hover:not(:disabled) {
  color: var(--color-text);
}

.password-toggle:focus-visible {
  outline: none;
  color: var(--color-text);
  box-shadow: var(--shadow-focus);
}

.password-toggle:disabled {
  opacity: var(--form-disabled-opacity);
  cursor: not-allowed;
}

/* ---------- 验证码：输入框 + 点图换一张 ---------- */
.captcha-field {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.captcha-input {
  flex: 1 1 auto;
  min-width: 0;
}

.captcha-image {
  flex: none;
  display: grid;
  place-items: center;
  /* 固定 132×44，和图片同尺寸：加载、换图时表单高度不跳动 */
  width: 132px;
  height: 44px;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
  overflow: hidden;
  transition: border-color var(--motion-fast) var(--ease-standard);
}

.captcha-image:hover:not(:disabled) {
  border-color: color-mix(in oklab, var(--color-border), var(--color-text) 18%);
}

.captcha-image:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.captcha-image:disabled {
  opacity: var(--form-disabled-opacity);
  cursor: not-allowed;
}

.captcha-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.captcha-placeholder {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-xs);
}

.login-btn {
  height: 46px;
  margin-top: var(--space-1);
  font-size: var(--text-base);
  letter-spacing: 0.35em;
  text-indent: 0.35em;
  box-shadow: 0 4px 14px
    color-mix(in oklab, var(--color-action) 35%, transparent);
}

/* ---------- 窄屏：品牌侧板收为顶部条 ---------- */
@media (max-width: 920px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    display: none;
  }

  .mobile-brand {
    display: flex;
  }

  .brand-tile.small {
    width: 36px;
    height: 36px;
    font-size: 18px;
    background: var(--color-action);
    color: var(--color-action-text);
    border: none;
  }

  .form-panel {
    background: var(--color-canvas);
  }

  .form-box {
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    box-shadow: var(--elev-raised);
    padding: var(--space-6);
  }
}
</style>
