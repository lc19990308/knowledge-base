# 变更记录：登录页移除开发身份入口并新增图形验证码（2026-11-26）

## 背景

登录页此前保留一个折叠的「开发身份（仅本地 DEV 构建）」面板（`import.meta.env.DEV` 时可见，后端 `DEV_HEADER_AUTH=true` 才接受 `X-Dev-Subject`）。用户要求：

1. 登录页去掉这个开发身份入口；
2. 登录必须输入图形验证码，且验证码正确才能登录。

## 实现过程

### 1. 后端：服务端出题 + 服务端判分

新增 `backend/src/main/java/dev/aiqa/auth/CaptchaService.java`：

- 发放（`issue()`）：4 位字符（字母数字，去掉 `I/L/O/0/1` 等易混字符），用 JDK 自带 `java.awt` + `ImageIO` 画 132×44 PNG（浅底 + 干扰线/噪点 + 字符逐个 ±15° 旋转），以 `data:image/png;base64,...` 返回，不引入第三方验证码库。
- 校验（`verify()`）：题面存在进程内 `ConcurrentHashMap`（不落库），默认 2 分钟过期；**校验一次即作废**，无论对错都不能复用同一个 `captchaId`；缺失、过期、已用过、字符不匹配统一 400 `INVALID_CAPTCHA`，只给稳定错误码、不给区分原因的 detail（前端按码提示）。
- 内存上限 10000 枚：每次发放先清过期项，仍然超限整体清空，避免内存随刷新次数无界增长。
- 测试可注入固定字符：属性 `aiqa.login.captcha.fixed-code`（默认空 = 每次随机）。只有隔离 E2E 与集成测试设置，生产不设置。

配套改动：

- `common/ErrorCode.java`：新增 `INVALID_CAPTCHA`。
- `api/AuthController.java`：新增 `GET /api/v1/auth/captcha`（公开、`Cache-Control: private, no-store`）；`LoginInput` 增加 `captchaId`、`captchaCode`；登录先 `captchas.verify(...)` 再走密码校验——验证码错误不进入密码校验，也不占用 `LocalAccountService` 的密码失败次数窗口。
- `security/SecurityConfig.java`：`/api/v1/auth/captcha` 加入匿名白名单。
- `resources/application.yml`：`aiqa.login.captcha.ttl`（`CAPTCHA_TTL`，默认 `PT2M`）与 `aiqa.login.captcha.fixed-code`（`CAPTCHA_FIXED_CODE`，默认空）。

### 2. 契约与测试

- `docs/ai-qa-openapi.yaml`：新增 `/auth/captcha` 与 `LoginCaptcha` schema，`LoginInput` 增加两个必填字段，新增 `InvalidCaptcha`（400 `INVALID_CAPTCHA`）响应，`Problem.code` 枚举补 `INVALID_CAPTCHA`。`OpenApiContractTest` 双向比对通过。
- 新增 `backend/src/test/java/dev/aiqa/CaptchaImageTest.java`：PNG 头校验、大小写不敏感、一次性、错误/缺失/过期拒绝（不依赖数据库与 Web 上下文）。
- `LocalLoginIT`：固定验证码 `K7QM`，登录辅助方法每次先 `GET /auth/captcha` 取新 `captchaId`；新增 `loginRequiresCaptchaAndConsumesItOnce`——缺字段 400、错误字符 400、同题复用 400、重新取题成功 200。
- `SessionExpiryIT`：真实 HTTP 登录同样改为先取验证码。
- 前端 E2E（`frontend/e2e/run.mjs`）：登录步骤先断言验证码图片可见并填入固定字符；`AUTH-admin` 额外覆盖「错误验证码 → 400 `INVALID_CAPTCHA` 且留在登录页」「正确验证码 + 错误密码 → 401」「正确验证码 + 正确密码 → 200」。

### 3. 前端：移除开发身份入口、接入验证码

