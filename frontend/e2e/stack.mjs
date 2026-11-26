import { execFile, spawn } from 'node:child_process'
import { once } from 'node:events'
import { closeSync, openSync } from 'node:fs'
import {
  cp,
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  writeFile,
} from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { env, platform } from 'node:process'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath, URL } from 'node:url'
import { promisify } from 'node:util'
import { build, preview } from 'vite'
import { randomBytes } from 'node:crypto'

const exec = promisify(execFile)
export const frontend = fileURLToPath(new URL('../', import.meta.url))
export const repo = dirname(frontend.replace(/\/$/, ''))
const bind = '127.0.0.1'
/** 隔离后端固定的登录验证码：E2E 无法识别随机图形，测试因此知道该填什么。 */
export const captchaCode = 'K7QM'
const dbUser = 'regression'
const local = (port) => `http://${bind}:${port}`

async function freePorts(count) {
  const servers = Array.from({ length: count }, () => createServer())
  try {
    await Promise.all(
      servers.map(async (server) => {
        server.listen(0, bind)
        await once(server, 'listening')
      }),
    )
    return servers.map((server) => server.address().port)
  } finally {
    for (const server of servers) server.close()
  }
}

export async function poll(
  read,
  done,
  { timeout = 30_000, label = '轮询' } = {},
) {
  const until = Date.now() + timeout
  let last
  do {
    last = await read()
    if (done(last)) return last
    await delay(250)
  } while (Date.now() < until)
  throw new Error(
    `${label}超时，最后状态 ${JSON.stringify(last)?.slice(0, 1200)}`,
  )
}

function launch(command, args, options, logPath) {
  const fd = openSync(logPath, 'wx', 0o600)
  try {
    const child = spawn(command, args, {
      ...options,
      stdio: ['ignore', fd, fd],
    })
    const closed = new Promise((resolve) => {
      child.once('close', resolve)
      child.once('error', resolve)
    })
    return { child, closed, logPath }
  } finally {
    closeSync(fd)
  }
}

async function stop(process) {
  if (!process) return
  process.child.kill('SIGTERM')
  const stopped = await Promise.race([
    process.closed.then(() => true),
    delay(5000, false, { ref: false }),
  ])
  if (!stopped) {
    process.child.kill('SIGKILL')
    await process.closed
  }
}

async function removeRuntime(root, base) {
  const resolved = await realpath(root)
  if (
    resolved !== root ||
    dirname(resolved) !== base ||
    !basename(resolved).startsWith('aiqa-e2e-')
  ) {
    throw new Error('隔离目录校验失败，保留目录供人工检查。')
  }
  await rm(resolved, { recursive: true })
}

