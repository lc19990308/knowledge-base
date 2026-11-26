import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { URL } from 'node:url'
import { Buffer } from 'node:buffer'
import { repo } from './stack.mjs'

export const prefix = '/api/v1'
const fixtureRoot = join(repo, 'docs', 'e2e-fixtures')
export const sha256 = (bytes) =>
  createHash('sha256').update(bytes).digest('hex')

export async function loadFixtures() {
  const files = new Map()
  const manifests = await Promise.all(
    ['SHA256SUMS', 'formats/SHA256SUMS'].map(async (name) => {
      const path = join(fixtureRoot, name)
      const bytes = await readFile(path)
      for (const line of bytes.toString('utf8').trim().split(/\r?\n/)) {
        const [, fingerprint, relative] =
          /^([a-f0-9]{64})\s+\*?(upload\/.+)$/.exec(line) ?? []
        assert(fingerprint && relative, `校验清单格式错误 ${name}`)
        const filePath = resolve(dirname(path), relative)
        assert(filePath.startsWith(`${dirname(path)}/upload/`))
        const file = await readFile(filePath)
        assert.equal(sha256(file), fingerprint, `样本哈希不符 ${relative}`)
        const fileName = relative.slice('upload/'.length)
        assert(!files.has(fileName), `重复样本 ${fileName}`)
        files.set(fileName, {
          path: filePath,
          fingerprint,
          size: file.byteLength,
        })
      }
      return { name, sha256: sha256(bytes) }
    }),
  )
  return { files, manifests }
}

export async function parseResponse(response, expected) {
  const status = response.status()
  const headers = response.headers()
  const body = [204, 205].includes(status)
    ? null
    : await response.json().catch(() => null)
  const requestId = headers['x-request-id'] ?? body?.requestId
  const path = new URL(response.url()).pathname
  const diagnostic = `${path} HTTP ${status} ${body?.code ?? ''} requestId=${requestId ?? '无'}`
  if (Array.isArray(expected)) assert(expected.includes(status), diagnostic)
  else assert.equal(status, expected, diagnostic)
  if (status >= 400) {
    assert(
      headers['content-type']?.startsWith('application/problem+json'),
      diagnostic,
    )
    assert.equal(body?.status, status, diagnostic)
    assert.equal(typeof body?.code, 'string', diagnostic)
  } else if (status !== 204 && status !== 205) {
    assert.equal(body?.code, 'OK', diagnostic)
    assert(Object.hasOwn(body, 'data'), diagnostic)
  }
  if (body) {
    assert.equal(typeof body.requestId, 'string', diagnostic)
    if (headers['x-request-id'])
      assert.equal(body.requestId, requestId, diagnostic)
  }
  return {
    data: status >= 400 ? body : body?.data,
    headers,
    path,
    requestId,
    status,
  }
}

export async function api(
  actor,
  path,
  { method = 'GET', data, status = 200, headers: extra = {}, multipart } = {},
) {
  assert(
    path.startsWith('/') && !path.startsWith('//'),
    '只允许应用内 API 路径',
  )
  const headers = { ...extra }
  if (method !== 'GET') {
    const csrf = await api(actor, '/auth/csrf')
    headers['X-CSRF-TOKEN'] = csrf.data.token
    headers['Idempotency-Key'] ??= randomUUID()
  }
  return parseResponse(
    await actor.context.request.fetch(`${prefix}${path}`, {
      method,
      data,
      multipart,
      headers,
      timeout: 20_000,
      maxRedirects: 0,
    }),
    status,
  )
}

export async function browserRequest(page, method, pattern, act, expected) {
  const [response] = await Promise.all([
    page.waitForResponse(
      (response) => {
        const path = new URL(response.url()).pathname
        return (
          path.startsWith(prefix) &&
          response.request().method() === method &&
          pattern.test(path)
        )
      },
      { timeout: 20_000 },
    ),
    act(),
  ])
  return parseResponse(response, expected)
}

export async function readBlob(actor, path, status) {
  const response = await actor.context.request.get(`${prefix}${path}`, {
    timeout: 15_000,
    maxRedirects: 0,
  })
  if (status !== 200) return parseResponse(response, status)
  assert.equal(response.status(), 200)
  assert(
    response.headers()['content-type']?.startsWith('application/octet-stream'),
  )
  return response.body()
}

export function textFixture(name, bytes, text) {
  const buffer = Buffer.alloc(bytes, 0x20)
  assert(text.length < bytes)
  buffer.write(text)
  return { name, mimeType: 'text/plain', buffer }
}

export async function visible(locator) {
  await locator.waitFor({ state: 'visible' })
  return locator
}

export const button = (target, name) =>
  target.getByRole('button', { name, exact: typeof name === 'string' })
export const row = (target, name) =>
  target.getByRole('row').filter({ hasText: name })
