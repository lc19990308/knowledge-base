import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { URL, URLSearchParams } from 'node:url'

const UI_TIMEOUT = 10_000
const STATISTICS_ZONE = 'Asia/Shanghai'
const beijingDay = new Intl.DateTimeFormat('en-CA', {
  timeZone: STATISTICS_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})
const beijingTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: STATISTICS_ZONE,
  dateStyle: 'medium',
  timeStyle: 'short',
  hour12: false,
})
const QUESTION_STATUS = {
  QUEUED: '排队中',
  RUNNING: '生成中',
  SUCCEEDED: '已回答',
  FAILED: '处理失败',
}
const SCENARIOS = [
  [
    'normal',
    '普通及受限检索',
    ['normal', 'restricted'],
    '仅依据当前可见引用回答。先给结论，再列出依据。',
  ],
  [
    'historical',
    '历史政策',
    ['historical'],
    '将失效政策标明为历史信息，不得作为现行依据。',
  ],
  [
    'uncertain',
    '政策待核实',
    ['uncertain'],
    '说明哪些政策有效性待核实，不要将待核实的信息写成结论。',
  ],
  [
    'formats',
    '多格式资料',
    ['formats'],
    '合并来自不同格式的证据，逐项标注引用来源。',
  ],
  [
    'comparison',
    '现行与历史对照',
    ['normal', 'historical'],
    '对照现行与历史政策，分别注明版本、适用条件及引用依据。',
  ],
]

// 先订阅再触发，避免快速响应丢失。筛选场景还需匹配本次请求的查询参数。
async function throughUI(page, method, path, action, matches = () => true) {
  const [response] = await Promise.all([
    page.waitForResponse(
      (response) => {
        const url = new URL(response.url())
        return (
          url.pathname === `/api/v1${path}` &&
          response.request().method() === method &&
          matches(url.searchParams)
        )
      },
      { timeout: UI_TIMEOUT },
    ),
    action(),
  ])
  return response
}

async function choose(scope, label, option) {
  const trigger = scope.getByRole('button', { name: new RegExp(`^${label}：`) })
  // 弹出菜单监听外部滚动，先把触发器滚入视口，再打开菜单。
  await trigger.scrollIntoViewIfNeeded({ timeout: UI_TIMEOUT })
  await trigger.click({ timeout: UI_TIMEOUT })
  await scope
    .getByRole('listbox', { name: label, exact: true })
    .getByRole('option', { name: option, exact: true })
    .click({ timeout: UI_TIMEOUT })
  assert.equal(await trigger.getAttribute('aria-label'), `${label}：${option}`)
}

