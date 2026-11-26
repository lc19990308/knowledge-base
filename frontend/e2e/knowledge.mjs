import assert from 'node:assert/strict'
import { URLSearchParams } from 'node:url'
import { poll } from './stack.mjs'
import {
  api,
  browserRequest,
  button,
  row,
  textFixture,
  visible,
} from './support.mjs'

const maxFileBytes = 10 * 1024 * 1024
const normalName = 'FIN-TR-017_差旅报销办法_v2.1.md'
const configs = {
  normal: {
    label: '现行差旅制度',
    files: [normalName, 'FIN-TR-022_差旅住宿标准_v1.2.md'],
  },
  restricted: {
    label: '受限驻点政策',
    files: ['FIN-SP-006_海外驻点例外_v1.0.md'],
  },
  historical: {
    label: '历史差旅归档',
    files: ['FIN-TR-017_差旅报销办法_v1.4.md'],
  },
  uncertain: {
    label: '待核实差旅口径',
    files: ['FIN-TR-031_差旅补贴试行口径_v0.9.md'],
  },
  formats: { label: '多格式差旅资料' },
  sizes: { label: '上传大小边界' },
}

const imports = (id) => new RegExp(`/spaces/${id}/imports$`)
const draft = (id) => `/spaces/${id}/draft`
const docPath = (space, document) => `/documents/${document}?space=${space}`

function finalBatch(batch) {
  return batch.files.every(
    (file) => !['QUEUED', 'PROCESSING'].includes(file.status),
  )
}

async function createSpace(actor, name) {
  const { page } = actor
  await page.goto('/knowledge')
  await button(page, /创建空间/).click()
  const modal = page.getByRole('dialog', { name: '创建空间', exact: true })
  await modal.getByLabel('所属部门').fill('财务运营部')
  await modal.getByLabel('空间名称').fill(name)
  const accepted = await browserRequest(
    page,
    'POST',
    /\/spaces$/,
    () => button(modal, '创建草稿').click(),
    201,
  )
  assert.equal(accepted.data.name, name)
  assert.equal(accepted.data.department, '财务运营部')
  await modal.waitFor({ state: 'hidden' })
  return { id: accepted.data.spaceId, name, requestId: accepted.requestId }
}

async function membership(admin, space, actor, canRead, canMaintain = false) {
  const result = await api(
    admin,
    `/spaces/${space.id}/members/${actor.me.userId}`,
    {
      method: 'PUT',
      data: { canRead, canMaintain },
    },
  )
  assert.equal(result.data.canRead, canRead)
  assert.equal(result.data.canMaintain, canMaintain)
}

function drawerFor(page, space) {
  return page.getByRole('dialog', {
    name: `「${space.name}」· 文档列表`,
    exact: true,
  })
}

function importDialog(page, space) {
  return page.getByRole('dialog', {
    name: `导入文档 · ${space.name}`,
    exact: true,
  })
}

async function openDrawer(actor, space) {
  const { page } = actor
  await page.goto('/knowledge')
  const filter = page.getByRole('searchbox', { name: '筛选空间名称' })
  await filter.fill(space.name)
  const target = row(page, space.name)
  await visible(target)
  await button(target, '查看').click()
  const drawer = drawerFor(page, space)
  await drawer.waitFor({ state: 'visible', timeout: 15_000 })
  return drawer
}

async function openImport(actor, space, entry) {
  const { page } = actor
  if (entry === 'old-link') {
    await page.goto(`/import?space=${space.id}`)
  } else if (entry === 'drawer') {
    const drawer = await openDrawer(actor, space)
    await button(drawer, '导入新文档').click()
  } else {
    await page.goto('/knowledge')
    await page.getByRole('searchbox', { name: '筛选空间名称' }).fill(space.name)
    await button(row(page, space.name), '导入').click()
  }
  const modal = importDialog(page, space)
  await visible(modal)
  await page.waitForURL('**/knowledge')
  return modal
}

async function closeImport(modal) {
  await button(modal, '关闭').last().click()
  await modal.waitFor({ state: 'hidden' })
}

async function upload(actor, space, modal) {
  return browserRequest(
    actor.page,
    'POST',
    imports(space.id),
    () => button(modal, '开始导入').click(),
    202,
  )
}

async function batchResult(actor, accepted) {
  return poll(
    async () => (await api(actor, `/imports/${accepted.data.batchId}`)).data,
    finalBatch,
    {
      timeout: 60_000,
      label: '文件处理',
    },
  )
}

