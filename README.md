# AI 智能问答平台

[English](README.en.md) | 简体中文

面向企业知识空间的政策问答系统：文档经审核发布后才可检索，回答逐段标注依据，引用可定位到原文；未审、已撤回、已失效的内容不会出现在回答里。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Vue 3.5 + TypeScript + Vite 7 + Vue Router；ECharts、CodeMirror、TipTap、`@lucide/vue` |
| 后端 | Java 17 + Spring Boot 4.1（WebMVC / JDBC / Security / OAuth2 Client）、PostgreSQL + Flyway、Apache Lucene（向量召回可选） |
| 测试 | 后端 JUnit 5 + MockMvc（跑真实 PostgreSQL 集成测试）；前端自研 Playwright E2E |
| CI | GitHub Actions（前后端两条流水线） |

## 目录结构

```
backend/    Java 后端（Maven，Spring Boot，Flyway 迁移）
frontend/   Vue 前端（Vite，npm）+ e2e/ 自研端到端脚本
docs/       需求、技术设计、OpenAPI 契约、变更记录与 E2E 证据
scripts/    索引与语料一致性辅助脚本
原型图/     设计原型（HTML）
```

## 本地运行

前提：JDK 17、Maven 3.6+、PostgreSQL、Node.js `^20.19` / `^22.13` / `>=24`。

后端（仓库根目录执行，凭据放在项目之外的本地文件里）：

```sh
set -a; . ~/.config/ai-qa/dev.env; set +a
cd backend && mvn spring-boot:run
```

`DB_URL`、`DB_USER`、`DB_PASSWORD` 必需；其余变量（对象存储、worker 开关、模型网关、验证码有效期等）见 [backend/README.md](backend/README.md)。首次启动会建引导账号（`AIQA_BOOTSTRAP_*` 可覆盖初始密码）。

前端：

```sh
cd frontend && npm ci && npm run dev
```

Vite 默认 `http://localhost:5173`，把 `/api`、`/oauth2`、`/login/oauth2` 代理到本机 `8080`。

登录需要账号密码 + 图形验证码（服务端出题、一次有效，默认 2 分钟过期）；验证码接口为 `GET /api/v1/auth/captcha`，登录页不提供开发身份入口。

## 验证

| 范围 | 命令 | 说明 |
| --- | --- | --- |
| 后端全部测试 + 打包 | `cd backend && mvn verify` | 未配置 `IT_DB_*` 时跳过数据库集成测试；配置后每个测试类自建独立 schema |
| 前端 lint / 类型 / 格式 | `cd frontend && npm run lint && npm run typecheck && npm run format:check` | lint 警告即失败 |
| 前端生产构建 | `cd frontend && npm run build` | 内置 `vue-tsc`，产物在 `frontend/dist/` |
| 端到端 | `cd frontend && npm run test:e2e` | 需本机 PostgreSQL 工具链（`initdb`/`postgres`/`psql`）、Java、Maven、Chrome；会拉起隔离前后端与数据库 |

## CI/CD

- `.github/workflows/backend.yml`：PR 与 push 触发，启动 Postgres 16 service，执行 `mvn -B verify`；仅默认分支 push 上传 `backend-jar-<短SHA>`。
- `.github/workflows/frontend.yml`：PR 与 push 触发，执行 `npm ci` → `lint` → `format:check` → `build`；仅默认分支 push 上传 `frontend-dist-<短SHA>`。
- 默认分支 `master` 已开保护：必须通过上述两个状态检查、禁止强推与删除。功能开发走 `feat/xxx` 分支 → PR → 检查通过后合并。
- **尚未接入 CD**：部署目标（服务器、发布方式、密钥、回滚路径）未确定前不写占位部署；确定后在流水线里补 deploy job（database → backend → frontend，带健康检查与人工审批）。
- E2E 未纳入流水线：脚本依赖本机工具链与离线 Maven 构建，改造后再接入。

## 文档

| 内容 | 文件 |
| --- | --- |
| 需求 | [docs/ai-qa-prd.md](docs/ai-qa-prd.md) |
| 技术方案 | [docs/ai-qa-technical-design.md](docs/ai-qa-technical-design.md) |
| 后端设计 | [docs/ai-qa-backend-design.md](docs/ai-qa-backend-design.md) |
| 接口契约 | [docs/ai-qa-openapi.yaml](docs/ai-qa-openapi.yaml) |
| 设计 token | [docs/ai-qa-design-tokens.md](docs/ai-qa-design-tokens.md) |
| 变更记录 | `docs/ai-qa-change-*.md`（每次改动一篇，含验证命令与遗留问题） |
| E2E 报告 | [docs/ai-qa-e2e-run-2026-11-25.md](docs/ai-qa-e2e-run-2026-11-25.md) |

## 约定

- 改动必须在本目录 `docs/` 追加变更记录，写清实现过程、验证命令与结果、遗留问题。
- SQL 变更只追加到指定迁移文件，不改既有语句；建表字段必须带中文注释。
- 接口字段或错误码变更需同步 `docs/ai-qa-openapi.yaml`。
- 密钥、令牌、个人路径不进代码与提交；本地配置放仓库外或 `*.local` 文件。