- `views/LoginView.vue`：删除 `<details>` 开发身份面板、`isDev`、`devSubject`、`submitDevLogin` 以及 `.dev-zone/.dev-form/.dev-note` 样式；新增验证码字段——输入框 + 可点击换图的图片按钮（`RefreshCw` 图标占位、「看不清？点击换一张」title / `aria-label`）。
  - 页面挂载时取一枚验证码；**每次登录失败后自动换新题并清空输入**（旧题已作废，不换必然连续失败）。
  - 提交前校验 4 位、取图成功才允许点登录；取图失败时按钮禁用并提示原因。
  - 样式全部复用既有 token（`--space-2`、`--color-border`、`--radius-control`、`--shadow-focus`、`--form-disabled-opacity`、`--motion-fast`、`--ease-standard`），图片容器固定 132×44 与图片同尺寸，换图时表单不跳动。
- `auth.ts`：新增 `CaptchaChallenge` 与 `fetchCaptcha()`；`login()` 增加 `captcha: { captchaId, captchaCode }` 参数；删除已无调用方的 `devLogin()`（后端 `DEV_HEADER_AUTH` 机制本身保留，供本地接口调试，只是登录页不再提供入口）。
- `formValidation.ts`：`FORM_LIMITS.captcha = 4`。
- `api/display.ts`：新增 `400 INVALID_CAPTCHA` 文案「验证码不正确或已失效，请按图片重新输入。」

### 4. 文档

- `backend/README.md`：环境变量表补 `CAPTCHA_TTL`、`CAPTCHA_FIXED_CODE`，登录说明补验证码流程与 400 码。
- `frontend/README.md`：替换「仅 DEV 显示开发身份入口」为验证码说明与「登录页不提供开发身份入口」。

## 验证命令与结果

- 后端集成/单元测试（隔离 PostgreSQL 实例，`IT_DB_*` 指向临时库）：
  `mvn -o test -Dtest='LocalLoginIT,SessionExpiryIT,CaptchaImageTest,OpenApiContractTest'`
  → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（`LocalLoginIT` 2、`SessionExpiryIT` 1、`CaptchaImageTest` 2、`OpenApiContractTest` 1）。
- `npm run typecheck`（`vue-tsc --noEmit`）：0 错误。
- `npm run lint`（`eslint . --max-warnings 0`）：0 错误、0 警告。
- `npm run test:e2e`（隔离 PostgreSQL + 后端 jar + 生产构建预览 + 本机 Chrome，验证码固定 `K7QM`）：
  - 汇总 `flow: PASS 36 / FAIL 12 / SKIP 0 / BLOCKED 0`，`quality: 20 BLOCKED`（本环境未接真实模型，属基线行为）。
  - 登录相关全部通过：`AUTH-admin`（含新增的错误验证码 400、错误密码 401、正确登录 200 三次断言）、`AUTH-maint`、`AUTH-reader`、`AUTH-outsider`、`AUTH-LOGOUT`。
  - 12 条失败与本改动无关，是既有 E2E 断言与今天其它页面改动不同步：`AD04-*`（仍在找 `role=tab` 的「高级文本」，而模板编辑器已改为「编辑模式」下拉）、`AD08/AD09/AD09-filter`（统计页指标标签定位不到）、`WB-SEARCH*`、`QA-CITE`、`STREAM-01`。这些用例在改动前后都不访问登录/验证码代码路径，未在本次范围内修改。
- 真实页面证据（同一隔离栈，Chrome headless 1440×900）：
  - `docs/e2e-results/login-captcha-2026-11-26.png`：登录页只保留用户名/密码/验证码，开发身份面板已不存在。
  - `docs/e2e-results/login-captcha-error-2026-11-26.png`：填错验证码提交，页面提示「验证码不正确或已失效，请按图片重新输入。（请求 …）」并自动换了一张新图。
  - 同一次脚本中，填入正确验证码后登录成功跳转 `/`，页面标题「工作台 · AI 智能问答平台」。
- `npx prettier --check`（本次改动的 6 个前端文件）：全部符合格式。

## 遗留问题

- **固定验证码是测试开关**：`CAPTCHA_FIXED_CODE` 一旦在部署环境设置，任何知道该值的人都能绕过图形验证码（仍受密码与失败限流约束）。生产必须留空；已写进 `backend/README.md` 环境变量表。
- **验证码存进程内存**：多实例部署时，A 实例出的题到 B 实例校验会失败（400，前端换题后可继续）。当前为单实例本地/内网部署，未引入 Redis；若后续横向扩容，需要把题面迁到共享存储。
- **无感官友好替代**：目前只有图形验证码，没有语音验证码；内网系统暂可接受，无障碍要求提升时需补充。
