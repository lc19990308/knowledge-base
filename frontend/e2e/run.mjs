import assert from 'node:assert/strict'
import console from 'node:console'
import { randomBytes, randomUUID } from 'node:crypto'
import { appendFile, mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import process, { env, versions } from 'node:process'
import { URL, pathToFileURL } from 'node:url'
import { runAdminSetup, runAssistantSetup, runStatistics } from './admin.mjs'
import {
  runAnswerBoundaries,
  runAnswers,
  runStreamingCheck,
  verifyConversationUi,
} from './assistant.mjs'
import { runKnowledge } from './knowledge.mjs'
import { captchaCode, poll, startStack } from './stack.mjs'
import {
  api,
  browserRequest,
  button,
  loadFixtures,
  prefix,
  visible,
} from './support.mjs'

const runId = randomUUID().slice(0, 8)
const reportDir = await mkdtemp(join(tmpdir(), `aiqa-front-e2e-${runId}-`))
const records = []
const events = []
const routes = new Set()
const managedRoutes = ['/settings', '/assistant-config', '/answer-templates']
const accounts = Object.fromEntries(
  [
    ['admin', '系统管理员'],
    ['maint', '资料维护员'],
    ['reader', '授权读者'],
    ['outsider', '范围外读者'],
  ].map(([key, name]) => [
    key,
    {
      username: `e2e_${key}_${runId}`,
      password: randomBytes(24).toString('base64url'),
      displayName: key === 'admin' ? name : `${name} ${runId}`,
    },
  ]),
)

function safeMessage(error) {
  let text = (error instanceof Error ? error.message : String(error)).slice(
    0,
    2200,
  )
  for (const account of Object.values(accounts))
    text = text.replaceAll(account.password, '[测试密码已隐藏]')
  return text
}

async function record(item) {
  records.push(item)
  await appendFile(
    join(reportDir, 'cases.jsonl'),
    `${JSON.stringify(item)}\n`,
    { mode: 0o600 },
  )
  console.log(
    `${item.result} ${item.id} ${item.title}${item.error ? ` · ${item.error.split('\n')[0]}` : ''}`,
  )
}

async function step(id, title, page, fn) {
  const time = Date.now()
  const firstEvent = events.length
  const details = () => ({
    id,
    title,
    elapsedMs: Date.now() - time,
    route: page ? new URL(page.url()).pathname : null,
    http: events
      .slice(firstEvent)
      .filter((event) => event.method !== 'GET' || event.status >= 400)
      .slice(-24),
  })
  try {
    const evidence = await fn()
    await record({ ...details(), result: 'PASS', evidence })
    return evidence
  } catch (error) {
    let screenshot
    if (page) {
      const file = `${id.replace(/[^a-z0-9-]/gi, '_')}.png`
      try {
        await page.screenshot({
          path: join(reportDir, file),
          mask: [page.locator('input[type=password]')],
          timeout: 3000,
        })
        screenshot = file
      } catch {
        // 页面关闭或崩溃时仍保留失败与网络元数据。
      }
    }
    await record({
      ...details(),
      result: 'FAIL',
      error: safeMessage(error),
      screenshot,
    })
    return undefined
  }
}

function observe(page) {
  page.on('framenavigated', (frame) => {
    if (
      frame === page.mainFrame() &&
      frame.url().startsWith('http://127.0.0.1:')
    )
      routes.add(new URL(frame.url()).pathname)
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (path.startsWith(prefix))
      events.push({
        path,
        method: response.request().method(),
        status: response.status(),
        requestId: response.headers()['x-request-id'],
      })
  })
  page.on('pageerror', (error) =>
    events.push({
      path: new URL(page.url()).pathname,
      error: safeMessage(error),
    }),
  )
}

async function signIn(browser, baseURL, key, target, checkInvalid = false) {
  const context = await browser.newContext({
    baseURL,
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 1000 },
  })
  const page = await context.newPage()
  page.setDefaultTimeout(9000)
  observe(page)
  const me = await step(
    `AUTH-${key}`,
    `账号密码登录并回跳 · ${key}`,
    page,
    async () => {
      await page.goto(target)
      await page.waitForURL(
        (url) =>
          url.pathname === '/login' &&
          url.searchParams.get('redirect') === target,
      )
      await visible(page.getByRole('heading', { name: '登录账号' }))
      assert.equal(
        await page.getByText(/开发身份/).count(),
        0,
        '正式构建不提供开发身份入口',
      )
      const account = accounts[key]
      await page.getByLabel('用户名', { exact: true }).fill(account.username)
      // 登录必须带图形验证码：隔离后端固定为 captchaCode，先确认图片已下发再填。
      const captchaInput = page.getByLabel('验证码', { exact: true })
      await visible(page.getByRole('img', { name: '图形验证码' }))
      await captchaInput.fill(captchaCode)
      if (checkInvalid) {
        // 验证码错误：服务端 400 INVALID_CAPTCHA，页面留在登录页并提示，密码不参与判断。
        await page
          .getByLabel('密码', { exact: true })
          .fill(randomBytes(18).toString('base64url'))
        await captchaInput.fill('AAAA')
        const formValidity = await page
          .locator('.login-form')
          .evaluate((form) => ({
            valid: form.checkValidity(),
            controls: [...form.elements].map((element) => ({
              name: element.getAttribute('name'),
              valid: element.validity.valid,
              flags: Object.entries(element.validity)
                .filter(([, value]) => typeof value === 'boolean' && value)
                .map(([name]) => name),
            })),
          }))
        assert.equal(
          formValidity.valid,
          true,
          `测试密码需满足 HTML 校验：${JSON.stringify(formValidity)}`,
        )
        const captchaRejected = await browserRequest(
          page,
          'POST',
          /\/auth\/login$/,
          () => page.getByLabel('密码', { exact: true }).press('Enter'),
          400,
        )
        assert.equal(captchaRejected.data.code, 'INVALID_CAPTCHA')
        await visible(page.getByText(/验证码不正确或已失效/))
        assert.equal(new URL(page.url()).pathname, '/login')
        // 失败后前端会自动换题：重新填正确验证码 + 错误密码，这次是 401。
        await captchaInput.fill(captchaCode)
        const rejected = await browserRequest(
          page,
          'POST',
          /\/auth\/login$/,
          () => page.getByLabel('密码', { exact: true }).press('Enter'),
          401,
        )
        await visible(page.getByRole('alert'))
        assert.equal(new URL(page.url()).pathname, '/login')
        assert.equal(rejected.data.code, 'INVALID_CREDENTIALS')
        // 再换一张题，为最后一次正确登录做准备。
        await captchaInput.fill(captchaCode)
      }
      await page.getByLabel('密码', { exact: true }).fill(account.password)
      const loggedIn = await browserRequest(
        page,
        'POST',
        /\/auth\/login$/,
        () => page.getByLabel('密码', { exact: true }).press('Enter'),
        200,
      )
      await page.waitForURL((url) => url.pathname === target)
      const current = await api({ context }, '/auth/me')
      assert.equal(current.data.displayName, account.displayName)
      assert.deepEqual(
        [...current.data.roles].sort(),
        key === 'admin' ? ['SYSTEM_ADMIN', 'USER'] : ['USER'],
      )
      return { ...current.data, loginRequestId: loggedIn.requestId }
    },
  )
  if (me) return { page, context, me }
  await context.close()
  return undefined
}

