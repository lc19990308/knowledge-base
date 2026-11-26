import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { URL } from 'node:url'
import { qualityCases, scoreAnswer } from './quality.mjs'
import {
  api,
  browserRequest,
  button,
  prefix,
  readBlob,
  sha256,
  visible,
} from './support.mjs'
import { poll } from './stack.mjs'
import { setReadAccessFromUi, withdrawKnowledge } from './knowledge.mjs'

const questionsPath = /\/questions$/
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const chat = (page) => page.locator('[aria-label="聊天记录"]')
const questionInput = (page) => page.getByRole('textbox', { name: '提问内容' })
const citationDialog = (page) =>
  page.getByRole('dialog', { name: '引用预览', exact: true })

function archiveAction(page, title, action) {
  return page.getByRole('button', {
    name: `${action}会话：${title}`,
    exact: true,
  })
}

async function ask(actor, assistant, text, { followUp } = {}) {
  const { page } = actor
  await page.goto(
    followUp
      ? `/assistant?conversation=${followUp}&assistant=${assistant.id}`
      : `/assistant?assistant=${assistant.id}&q=${encodeURIComponent(text)}`,
  )
  if (followUp) await questionInput(page).fill(text)
  else
    await poll(
      () => questionInput(page).inputValue(),
      (value) => value === text,
      { label: '提问预填' },
    )
  const request = page.waitForRequest(
    (item) =>
      item.method() === 'POST' &&
      new URL(item.url()).pathname.startsWith(prefix) &&
      questionsPath.test(new URL(item.url()).pathname),
  )
  const started = Date.now()
  const [accepted, sent] = await Promise.all([
    browserRequest(
      page,
      'POST',
      questionsPath,
      () => button(page, '发送').click(),
      202,
    ),
    request,
  ])
  const body = sent.postDataJSON()
  assert.equal(body.text, text)
  assert(
    !Object.hasOwn(body, 'requestedSpaceIds'),
    '页面提问的空间应由助手绑定决定',
  )
  assert(sent.headers()['idempotency-key'], '提问必须携带幂等键')
  const conversationId = followUp ?? accepted.data.conversation?.conversationId
  assert(uuid.test(conversationId), '问答会话 ID 无效')
  if (followUp)
    assert.equal(accepted.path, `${prefix}/conversations/${followUp}/questions`)
  else {
    assert.equal(body.assistantId, assistant.id)
    assert.equal(accepted.data.conversation.assistantId, assistant.id)
    assert.equal(accepted.path, `${prefix}/questions`)
  }
  const questionId = accepted.data.questionId
  const result = await poll(
    async () => (await api(actor, `/questions/${questionId}`)).data,
    (state) => ['SUCCEEDED', 'FAILED'].includes(state.status),
    { label: '问答终态', timeout: 45_000 },
  )
  const elapsedMs = Date.now() - started
  await page.waitForURL(
    (url) => url.searchParams.get('conversation') === conversationId,
  )
  await visible(chat(page).getByText(text, { exact: true }).last())
  await visible(
    chat(page)
      .locator('.msg-ai')
      .last()
      .getByText(result.status === 'SUCCEEDED' ? '已回答' : '处理失败', {
        exact: false,
      })
      .last(),
  )
  const history = await api(actor, `/conversations/${conversationId}/messages`)
  const saved = history.data.items.filter(
    (message) => message.questionId === questionId,
  )
  assert.equal(saved.find((message) => message.role === 'USER')?.text, text)
  assert.equal(
    saved.find((message) => message.role === 'ASSISTANT')?.status,
    result.status,
  )
  return { ...result, conversationId, elapsedMs, requestId: accepted.requestId }
}

async function collectCitations(actor, paragraphs) {
  const references = paragraphs.flatMap((part) => part.citations ?? [])
  const citations = await Promise.all(
    references.map(async ({ citationId }) => {
      const found = await api(actor, `/citations/${citationId}`)
      return { ...found.data, id: citationId, requestId: found.requestId }
    }),
  )
  return { references, citations }
}