async function putPolicyMetadata(actor, space, key, current) {
  const policy = {
    normal: { policyState: 'EFFECTIVE', effectiveFrom: '2025-01-01' },
    restricted: { policyState: 'EFFECTIVE', effectiveFrom: '2025-01-01' },
    historical: {
      policyState: 'EFFECTIVE',
      effectiveFrom: '2023-01-01',
      effectiveUntil: '2024-12-31',
    },
    uncertain: { policyState: 'UNKNOWN' },
  }[key]
  if (!policy) return current
  const entries = current.data.entries.map((entry) => ({
    documentVersionId: entry.documentVersionId,
    ...policy,
  }))
  return api(actor, draft(space.id), {
    method: 'PUT',
    data: { entries },
    headers: { 'If-Match': current.headers.etag },
  })
}

async function submitReview(maint, space) {
  const drawer = await openDrawer(maint, space)
  return browserRequest(
    maint.page,
    'POST',
    /\/publish-requests$/,
    () => button(drawer, '提交变更审核').click(),
    201,
  )
}

async function reviewDialog(admin, space, tab) {
  await admin.page.goto('/publishing')
  await admin.page.getByRole('tab', { name: tab, exact: true }).click()
  await button(row(admin.page, space.name), '查看').first().click()
  return visible(
    admin.page.getByRole('dialog', { name: space.name, exact: true }),
  )
}

export async function setReadAccessFromUi(admin, actor, space, enabled) {
  const { page } = admin
  await page.goto('/settings')
  await page.getByRole('tab', { name: '成员与角色', exact: true }).click()
  await page
    .getByRole('searchbox', { name: '搜索成员' })
    .fill(actor.me.displayName)
  await button(row(page, actor.me.displayName), '编辑').click()
  const dialog = page.getByRole('dialog', {
    name: `编辑成员 · ${actor.me.displayName}`,
    exact: true,
  })
  const access = dialog.getByRole('switch', {
    name: `${space.name} 读取权限`,
    exact: true,
  })
  await visible(access)
  assert.equal(
    await access.isChecked(),
    !enabled,
    '权限初始状态应与用例准备一致',
  )
  await access.click()
  assert.equal(await access.isChecked(), enabled)
  const saved = await browserRequest(
    page,
    'PUT',
    /\/space-memberships$/,
    () => button(dialog, '保存').click(),
    200,
  )
  await dialog.waitFor({ state: 'hidden' })
  const current = await api(
    admin,
    `/users/${actor.me.userId}/space-memberships`,
  )
  assert.equal(
    current.data.items.some(
      (item) => item.spaceId === space.id && item.canRead,
    ),
    enabled,
  )
  return {
    requestId: saved.requestId,
    userId: actor.me.userId,
    spaceId: space.id,
    canRead: enabled,
  }
}