// 调用方负责启动隔离实例、注入 Playwright baseURL，并为每轮提供唯一的合成账号和空间。
export async function runAdminSetup({ admin, api, step, runId, accounts }) {
  const { page } = admin
  const users = {}

  for (const key of ['maint', 'reader', 'outsider']) {
    let fallbackEvidence
    await step(
      `AD01-${key}`,
      `通过系统设置创建普通账号 ${key}`,
      page,
      async () => {
        const account = accounts?.[key]
        assert.ok(account, `accounts.${key} 缺失`)
        const { username, displayName, password } = account
        assert.ok(
          typeof username === 'string' &&
            /^[a-z0-9][a-z0-9_.-]{2,63}$/i.test(username),
          `accounts.${key}.username 不满足格式`,
        )
        assert.ok(
          typeof displayName === 'string' &&
            displayName.trim() === displayName &&
            displayName.length > 0 &&
            displayName.length <= 60,
          `accounts.${key}.displayName 不满足格式`,
        )
        assert.ok(
          typeof password === 'string' &&
            password.length >= 8 &&
            Buffer.byteLength(password, 'utf8') <= 72,
          `accounts.${key}.password 不满足长度要求`,
        )
        const lookup = `/users?q=${encodeURIComponent(displayName)}`
        const { data: before } = await api(admin, lookup)
        assert.ok(
          !before.items.some((user) => user.displayName === displayName),
          `${displayName} 已存在，本轮显示名必须唯一`,
        )

        await page.goto('/settings')
        await page
          .getByRole('tab', { name: '成员与角色', exact: true })
          .click({ timeout: UI_TIMEOUT })
        await page
          .getByRole('button', { name: /新建账号$/ })
          .click({ timeout: UI_TIMEOUT })
        const dialog = page.getByRole('dialog', {
          name: '新建账号',
          exact: true,
        })
        await dialog.getByLabel('用户名', { exact: true }).fill(username)
        await dialog.getByLabel('显示名', { exact: true }).fill(displayName)
        await dialog.getByLabel('初始密码', { exact: true }).fill(password)
        assert.equal(
          await dialog
            .getByRole('switch', { name: '授予系统管理员', exact: true })
            .isChecked(),
          false,
        )
        const submit = dialog.getByRole('button', {
          name: '创建账号',
          exact: true,
        })
        let response
        try {
          response = await throughUI(page, 'POST', '/users', () =>
            submit.click({ timeout: UI_TIMEOUT }),
          )
        } catch (failure) {
          const alert = dialog.getByRole('alert')
          if (await alert.isVisible()) {
            fallbackEvidence = (await alert.innerText()).trim()
          } else if (await submit.isVisible()) {
            const invalid = await dialog
              .locator('form input:invalid')
              .evaluateAll((inputs) =>
                inputs.map(
                  (input) => `${input.id}：${input.validationMessage}`,
                ),
              )
            if (invalid.length) fallbackEvidence = invalid.join('，')
          }
          if (fallbackEvidence)
            assert.fail(`有效账号被创建界面拒绝：${fallbackEvidence}`)
          throw failure
        }
        if (response.status() !== 201) {
          const problem = await response.json().catch(() => ({}))
          const alert = dialog.getByRole('alert')
          await alert.waitFor({ state: 'visible', timeout: UI_TIMEOUT })
          const detail = `${response.status()} ${problem.code ?? '未知错误'} · ${(await alert.innerText()).trim()}`
          // 仅对有效输入的应用错误回填；账号冲突、认证失败、限流、依赖故障继续如实报告。
          if ([400, 422, 500].includes(response.status()))
            fallbackEvidence = detail
          assert.fail(`创建普通账号失败：${detail}`)
        }
        const { data: created } = await response.json()
        const { data: listed } = await api(admin, lookup)
        const persisted = listed.items.find(
          (user) => user.userId === created.userId,
        )
        assert.ok(persisted, '创建响应中的 userId 必须出现在成员查询中')
        assert.deepEqual(
          [
            persisted.displayName,
            persisted.issuer,
            persisted.enabled,
            persisted.roles,
            persisted.canCreateSpace,
          ],
          [displayName, 'local', true, ['USER'], false],
        )
        users[key] = {
          id: persisted.userId,
          userId: persisted.userId,
          username,
          displayName,
        }

        await page
          .getByText(
            `已创建本地账号「${username}」，该账号现在可以在登录页登录。`,
            { exact: true },
          )
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
        await page
          .getByRole('searchbox', { name: '搜索成员' })
          .fill(persisted.userId)
        const row = page.getByRole('row').filter({
          has: page
            .getByRole('cell')
            .filter({ has: page.getByText(displayName, { exact: true }) }),
        })
        await row
          .getByText(persisted.userId.slice(0, 8), { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        assert.deepEqual(
          (await row.getByRole('cell').allTextContents())
            .slice(1, 5)
            .map((cell) => cell.trim()),
          ['本地账号', '启用', '否', '普通用户'],
        )
        return {
          userId: persisted.userId,
          roles: persisted.roles,
          requestId: await response.headerValue('x-request-id'),
        }
      },
    )

    if (!users[key] && fallbackEvidence) {
      await step(
        `AD01-${key}-fallback`,
        `界面创建失败后衔接普通账号 ${key}`,
        page,
        async () => {
          const { username, displayName, password } = accounts[key]
          const { data: listed } = await api(
            admin,
            `/users?q=${encodeURIComponent(displayName)}`,
          )
          const existing = listed.items.filter(
            (user) => user.displayName === displayName,
          )
          assert.ok(existing.length <= 1, '显示名冲突，无法安全回填')
          const created =
            existing[0] ??
            (
              await api(admin, '/users', {
                method: 'POST',
                status: 201,
                data: { username, displayName, password, roles: ['USER'] },
              })
            ).data
          assert.ok(created.userId, '回填账号缺少 userId')
          assert.deepEqual(
            [
              created.displayName,
              created.issuer,
              created.enabled,
              created.roles,
              created.canCreateSpace,
            ],
            [displayName, 'local', true, ['USER'], false],
          )
          users[key] = {
            id: created.userId,
            userId: created.userId,
            username,
            displayName,
          }
          return {
            userId: created.userId,
            recoveredBy: existing.length ? 'lookup' : 'api',
            originalFailure: fallbackEvidence,
          }
        },
      )
    }
  }

  await step(
    'AD02',
    '全局引用上限和用量开关保存、刷新、还原',
    page,
    async () => {
      const path = '/settings/global'
      const { data: before } = await api(admin, path)
      assert.equal(
        before.defaultModelId,
        null,
        '隔离实例必须保留测试模型（全局模型未指定）',
      )
      await page.goto('/settings')
      const open = page.getByRole('button', {
        name: '编辑全局配置',
        exact: true,
      })
      const summary = page.getByRole('region', {
        name: '全局默认',
        exact: true,
      })
      await summary
        .getByText(`${before.contextLimit} 段 / 次`, { exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      await page
        .getByText('正在加载…', { exact: true })
        .waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
      const dialog = page.getByRole('dialog', { name: '全局配置', exact: true })
      const limit = dialog.getByRole('spinbutton', {
        name: '上下文引用上限（每次提问）',
      })
      const usage = dialog.getByRole('switch', {
        name: '用量上报',
        exact: true,
      })
      const save = dialog.getByRole('button', { name: '保存', exact: true })
      const nextLimit = before.contextLimit === 12 ? 13 : 12
      const nextUsage = !before.usageReportEnabled
      let changed = false
      try {
        await open.click({ timeout: UI_TIMEOUT })
        const zone = dialog.getByLabel('统计时区', { exact: true })
        assert.equal(await zone.inputValue(), 'Asia/Shanghai（北京时间）')
        assert.equal(await zone.isDisabled(), true)
        assert.equal(await limit.inputValue(), String(before.contextLimit))
        assert.equal(await usage.isChecked(), before.usageReportEnabled)
        await limit.fill(String(nextLimit))
        await usage.setChecked(nextUsage)
        const response = await throughUI(page, 'PUT', path, () => save.click())
        changed = response.ok()
        assert.equal(
          response.status(),
          200,
          `保存全局设置，requestId=${await response.headerValue('x-request-id')}`,
        )
        assert.equal(
          await response.request().headerValue('if-match'),
          `"${before.revision}"`,
        )
        await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
        const { data: after } = await api(admin, path)
        assert.deepEqual(
          [
            after.contextLimit,
            after.usageReportEnabled,
            after.defaultModelId,
            after.templateId,
          ],
          [nextLimit, nextUsage, null, before.templateId],
        )
        assert.equal(after.revision, before.revision + 1)
        await page.reload()
        await summary
          .getByText(`${nextLimit} 段 / 次`, { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        await summary
          .getByText(nextUsage ? '已启用' : '已关闭', { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      } finally {
        if (changed) {
          // 重进页面读取当前 ETag，即使保存后的某个断言失败，也能恢复测试前的配置。
          await page.goto('/settings')
          await summary
            .getByText(`${nextLimit} 段 / 次`, { exact: true })
            .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
          await open.click({ timeout: UI_TIMEOUT })
          await limit.fill(String(before.contextLimit))
          await usage.setChecked(before.usageReportEnabled)
          const response = await throughUI(page, 'PUT', path, () =>
            save.click(),
          )
          assert.equal(response.status(), 200, '恢复全局设置失败')
          await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
          const { data: restored } = await api(admin, path)
          assert.deepEqual(
            [
              restored.contextLimit,
              restored.usageReportEnabled,
              restored.defaultModelId,
              restored.templateId,
            ],
            [
              before.contextLimit,
              before.usageReportEnabled,
              null,
              before.templateId,
            ],
          )
        }
      }
    },
  )

  await step('AD03', '模型配置新增、启用、停用及可选项对账', page, async () => {
    const path = '/model-configs'
    assert.ok(typeof runId === 'string' && runId.trim(), 'runId 缺失')
    assert.equal(
      (await api(admin, '/settings/global')).data.defaultModelId,
      null,
      '隔离实例必须保留测试模型',
    )
    const alias = `E2E ${runId} 合成模型`
    const baseUrl = 'https://model.e2e.invalid/v1'
    const modelName = 'e2e-placeholder'
    const credentialRef = 'e2e:unconfigured'
    await page.goto('/settings')
    await page
      .getByText('正在加载…', { exact: true })
      .waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
    await page
      .getByRole('button', { name: /新建模型配置$/ })
      .click({ timeout: UI_TIMEOUT })
    const create = page.getByRole('dialog', {
      name: '新建模型配置',
      exact: true,
    })
    await create.getByLabel('模型别名', { exact: true }).fill(alias)
    await choose(create, '协议', 'Anthropic Messages 原生协议')
    await create.getByLabel('网关地址', { exact: true }).fill(baseUrl)
    await create.getByLabel('模型 ID', { exact: true }).fill(modelName)
    await create.getByLabel('凭据引用', { exact: true }).fill(credentialRef)
    await create
      .getByRole('switch', { name: '启用', exact: true })
      .setChecked(false)
    const response = await throughUI(page, 'POST', path, () =>
      create.getByRole('button', { name: '保存', exact: true }).click(),
    )
    assert.equal(
      response.status(),
      201,
      `新建合成模型，requestId=${await response.headerValue('x-request-id')}`,
    )
    const { data: created } = await response.json()
    assert.ok(created.id, '缺少合成模型 ID')
    await create.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
    const row = page
      .getByRole('row')
      .filter({ has: page.getByRole('cell', { name: alias, exact: true }) })
    await row
      .getByRole('cell', { name: '停用', exact: true })
      .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    assert.deepEqual(
      [
        created.alias,
        created.protocol,
        created.baseUrl,
        created.modelName,
        created.credentialRef,
        created.enabled,
        created.isDefault,
      ],
      [alias, 'anthropic', baseUrl, modelName, credentialRef, false, false],
    )
    assert.equal(
      (await api(admin, `${path}/options`)).data.items.some(
        (item) => item.id === created.id,
      ),
      false,
    )

    // 配置 CRUD 不请求网关；临时启用只验证目录可选项，地址与凭据均为合成占位。
    let enabled = false
    const edit = page.getByRole('dialog', { name: '编辑模型配置', exact: true })
    const editButton = row.getByRole('button', { name: '编辑', exact: true })
    try {
      await editButton.click({ timeout: UI_TIMEOUT })
      await edit
        .getByRole('switch', { name: '启用', exact: true })
        .setChecked(true)
      const result = await throughUI(page, 'PUT', `${path}/${created.id}`, () =>
        edit.getByRole('button', { name: '保存', exact: true }).click(),
      )
      enabled = result.ok()
      assert.equal(
        result.status(),
        200,
        `启用合成模型，requestId=${await result.headerValue('x-request-id')}`,
      )
      assert.equal(
        await result.request().headerValue('if-match'),
        `"${created.revision}"`,
      )
      await edit.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
      await row
        .getByRole('cell', { name: '启用', exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      const option = (await api(admin, `${path}/options`)).data.items.find(
        (item) => item.id === created.id,
      )
      assert.deepEqual(option, { id: created.id, alias, isDefault: false })
    } finally {
      if (enabled) {
        await edit.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
        await row
          .getByRole('cell', { name: '启用', exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        await editButton.click({ timeout: UI_TIMEOUT })
        await edit
          .getByRole('switch', { name: '启用', exact: true })
          .setChecked(false)
        const result = await throughUI(
          page,
          'PUT',
          `${path}/${created.id}`,
          () => edit.getByRole('button', { name: '保存', exact: true }).click(),
        )
        assert.equal(result.status(), 200, '停用合成模型失败')
        await edit.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
        await row
          .getByRole('cell', { name: '停用', exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        const { data } = await api(admin, path)
        const saved = data.items.find((item) => item.id === created.id)
        assert.deepEqual(
          [saved?.enabled, saved?.isDefault, saved?.revision],
          [false, false, created.revision + 2],
        )
        assert.equal(
          (await api(admin, `${path}/options`)).data.items.some(
            (item) => item.id === created.id,
          ),
          false,
        )
        assert.equal(
          (await api(admin, '/settings/global')).data.defaultModelId,
          null,
        )
      }
    }
  })

  await step(
    'AD03-matrix',
    '权限矩阵区分普通用户、维护者与系统管理员',
    page,
    async () => {
      await page.goto('/settings')
      await page
        .getByRole('tab', { name: '权限矩阵', exact: true })
        .click({ timeout: UI_TIMEOUT })
      const matrix = page.getByRole('region', { name: '权限矩阵', exact: true })
      for (const [action, roles] of [
        ['搜索 / 问答已发布且有权限的空间', ['✓', '✓', '✓']],
        ['导入文档（需空间维护权限）', ['—', '✓', '✓']],
        ['回答模板 / 全局模型配置', ['—', '—', '✓']],
      ]) {
        const row = matrix.getByRole('row').filter({
          has: page.getByRole('cell', { name: action, exact: true }),
        })
        await row.waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        assert.deepEqual(
          (await row.getByRole('cell').allTextContents())
            .slice(1)
            .map((cell) => cell.trim()),
          roles,
          action,
        )
      }
    },
  )

  return { users }
}

export async function runAssistantSetup({ admin, api, step, runId, spaces }) {
  const { page } = admin
  const assistants = {}
  const templates = {}
  const scenarios = SCENARIOS.filter(([, , keys]) =>
    keys.every((key) => spaces?.[key]?.id && spaces[key].name),
  )
  assert.ok(typeof runId === 'string' && runId.trim(), 'runId 缺失')

  for (const [key, label, spaceKeys, rule] of scenarios) {
    const name = `E2E ${runId} ${label}`
    const templateName = `${name}模板`
    const body = `${rule}\n\n问题：{question}\n引用：{references}\n政策有效性：{validity}`
    await step(`AD04-${key}`, `创建并核对${label}回答模板`, page, async () => {
      await page.goto('/answer-templates')
      await page
        .getByText('正在加载…', { exact: true })
        .waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
      await page
        .getByRole('button', { name: /新建模板$/ })
        .click({ timeout: UI_TIMEOUT })
      const dialog = page.getByRole('dialog', { name: '新建模板', exact: true })
      await dialog.getByLabel('模板名称', { exact: true }).fill(templateName)
      await dialog
        .getByRole('tab', { name: '高级文本', exact: true })
        .click({ timeout: UI_TIMEOUT })
      const editor = dialog.locator('.cm-content[contenteditable="true"]')
      // insertText 走 CodeMirror 的输入处理，避免经 DOM 直接改值丢失多行分隔。
      await editor.click({ timeout: UI_TIMEOUT })
      await page.keyboard.insertText(body)
      await dialog
        .getByText('格式正确', { exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      assert.equal(
        await dialog
          .getByRole('switch', { name: '启用模板', exact: true })
          .isChecked(),
        true,
      )
      const response = await throughUI(page, 'POST', '/answer-templates', () =>
        dialog.getByRole('button', { name: '保存模板', exact: true }).click(),
      )
      assert.equal(
        response.status(),
        201,
        `保存回答模板，requestId=${await response.headerValue('x-request-id')}`,
      )
      const { data: created } = await response.json()
      assert.ok(created.templateId, '缺少回答模板 ID')
      const { data: saved } = await api(
        admin,
        `/answer-templates/${created.templateId}`,
      )
      assert.deepEqual(
        [
          saved.name,
          saved.body,
          saved.enabled,
          saved.isGlobal,
          saved.assistantIds,
        ],
        [templateName, body, true, false, []],
      )
      templates[key] = saved
      await page
        .getByText('已创建模板。', { exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
      const row = page.getByRole('row').filter({
        has: page.getByRole('cell', {
          name: new RegExp(
            '^' + templateName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
          ),
        }),
      })
      await row
        .getByRole('cell', { name: '使用中', exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    })

    if (!templates[key]) continue
    await step(
      `AD06-${key}`,
      `创建${label}助手并核对模板与空间绑定`,
      page,
      async () => {
        const template = templates[key]
        const targets = spaceKeys.map((key) => spaces[key])
        assert.equal(
          new Set(targets.map((space) => space.id)).size,
          targets.length,
          '场景的绑定空间必须彼此独立',
        )
        await page.goto('/assistant-config')
        await page
          .getByRole('button', { name: /新建助手$/ })
          .click({ timeout: UI_TIMEOUT })
        const dialog = page.getByRole('dialog', {
          name: '新建助手',
          exact: true,
        })
        await dialog.getByLabel('助手名称', { exact: true }).fill(name)
        await choose(
          dialog,
          '回答模板',
          `助手级：${templateName}（优先于全局）`,
        )
        const model = dialog.getByLabel('模型', { exact: true })
        assert.equal(await model.inputValue(), '继承全局配置')
        assert.equal(await model.isDisabled(), true)
        const search = dialog.getByRole('searchbox', {
          name: '搜索空间名称',
          exact: true,
        })
        for (const target of targets) {
          const response = await throughUI(
            page,
            'GET',
            '/spaces',
            () => search.fill(target.name),
            (query) => query.get('q') === target.name,
          )
          assert.equal(response.status(), 200, `筛选空间 ${target.name}`)
          const { data } = await response.json()
          const matches = data.items.filter((item) => item.name === target.name)
          assert.deepEqual(
            matches.map((item) => item.spaceId),
            [target.id],
            '空间名称应唯一匹配本轮数据',
          )
          await dialog
            .locator('.result-list')
            .getByRole('listitem')
            .filter({ has: page.getByText(target.name, { exact: true }) })
            .getByRole('button', { name: '添加', exact: true })
            .click({ timeout: UI_TIMEOUT })
          await dialog
            .getByRole('button', { name: `移除 ${target.name}`, exact: true })
            .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        }
        await dialog
          .getByText(`已选 ${targets.length} 个空间`, { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        const response = await throughUI(page, 'POST', '/assistants', () =>
          dialog.getByRole('button', { name: '保存配置', exact: true }).click(),
        )
        assert.equal(
          response.status(),
          201,
          `创建助手，requestId=${await response.headerValue('x-request-id')}`,
        )
        const { data: created } = await response.json()
        assert.ok(created.assistantId, '创建响应缺少助手 ID')
        const { data: saved } = await api(
          admin,
          `/assistants/${created.assistantId}`,
        )
        assert.deepEqual(
          [saved.name, saved.enabled, saved.templateId, saved.template],
          [name, true, template.templateId, body],
        )
        const expectedBindings = targets
          .map((space) => [space.id, space.name, 'RETRIEVAL'])
          .sort()
        assert.deepEqual(
          saved.bindings
            .map((binding) => [
              binding.spaceId,
              binding.spaceName,
              binding.role,
            ])
            .sort(),
          expectedBindings,
        )
        assistants[key] = { id: saved.assistantId, name: saved.name }
        await page
          .getByText('已创建助手。', { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
        const row = page
          .getByRole('table', { name: '助手列表' })
          .getByRole('row')
          .filter({ has: page.getByRole('cell', { name, exact: true }) })
        await row
          .getByRole('cell', { name: '启用中', exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        assert.equal(
          await row.locator('.assistant-bindings').innerText(),
          saved.bindings.map((binding) => binding.spaceName).join('、'),
        )
        assert.deepEqual(
          (await api(admin, `/answer-templates/${template.templateId}`)).data
            .assistantIds,
          [saved.assistantId],
        )
        return {
          assistantId: saved.assistantId,
          templateId: saved.templateId,
          spaceIds: saved.bindings.map((binding) => binding.spaceId),
          requestId: await response.headerValue('x-request-id'),
        }
      },
    )
  }

  const previewTemplate = Object.values(templates)[0]
  if (previewTemplate) {
    await step(
      'AD05',
      '回答模板本地预览替换问题、引用与政策有效性变量',
      page,
      async () => {
        await page.goto('/answer-templates')
        await page
          .getByRole('button', { name: /新建模板$/ })
          .click({ timeout: UI_TIMEOUT })
        const dialog = page.getByRole('dialog', {
          name: '新建模板',
          exact: true,
        })
        await dialog
          .getByRole('tab', { name: '高级文本', exact: true })
          .click({ timeout: UI_TIMEOUT })
        await dialog
          .locator('.cm-content[contenteditable="true"]')
          .click({ timeout: UI_TIMEOUT })
        await page.keyboard.insertText(previewTemplate.body)
        const question = 'E2E 合成政策需要哪些材料？'
        await dialog.getByLabel('测试问题', { exact: true }).fill(question)
        const output = dialog.locator('pre.template-test-output')
        const trigger = dialog.getByRole('button', { name: /^测试场景：/ })
        const menu = dialog.getByRole('listbox', {
          name: '测试场景',
          exact: true,
        })
        const actual = {}
        const expected = {}
        const presets = [
          ['有正常引用', '《差旅管理办法》第 8 条（测试资料）', '当前有效'],
          ['没有引用', '（没有检索到可引用资料）', '无可验证政策'],
          [
            '包含失效政策',
            '《差旅管理办法》第 8 条（测试资料）',
            '该政策已失效，请勿作为当前依据',
          ],
        ]
        for (const [
          index,
          [scenario, references, validity],
        ] of presets.entries()) {
          if (index > 0) {
            try {
              await trigger.scrollIntoViewIfNeeded({ timeout: UI_TIMEOUT })
              await trigger.press('ArrowDown', { timeout: UI_TIMEOUT })
              await menu
                .locator('[aria-selected="true"]:focus')
                .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
              await page.keyboard.press('ArrowDown')
              await menu
                .getByRole('option', { name: scenario, exact: true })
                .and(menu.locator(':focus'))
                .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
              await page.keyboard.press('Enter')
            } catch (cause) {
              const defaultScenario = presets[0][0]
              const previewIssue =
                actual[defaultScenario] === expected[defaultScenario]
                  ? ''
                  : '。默认场景的预览变量也未正确替换'
              throw new Error(
                `测试场景菜单无法用键盘切换到「${scenario}」${previewIssue}`,
                { cause },
              )
            }
          }
          assert.equal(
            await trigger.getAttribute('aria-label'),
            `测试场景：${scenario}`,
            '测试场景未切换到预期选项',
          )
          actual[scenario] = await output.innerText()
          expected[scenario] = previewTemplate.body
            .replace('{question}', question)
            .replace('{references}', references)
            .replace('{validity}', validity)
        }
        assert.deepEqual(
          actual,
          expected,
          '三种场景可选，但预览应替换问题、引用与政策有效性变量',
        )
        await dialog
          .getByRole('button', { name: '取消', exact: true })
          .click({ timeout: UI_TIMEOUT })
      },
    )
  }

  const configured = Object.keys(assistants)
  if (configured.length >= 2) {
    const [globalKey, preferredKey] = configured
    const fallback = templates[globalKey]
    const preferred = templates[preferredKey]
    await step('AD07', '验证助手模板优先于全局模板并还原', page, async () => {
      const path = '/settings/global'
      const { data: before } = await api(admin, path)
      assert.equal(before.defaultModelId, null, '全局模型必须保持未指定')
      assert.notEqual(
        before.templateId,
        fallback.templateId,
        '全局测试模板应为此次新建',
      )
      const { data: allTemplates } = await api(admin, '/answer-templates')
      const originalTemplate = allTemplates.items.find(
        (item) => item.templateId === before.templateId,
      )
      assert.ok(
        before.templateId === null || originalTemplate,
        '无法还原原全局模板',
      )
      await page.goto('/settings')
      await page
        .getByText('正在加载…', { exact: true })
        .waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
      const open = page.getByRole('button', {
        name: '编辑全局配置',
        exact: true,
      })
      const dialog = page.getByRole('dialog', { name: '全局配置', exact: true })
      const save = dialog.getByRole('button', { name: '保存', exact: true })
      const preferredAssistant = assistants[preferredKey]
      let changed = false
      try {
        await open.click({ timeout: UI_TIMEOUT })
        await choose(dialog, '全局回答模板', fallback.name)
        const response = await throughUI(page, 'PUT', path, () => save.click())
        changed = response.ok()
        assert.equal(
          response.status(),
          200,
          `设置全局回答模板，requestId=${await response.headerValue('x-request-id')}`,
        )
        await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
        const { data: global } = await api(admin, path)
        const { data: assistant } = await api(
          admin,
          `/assistants/${preferredAssistant.id}`,
        )
        assert.deepEqual(
          [global.templateId, global.template, global.defaultModelId],
          [fallback.templateId, fallback.body, null],
        )
        assert.deepEqual(
          [assistant.templateId, assistant.template],
          [preferred.templateId, preferred.body],
        )
        assert.equal(
          (await api(admin, `/answer-templates/${fallback.templateId}`)).data
            .isGlobal,
          true,
        )

        await page.goto('/assistant-config')
        await page
          .getByRole('searchbox', { name: '筛选助手名称', exact: true })
          .fill(preferredAssistant.name)
        const row = page
          .getByRole('table', { name: '助手列表' })
          .getByRole('row')
          .filter({
            has: page.getByRole('cell', {
              name: preferredAssistant.name,
              exact: true,
            }),
          })
        await row
          .getByRole('button', { name: '编辑', exact: true })
          .click({ timeout: UI_TIMEOUT })
        const assistantDetail = page.getByRole('dialog', {
          name: `编辑助手：${preferredAssistant.name}`,
          exact: true,
        })
        await assistantDetail
          .getByRole('button', {
            name: `回答模板：助手级：${preferred.name}（优先于全局）`,
            exact: true,
          })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      } finally {
        if (changed) {
          await page.goto('/settings')
          await page
            .getByText('正在加载…', { exact: true })
            .waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
          await open.click({ timeout: UI_TIMEOUT })
          const originalOption = originalTemplate
            ? `${originalTemplate.name}${originalTemplate.enabled ? '' : '（已停用）'}`
            : '未指定'
          await choose(dialog, '全局回答模板', originalOption)
          const response = await throughUI(page, 'PUT', path, () =>
            save.click(),
          )
          assert.equal(response.status(), 200, '恢复全局回答模板失败')
          await dialog.waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
          const { data: restored } = await api(admin, path)
          assert.deepEqual(
            [
              restored.templateId,
              restored.defaultModelId,
              restored.contextLimit,
              restored.usageReportEnabled,
            ],
            [
              before.templateId,
              null,
              before.contextLimit,
              before.usageReportEnabled,
            ],
          )
        }
      }
    })
  }

  return { assistants }
}

export async function runStatistics({ admin, reader, api, step, questionIds }) {
  const today = beijingDay.format(new Date())
  const ids = new Set(questionIds)
  const fixtureRows = new Map()
  const stats = (period, date, subject = {}) =>
    new URLSearchParams({ period, date, ...subject }).toString()
  const allUsers = { allUsers: 'true' }

  async function reconcile(actor, period, date, subject, expectedOwner) {
    const { page } = actor
    const main = page.getByRole('main')
    const query = stats(period, date, subject)
    const [summary, trend, detail] = await Promise.all([
      api(actor, `/usage?${query}`),
      api(actor, `/usage/series?${query}`),
      api(actor, `/usage/questions?${query}`),
    ])
    const usage = summary.data
    const { buckets } = trend.data
    const { items } = detail.data
    assert.deepEqual(
      [usage.period, usage.timeZone, usage.userId],
      [period, STATISTICS_ZONE, expectedOwner],
    )
    assert.deepEqual(
      [trend.data.period, trend.data.date, trend.data.timeZone],
      [period, date, STATISTICS_ZONE],
    )
    const start = new Date(`${date}T00:00:00Z`)
    if (period === 'WEEK')
      start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7))
    const end = new Date(start)
    end.setUTCDate(end.getUTCDate() + (period === 'DAY' ? 1 : 7))
    assert.deepEqual(
      [usage.periodStart, usage.periodEndExclusive],
      [start.toISOString().slice(0, 10), end.toISOString().slice(0, 10)],
      '北京时间自然周期边界',
    )
    assert.equal(buckets.length, period === 'DAY' ? 24 : 7)
    assert.equal(buckets[0].bucketStart.slice(0, 10), usage.periodStart)
    assert.equal(
      buckets.reduce((sum, row) => sum + row.questionCount, 0),
      usage.questionCount,
    )
    assert.equal(
      buckets.reduce((sum, row) => sum + row.failedQuestionCount, 0),
      usage.failedQuestionCount,
    )
    assert.equal(items.length, Math.min(usage.questionCount, 200))
    assert.equal(new Set(items.map((row) => row.questionId)).size, items.length)
    for (const item of items) {
      const day = beijingDay.format(new Date(item.acceptedAt))
      assert.ok(
        day >= usage.periodStart && day < usage.periodEndExclusive,
        `提问 ${item.questionId} 超出统计周期`,
      )
      if (expectedOwner !== null)
        assert.equal(
          item.userId,
          expectedOwner,
          `提问 ${item.questionId} 归属错误`,
        )
    }
    if (usage.questionCount <= 200) {
      const reported = items.filter((row) => row.tokenTotal !== null)
      assert.equal(
        usage.failedQuestionCount,
        items.filter((row) => row.status === 'FAILED').length,
      )
      assert.equal(
        usage.reportedTokenCount,
        reported.length
          ? reported.reduce((sum, row) => sum + row.tokenTotal, 0)
          : null,
        '已上报 0 和未上报 null 不得混淆',
      )
    }

    await main
      .getByText('正在加载…', { exact: true })
      .waitFor({ state: 'hidden', timeout: UI_TIMEOUT })
    assert.equal(
      await main.getByLabel('周期内日期', { exact: true }).inputValue(),
      date,
    )
    const metrics = main.getByRole('region', { name: '统计指标', exact: true })
    await metrics.waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    const metricLabels = ['会话数', '提问次数', '失败请求数', 'Tokens 用量']
    const metricValues = [
      usage.conversationCount,
      usage.questionCount,
      usage.failedQuestionCount,
      usage.reportedTokenCount ?? '—',
    ].map(String)
    assert.deepEqual(
      await metrics.locator('.bk-kpi .label').allTextContents(),
      metricLabels,
    )
    assert.deepEqual(
      await metrics.locator('.bk-kpi .value').allTextContents(),
      metricValues,
    )
    const failureDelta =
      usage.questionCount === 0
        ? '本周期没有提问'
        : `失败率 ${((usage.failedQuestionCount / usage.questionCount) * 100).toFixed(1)}%`
    await metrics
      .getByText(failureDelta, { exact: true })
      .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    const tokenDelta =
      usage.reportedTokenCount !== null
        ? `未上报调用 ${usage.unreportedModelCallCount} 次`
        : usage.unreportedModelCallCount === 0
          ? '本周期没有模型调用'
          : `已调用但全部 ${usage.unreportedModelCallCount} 次未上报`
    await metrics
      .getByText(tokenDelta, { exact: true })
      .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    const trendPanel = main.getByRole('region', { name: '趋势', exact: true })
    await trendPanel
      .locator('canvas')
      .first()
      .waitFor({ state: 'visible', timeout: UI_TIMEOUT })

    const details = main.locator('details.detail-card')
    if ((await details.getAttribute('open')) === null) {
      await details
        .getByText('提问明细（供对账，最多最近 200 条）', { exact: true })
        .click({ timeout: UI_TIMEOUT })
    }
    const isAdmin = actor === admin
    const header = [
      '北京时间',
      ...(isAdmin ? ['用户'] : []),
      '提问',
      '状态',
      '已上报 Tokens',
    ]
    if (items.length > 0) {
      await page.waitForFunction(
        () => {
          const root = globalThis.document.querySelector('details.detail-card')
          return root?.open && root.querySelectorAll('tbody tr').length > 0
        },
        undefined,
        { timeout: UI_TIMEOUT },
      )
    }
    if (items.length === 0) {
      await details
        .getByText('该周期没有提问记录。', { exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
    } else {
      const headers = await details.locator('thead th').allTextContents()
      assert.deepEqual(
        headers.map((text) => text.trim()),
        header,
      )
      const visible = await details.locator('tbody tr').evaluateAll((rows) =>
        rows.map((row) => {
          const cells = Array.from(row.cells, (cell) => cell.textContent.trim())
          return {
            acceptedAt: cells[0],
            question: row.querySelector('.bk-row-title').textContent.trim(),
            idPrefix: row.querySelector('.bk-row-sub').textContent.trim(),
            status: cells.at(-2),
            tokens: cells.at(-1),
          }
        }),
      )
      assert.deepEqual(
        visible,
        items.map((item) => ({
          acceptedAt: beijingTime.format(new Date(item.acceptedAt)),
          question: item.text.trim(),
          idPrefix: item.questionId.slice(0, 8),
          status: QUESTION_STATUS[item.status] ?? item.status,
          tokens: String(item.tokenTotal ?? '未上报'),
        })),
        '提问明细按顺序核对时间、文本、ID、状态和 Tokens',
      )
    }
    return { usage, items }
  }

  if (reader?.page) {
    const { page } = reader
    await step(
      'AD08-nav',
      '普通用户从工作台打开个人问答统计',
      page,
      async () => {
        await page.goto('/')
        const main = page.getByRole('main')
        const navigation = page.getByRole('complementary', {
          name: '平台侧栏',
          exact: true,
        })
        await main
          .getByRole('heading', { name: '运营看板', exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        const sidebarHasStatistics =
          (await navigation
            .getByRole('link', { name: '问答统计', exact: true })
            .count()) > 0
        // 工作台加载完自己的用量，再监听统计页发出的同路径请求。
        await main
          .getByRole('button', { name: '刷新', exact: true, disabled: false })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        const entry = main.getByRole('link', {
          name: '问答统计 ›',
          exact: true,
        })
        const response = await throughUI(
          page,
          'GET',
          '/usage',
          () => entry.click({ timeout: UI_TIMEOUT }),
          (query) =>
            query.get('period') === 'WEEK' &&
            !query.has('userId') &&
            !query.has('allUsers'),
        )
        assert.equal(response.status(), 200)
        await page.waitForURL((url) => url.pathname === '/statistics', {
          timeout: UI_TIMEOUT,
        })
        await main
          .getByRole('heading', { name: '问答统计', exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        await main
          .getByRole('region', { name: '统计指标', exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        const { data } = await response.json()
        assert.equal(data.userId, reader.me.userId)
        return {
          from: '/',
          to: '/statistics',
          sidebarHasStatistics,
          requestId: await response.headerValue('x-request-id'),
        }
      },
    )
  }

  await step(
    'AD09',
    '管理员全量问答、趋势、用户排行与空间用量对账',
    admin.page,
    async () => {
      const { page } = admin
      await page.goto('/statistics')
      const main = page.getByRole('main')
      await main
        .getByRole('button', { name: '按用户筛选：全部用户', exact: true })
        .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      const date = await main
        .getByLabel('周期内日期', { exact: true })
        .inputValue()
      assert.match(date, /^\d{4}-\d{2}-\d{2}$/)
      const query = stats('WEEK', date)
      const { data: current } = await api(
        admin,
        `/usage/questions?${stats('WEEK', date, allUsers)}`,
      )
      for (const row of current.items)
        if (ids.has(row.questionId)) fixtureRows.set(row.questionId, row)
      // 套件跨北京时间周界时，上一自然周也可能有本轮已受理的问题。
      if (fixtureRows.size < ids.size) {
        const previousDate = new Date(`${date}T00:00:00Z`)
        previousDate.setUTCDate(previousDate.getUTCDate() - 7)
        const { data: previous } = await api(
          admin,
          `/usage/questions?${stats('WEEK', previousDate.toISOString().slice(0, 10), allUsers)}`,
        )
        for (const row of previous.items)
          if (ids.has(row.questionId)) fixtureRows.set(row.questionId, row)
      }
      const [{ usage }, ranking, spaces] = await Promise.all([
        reconcile(admin, 'WEEK', date, allUsers, null),
        api(admin, `/usage/ranking?${query}`),
        api(admin, `/usage/spaces?${query}`),
      ])
      assert.ok(
        ids.size > 0,
        '需传入本轮实际受理且已终态的 questionIds，才能验证明细纳入用量',
      )
      assert.deepEqual(
        [...fixtureRows.keys()].sort(),
        [...ids].sort(),
        '管理员明细缺少本轮 questionId',
      )
      for (const row of fixtureRows.values())
        assert.ok(
          ['SUCCEEDED', 'FAILED'].includes(row.status),
          `提问 ${row.questionId} 尚未终态`,
        )
      const rankRows = ranking.data.items
      const rankPanel = main.getByRole('region', {
        name: '用户用量排行',
        exact: true,
      })
      if (rankRows.length === 0) {
        await rankPanel
          .getByText('该周期没有用量记录。', { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
        assert.equal(usage.questionCount, 0)
      } else {
        const columns = [
          'conversationCount',
          'questionCount',
          'failedQuestionCount',
          'unreportedModelCallCount',
        ]
        const totals = columns.map((field) =>
          rankRows.reduce((sum, row) => sum + row[field], 0),
        )
        assert.deepEqual(
          totals,
          columns.map((field) => usage[field]),
          '用户排行合计应与管理员汇总一致',
        )
        const counts = rankRows
          .map((row) => row.reportedTokenCount)
          .filter((count) => count !== null)
        assert.equal(
          usage.reportedTokenCount,
          counts.length ? counts.reduce((sum, count) => sum + count, 0) : null,
        )
        const actual = await rankPanel.locator('tbody tr').evaluateAll((rows) =>
          rows.map((row) => [
            row.querySelector('.bk-row-title').textContent.trim(),
            row.querySelector('.bk-row-sub').textContent.trim(),
            ...Array.from(row.cells)
              .slice(1)
              .map((cell) => cell.textContent.trim()),
          ]),
        )
        assert.deepEqual(
          actual,
          rankRows.map((row) =>
            [
              row.displayName,
              row.userId.slice(0, 8),
              row.conversationCount,
              row.questionCount,
              row.failedQuestionCount,
              row.reportedTokenCount ?? '未上报',
              row.unreportedModelCallCount,
            ].map(String),
          ),
        )
        const [conversations, questions, failures, unreported] = totals
        assert.deepEqual(
          (await rankPanel.locator('tfoot td').allTextContents()).map((text) =>
            text.trim(),
          ),
          ['合计', conversations, questions, failures, '—', unreported].map(
            String,
          ),
        )
      }
      const spacePanel = main.getByRole('region', {
        name: '空间用量',
        exact: true,
      })
      const spaceRows = spaces.data.items
      if (spaceRows.length === 0) {
        await spacePanel
          .getByText('该周期没有空间被答案引用。', { exact: true })
          .waitFor({ state: 'visible', timeout: UI_TIMEOUT })
      } else {
        const actual = await spacePanel
          .locator('tbody tr')
          .evaluateAll((rows) =>
            rows.map((row) => [
              row.querySelector('.bk-row-title').textContent.trim(),
              row.querySelector('.bk-row-sub').textContent.trim(),
              ...Array.from(row.cells)
                .slice(1)
                .map((cell) => cell.textContent.trim()),
            ]),
          )
        assert.deepEqual(
          actual,
          spaceRows.map((row) =>
            [
              row.name,
              row.spaceId.slice(0, 8),
              row.citationCount,
              row.questionCount,
            ].map(String),
          ),
        )
      }
    },
  )

  if (!reader?.page) return
  const readerId = reader.me.userId
  const readerName = reader.me.displayName
  const owned = [...fixtureRows.values()].filter(
    (row) => row.userId === readerId,
  )
  const other = [...fixtureRows.values()].filter(
    (row) => row.userId !== readerId,
  )
  // 以已受理的问题锚定自然日，跨午夜运行也不会误把上一日的数据当作丢失。
  const auditDate = owned.length
    ? beijingDay.format(new Date(owned[0].acceptedAt))
    : today
  const dayOwned = owned.filter(
    (row) => beijingDay.format(new Date(row.acceptedAt)) === auditDate,
  )
  const dayQuery = stats('DAY', auditDate)
  let personal

  await step(
    'AD08',
    '普通用户按自然日对账并限制统计查询范围',
    reader.page,
    async () => {
      const { page } = reader
      await page.goto('/statistics')
      const main = page.getByRole('main')
      const date = main.getByLabel('周期内日期', { exact: true })
      await date.fill(auditDate)
      const response = await throughUI(
        page,
        'GET',
        '/usage',
        () => choose(main, '周期类型', '自然日'),
        (query) =>
          query.get('period') === 'DAY' &&
          query.get('date') === auditDate &&
          !query.has('userId') &&
          !query.has('allUsers'),
      )
      assert.equal(
        response.status(),
        200,
        `读取个人统计，requestId=${await response.headerValue('x-request-id')}`,
      )
      personal = await reconcile(reader, 'DAY', auditDate, {}, readerId)
      assert.equal(
        await main.getByRole('button', { name: /^按用户筛选：/ }).count(),
        0,
      )
      assert.equal(
        await main.getByLabel('搜索统计用户', { exact: true }).count(),
        0,
      )
      for (const name of ['用户用量排行', '空间用量']) {
        assert.equal(
          await main
            .getByRole('region', { name, exact: true })
            .getByRole('table')
            .count(),
          0,
          `普通用户不可访问${name}数据`,
        )
      }
      assert.ok(
        dayOwned.length > 0,
        '本轮 questionIds 中需至少有一条读者已受理提问',
      )
      const visibleIds = new Set(personal.items.map((item) => item.questionId))
      for (const item of dayOwned)
        assert.ok(
          visibleIds.has(item.questionId),
          `个人明细缺少提问 ${item.questionId}`,
        )
      for (const item of other)
        assert.ok(
          !visibleIds.has(item.questionId),
          `个人明细显示他人提问 ${item.questionId}`,
        )
      for (const path of ['/usage', '/usage/series', '/usage/questions']) {
        const denied = await api(reader, `${path}?${dayQuery}&allUsers=true`, {
          status: 403,
        })
        assert.equal(denied.data.code, 'FORBIDDEN')
      }
      for (const path of ['/usage/ranking', '/usage/spaces']) {
        const denied = await api(reader, `${path}?${dayQuery}`, { status: 403 })
        assert.equal(denied.data.code, 'FORBIDDEN')
      }
    },
  )

  await step(
    'AD09-filter',
    '管理员搜索并筛选普通用户，与个人统计交叉核对',
    admin.page,
    async () => {
      const { page } = admin
      await page.goto('/statistics')
      const main = page.getByRole('main')
      await main.getByLabel('周期内日期', { exact: true }).fill(auditDate)
      await choose(main, '周期类型', '自然日')
      const search = main.getByLabel('搜索统计用户', { exact: true })
      const users = await throughUI(
        page,
        'GET',
        '/users',
        () => search.fill(readerId),
        (query) => query.get('q') === readerId,
      )
      assert.equal(users.status(), 200)
      const { data: listed } = await users.json()
      assert.deepEqual(
        listed.items.map((item) => [item.userId, item.displayName]),
        [[readerId, readerName]],
      )
      const response = await throughUI(
        page,
        'GET',
        '/usage',
        () => choose(main, '按用户筛选', readerName),
        (query) =>
          query.get('period') === 'DAY' &&
          query.get('date') === auditDate &&
          query.get('userId') === readerId &&
          !query.has('allUsers'),
      )
      assert.equal(response.status(), 200)
      const scoped = await reconcile(
        admin,
        'DAY',
        auditDate,
        { userId: readerId },
        readerId,
      )
      const self = personal ?? {
        usage: (await api(reader, `/usage?${dayQuery}`)).data,
        items: (await api(reader, `/usage/questions?${dayQuery}`)).data.items,
      }
      assert.deepEqual(
        scoped,
        self,
        '管理员按用户筛选与该用户自己的统计必须一致',
      )
      const detailRows = main.locator('details.detail-card tbody tr')
      const names = (
        await detailRows.locator('td:nth-child(2)').allTextContents()
      ).map((text) => text.trim())
      assert.deepEqual(
        names,
        scoped.items.map(() => readerName),
      )
      if (other.length) {
        const denied = await api(
          reader,
          `/usage?${stats('DAY', auditDate, { userId: other[0].userId })}`,
          { status: 403 },
        )
        assert.equal(denied.data.code, 'FORBIDDEN')
      }
    },
  )
}