async function checkShell({ admin, reader, outsider }) {
  await step(
    'AUTH-RBAC',
    '普通账号访问管理页跳转 403，找不到的页面有返回入口',
    reader.page,
    async () => {
      const { page } = reader
      for (const path of managedRoutes) {
        await page.goto(path)
        await page.waitForURL('**/403')
        await visible(page.getByRole('heading', { name: '无权访问该页面' }))
      }
      const denied = await api(reader, '/settings/global', { status: 403 })
      assert.equal(denied.data.code, 'FORBIDDEN')
      await page.getByRole('link', { name: '返回工作台', exact: true }).click()
      await page.waitForURL((url) => url.pathname === '/')
      await page.goto('/missing-e2e-screen')
      await visible(page.getByText('找不到该页面', { exact: true }))
      return {
        managedRoutes,
        status: denied.status,
        requestId: denied.requestId,
      }
    },
  )

  await step(
    'WB-01',
    '工作台切换统计周期、刷新并打开快速入口',
    admin.page,
    async () => {
      const { page } = admin
      await page.goto('/')
      await visible(page.getByRole('heading', { name: '运营看板' }))
      await visible(page.getByRole('region', { name: '核心指标' }))
      const nextUsage = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          url.pathname === `${prefix}/usage` &&
          url.searchParams.get('period') === 'DAY'
        )
      })
      await button(page, /^统计周期：/).click()
      await page.getByRole('option', { name: '今日', exact: true }).click()
      assert.equal((await nextUsage).status(), 200)
      const refreshed = await browserRequest(
        page,
        'GET',
        /\/usage$/,
        () => button(page, '刷新').click(),
        200,
      )
      assert.equal(refreshed.data.period, 'DAY')
      assert.equal(refreshed.data.timeZone, 'Asia/Shanghai')
      await page
        .getByRole('navigation', { name: '快捷入口' })
        .getByRole('link', { name: '知识库', exact: true })
        .click()
      await page.waitForURL('**/knowledge')
      await visible(page.getByRole('heading', { name: '知识空间' }))
      return { period: refreshed.data.period, requestId: refreshed.requestId }
    },
  )

  await step(
    'WB-THEME',
    '浅色与深色主题切换持久化，并在窄屏保持导览',
    outsider.page,
    async () => {
      const { page } = outsider
      await page.goto('/')
      await button(page, '深色主题').click()
      await page.reload()
      await page.waitForFunction(
        () => globalThis.document.documentElement.dataset.theme === 'dark',
      )
      await button(page, '浅色主题').click()
      await page.waitForFunction(
        () => globalThis.document.documentElement.dataset.theme === 'light',
      )
      await page.setViewportSize({ width: 390, height: 844 })
      try {
        await visible(page.getByRole('heading', { name: '运营看板' }))
        const width = await page.evaluate(() => ({
          client: globalThis.document.documentElement.clientWidth,
          content: globalThis.document.documentElement.scrollWidth,
        }))
        assert(
          width.content <= width.client + 1,
          `窄屏横向溢出 ${JSON.stringify(width)}`,
        )
        return { theme: 'light', width }
      } finally {
        await page.setViewportSize({ width: 1440, height: 1000 })
      }
    },
  )
}