export async function runKnowledge({
  admin,
  maint,
  reader,
  outsider,
  step,
  runId,
  fixtures,
}) {
  const spaces = {}
  await step(
    'KB-UI01',
    '创建空间并为不同角色分配隔离数据',
    admin.page,
    async () => {
      for (const [key, config] of Object.entries(configs)) {
        // 此空间由维护者创建，保留管理员没有显式成员记录时的回归场景。
        if (key === 'restricted') {
          await api(admin, `/users/${maint.me.userId}/access`, {
            method: 'PUT',
            data: { enabled: true, roles: ['USER'], canCreateSpace: true },
          })
          await maint.page.reload()
        }
        const owner = key === 'restricted' ? maint : admin
        const space = await createSpace(owner, `E2E ${config.label} ${runId}`)
        spaces[key] = space
        if (owner !== maint) await membership(admin, space, maint, true, true)
        if (key !== 'sizes') await membership(admin, space, reader, true)
        // 范围外读者的常规空间权限由下个用例通过系统设置界面保存。
      }
      return { spaces: globalThis.structuredClone(spaces) }
    },
  )

  if (spaces.normal) {
    const granted = await step(
      'KB-GRANT',
      '系统设置页面授予读者空间权限并读回',
      admin.page,
      () => setReadAccessFromUi(admin, outsider, spaces.normal, true),
    )
    if (!granted) {
      await step(
        'KB-GRANT-fallback',
        '通过 API 补齐读者授权以继续后续测试',
        admin.page,
        () => membership(admin, spaces.normal, outsider, true),
      )
    }
  }

  for (const [key, space] of Object.entries(spaces)) {
    if (key === 'sizes') continue
    const names =
      configs[key].files ??
      [...fixtures.keys()].filter((name) => name.startsWith('FIN-FMT-')).sort()
    const result = await step(
      `KB-UP-${key}`,
      `浏览器导入、解析并自动加入草稿 · ${configs[key].label}`,
      maint.page,
      async () => {
        const entry =
          { normal: 'row', restricted: 'drawer', historical: 'old-link' }[
            key
          ] ?? 'row'
        const modal = await openImport(maint, space, entry)
        await modal
          .locator('input[type=file]')
          .setInputFiles(names.map((name) => fixtures.get(name).path))
        if (key === 'normal') {
          await visible(
            modal.locator('pre').filter({ hasText: '15 个工作日内' }),
          )
        }
        if (key === 'formats') {
          const previewLink = await visible(
            modal.getByRole('link', { name: /打开文件预览.*FIN-FMT-101/ }),
          )
          assert.match(await previewLink.getAttribute('href'), /^blob:/)
        }
        const accepted = await upload(maint, space, modal)
        const final = await batchResult(maint, accepted)
        assert.deepEqual(
          final.files.map((file) => file.name),
          names,
        )
        for (const file of final.files) {
          if (file.name.startsWith('FIN-FMT-106')) {
            assert.equal(file.status, 'NO_TEXT')
            assert.equal(file.code, 'NO_EXTRACTABLE_TEXT')
          } else if (file.name.startsWith('FIN-FMT-107')) {
            assert.equal(file.status, 'REJECTED')
            assert.equal(file.code, 'SUSPICIOUS_FILE')
            assert(!file.documentVersionId)
          } else {
            assert.equal(file.status, 'READY', file.name)
            assert(file.documentVersionId, file.name)
          }
        }
        await visible(modal.getByText(/已将 \d+ 份文档加入草稿/))
        const current = await api(maint, draft(space.id))
        assert(current.headers.etag)
        assert.deepEqual(
          current.data.entries.map((entry) => entry.documentVersionId).sort(),
          final.files
            .map((file) => file.documentVersionId)
            .filter(Boolean)
            .sort(),
        )
        await closeImport(modal)
        const metadata = await putPolicyMetadata(maint, space, key, current)
        if (key === 'normal') {
          const drawer = await openDrawer(maint, space)
          await button(row(drawer, normalName), '预览').click()
          await visible(drawer.getByText(/费用分摊方式/))
          const before = await api(
            reader,
            `/spaces/${space.id}/search?q=${encodeURIComponent('费用分摊')}`,
            { status: 404 },
          )
          assert.equal(before.data.code, 'RESOURCE_NOT_FOUND')
        }
        return {
          batch: final,
          draftRevision: metadata.data.revision,
          uploadRequestId: accepted.requestId,
        }
      },
    )
    if (!result) continue
    space.documents = result.batch.files

    const published = await step(
      `KB-PUB-${key}`,
      `知识库提审、冻结快照及审核生效 · ${configs[key].label}`,
      admin.page,
      async () => {
        const submitted = await submitReview(maint, space)
        assert.equal(submitted.data.status, 'PENDING_REVIEW')
        if (key === 'uncertain') {
          const dialog = await reviewDialog(admin, space, '待审核')
          await button(dialog, '驳回').click()
          await visible(
            dialog
              .getByRole('alert')
              .filter({ hasText: '驳回必须填写审核意见' }),
          )
          const reason = '合成测试：需要补充有效性说明'
          await dialog.getByLabel('审核意见').fill(reason)
          const rejected = await browserRequest(
            admin.page,
            'POST',
            /\/decision$/,
            () => button(dialog, '驳回').click(),
            200,
          )
          assert.equal(rejected.data.status, 'REJECTED')
          assert.equal(rejected.data.decisionReason, reason)
          await button(dialog, '关闭').click()
          await admin.page
            .getByRole('tab', { name: '已驳回', exact: true })
            .click()
          await visible(
            row(admin.page, space.name).getByRole('cell', {
              name: '已拒绝',
              exact: true,
            }),
          )
          await maint.page.goto('/publishing')
          await maint.page
            .getByRole('tab', { name: '已驳回', exact: true })
            .click()
          await button(row(maint.page, space.name), '查看').click()
          const review = maint.page.getByRole('dialog', {
            name: space.name,
            exact: true,
          })
          await visible(
            review.getByText(`审核意见：${reason}`, { exact: true }),
          )
          assert.equal(await button(review, '通过并发布').count(), 0)
          assert.equal(
            (await api(reader, `/spaces/${space.id}`)).data
              .currentPublicationId,
            null,
          )
        }
        const pending =
          key === 'uncertain' ? await submitReview(maint, space) : submitted
        const dialog = await reviewDialog(admin, space, '待审核')
        const frozen = await api(
          admin,
          `/publish-requests/${pending.data.requestId}`,
        )
        const docIds = space.documents
          .map((file) => file.documentVersionId)
          .filter(Boolean)
          .sort()
        assert.deepEqual(
          frozen.data.snapshot.map((entry) => entry.documentVersionId).sort(),
          docIds,
        )
        const approved = await browserRequest(
          admin.page,
          'POST',
          /\/decision$/,
          () => button(dialog, '通过并发布').click(),
          200,
        )
        assert(
          ['PUBLISHED', 'APPROVED_WAITING_INDEX'].includes(
            approved.data.status,
          ),
        )
        const active = await poll(
          async () =>
            (await api(admin, `/publish-requests/${pending.data.requestId}`))
              .data,
          (state) => state.status === 'PUBLISHED',
          {
            timeout: 60_000,
            label: '发布生效',
          },
        )
        assert(active.publicationId)
        assert.equal(
          (await api(reader, `/spaces/${space.id}`)).data.currentPublicationId,
          active.publicationId,
        )
        return {
          publicationId: active.publicationId,
          snapshotId: active.snapshotId,
          publishRequestId: active.requestId,
          approvalRequestId: approved.requestId,
        }
      },
    )
    if (published) Object.assign(space, published)
  }

  if (spaces.sizes) await checkSizes(step, maint, spaces.sizes)
  if (spaces.normal?.publicationId) {
    await checkSearch(step, reader, maint, spaces.normal, fixtures)
    await checkStaleDraft(step, maint, spaces.normal)
  }
  await step(
    'KB-UPD',
    '已入库文档更新：同文件名生成新版本并替换草稿条目',
    maint.page,
    async () => {
      const space = await createSpace(maint, `E2E 更新验证 ${runId}`)
      await membership(admin, space, maint, true, true)
      const original = textFixture(
        'update-check.txt',
        512,
        '更新前：差旅补贴每天一百元。',
      )
      const imported = await api(maint, `/spaces/${space.id}/imports`, {
        method: 'POST',
        status: 202,
        multipart: { files: original },
      })
      const batchId = imported.data.batchId
      const ready = await poll(
        async () => (await api(maint, `/imports/${batchId}`)).data,
        (batch) => batch.status === 'COMPLETED',
        { timeout: 40_000, label: '等待导入批次完成' },
      )
      const first = ready.files[0]
      assert.equal(first.status, 'READY')
      assert.equal(first.versionNumber, 1)

      const draftBefore = await api(maint, `/spaces/${space.id}/draft`)
      await api(maint, `/spaces/${space.id}/draft`, {
        method: 'PUT',
        headers: { 'If-Match': draftBefore.headers.etag },
        data: {
          entries: [
            {
              documentVersionId: first.documentVersionId,
              policyState: 'EFFECTIVE',
              effectiveFrom: '2025-01-01',
              effectiveUntil: null,
            },
          ],
        },
      })

      const replacement = textFixture(
        'update-check.txt',
        512,
        '更新后：差旅补贴每天二百元，自 2026 年起执行。',
      )
      const updated = await api(
        maint,
        `/spaces/${space.id}/documents/${first.documentVersionId}/versions`,
        { method: 'POST', status: 202, multipart: { file: replacement } },
      )
      assert.equal(updated.data.versionNumber, 2)
      const secondId = updated.data.documentVersionId
      assert.notEqual(secondId, first.documentVersionId)

      const renamed = textFixture(
        'update-check-renamed.txt',
        512,
        '改名不应被接受。',
      )
      const refused = await api(
        maint,
        `/spaces/${space.id}/documents/${first.documentVersionId}/versions`,
        { method: 'POST', status: 422, multipart: { file: renamed } },
      )
      assert.match(
        refused.data.detail ?? '',
        /文件名一致/,
        `改名更新应被拒绝，实际 ${refused.status} ${JSON.stringify(refused.data)}`,
      )

      const draftAfter = await api(maint, `/spaces/${space.id}/draft`)
      await api(maint, `/spaces/${space.id}/draft`, {
        method: 'PUT',
        headers: { 'If-Match': draftAfter.headers.etag },
        data: {
          entries: [
            {
              documentVersionId: secondId,
              replacesDocumentVersionId: first.documentVersionId,
              policyState: 'EFFECTIVE',
              effectiveFrom: '2025-01-01',
              effectiveUntil: null,
            },
          ],
        },
      })

      const parsed = await poll(
        async () =>
          (await api(maint, `/spaces/${space.id}/draft/documents/${secondId}`))
            .data,
        (document) => document.parseState === 'READY',
        { timeout: 40_000, label: '等待新版本解析完成' },
      )
      assert.match(parsed.previewText ?? '', /每天二百元/)

      const drawer = await openDrawer(maint, space)
      await visible(drawer.getByText('更新', { exact: true }))
      await button(drawer, '关闭').click()
      return {
        spaceId: space.id,
        newVersion: updated.data.versionNumber,
        replaced: first.documentVersionId,
      }
    },
  )

  return Object.fromEntries(
    Object.entries(spaces).filter(([, space]) => space.publicationId),
  )
}

