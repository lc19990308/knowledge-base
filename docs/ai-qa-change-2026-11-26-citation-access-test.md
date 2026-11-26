# 变更记录：对齐引用撤权用例，并补普通成员场景（2026-11-26）

## 背景

CI 首跑后端红：`PublishingAndWithdrawalIT.unapprovedContentStaysHiddenAndWithdrawalMasksHistory` 第 312 行期望「撤掉管理员的空间成员关系后，引用详情 404」，实际 200。

判定依据（只读核对，未改实现与权限语义）：

- `QuestionRepository.paragraphStillAuthorized` 的 SQL 里，对 `SYSTEM_ADMIN` 有显式放行：`(m.user_id IS NULL AND NOT EXISTS (SELECT 1 FROM user_role ... role='SYSTEM_ADMIN'))` 才判未授权。
- 该用例自身注释也写着「检索已按管理员口径放开：成员关系不再限制系统管理员的使用侧读取」。
- `docs/ai-qa-change-2026-11-26-cleaning-backend.md` 早已把这条记为已知失败（代码文件时间 2026-11-25 22:11）。

所以是**用例断言落后于实现**，不是代码 bug。CI 要作为门禁，必须让它反映当前语义。

## 实现过程

`backend/src/test/java/dev/aiqa/PublishingAndWithdrawalIT.java`：

1. 管理员段落改为按「放行」语义断言：撤权/加回两种状态下，`citations/{id}`、`citations/{id}/content`、空间检索都应为 200，并删掉原来只对撤权生效的 404 分支。
2. 新增普通成员对照场景（补回被管理员放行「盖住」的覆盖）：
   - 用既有 `READER`（dev 头账号，非系统管理员）授予该空间只读成员关系；
   - 以 READER 身份建会话、提问、等 worker 处理，取得引用 id；
   - 断言作为成员时引用预览与原件都 200；
   - 删除 READER 的成员关系后，断言预览 404、原件 404，且该问题的段落状态变为 `MASKED`。
3. 新增两个测试辅助方法 `grantMembership` / `revokeMembership`，`granted_by` 记为管理员，满足 `space_member.granted_by` 的 NOT NULL 约束。

## 验证命令与结果

- `mvn -o -B -ntp test -Dtest='PublishingAndWithdrawalIT'`（`IT_DB_*` 指向临时 PostgreSQL）：
  `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
- 全量 `mvn -o -B -ntp verify`：`Tests run: 128, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（此前 127/128，差的就是本条）。

## 遗留问题

- 这条用例现在锁定了「系统管理员不受空间成员关系限制」的产品语义。将来若要收紧管理员读取权限，必须同时改 `paragraphStillAuthorized` 与本用例，否则 CI 会按新语义红。
- 管理员撤权后仍可读，意味着审计上「管理员读取」不会被成员关系拦住；如需留痕，应在引用读取处补审计事件（本次未做）。