async function checkWorkbenchAndLogout(actor, step) {
  await step(
    'WB-02',
    '顶栏快捷提问只预填，发送前没有新增会话',
    actor.page,
    async () => {
      const { page } = actor
      await page.goto('/')
      const term = '成都差旅住宿需要哪些凭证？'
      const count = (await api(actor, '/conversations')).data.total
      const quick = page.getByRole('searchbox', { name: '快捷提问' })
      await quick.fill(term)
      await quick.press('Enter')
      await page.waitForURL(
        (url) => url.pathname === '/assistant' && !url.searchParams.has('q'),
      )
      const field = page.getByRole('textbox', { name: '提问内容' })
      await poll(
        () => field.inputValue(),
        (value) => value === term,
        { label: '快捷提问预填' },
      )
      assert.equal((await api(actor, '/conversations')).data.total, count)
      return { prefilled: term, conversations: count }
    },
  )
  await step(
    'AUTH-LOGOUT',
    '退出登录后会话失效，访问受保护页面回到登录',
    actor.page,
    async () => {
      const { page } = actor
      await page.goto('/knowledge')
      await button(page, /^退出登录（当前/)
        .filter({ visible: true })
        .click()
      const dialog = page.getByRole('dialog', { name: '退出登录', exact: true })
      const loggedOut = await browserRequest(
        page,
        'POST',
        /\/auth\/logout$/,
        () => button(dialog, '退出登录').click(),
        204,
      )
      await page.waitForURL('**/login')
      const denied = await api(actor, '/auth/me', { status: 401 })
      await page.goto('/knowledge')
      await page.waitForURL(
        (url) =>
          url.pathname === '/login' &&
          url.searchParams.get('redirect') === '/knowledge',
      )
      assert.equal(denied.data.code, 'UNAUTHENTICATED')
      return { logout: loggedOut.status, session: denied.status }
    },
  )
}

