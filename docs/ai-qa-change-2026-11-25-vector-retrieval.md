# 变更记录：真实模型 + 语义相关性 + 向量召回（2026-11-25）

## 背景

全站 E2E 长期有 1 道质量题失败（`QA09` 差旅早餐额度）：资料里没有早餐条款，关键词检索却命中报销时限段落，本地替身模型只做摘录、不会拒答。词法规则无法区分"被问对象不存在"和"口语改写"，因此需要语义能力。用户确认：接入真实模型，分两步做语义相关性（先判定门控，再向量召回），嵌入使用本机小模型。

## 实现过程

1. **真实模型接入（网关配置，不改代码路径）**
   - 复用既有 `model_config`（协议 + 网关地址 + 模型 ID + 凭据引用）与 `global_setting.default_model_id` 指针；凭据仍只从环境变量 `aiqa.model.credentials.<ref>` 解析。
   - 验收时用本机 Magpie 网关 `http://127.0.0.1:3425/v1`（OpenAI 兼容，无需密钥），模型 `auto/deepseek-v4.1-flash`。
   - E2E 新增 `AD03-REAL`（`E2E_REAL_MODEL=1`）：经接口建模型配置并设为默认，后续问答与判定都走真实模型；不设该变量时行为与之前完全一致。

2. **语义相关性判定（第一步）**
   - 新增 `RelevanceJudge` 接口与 `ModelRelevanceJudge` 实现：把问题与前 8 条候选证据（每条 600 字，带 `EFFECTIVE/EXPIRED/UNCERTAIN` 标签）发到同一网关，只要求一行 JSON `{"sufficient":true|false,"reason":"…"}`。
   - 判定不足 → `AnswerService` 走 `NO_EVIDENCE`（不写引用）；判定失败按**失败开放**继续回答；判定消耗的 Tokens 单独记一条 `model_call_usage`。
   - 开关 `aiqa.answer.relevance`（`model` 默认 / `fake` / `off`）与 `aiqa.answer.relevance-timeout`（默认 20s）。未配置默认模型时自动旁路。
   - 判定提示明确「资料标注待核实仍属有依据，应说明状态而非拒答」，避免把 `QA07` 这类待核实题误拒。

3. **向量召回（第二步）**
   - 迁移 `V14__chunk_embedding.sql`：`document_chunk.embedding real[]`（可空，带中文注释）。
   - 新增 `EmbeddingClient`：OpenAI 兼容 `/embeddings`，批量 16 条，失败返回 null 不抛异常；配置 `aiqa.embedding.enabled/url/model/timeout`。
   - 解析与索引重建 worker 在切片后批量生成向量并随块写入（`IndexChunk` 增加 `embedding` 字段，保留三参构造兼容旧调用）。
   - 新增 `HybridRanker`：词法候选 60 条 + 向量候选 60 条，按 0.45×词法 + 0.55×向量混合排序；纯向量候选需达到本次最高相似度的 75%；最多 20 条进模型。嵌入关闭或失败时完全回退原词法路径。
   - 本机嵌入服务：`llama-server -m .cache/models/qwen3-embed-q8_0.gguf --embedding --pooling last -c 1024 -b 512 --host 127.0.0.1 --port 3437`（Q8_0，1024 维）。模型文件位于 `.cache/models/qwen3-embed-q8_0.gguf`（本机缓存，不入库）。

4. **测试与判分修正**
   - 新增 `ModelRelevanceJudgeTest`（开关、旁路、缺凭据失败开放、提示构造、两种协议解析、非法响应）、`EmbeddingClientTest`（解析、余弦、字面量、关闭态）、`HybridRankerTest`（词法优先、向量补充、噪声截断、去重、上限）。
   - E2E 新增 `EMB-01`（向量覆盖率与维度断言）/`EMB-OFF`（默认关闭时向量数为 0）。
   - 判分侧：去掉 Markdown 强调标记再核对事实；`QA12` 拆成「到达目的地后」「24 小时内」两个事实，避免因加粗/断句误判。

## 验证命令

```sh
# 1. 本机嵌入服务
llama-server -m .cache/models/qwen3-embed-q8_0.gguf \
  --embedding --pooling last -c 1024 -b 512 --host 127.0.0.1 --port 3437

# 2. 后端单测
cd backend
mvn -o -q -Dtest='EmbeddingClientTest,HybridRankerTest,ModelRelevanceJudgeTest,EvidenceSupportTest,TextExtractorTest,GatewayAnswerModelTest' test

# 3. 全站 E2E（真实模型 + 向量召回）
cd frontend
E2E_REAL_MODEL=1 E2E_EMBEDDING=1 RELEVANCE_GATE=model \
AIQA_MODEL_CREDENTIALS_MAGPIE=local \
E2E_MODEL_BASE_URL=http://127.0.0.1:3425/v1 \
E2E_MODEL_NAME=auto/deepseek-v4.1-flash \
npm run test:e2e

# 4. 前端门禁
npm run lint && npm run typecheck && npm run build
```