async function checkStaleDraft(step, maint, space) {
  await step(
    'KB-ETAG',
    '其他窗口修改草稿后，旧版本提审被拒并刷新草稿',
    maint.page,
    async () => {
      const drawer = await openDrawer(maint, space)
      const before = await api(maint, draft(space.id))
      await visible(
        drawer.getByText(`草稿版本 ${before.headers.etag}`, { exact: true }),
      )
      const written = await api(maint, draft(space.id), {
        method: 'PUT',
        headers: { 'If-Match': before.headers.etag },
        data: { entries: before.data.entries },
      })
      assert.notEqual(written.headers.etag, before.headers.etag)
      const rejected = await browserRequest(
        maint.page,
        'POST',
        /\/publish-requests$/,
        () => button(drawer, '提交变更审核').click(),
        412,
      )
      assert.equal(rejected.data.code, 'ETAG_MISMATCH')
      await visible(
        drawer.getByText(`草稿版本 ${written.headers.etag}`, { exact: true }),
      )
      assert.equal(
        (await api(maint, `/spaces/${space.id}`)).data.currentPublicationId,
        space.publicationId,
      )
      return {
        previousEtag: before.headers.etag,
        currentEtag: written.headers.etag,
        requestId: rejected.requestId,
      }
    },
  )
}

