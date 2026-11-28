# 变更记录：回答质量 S1（引用真实性、输入一致、失败拒答）

- 日期：2026-11-27
- 方案：[ai-qa-answer-quality-plan.md](./ai-qa-answer-quality-plan.md) 的 S1 阶段
- 范围：仅改回答质量链路，不动页面结构、不动权限与发布逻辑

## 背景

代码评审发现三处会静默损害回答质量的问题：

1. 模型没有标注 `[n]` 时，`parseCitations` 把全部证据回填成引用，引用列表与模型实际使用不一致；
2. 判定只看前 8 条、每条 600 字，生成却看全部 20 条、每块最多 4000 字，两边输入不一致；
3. 判定服务不可用时一律放行（fail-open），网关抖动时可能把编造内容当成有依据。

方案评审确认三项决策：允许安装 pgvector、判定失败默认拒答、精排先用网关 listwise。本次只实施 S1。

## 实现过程

### 1. 引用不得回填

| 文件                                                            | 改动                                                                                                      |
| --------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `backend/src/main/java/dev/aiqa/answer/GatewayAnswerModel.java` | `parseCitations` 解析不到 `[n]` 时返回空列表，不再回填全部证据                                            |
| `backend/src/main/java/dev/aiqa/answer/AnswerService.java`      | `paragraphKind` 收到空引用时按「无引用」判断，不参与冲突与失效判定；新增 `aiqa.answer.uncited` 指标与日志 |
| `frontend/src/views/AssistantView.vue`                          | 有正文但无引用的段落显示「本条答案未标注来源，请结合原文核对」，`NO_EVIDENCE` 段落不提示                  |

### 2. 判定与生成输入对齐，切块 v2

| 文件                                                                     | 改动                                                                                                                                             |
| ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `backend/src/main/java/dev/aiqa/ingest/TextExtractor.java`               | 窗口从固定 4000 字改为可配置（默认 800），新增 120 字重叠；保留换行切分与代理对保护                                                              |
| `backend/src/main/java/dev/aiqa/answer/AnswerService.java`               | 新增 `selectEvidence`：按重排顺序取前 N 条（默认 10）并截断到 800 字；判定、生成、`stillCurrent`、段落类型、`model_call_evidence` 落库全部改用它 |
| `backend/src/main/java/dev/aiqa/answer/ModelRelevanceJudge.java`         | 判定证据条数与单条长度改为与生成共用配置（默认 10 × 800），`userPrompt` 由静态改为实例方法                                                       |
| `backend/src/main/resources/application.yml`                             | 新增 `aiqa.chunking.max-chars/overlap`、`aiqa.answer.evidence-max-count/evidence-max-chars`                                                      |
| `backend/src/main/resources/db/migration/V18__answer_retrieval_text.sql` | `question.retrieval_text`，供多轮改写（S2.3）使用                                                                                                |

`CANDIDATE_LIMIT` 20 → 24：块变小后需要更多候选覆盖复合问题。

### 3. 判定失败默认拒答

| 文件                                                             | 改动                                                                                                                                             |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `backend/src/main/java/dev/aiqa/answer/ModelRelevanceJudge.java` | 新增 `aiqa.answer.relevance-failure`（默认 `closed`）：判定不可用时返回 `sufficient=false, available=false`；`open` 保持旧行为。日志区分两种模式 |
| `backend/src/main/java/dev/aiqa/answer/AnswerService.java`       | 判定不可用时写「暂时无法确认回答依据，请稍后重试。」，与「资料中没有」区分                                                                       |
| `backend/src/main/java/dev/aiqa/answer/RelevanceJudge.java`      | 接口注释同步为按配置放行/拒答                                                                                                                    |

## 验证

### 单测

```sh
cd backend && mvn -o -Dtest='GatewayAnswerModelTest,ModelRelevanceJudgeTest,EvidenceSupportTest,HybridRankerTest,TextExtractorTest,SchemaMigrationTest' test
```

结果：39 项通过，1 项跳过（`SchemaMigrationTest` 未配 `IT_DB_*` 时跳过）。

新增/修改用例：

- `GatewayAnswerModelTest.answerWithoutCitationMarkersDoesNotBackfillEveryEvidence`
- `ModelRelevanceJudgeTest.missingCredentialFailsClosedByDefault`、`failureModeOpenKeepsAnswering`、`unparseableVerdictFailsClosed`、`unparseableVerdictFailsOpenWhenConfigured`
- `TextExtractorTest` 改为窗口/重叠断言，新增代理对不切断用例

### 集成测试（独立库 `ai_qa_it`）

```sh
export IT_DB_URL="jdbc:postgresql://127.0.0.1:5432/ai_qa_it"
export IT_DB_USER=... IT_DB_PASSWORD=...
cd backend && mvn -o test
```

结果：**132 项通过，0 失败 0 跳过**（含全部 `*IT`），`SchemaMigrationTest` 在全量迁移中包含 V18，日志显示 `Successfully applied 18 migrations ... now at version v18`。

### 前端门禁

```sh
cd frontend && npm run lint && npm run typecheck
```

结果：均通过。

## 遗留问题

1. **未执行重索引**：切块规则改变后，开发库与任何已部署环境的现有文本块仍是 4000 字旧块。新回答会同时召回新旧块（同一文档被分别切分），不符合改造目标。需要在维护窗口按空间调用 `POST /api/v1/spaces/{spaceId}/reindex`，并在启用嵌入的环境确认重建后向量已生成。开发库本次未动。
2. **未跑真机 E2E**：质量题与浏览器流程需要真实模型网关；本次只跑了单测与集成测试。`docs/ai-qa-e2e-run-*.md` 未更新。
3. **前端提示未在浏览器验证**：只有 `lint`/`typecheck`，「未标注来源」的视觉位置需要人工确认。
4. **判定失败拒答会增加拒答率**：网关不稳时用户会看到「暂时无法确认回答依据」，需要配套指标告警（`aiqa.relevance.unavailable`）与运营说明。
5. S2/S3 未开始：向量召回规模（pgvector）、精排合并、多轮改写、屏蔽粒度、50 题题库与 CI 回放门禁。