## 执行结果

| 项         | 结果                                                                       |
| ---------- | -------------------------------------------------------------------------- |
| 浏览器流程 | 81/81 通过（含 `AD03-REAL`、`EMB-01`）                                     |
| 质量题     | 20/20 通过；`QA09`、`QA10`、`QA20` 均 `NO_EVIDENCE`，`QA07` 为 `UNCERTAIN` |
| 向量覆盖   | `EMB-01`：45/45 文本块有向量，维度 1024                                    |
| 检索指标   | Recall@20 100%，Hit@5 17/17                                                |
| 延迟       | 均值 11.39 s，p95 32.07 s（主要耗时在真实模型生成）                        |
| 后端单测   | 全部通过                                                                   |
| 前端门禁   | lint / typecheck / build 通过，改动文件 Prettier 通过                      |

证据包：`docs/e2e-results/frontend-e2e-vector-c02c96a5.zip`（本轮）、`frontend-e2e-real-3d10e9a8.zip`（仅真实模型）、`frontend-e2e-61e469e1.zip`（本地替身基线）。

## 开发环境接入（2026-11-25 21:38）

开发库 `ai_qa_dev` 里原本已有两条 Magpie 模型配置（`Magpie · DeepSeek V4.1 Flash`、`Magpie · GLM-5.3-Flash`，`credential_ref=vault:magpie`），默认指针指向 DeepSeek。因此开发环境**不需要再建模型**，只需补嵌入开关并重算向量：

1. 从 `~/.config/ai-qa/dev.env` 载入数据库与凭据，额外注入运行变量后启动：
   `RELEVANCE_GATE=model EMBEDDING_ENABLED=true EMBEDDING_URL=http://127.0.0.1:3437/v1 EMBEDDING_MODEL=qwen3-embed AIQA_MODEL_CREDENTIALS_VAULT_MAGPIE=local mvn -o spring-boot:run`
   （Magpie 本机网关不校验密钥，任给非空值即可。）
2. 启动时自动应用迁移 V14；对 6 个已发布空间各调一次 `POST /api/v1/spaces/{id}/reindex`，17 个索引任务全部成功。
3. 向量覆盖：已发布空间的文本块全部有向量（员工手册 7/7、差旅政策 7/7、格式审核 30/30 等）；未发布空间与草稿不参与检索，保持 NULL。
4. 前端 `npm run dev` 运行在 `http://localhost:5173`（IPv6 回环，`/api` 代理返回 200）。

验证证据（真实开发库）：

| 场景                             | 结果                                                                                                          |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `差旅早餐可以报销多少元？`       | `NO_EVIDENCE`，0 引用；日志判定理由「资料仅提及报销时限和住宿上限，未涉及早餐报销额度」                       |
| `跨境出差需要提前几天提交申请？` | `ANSWER`，1 条引用 `跨境出差规定.md`，正文「提前五个工作日」                                                  |
| 浏览器（localhost:5173）         | 登录后助手页显示「系统默认 · Magpie · DeepSeek V4.1 Flash」，早餐问题渲染「无依据」，同一会话上一条正例带引用 |

持久化：上述四个变量已写入 `~/.config/ai-qa/dev.env`（权限保持 600，写入前备份为 `dev.env.bak-20261125-214903`）：

```sh
export EMBEDDING_ENABLED=true
export EMBEDDING_URL=http://127.0.0.1:3437/v1
export EMBEDDING_MODEL=qwen3-embed
export RELEVANCE_GATE=model
```

验证方式：只加载该文件重启后端（不再手工注入变量），把 `员工手册` 一条文本块的 `embedding` 置空后再调一次 reindex，25 秒后该列为空的行数为 0——说明嵌入开关在重启后依然生效。

## 引用编号与正文对齐（2026-11-25 22:10）

现象：回答正文用 `[1]`、`[18]` 标注，引用列表却按数组下标重新编号 1..9，列表里的 1 号文档并不是正文的 `[1]`；`[18]` 在 9 条列表里根本找不到。

原因：`QuestionRepository.citations` 按 `citation.id`（UUID）排序，前端用 `index + 1` 当编号，而正文编号来自证据序号（`evidence_ordinal + 1`）。

修复：查询改为 `c.evidence_ordinal + 1 AS marker` 并按 `p.ordinal, c.evidence_ordinal` 排序；`CitationView` 增加 `marker`；前端直接显示 `citation.marker`。属于向后兼容的加字段。