async function checkSizes(step, maint, space) {
  await step(
    'KB-SIZE-REJECT',
    '前端拦截超限文件，单文件 10 MiB 限制返回 413',
    maint.page,
    async () => {
      const invalid = textFixture(
        'oversize.txt',
        maxFileBytes + 1,
        '合成测试资料',
      )
      const modal = await openImport(maint, space, 'row')
      await modal.locator('input[type=file]').setInputFiles(invalid)
      await visible(
        modal.getByRole('alert').filter({ hasText: 'oversize.txt' }),
      )
      assert(await button(modal, '开始导入').isDisabled())
      const denied = await api(maint, `/spaces/${space.id}/imports`, {
        method: 'POST',
        status: 413,
        multipart: { files: invalid },
      })
      assert.equal(
        denied.data.code,
        'PAYLOAD_TOO_LARGE',
        `上传 ${invalid.buffer.length} 字节，HTTP ${denied.status}，code=${denied.data.code}，requestId=${denied.requestId}`,
      )
      return {
        bytes: invalid.buffer.length,
        status: denied.status,
        requestId: denied.requestId,
      }
    },
  )
  await step(
    'KB-SIZE-VALID',
    '10 MiB 原件可上传，浏览器展示真实 XHR 字节进度',
    maint.page,
    async () => {
      const valid = textFixture(
        'exact-limit.txt',
        maxFileBytes,
        '合成测试资料：上传大小边界',
      )
      await maint.page.addInitScript(() => {
        globalThis.e2eUploadProgress = { transfers: [], rendered: [] }
        const metrics = globalThis.e2eUploadProgress
        const send = globalThis.XMLHttpRequest.prototype.send
        globalThis.XMLHttpRequest.prototype.send = function (body) {
          if (body instanceof globalThis.FormData) {
            this.upload.addEventListener('progress', (event) => {
              if (event.lengthComputable)
                metrics.transfers.push({
                  loaded: event.loaded,
                  total: event.total,
                })
            })
          }
          return send.call(this, body)
        }
        const observer = new globalThis.MutationObserver(() => {
          const bar = globalThis.document.querySelector(
            'progress[aria-label^="上传进度"]',
          )
          if (
            bar?.getClientRects().length &&
            metrics.rendered.at(-1) !== bar.value
          )
            metrics.rendered.push(bar.value)
        })
        observer.observe(globalThis.document, {
          subtree: true,
          attributes: true,
          childList: true,
          attributeFilter: ['value'],
        })
      })
      const modal = await openImport(maint, space, 'row')
      await modal.locator('input[type=file]').setInputFiles(valid)
      await visible(modal.locator('pre').filter({ hasText: '上传大小边界' }))
      const accepted = await upload(maint, space, modal)
      const progress = await maint.page.evaluate(
        () => globalThis.e2eUploadProgress,
      )
      assert(
        progress.transfers.some(
          ({ loaded, total }) => loaded > 0 && total > maxFileBytes,
        ),
        JSON.stringify(progress),
      )
      assert(progress.rendered.length > 0, '真实上传时未渲染进度条')
      const processed = await batchResult(maint, accepted)
      assert.equal(processed.files[0].sizeBytes, maxFileBytes)
      assert.equal(processed.files[0].status, 'READY')
      await visible(modal.getByText(/已将 1 份文档加入草稿/))
      return {
        maximumBytes: maxFileBytes,
        progress,
        batchId: accepted.data.batchId,
        uploadRequestId: accepted.requestId,
      }
    },
  )
}