async function candidatesFor(sql, questionId, { status, paragraphs }) {
  assert(uuid.test(questionId))
  if (status !== 'SUCCEEDED') return undefined
  if (paragraphs.some((part) => part.kind === 'NO_EVIDENCE')) return []
  // 从同轮隔离库只读模型实际收到的候选和顺序，与最终引用独立核对。
  return JSON.parse(
    await sql(`
    SELECT coalesce(json_agg(json_build_object(
      'name', version.display_filename, 'previewText', evidence.evidence_excerpt,
      'sourceLocation', evidence.source_location, 'documentVersionId', evidence.document_version_id
    ) ORDER BY evidence.ordinal), '[]'::json)
    FROM model_call_usage usage JOIN model_call_evidence evidence USING (call_id)
    JOIN document_version version ON version.id = evidence.document_version_id
    WHERE usage.question_id = '${questionId}'
  `),
  )
}

export async function runAnswers({
  actors,
  assistants,
  spaces,
  fixtures,
  step,
  sql,
  saveQuality,
}) {
  const results = new Map()
  const quality = []
  const conversations = new Map()
  for (const testCase of qualityCases) {
    const actor = actors[testCase.role]
    const { scope } = testCase
    const assistant = assistants[scope]
    if (!assistant) {
      quality.push({
        id: testCase.id,
        title: testCase.title,
        result: 'BLOCKED',
        reason: `助手前置数据未完成：${scope}`,
      })
      continue
    }
    // 质量差异单独保留评分。每道题的浏览器/API/历史链路仍独立断言。
    await step(
      `${testCase.id}-FLOW`,
      `浏览器问答 · ${testCase.title}`,
      actor.page,
      async () => {
        const lane = `${scope}/${testCase.role}`
        const answer = await ask(actor, assistant, testCase.question, {
          followUp: conversations.get(lane),
        })
        conversations.set(lane, answer.conversationId)
        const { references, citations } = await collectCitations(
          actor,
          answer.paragraphs,
        )
        const candidates = await candidatesFor(sql, answer.questionId, answer)
        const observation = {
          ...answer,
          citations,
          candidates,
          paragraphs: answer.paragraphs.map((part) => ({
            ...part,
            citations: part.citations?.map((citation) => ({
              id: citation.citationId,
              name: citation.label,
            })),
          })),
        }
        const score = scoreAnswer(testCase, observation)
        const review = {
          id: testCase.id,
          title: testCase.title,
          question: testCase.question,
          scope: testCase.scope,
          role: testCase.role,
          expected: testCase.expected,
          result: score.score.answerPassed ? 'PASS' : 'FAIL',
          score,
          observation,
        }
        quality.push(review)
        results.set(testCase.id, { actor, answer, references, citations })
        assert.equal(answer.status, 'SUCCEEDED', answer.failureCode)
        return {
          questionId: answer.questionId,
          conversationId: answer.conversationId,
          requestId: answer.requestId,
          elapsedMs: answer.elapsedMs,
          answerKind: score.answer.kind,
          quality: review.result,
          citationIds: references.map(({ citationId }) => citationId),
        }
      },
    )
    if (
      !results.has(testCase.id) &&
      !quality.some((record) => record.id === testCase.id)
    ) {
      quality.push({
        id: testCase.id,
        title: testCase.title,
        question: testCase.question,
        expected: testCase.expected,
        result: 'BLOCKED',
        reason: '见同题 FLOW 的页面或接口失败记录',
      })
    }
    await saveQuality(quality)
  }

  const readerResult = [...results.values()].find(
    ({ actor, citations }) =>
      actor === actors.reader &&
      citations.some(
        (citation) =>
          citation.name.includes('v2.1') &&
          citation.previewText?.includes('15 个工作日内'),
      ),
  )
  if (readerResult)
    await step(
      'QA-CITE',
      '引用预览、原件下载与上传 SHA-256 对账',
      actors.reader.page,
      async () => {
        const { actor, answer, citations } = readerResult
        const selected = citations.find(
          (item) =>
            item.name.includes('v2.1') &&
            item.previewText?.includes('15 个工作日内'),
        )
        await actor.page.goto(
          `/assistant?conversation=${answer.conversationId}`,
        )
        const question = quality.find(
          (item) => item.observation?.questionId === answer.questionId,
        ).question
        const message = chat(actor.page)
          .locator('.msg-user')
          .filter({ hasText: question })
        const answerBlock = message.locator('xpath=following-sibling::*[1]')
        // 同一文件可能有多段引用，依据原始引用顺序选择目标证据。
        const index = readerResult.references.findIndex(
          (ref) => ref.citationId === selected.citationId,
        )
        const detail = await browserRequest(
          actor.page,
          'GET',
          new RegExp(`/citations/${selected.citationId}$`),
          () => answerBlock.locator('.cite-item').nth(index).click(),
          200,
        )
        const modal = citationDialog(actor.page)
        await visible(modal.getByText(/15 个工作日内/))
        await visible(
          modal
            .getByRole('definition')
            .filter({ hasText: detail.data.sourceLocation }),
        )
        assert.equal(detail.data.evidenceGranularity, 'CHUNK')
        assert(
          spaces.normal.documents.some(
            (doc) => doc.documentVersionId === detail.data.documentVersionId,
          ),
        )
        const [download] = await Promise.all([
          actor.page.waitForEvent('download'),
          button(modal, '下载原文件').click(),
        ])
        assert.equal(download.suggestedFilename(), detail.data.name)
        const hash = sha256(await readFile(await download.path()))
        assert.equal(hash, fixtures.get(detail.data.name).fingerprint)
        await button(modal, '关闭预览').click()
        return {
          questionId: answer.questionId,
          citationId: selected.citationId,
          documentVersionId: detail.data.documentVersionId,
          sha256: hash,
          requestId: detail.requestId,
        }
      },
    )
  else
    await step('QA-CITE', '引用预览与下载', actors.reader.page, () =>
      assert.fail('没有取得现行报销时限的正确引用。'),
    )

  const outsiderConversation = conversations.get('normal/outsider')
  if (outsiderConversation)
    await step(
      'QA-ARCH',
      '真实会话归档、恢复并与 API 对账',
      actors.outsider.page,
      async () => {
        const { page } = actors.outsider
        await page.goto(`/assistant?conversation=${outsiderConversation}`)
        const conversation = (
          await api(actors.outsider, `/conversations/${outsiderConversation}`)
        ).data
        const archive = new RegExp(
          `/conversations/${outsiderConversation}/archive$`,
        )
        await browserRequest(
          page,
          'PUT',
          archive,
          () => archiveAction(page, conversation.title, '归档').click(),
          200,
        )
        await visible(page.getByText(/此会话已归档/))
        assert(
          (
            await api(actors.outsider, '/conversations?archived=true')
          ).data.items.some(
            (item) => item.conversationId === outsiderConversation,
          ),
        )
        await button(page, '已归档').click()
        await browserRequest(
          page,
          'PUT',
          archive,
          () => archiveAction(page, conversation.title, '恢复').click(),
          200,
        )
        assert.equal(
          (await api(actors.outsider, `/conversations/${outsiderConversation}`))
            .data.archived,
          false,
        )
        return { conversationId: outsiderConversation }
      },
    )

  await saveQuality(quality)
  return {
    results,
    readerResult,
    outsiderConversation,
    questionIds: [...results.values()].map(({ answer }) => answer.questionId),
  }
}