验证：同一个问题（`b82261c2…`）正文编号 `[1,6,18,5,4,2,5,8,8,7,3]`，列表 marker 为 `1,2,3,4,5,6,7,8,18`，一一对应；浏览器截图确认列表显示 1 与 3，而不是 1 与 2。

## 顺带发现的数据问题（未修改）

用户实际提问暴露了基准语料自身的矛盾：同一份资料里杭州出现三个数字——`FIN-TR-022` 城市分档把杭州列入 B 档 460 元，`FIN-FMT-103` 台账写杭州 A 档 620 元，同一台账的「节期例外」又写设备巡检周 710 元。模型如实指出「资料未说明冲突适用优先级」，这个回答是对的；但基准题 `QA14` 期望 620，等于把矛盾数据当成了唯一答案。

建议在基准里明确杭州的规范值（例如分档表与台账口径分开说明，或统一为 620/710 两种场景），再更新题卡预期；否则真实模型会持续给出「无法唯一确定」这种更正确、但判分判失败的回答。

## 语料口径修订与一致性体检（2026-11-25 22:20）

### 1. 基准语料里「杭州」自相矛盾，已消除

原状：`FIN-TR-022` 分档表把杭州列入 B 档 460 元，`FIN-FMT-103` 台账「住宿限额」又写杭州 A 档 620 元，同一台账「节期例外」再写杭州 710 元。三处并列出现时，真实模型只能回答「资料未说明优先级，无法唯一确定」。

处理（改语料，不改判分逻辑）：

- 台账「住宿限额」的城市由杭州改为**宁波**（620 元不变），杭州在台账里只保留「设备巡检周临时上限 710」这一场景例外；
- 台账新增「口径说明」工作表，写明三条规则：城市执行价经财务运营部批准、与分档表不一致时**以台账为准**；节期例外只在标注场景生效；执行价含税不含早餐；
- `QA14` 提问与断言由杭州 620 改为宁波 620（`frontend/e2e/quality.mjs`），`formats/SHA256SUMS` 同步更新哈希。

实测（真实模型 + 评测空间）：

| 问题                 | 修订后回答                                                        |
| -------------------- | ----------------------------------------------------------------- |
| 宁波日常每人每晚限额 | 620 元，含税不含早餐，并说明与分档表不一致时以台账为准            |
| 杭州日常每人每晚限额 | 460 元（B 档），并指出设备巡检周的 710 元是场景例外，不改变日常价 |

### 2. 新增只读体检脚本 `scripts/check-corpus-consistency.py`

```sh
set -a; . ~/.config/ai-qa/dev.env; set +a
python3 scripts/check-corpus-consistency.py            # 全部已发布空间
python3 scripts/check-corpus-consistency.py --space 员工手册
```

- 提取两类结构化事实：XLSX 行的「表头上下文：城市：杭州；上限（元）：620」，以及 Markdown 表格（表头含限额/上限且某列是城市/地区）。
- 把「上限（元）」「差旅住宿限额」归一到同一口径后再比对**主体 + 指标 → 数值**，同键多值即判冲突；同时报告跨空间重名文档与无向量文本块。
- 发现冲突退出码 1，可直接放进发布前置检查。纯段落里的数字（如「十五个工作日内」）不做语义比对，脚本末尾明确写出该限制。

修订前后对照：修订前脚本对全库报 `杭州 · 住宿限额 出现 2 个不同值（460 / 620元）`，退出码 1；修订并重新发布评测空间后，同一命令对评测空间返回「未发现冲突」，退出码 0。

全库再跑仍会命中 `杭州 460 / 620元`，来源是开发库里**旧的 E2E 格式审核空间**——它保存的是修订前导入的台账快照，不属于当前语料。这也说明脚本可以当发布门槛用：仓库里的语料干净，残留数据会被指名道姓列出来。

### 3. 回归

`E2E_REAL_MODEL=1 E2E_EMBEDDING=1 RELEVANCE_GATE=model` 全量重跑（run `8cf5b5f3`）：**81/81 流程、20/20 质量题**，`QA14` 改为宁波后仍 4/4 事实命中，回答同时引用了台账口径说明；延迟均值 11.82 s、p95 29.10 s。证据包 `docs/e2e-results/frontend-e2e-corpus-8cf5b5f3.zip`。

## 入库切片索引进度怎么查（2026-11-25 22:55）

页面与接口只能看到"解析状态/是否可检索"，**看不到切了多少块、多少块有向量**，因此补了一个只读脚本 `scripts/index-progress.py`：

```sh
set -a; . ~/.config/ai-qa/dev.env; set +a
python3 scripts/index-progress.py                   # 全部空间
python3 scripts/index-progress.py --space 员工手册   # 单空间
python3 scripts/index-progress.py --json            # 机器可读
```