async function checkSearch(step, reader, maint, space, fixtures) {
  const current = space.documents.find((file) => file.name === normalName)
  await step(
    'KB-SEARCH',
    '搜索接口按标题与正文对账，文档详情真实渲染',
    reader.page,
    async () => {
      const search = async (q, field) =>
        api(
          reader,
          `/spaces/${space.id}/search?${new URLSearchParams({ q, field })}`,
        )
      const title = await search('FIN-TR-017', 'NAME')
      assert(
        title.data.items.some(
          (item) =>
            item.documentVersionId === current.documentVersionId &&
            item.match === 'NAME',
        ),
      )
      const text = await search('费用分摊', 'TEXT')
      assert(
        text.data.items.some(
          (item) =>
            item.documentVersionId === current.documentVersionId &&
            item.match === 'TEXT',
        ),
      )
      const empty = await search('火星冰川结晶', 'TEXT')
      assert.equal(empty.data.status, 'NO_MATCH')
      assert.deepEqual(empty.data.items, [])
      await reader.page.goto(docPath(space.id, current.documentVersionId))
      await visible(
        reader.page.getByRole('heading', { name: current.name, exact: true }),
      )
      await visible(reader.page.getByText(/费用分摊方式/))
      return {
        documentVersionId: current.documentVersionId,
        titleRequestId: title.requestId,
        textRequestId: text.requestId,
        empty: empty.data.status,
      }
    },
  )
  await step(
    'KB-DUP',
    '同空间重复导入跳过且原草稿版本保持',
    maint.page,
    async () => {
      const modal = await openImport(maint, space, 'row')
      const before = await api(maint, draft(space.id))
      await modal
        .locator('input[type=file]')
        .setInputFiles(fixtures.get(normalName).path)
      const accepted = await upload(maint, space, modal)
      const final = await batchResult(maint, accepted)
      assert.equal(final.files[0].status, 'SKIPPED_DUPLICATE')
      await visible(modal.getByText(/同名已跳过/).first())
      assert.equal(
        (await api(maint, draft(space.id))).headers.etag,
        before.headers.etag,
      )
      await closeImport(modal)
      return {
        status: final.files[0].status,
        batchId: final.batchId,
        requestId: accepted.requestId,
      }
    },
  )
  await step(
    'KB-READ',
    '只读用户不能导入，文档地址缺少空间时明确提示',
    reader.page,
    async () => {
      await reader.page.goto('/knowledge')
      const listRow = row(reader.page, space.name)
      await visible(listRow)
      assert(await button(listRow, '导入').isDisabled())
      await reader.page.goto(`/documents/${current.documentVersionId}`)
      await visible(
        reader.page.getByRole('alert').filter({ hasText: '缺少空间上下文' }),
      )
      return { readOnly: true, documentVersionId: current.documentVersionId }
    },
  )
}

export async function withdrawKnowledge(admin, space) {
  const dialog = await reviewDialog(admin, space, '已发布')
  await dialog.getByLabel('审核意见').fill('合成测试：撤回发布后核对旧引用')
  return browserRequest(
    admin.page,
    'POST',
    /\/withdrawals$/,
    () => button(dialog, '撤回发布').click(),
    200,
  )
}