export async function runAnswerBoundaries({
  actors,
  assistants,
  spaces,
  step,
  answers,
  sql,
}) {
  const { admin, reader, outsider } = actors
  const restricted = [...answers.results.values()].find(
    ({ actor, citations }) =>
      actor === reader &&
      citations.some(
        (cite) =>
          cite.name.startsWith('FIN-SP-006') &&
          cite.previewText?.includes('873'),
      ),
  )
  if (spaces.restricted && assistants.normal && restricted) {
    const citation = restricted.citations.find(
      (item) =>
        item.name.startsWith('FIN-SP-006') && item.previewText?.includes('873'),
    )
    await step(
      'QA-PERM',
      '受限原件及现有引用对未授权读者不可见',
      outsider.page,
      async () => {
        const doc = citation.documentVersionId
        for (const path of [
          `/spaces/${spaces.restricted.id}/search?q=873`,
          `/spaces/${spaces.restricted.id}/documents/${doc}`,
          `/citations/${citation.citationId}`,
        ]) {
          const denied = await api(outsider, path, { status: 404 })
          assert.equal(denied.data.code, 'RESOURCE_NOT_FOUND')
          assert(!JSON.stringify(denied.data).includes('873 元'))
        }
        await readBlob(
          outsider,
          `/citations/${citation.citationId}/content`,
          404,
        )
        await outsider.page.goto(
          `/documents/${doc}?space=${spaces.restricted.id}`,
        )
        await visible(
          outsider.page.getByRole('alert').filter({ hasText: '没有访问权限' }),
        )
        return { citationId: citation.citationId, denied: 404 }
      },
    )
    await step(
      'QA-ADMIN',
      '无显式空间成员记录的系统管理员按既有权限查受限政策',
      admin.page,
      async () => {
        const members = await api(
          admin,
          `/spaces/${spaces.restricted.id}/members`,
        )
        assert(
          !members.data.items.some(
            (member) => member.userId === admin.me.userId,
          ),
          '此案例要求管理员依靠角色权限',
        )
        const space = await api(admin, `/spaces/${spaces.restricted.id}`)
        assert.equal(space.data.canRead, true)
        const found = await api(
          admin,
          `/spaces/${spaces.restricted.id}/search?q=${encodeURIComponent('高原设备验收专项驻点津贴')}`,
        )
        assert(
          found.data.items.some(
            (hit) => hit.documentVersionId === citation.documentVersionId,
          ),
        )
        const question = qualityCases.find(({ id }) => id === 'QA19').question
        const answer = await ask(admin, assistants.normal, question)
        const candidates = await candidatesFor(sql, answer.questionId, answer)
        assert(
          candidates?.some(
            (item) => item.documentVersionId === citation.documentVersionId,
          ),
          `搜索命中 ${citation.documentVersionId}（requestId=${found.requestId}），空间 canRead=true（requestId=${space.requestId}），管理员问题 ${answer.questionId} 的 ${candidates?.length ?? 0} 条问答候选均不含该文档。提问 requestId=${answer.requestId}`,
        )
        const backed = await collectCitations(admin, answer.paragraphs)
        assert(
          backed.citations.some(
            (item) => item.documentVersionId === citation.documentVersionId,
          ),
          `候选命中但回答缺少受限政策引用。questionId=${answer.questionId} requestId=${answer.requestId}`,
        )
        return { questionId: answer.questionId, requestId: answer.requestId }
      },
    )
    await step(
      'QA-REVOKE',
      '撤销读者权限后屏蔽历史回答和旧引用',
      reader.page,
      async () => {
        const revocation = await setReadAccessFromUi(
          admin,
          reader,
          spaces.restricted,
          false,
        )
        const old = await api(
          reader,
          `/questions/${restricted.answer.questionId}`,
        )
        assert(
          old.data.paragraphs.some(
            (part) =>
              part.status === 'MASKED' && !part.text && !part.citations?.length,
          ),
        )
        await api(reader, `/citations/${citation.citationId}`, { status: 404 })
        await readBlob(reader, `/citations/${citation.citationId}/content`, 404)
        await reader.page.goto(
          `/assistant?conversation=${restricted.answer.conversationId}`,
        )
        await visible(
          chat(reader.page)
            .getByText(/来源已不可用/)
            .first(),
        )
        return {
          questionId: old.data.questionId,
          citationId: citation.citationId,
          status: 'MASKED',
          revocation,
        }
      },
    )
  }

  if (answers.readerResult && spaces.normal) {
    await step(
      'QA-WITHDRAW',
      '发布撤回后旧引用、文档和搜索失效',
      admin.page,
      async () => {
        const { answer, citations } = answers.readerResult
        const citation = citations.find(
          (item) =>
            item.name.includes('v2.1') &&
            item.previewText?.includes('15 个工作日内'),
        )
        const withdrawn = await withdrawKnowledge(admin, spaces.normal)
        assert(!withdrawn.data.currentPublicationId)
        await api(
          reader,
          `/spaces/${spaces.normal.id}/search?q=${encodeURIComponent('费用分摊')}`,
          { status: 404 },
        )
        const previewPath = `/spaces/${spaces.normal.id}/documents/${citation.documentVersionId}`
        await api(reader, previewPath, { status: 404 })
        await readBlob(reader, `${previewPath}/content`, 404)
        await api(reader, `/citations/${citation.citationId}`, { status: 404 })
        await readBlob(reader, `/citations/${citation.citationId}/content`, 404)
        const old = await api(reader, `/questions/${answer.questionId}`)
        assert(
          old.data.paragraphs.some(
            (part) =>
              part.status === 'MASKED' && !part.text && !part.citations?.length,
          ),
        )
        await reader.page.goto(
          `/documents/${citation.documentVersionId}?space=${spaces.normal.id}`,
        )
        await visible(
          reader.page.getByRole('alert').filter({ hasText: '来源已撤回' }),
        )
        await reader.page.goto(
          `/assistant?conversation=${answer.conversationId}`,
        )
        await visible(
          chat(reader.page)
            .getByText(/来源已不可用/)
            .first(),
        )
        return {
          questionId: answer.questionId,
          citationId: citation.citationId,
          requestId: withdrawn.requestId,
        }
      },
    )
  }

  if (answers.outsiderConversation) {
    await step(
      'QA-DELETE',
      '取消删除保留历史，确认删除后会话与消息入口失效',
      outsider.page,
      async () => {
        const id = answers.outsiderConversation
        const { page } = outsider
        const { data: conversation } = await api(
          outsider,
          `/conversations/${id}`,
        )
        await page.goto(`/assistant?conversation=${id}`)
        const remove = archiveAction(page, conversation.title, '删除')
        const dialog = page.getByRole('dialog', {
          name: '删除会话',
          exact: true,
        })
        await remove.click()
        await button(dialog, '取消').click()
        assert.equal(
          (await api(outsider, `/conversations/${id}`)).data.conversationId,
          id,
        )
        await remove.click()
        const deleted = await browserRequest(
          page,
          'DELETE',
          new RegExp(`/conversations/${id}$`),
          () => button(dialog, '确认删除').click(),
          204,
        )
        await dialog.waitFor({ state: 'hidden' })
        await page.waitForURL(
          (url) => url.searchParams.get('conversation') !== id,
        )
        await api(outsider, `/conversations/${id}`, { status: 404 })
        await api(outsider, `/conversations/${id}/messages`, { status: 404 })
        return { conversationId: id, requestId: deleted.requestId }
      },
    )
  }
}