/**
 * 工作台文档检索：范围由助手绑定决定，页面没有空间选择器。
 * 正向检索必须从输入关键词一路走到文档预览，负向检索只给空结果说明。
 */
async function checkWorkbenchSearch(reader, step, assistant) {
  const searchBox = (page) =>
    page.getByRole('searchbox', { name: '搜索关键词', exact: true })
  await step(
    'WB-SEARCH',
    '工作台按助手绑定范围检索正文并打开文档预览',
    reader.page,
    async () => {
      const { page } = reader
      await page.goto('/')
      await visible(page.getByRole('heading', { name: '运营看板' }))
      const input = searchBox(page)
      await visible(input)
      // 先等检索卡片加载出范围，再决定要不要换助手；否则可能在校验默认助手。
      const region = page.getByRole('region', { name: '文档检索' })
      await visible(region)
      // 用元素而不是文本正则判断"卡片已加载"：范围文本带缩进，锚定正则会被空白挡住。
      await region.locator('.dash-search-scope').waitFor({ state: 'visible' })
      // 检索范围跟着助手走，先在页面上选中本轮建好的助手。
      const picker = region.getByRole('button', { name: /^检索助手：/ })
      if (await picker.isVisible()) {
        await picker.click()
        const listbox = page.getByRole('listbox', { name: '检索助手' })
        await listbox.waitFor({ state: 'visible' })
        const options = await listbox.getByRole('option').allInnerTexts()
        const index = options.findIndex((text) => text.includes(assistant.name))
        assert(
          index >= 0,
          `助手列表缺少 ${assistant.name}：${JSON.stringify(options)}`,
        )
        await listbox.getByRole('option').nth(index).click()
      }
      await page
        .waitForFunction(
          (name) =>
            globalThis.document
              .querySelector('.dash-search-scope')
              ?.textContent?.includes(name) ?? false,
          assistant.name,
          { timeout: 10_000 },
        )
        .catch(async () => {
          const current = await region
            .locator('.dash-search-scope')
            .innerText()
            .catch(() => '（无范围文本）')
          throw new Error(
            `检索范围未切换到 ${assistant.name}，当前为 ${current}`,
          )
        })
      await input.fill('费用分摊')
      const answered = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          url.pathname.endsWith('/search') &&
          url.searchParams.get('q') === '费用分摊'
        )
      })
      try {
        await page.getByRole('button', { name: /^检索$/ }).click()
      } catch (error) {
        // 点击失败时先收掉等待，避免留下未处理的 Promise 拒绝打断整轮。
        answered.catch(() => {})
        throw error
      }
      assert.equal((await answered).status(), 200)
      const hit = page
        .getByRole('link', { name: /FIN-TR-017_差旅报销办法_v2[.]1/ })
        .first()
      await visible(hit)
      await hit.click()
      await page.waitForURL(/\/documents\//)
      await visible(page.getByText(/费用分摊方式/))
      const url = new URL(page.url())
      return {
        query: '费用分摊',
        documentId: url.pathname.split('/').at(-1),
        space: url.searchParams.get('space'),
      }
    },
  )
  await step(
    'WB-SEARCH-EMPTY',
    '工作台检索无匹配时明确说明没有结果',
    reader.page,
    async () => {
      const { page } = reader
      await page.goto('/')
      const input = searchBox(page)
      await visible(input)
      await input.fill('绒冠蜥保育周期')
      await page.getByRole('button', { name: /^检索$/ }).click()
      await visible(
        page.getByText('没有检索到匹配的文件名或正文。', { exact: true }),
      )
      return { query: '绒冠蜥保育周期' }
    },
  )
}