按文档输出：解析状态（READY / NO_TEXT / FAILED）、文本块数、有向量的块数、最近一次索引任务与时间、是否在当前发布快照内；末尾汇总文档数、可检索数、向量覆盖率，并对"解析成功但向量不全"的文档提示可执行 `POST /spaces/{id}/reindex`。

三处可观测点分工：

| 想看的                     | 位置                                                                                            |
| -------------------------- | ----------------------------------------------------------------------------------------------- |
| 上传后逐文件解析进度       | 导入弹窗实时状态；`GET /api/v1/imports/{batchId}`                                               |
| 能不能被检索（是否已发布） | 知识库 → 查看（可检索/状态列）；`GET /api/v1/spaces/{spaceId}` 的 documentCount/searchableCount |
| 切片数、向量数、索引任务   | 只有本脚本（或等价 SQL），REST 未暴露                                                           |

## 已入库文档更新（2026-11-26 11:20）

需求：知识库里的文档只能「同名跳过」，改内容得另存文件名（界面上也写着「新版须另存文件名」）。

实现：

- 迁移 `V15__document_versioning.sql`：`document_version` 增加 `document_id`（逻辑文档标识，回填为首版 id 并设非空），删除 `(space_id, normalized_filename)` 唯一约束，改为 `(document_id, version_number)` 唯一；旧版本与历史引用保持不变。字段带中文注释。
- 仓储：`DocumentRow` 增加 `documentId`，插入语句写入 `document_id` 与 `version_number`；同名查询按版本号取最新；新增 `nextVersionNumberForDocument`。
- 服务：`ImportService.updateDocument` —— 校验维护权限、文件名必须与目标一致（改名请走导入）、复用上传的暂存/扫描/幂等，插入下一版本并入队解析；幂等键回放返回既有版本。
- 接口：`POST /api/v1/spaces/{spaceId}/documents/{documentVersionId}/versions`（`multipart` 字段 `file`，需 `Idempotency-Key`，返回 202 + 新版本 id/版本号）。
- 前端：文档列表每行新增「更新」（复用共享组件 `FormFileInput`，新增 `secondary` 变体）；上传后先把新版本写入草稿再轮询解析（草稿接口才读得到新版本），解析失败或无正文时回退到原条目；提示改为「同名文件可直接更新为新版本」。

验证：

- 接口实测（临时空间）：v1 导入 → 更新后返回 `versionNumber: 2`、新版本 READY 且正文是「每天一百五十元」，v1 与其文本块仍在。
- 浏览器实测：空间文档抽屉点「更新」上传同名文件，列表变为 `v3`、状态「可检索」，草稿条目被替换并提示「已生成 v3 并替换草稿条目，提交审核后生效」。
- E2E 新增 `KB-UPD`：接口级验证版本号递增、改名更新被拒（400「更新须保持文件名一致」）、草稿替换与解析文本；并确认抽屉出现「更新」控件。

### 回归现状（2026-11-26 12:15）

- `KB-UPD` 在真实模型 + 向量 + 判定的全量运行中 **PASS**（run `d6753095`）。
- 同一轮里 `AD04-*` 5 条与质量题 20 条 BLOCKED，原因是**并行进行的前端改造**：`新建模板` 弹窗把「高级文本」页签换成了「编辑模式」单选（`TemplateBodyEditor.vue`），而 E2E 仍在点页签；质量题依赖这些模板创建步骤，因此连带 BLOCKED。
- 同时 `SelectMenu.vue` 引用了 `../motion` 里尚未导出的 `useExitAnimation`，`npm run typecheck` 报错；`TemplateBodyEditor.vue` 的裸 `<input type="radio">` 与 `e2e/tmp-button-visual.mjs` 的 `console` 各触发一条 lint 错误。
- 以上三处都在我这次改动之外、且正在被并发编辑，未做修改。本人改动的文件单跑 `eslint` 通过；上述问题修完后重跑即可全绿。

## 遗留问题

- 向量相似度在 JVM 内计算，扫描上限 2000 块；语料变大需迁 pgvector（本机 PostgreSQL 17 未安装该扩展）或数据库侧近邻索引。
- 已上线空间的旧文本块没有向量，需 `POST /spaces/{spaceId}/reindex` 重算；历史引用仍使用冻结证据。
- 开发环境（8080/5173 那套）尚未写入默认模型配置；本轮只在隔离测试库完成接入。
- 嵌入与判定都是本机服务，未做并发/失败演练（判定有失败开放，嵌入有回退词法，但没有在真实故障下压测）。
- 真实业务原件仍未参与；本轮真实模型只看到合成题卡。