export async function verifyConversationUi({ actor, assistants, step }) {
  if (!assistants.normal || !assistants.formats) return
  const { page } = actor
  await step(
    'QA-SWITCH',
    '新提问不写空会话，切换助手只在确认后切换',
    page,
    async () => {
      const before = (await api(actor, '/conversations')).data.total
      const baseline = (await api(actor, '/conversations')).data.items.find(
        (item) => item.assistantId === assistants.normal.id,
      )
      assert(baseline, '缺少现行助手会话')
      await page.goto(`/assistant?conversation=${baseline.conversationId}`)
      await visible(chat(page).locator('.msg-user').first())
      await button(page, /^切换助手：/).click()
      await page
        .getByRole('option', { name: new RegExp(assistants.formats.name) })
        .click()
      const confirm = page.getByRole('dialog', {
        name: '切换助手',
        exact: true,
      })
      await button(confirm, '取消').click()
      assert.equal(
        new URL(page.url()).searchParams.get('conversation'),
        baseline.conversationId,
      )
      await button(page, /^切换助手：/).click()
      await page
        .getByRole('option', { name: new RegExp(assistants.formats.name) })
        .click()
      await button(confirm, '开始新会话').click()
      await page.waitForURL(
        (url) =>
          url.searchParams.get('assistant') === assistants.formats.id &&
          !url.searchParams.has('conversation'),
      )
      await visible(chat(page).getByText(/提出第一个问题后会自动建立会话/))
      assert.equal((await api(actor, '/conversations')).data.total, before)
      assert.equal(
        await page.getByRole('button', { name: /检索范围/ }).count(),
        0,
      )
      return {
        previousConversationId: baseline.conversationId,
        storedConversations: before,
      }
    },
  )
}