const saveJson = (name, value) =>
  writeFile(join(reportDir, name), `${JSON.stringify(value, null, 2)}\n`, {
    mode: 0o600,
  })
console.log(`E2E 报告目录 ${reportDir}`)
let stack
let browser
let browserVersion
let manifestInfo
let quality = []
let fatal
try {
  const { files, manifests } = await loadFixtures()
  manifestInfo = manifests
  const modulePath = env.E2E_PLAYWRIGHT_MODULE
  assert(
    !modulePath || isAbsolute(modulePath),
    'E2E_PLAYWRIGHT_MODULE 需要已安装包入口的绝对路径',
  )
  const playwright = await import(
    modulePath ? pathToFileURL(modulePath).href : 'playwright'
  )
  stack = await startStack(reportDir, accounts)
  browser = await playwright.chromium.launch({
    channel: env.E2E_BROWSER_CHANNEL ?? 'chrome',
    headless: true,
    args: ['--no-proxy-server'],
  })
  browserVersion = browser.version()
  const { baseURL, sql } = stack
  const admin = await signIn(browser, baseURL, 'admin', '/knowledge', true)
  assert(admin, '管理员登录未通过，依赖该角色的业务场景未执行。')
  await runAdminSetup({ admin, api, step, runId, accounts })
  const maint = await signIn(browser, baseURL, 'maint', '/knowledge')
  const reader = await signIn(browser, baseURL, 'reader', '/assistant')
  const outsider = await signIn(browser, baseURL, 'outsider', '/')
  assert(
    maint && reader && outsider,
    '所需普通账号登录未通过，后续多角色场景未执行。',
  )
  const actors = { admin, maint, reader, outsider }
  await checkShell(actors)
  const spaces = await runKnowledge({ ...actors, step, runId, fixtures: files })
  await saveJson('spaces.json', spaces)
  if (env.E2E_EMBEDDING === '1') {
    await step('EMB-01', '文本块写入嵌入向量', reader.page, async () => {
      const total = Number(await sql('SELECT count(*) FROM document_chunk'))
      const withVector = Number(
        await sql(
          'SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL',
        ),
      )
      assert(total > 0, '没有可索引的文本块')
      assert.equal(
        withVector,
        total,
        `向量覆盖不完整：${withVector}/${total}，嵌入服务可能未生效`,
      )
      const dims = Number(
        await sql(
          'SELECT array_length(embedding, 1) FROM document_chunk WHERE embedding IS NOT NULL LIMIT 1',
        ),
      )
      assert(dims >= 256, `向量维度异常：${dims}`)
      return { chunks: total, withVector, dims }
    })
  } else {
    await step(
      'EMB-OFF',
      '未启用嵌入时文本块保持无向量',
      reader.page,
      async () => {
        const withVector = Number(
          await sql(
            'SELECT count(*) FROM document_chunk WHERE embedding IS NOT NULL',
          ),
        )
        assert.equal(withVector, 0, '默认应关闭向量召回')
        return { withVector }
      },
    )
  }
  const { assistants } = await runAssistantSetup({
    admin,
    api,
    step,
    runId,
    spaces,
  })
  let realModel = null
  if (env.E2E_REAL_MODEL === '1') {
    realModel = await step(
      'AD03-REAL',
      '接入真实模型网关并设为默认',
      admin.page,
      async () => {
        const baseUrl = env.E2E_MODEL_BASE_URL
        const modelName = env.E2E_MODEL_NAME
        assert(
          baseUrl && modelName,
          '真实模型需要 E2E_MODEL_BASE_URL 与 E2E_MODEL_NAME',
        )
        const alias = `E2E ${runId} 真实模型`
        const created = await api(admin, '/model-configs', {
          method: 'POST',
          status: 201,
          data: {
            alias,
            protocol: env.E2E_MODEL_PROTOCOL ?? 'openai_compatible',
            baseUrl,
            modelName,
            credentialRef: env.E2E_MODEL_CREDENTIAL_REF ?? 'magpie',
            enabled: true,
          },
        })
        const id = created.data.id
        assert(id, '缺少真实模型配置 ID')
        const current = await api(admin, '/settings/global')
        const etag = current.headers.etag
        assert(etag, '缺少全局设置 ETag')
        const saved = await api(admin, '/settings/global', {
          method: 'PUT',
          headers: { 'If-Match': etag },
          data: {
            defaultModelId: id,
            templateId: current.data.templateId,
            contextLimit: current.data.contextLimit,
            usageReportEnabled: current.data.usageReportEnabled,
          },
        })
        assert.equal(saved.data.defaultModelId, id)
        return { id, alias, modelName, baseUrl }
      },
    )
  }
  await checkWorkbenchSearch(reader, step, assistants.normal)
  const defaultModelId = (await api(admin, '/settings/global')).data
    .defaultModelId
  const modelOptions = (await api(reader, '/model-configs/options')).data.items
  if (realModel) {
    assert.equal(defaultModelId, realModel.id, '默认模型应为本次接入的真实模型')
    assert(
      modelOptions.some((item) => item.alias === realModel.alias),
      '普通用户的可选模型里应包含真实模型',
    )
  } else {
    assert.equal(defaultModelId, null, '本轮只使用本地 FakeAnswerModel')
    assert.equal(modelOptions.length, 0, '本轮模型列表应为空')
  }
  const answers = await runAnswers({
    actors,
    assistants,
    spaces,
    step,
    sql,
    fixtures: files,
    saveQuality: async (scored) => {
      quality = scored
      await saveJson('quality.json', scored)
    },
  })
  await verifyConversationUi({ actor: reader, assistants, step })
  // 增量流必须在撤回类边界用例之前跑：那之后 normal 空间已被撤回，问题只剩「无依据」。
  await runStreamingCheck({ reader, assistants, step, reportDir })
  await runStatistics({
    admin,
    reader,
    api,
    step,
    questionIds: answers.questionIds,
  })
  await runAnswerBoundaries({ actors, assistants, spaces, step, answers, sql })
  await checkWorkbenchAndLogout(outsider, step)
} catch (error) {
  fatal = safeMessage(error)
  console.error(fatal)
} finally {
  await browser?.close()
  await stack?.close()
  const counts = (values) =>
    Object.fromEntries(
      ['PASS', 'FAIL', 'SKIP', 'BLOCKED'].map((tag) => [
        tag,
        values.filter(({ result }) => result === tag).length,
      ]),
    )
  const summary = {
    runId,
    finishedAt: new Date().toISOString(),
    timeZone: 'Asia/Shanghai',
    node: versions.node,
    browser: browserVersion,
    model:
      env.E2E_REAL_MODEL === '1'
        ? `真实模型 ${env.E2E_MODEL_NAME ?? ''}（${env.E2E_MODEL_BASE_URL ?? ''}）`.trim()
        : 'FakeAnswerModel',
    fixtureManifests: manifestInfo,
    routes: [...routes].sort(),
    flow: counts(records),
    quality: counts(quality),
    fatal,
  }
  await saveJson('summary.json', summary)
  await saveJson('browser-events.json', events)
  console.log(JSON.stringify(summary))
  const failed = [...records, ...quality].some(({ result }) =>
    ['FAIL', 'BLOCKED'].includes(result),
  )
  process.exitCode = fatal || failed ? 1 : 0
}