/** 启动独立的本地数据库、后端与生产构建预览，继承的开发/网关环境不传入应用。 */
export async function startStack(reportDir, accounts) {
  // Darwin 的 Unix socket 地址长度有上限，使用短的系统临时路径。
  const base = await realpath(platform === 'darwin' ? '/tmp' : tmpdir())
  const root = await mkdtemp(join(base, 'aiqa-e2e-'))
  const dbPath = join(root, 'pg')
  const backend = join(root, 'backend')
  const dist = join(root, 'dist')
  const password = randomBytes(24).toString('hex')
  const pwFile = join(root, 'pg-password')
  let database
  let application
  let frontendServer
  const close = async () => {
    frontendServer?.httpServer.closeAllConnections()
    if (frontendServer)
      await new Promise((resolve) => frontendServer.httpServer.close(resolve))
    await stop(application)
    await stop(database)
    await removeRuntime(root, base)
  }

  try {
    const [dbPort, apiPort, uiPort] = await freePorts(3)
    const dbArgs = [
      '-X',
      '-w',
      '-h',
      bind,
      '-p',
      String(dbPort),
      '-U',
      dbUser,
      '-d',
      'postgres',
      '-v',
      'ON_ERROR_STOP=1',
    ]
    const sql = async (statement) => {
      const { stdout } = await exec('psql', [...dbArgs, '-Atc', statement], {
        env: { PATH: env.PATH, PGPASSWORD: password },
        timeout: 5000,
      })
      return stdout.trim()
    }
    await writeFile(pwFile, `${password}\n`, { mode: 0o600 })
    await mkdir(backend)
    await Promise.all([
      exec(
        'initdb',
        [
          '-D',
          dbPath,
          '-U',
          dbUser,
          '-A',
          'scram-sha-256',
          `--pwfile=${pwFile}`,
          '-E',
          'UTF8',
          '--no-locale',
          '--no-sync',
        ],
        { timeout: 25_000 },
      ),
      cp(join(repo, 'backend', 'pom.xml'), join(backend, 'pom.xml')),
      cp(join(repo, 'backend', 'src'), join(backend, 'src'), {
        recursive: true,
      }),
      build({
        root: frontend,
        logLevel: 'error',
        build: { outDir: dist },
        configFile: join(frontend, 'vite.config.ts'),
      }),
    ])
    database = launch(
      'postgres',
      ['-D', dbPath, '-h', bind, '-p', String(dbPort), '-k', root],
      {},
      join(reportDir, 'postgres.log'),
    )
    await poll(
      async () => sql('show data_directory').catch(() => ''),
      (value) => value === dbPath,
      {
        timeout: 15_000,
        label: '隔离 PostgreSQL 就绪',
      },
    )

    const maven = launch(
      'mvn',
      ['-o', '-DskipTests', 'package'],
      { cwd: backend },
      join(reportDir, 'maven.log'),
    )
    // Maven 在隔离副本构建，日常后端仍使用其自己的 target 目录。
    const mavenExit = await maven.closed
    if (mavenExit !== 0) throw new Error('隔离后端构建失败，请查看 maven.log。')
    const backendEnv = {
      PATH: env.PATH,
      JAVA_HOME: env.JAVA_HOME,
      LANG: 'en_US.UTF-8',
      TZ: 'Asia/Shanghai',
      DB_URL: `jdbc:postgresql://${bind}:${dbPort}/postgres`,
      DB_USER: dbUser,
      DB_PASSWORD: password,
      SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE: '11MB',
      SERVER_ADDRESS: bind,
      SERVER_PORT: String(apiPort),
      PROCESS_ROLE: 'all',
      WORKER_ENABLED: 'true',
      OBJECT_STORE_TYPE: 'local',
      OBJECT_STORE_LOCAL_ROOT: join(root, 'objects'),
      DEV_HEADER_AUTH: 'false',
      // 登录必填图形验证码；隔离环境固定字符，生产不会设置这个变量。
      CAPTCHA_FIXED_CODE: captchaCode,
      MODEL_PROVIDER: 'fake',
      // 判定器默认只验证接线；接真实模型时由调用方显式设 RELEVANCE_GATE=model。
      RELEVANCE_GATE: env.RELEVANCE_GATE ?? 'fake',
      // 向量召回按需开启：嵌入服务不在时保持关闭，检索回退词法。
      ...(env.E2E_EMBEDDING === '1'
        ? {
            EMBEDDING_ENABLED: 'true',
            EMBEDDING_URL: env.E2E_EMBEDDING_URL ?? 'http://127.0.0.1:3437/v1',
            EMBEDDING_MODEL: env.E2E_EMBEDDING_MODEL ?? 'qwen3-embed',
          }
        : {}),
      // 凭据只从环境变量透传，不写进代码或测试数据；本地网关可给任意非空值。
      ...(env.AIQA_MODEL_CREDENTIALS_MAGPIE
        ? { AIQA_MODEL_CREDENTIALS_MAGPIE: env.AIQA_MODEL_CREDENTIALS_MAGPIE }
        : {}),
      AIQA_BOOTSTRAP_ADMIN_USERNAME: accounts.admin.username,
      AIQA_BOOTSTRAP_ADMIN_PASSWORD: accounts.admin.password,
      AIQA_BOOTSTRAP_USER_USERNAME: 'e2e_bootstrap_user',
      AIQA_BOOTSTRAP_USER_PASSWORD: randomBytes(18).toString('hex'),
    }
    application = launch(
      'java',
      ['-jar', join(backend, 'target', 'ai-qa-backend-0.1.0-SNAPSHOT.jar')],
      { cwd: backend, env: backendEnv },
      join(reportDir, 'backend.log'),
    )
    await poll(
      async () => {
        const log = await readFile(application.logPath, 'utf8')
        if (application.child.exitCode !== null)
          throw new Error('隔离后端已退出，请查看 backend.log。')
        if (!log.includes('Started BackendApplication')) return null
        return globalThis
          .fetch(`${local(apiPort)}/actuator/health/readiness`, {
            signal: globalThis.AbortSignal.timeout(1000),
          })
          .then((response) => response.json())
          .catch(() => null)
      },
      (health) => health?.status === 'UP',
      { timeout: 30_000, label: '隔离后端就绪' },
    )

    frontendServer = await preview({
      configFile: false,
      root: frontend,
      logLevel: 'error',
      build: { outDir: dist },
      preview: {
        host: bind,
        port: uiPort,
        strictPort: true,
        proxy: { '/api': local(apiPort) },
      },
    })
    return { baseURL: local(uiPort), sql, close }
  } catch (error) {
    await close()
    throw error
  }
}
