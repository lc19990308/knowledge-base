# AI Knowledge Q&A Platform

English | [简体中文](README.md)

Policy Q&A over enterprise knowledge spaces: documents become searchable only after review and publication, every answer paragraph carries its evidence, and citations resolve to the source text. Unreviewed, withdrawn, or expired material never appears in answers.

## Stack

| Layer | Choice |
| --- | --- |
| Frontend | Vue 3.5 + TypeScript + Vite 7 + Vue Router; ECharts, CodeMirror, TipTap, `@lucide/vue` |
| Backend | Java 17 + Spring Boot 4.1 (WebMVC / JDBC / Security / OAuth2 Client), PostgreSQL + Flyway, Apache Lucene (optional vector recall) |
| Tests | Backend JUnit 5 + MockMvc against real PostgreSQL; custom Playwright end-to-end suite on the frontend |
| CI | GitHub Actions (separate backend and frontend pipelines) |

## Layout

```
backend/   Java service (Maven, Spring Boot, Flyway migrations)
frontend/  Vue app (Vite, npm) plus e2e/ end-to-end scripts
docs/      Requirements, design, OpenAPI contract, change records, E2E evidence
scripts/   Index and corpus consistency helpers
原型图/     HTML design prototypes
```

## Run locally

Prerequisites: JDK 17, Maven 3.6+, PostgreSQL, Node.js `^20.19` / `^22.13` / `>=24`.

Backend (run from the repository root; keep credentials outside the repo):

```sh
set -a; . ~/.config/ai-qa/dev.env; set +a
cd backend && mvn spring-boot:run
```

`DB_URL`, `DB_USER` and `DB_PASSWORD` are required. Other settings (object storage, worker toggles, model gateway, captcha TTL) are documented in [backend/README.md](backend/README.md). Bootstrap accounts are created on first start; `AIQA_BOOTSTRAP_*` overrides their initial passwords.

Frontend:

```sh
cd frontend && npm ci && npm run dev
```

Vite serves `http://localhost:5173` and proxies `/api`, `/oauth2` and `/login/oauth2` to `8080`.

Sign-in requires username, password and a graphical captcha (issued server-side, single use, 2-minute TTL by default). The captcha endpoint is `GET /api/v1/auth/captcha`; the login page has no developer-identity entry.

## Verification

| Scope | Command | Notes |
| --- | --- | --- |
| Backend tests + package | `cd backend && mvn verify` | Database integration tests are skipped unless `IT_DB_*` is set; each test class gets its own schema |
| Frontend lint / types / format | `cd frontend && npm run lint && npm run typecheck && npm run format:check` | Warnings fail lint |
| Frontend production build | `cd frontend && npm run build` | Runs `vue-tsc` first; output in `frontend/dist/` |
| End-to-end | `cd frontend && npm run test:e2e` | Needs local PostgreSQL binaries (`initdb`/`postgres`/`psql`), Java, Maven and Chrome; starts an isolated stack |

## CI/CD

- `.github/workflows/backend.yml`: on pull requests and pushes, starts a Postgres 16 service, runs `mvn -B verify`, and on the default branch uploads `backend-jar-<short-sha>`.
- `.github/workflows/frontend.yml`: on pull requests and pushes, runs `npm ci` → `lint` → `format:check` → `build`, and on the default branch uploads `frontend-dist-<short-sha>`.
- The default branch `master` is protected: both status checks must pass; force pushes and deletions are blocked. Feature work goes through `feat/*` branches and pull requests.
- **No CD yet**: deployment target, release method, credentials and rollback path are undecided, so no placeholder deploy job exists. Once decided, the pipeline gains a deploy job (database → backend → frontend, with health checks and manual approval).
- E2E is not in the pipeline: the scripts depend on local tooling and an offline Maven build, and need rework before they can run on hosted runners.

## Documentation

| Topic | File |
| --- | --- |
| Requirements | [docs/ai-qa-prd.md](docs/ai-qa-prd.md) |
| Technical design | [docs/ai-qa-technical-design.md](docs/ai-qa-technical-design.md) |
| Backend design | [docs/ai-qa-backend-design.md](docs/ai-qa-backend-design.md) |
| API contract | [docs/ai-qa-openapi.yaml](docs/ai-qa-openapi.yaml) |
| Design tokens | [docs/ai-qa-design-tokens.md](docs/ai-qa-design-tokens.md) |
| Change records | `docs/ai-qa-change-*.md` (one per change, with commands run and open issues) |
| E2E report | [docs/ai-qa-e2e-run-2026-11-25.md](docs/ai-qa-e2e-run-2026-11-25.md) |

## Conventions

- Every change appends a record under `docs/` describing implementation, verification commands and results, and open issues.
- SQL changes are appended to the designated migration file only; table and column comments are written in Chinese.
- API field or error-code changes must be reflected in `docs/ai-qa-openapi.yaml`.
- Secrets, tokens and personal paths never enter code or commits; local configuration lives outside the repository or in `*.local` files.