/** 把 SSE 响应体拆成事件数组；冒号注释和空块忽略。 */
function parseSse(body) {
  return body
    .split('\n\n')
    .map((block) => {
      let event = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).trim())
      }
      return { event, data: data.join('\n') }
    })
    .filter((frame) => frame.data !== '')
}

/**
 * 增量流回归：提问后立刻订阅 SSE，断言增量先于结束、分片拼接等于最终落库段落；
 * 浏览器侧则确认生成中先出现逐字区域，结束后换成带引用的正式回答。
 *
 * 不依赖任何外部网关：回答瞬间结束时后端会回放缓冲文本再发结束，
 * 因此慢模型和本地替身都能稳定观察到增量。
 */
export async function runStreamingCheck({
  reader,
  assistants,
  step,
  reportDir,
}) {
  await step(
    'STREAM-01',
    '回答增量流：SSE 逐段下发，浏览器逐字显示后换成正式回答',
    reader.page,
    async () => {
      const testCase = qualityCases[0]
      const assistant = assistants[testCase.scope]
      const queued = await api(reader, '/questions', {
        method: 'POST',
        status: 202,
        data: { assistantId: assistant.id, text: testCase.question },
      })
      const questionId = queued.data.questionId
      const stream = await reader.page.evaluate(async (id) => {
        const answer = await globalThis.fetch(
          `/api/v1/questions/${id}/stream`,
          { headers: { Accept: 'text/event-stream' } },
        )
        return {
          status: answer.status,
          contentType: answer.headers.get('content-type'),
          body: await answer.text(),
        }
      }, questionId)
      assert.equal(stream.status, 200)
      assert(
        stream.contentType?.startsWith('text/event-stream'),
        String(stream.contentType),
      )
      const frames = parseSse(stream.body)
      const deltas = frames
        .filter((frame) => frame.event === 'delta')
        .map((frame) => JSON.parse(frame.data).text)
      const deltaAt = frames.findIndex((frame) => frame.event === 'delta')
      const doneAt = frames.findIndex((frame) => frame.event === 'done')
      assert(deltaAt >= 0, '应收到 delta 事件')
      assert(doneAt > deltaAt, 'done 必须晚于增量')
      const finalState = await poll(
        async () => (await api(reader, `/questions/${questionId}`)).data,
        (state) => ['SUCCEEDED', 'FAILED'].includes(state.status),
        { label: '流式回答终态' },
      )
      assert.equal(finalState.status, 'SUCCEEDED')
      assert.equal(finalState.paragraphs?.[0]?.text, deltas.join(''))

      // 浏览器：生成中先显示逐字区域，结束后换成带引用的正式回答。
      const { page } = reader
      await page.goto(
        `/assistant?assistant=${assistant.id}&q=${encodeURIComponent(testCase.question)}`,
      )
      await poll(
        () => questionInput(page).inputValue(),
        (value) => value === testCase.question,
        { label: '流式提问预填' },
      )
      const accepted = page.waitForResponse((response) => {
        const path = new URL(response.url()).pathname
        return (
          response.request().method() === 'POST' && questionsPath.test(path)
        )
      })
      await button(page, '发送').click()
      assert.equal((await accepted).status(), 202)
      await page.waitForSelector('.ans-stream', { timeout: 15_000 })
      const partialText = (await page.locator('.ans-stream').innerText()).trim()
      assert(partialText.length > 0, '生成中应显示逐字文本')
      await page.screenshot({
        path: join(reportDir, 'streaming-in-progress.png'),
      })
      await page.waitForSelector('.msg-ai .cite-item', { timeout: 30_000 })
      const finalText = (
        await chat(page).locator('.msg-ai').last().innerText()
      ).trim()
      assert(finalText.includes('15 个工作日'), finalText)
      assert(finalText.includes('引用文档'), finalText)
      // 引用列表默认展开，点标题可收起、再点恢复，且开关状态同步给读屏。
      const citeBlock = chat(page).locator('.msg-ai .cite-block').last()
      const citeToggle = citeBlock.locator('.cite-toggle')
      const firstCite = citeBlock.locator('.cite-item').first()
      await visible(firstCite)
      assert.equal(await citeToggle.getAttribute('aria-expanded'), 'true')
      await citeToggle.click()
      assert.equal(await citeToggle.getAttribute('aria-expanded'), 'false')
      assert.equal(await firstCite.isVisible(), false)
      await citeToggle.click()
      assert.equal(await citeToggle.getAttribute('aria-expanded'), 'true')
      await visible(firstCite)
      return {
        screenshot: 'streaming-in-progress.png',
        partialText: partialText.slice(0, 160),
        finalText: finalText.slice(0, 240),
        deltaCount: deltas.length,
        deltaText: deltas.join(''),
        persistedText: finalState.paragraphs?.[0]?.text,
        doneAfterDelta: true,
      }
    },
  )
}
