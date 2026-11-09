# 银行/保险合规 RAG 评测集模板（50 题）

本文档是合规评测集的**建集模板**，不是可直接提交的数据集。除文末明确标注的占位骨架外，不预置题目、参考答案或 gold 证据；所有 gold 必须来自实际已上传、已完成向量化的文件。

## 1. 硬规则

1. 不写真实客户数据：客户名称、账号、保单号、身份证号、手机号、合同编号一律不得出现；内部制度和产品条款用虚构机构、虚构产品名，并在文中标注“示例”。
2. 监管规定只用公开原文（国家金融监督管理总局、中国人民银行、国家外汇管理局、金融监管总局地方监管局等官网发布件），保留发布机构、文号、生效日期。
3. 题目、参考答案、`expectedFileNames`、`expectedEvidence` 只能引用知识库里真实存在且评测发起人可见的文件；文件没上传前，对应行不要填 gold。
4. `expectedEvidence` 必须是条款原文中可直接搜到的唯一短语，不能写概括、同义改写或条款号本身。
5. 当前简历/文档里出现的 **76.1 / 80.4** 是技术评测集 `paismart-eval-v1.jsonl` 的分块级 Recall@5（rerank 口径 / RRF 兜底口径），**不是合规集结果**。对外引用前必须在本合规集上重跑，并按同一口径记录。

## 2. 50 题构成

`id` 前缀只用于人工和脚本分组；报告逐条保留 `caseId`，当前汇总对象里没有 `byType` 字段，按题型看指标时用报告 `cases` 配合前缀自行汇总。

| 类别 | id 前缀 | 题数 | 考什么 | gold 标注要点 |
| --- | --- | --- | --- | --- |
| 条款事实 | `fact-` | 12 | 单条款内的数字、期限、比例、报送对象、禁止性规定 | 期望文件 + 条款原文中的唯一短语；参考答案含结论和条款号 |
| 跨文档综合 | `multi-` | 8 | 监管规定与内部制度/产品条款组合后才能回答 | `expectedFileNames` 填多篇；参考答案说明每篇各自规定了什么 |
| 地区适用 | `region-` | 6 | 同一主题不同地区/辖区的适用差异 | 只标适用该地区的文件；参考答案说明适用与不适用，不能合并两地口径 |
| 版本/当前可检索 | `version-` | 6 | 哪一版当前可检索、新旧版本差异 | 只标当前可检索版本；参考答案含版本号与生效日期；旧版作为干扰项留在库中 |
| 冲突条款 | `conflict-` | 5 | 监管要求与内部制度冲突、或两版要求冲突 | 参考答案指出冲突点及以哪个为准（上位法/新版本/更严标准），不得把冲突条款融合成一个结论 |
| 无答案/应拒答 | `noanswer-` | 6 | 知识库没有答案或问题前提错误 | `expectedFileNames`/`expectedFileMd5s` 留空，不计算检索指标；参考答案明确拒答或指出前提不成立，不得编造 |
| 缺用户条件/应澄清 | `clarify-` | 4 | 缺少地区、产品类型、机构类型、客户类型等必要条件 | 参考答案写出应追问的条件，不得直接给出适用结论 |
| 条款引用 | `citation-` | 3 | 回答必须带条款路径/条款号 | 参考答案写明应引用的 `articlePath`/`clauseNo`（如“第二章 风险管理 > 第十二条”“第十二条第二款”）；检索标注同条款事实 |

合计 50 题。实际配比可按语料调整，但八类都要有，且 `noanswer-` 不能少于 5 题，否则拒答能力没有样本。

## 3. JSONL 格式（与 `RagEvalCaseRunner` / `paismart-eval-v1.jsonl` 一致）

每行一个 JSON 对象，UTF-8；空行以及 `#`、`//` 开头的行会被跳过；单次最多 `eval.max-cases` 条（默认 500）。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 否 | 用例标识，缺省为 `line-<行号>`；建议用上表前缀，便于分组 |
| `question` | 是 | 单轮问题文本 |
| `referenceAnswer` | 是 | 人工参考答案；评委以它为准判断事实与要点覆盖 |
| `expectedFileNames` | 否 | 期望命中的文件名列表，匹配时忽略大小写 |
| `expectedFileMd5s` | 否 | 期望命中的文件 MD5，优先于文件名；换环境文件名变化时用它 |
| `expectedEvidence` | 否 | 分块级标注：答案分块里一定出现的短语列表，命中分块包含任意一个才算相关。匹配前做 NFKC 归一化、去掉空白并转小写；不填时同一文件的任意分块都算相关（文件级口径） |
| `expectedBehavior` | 否 | 填 `clarify_first` 时该题生成阶段会拿到 `ask_clarification` 工具并被要求条件不明先追问，评委接受“先追问”或“给出数值并说明适用条件”；不填按普通题判分 |

`expectedFileNames` 和 `expectedFileMd5s` 至少给一个才计算 Recall/MRR；两类都不给时只评回答（无答案/应拒答题按这种方式标注）。

占位骨架（尖括号内容是占位符，替换前不能提交，否则检索必然命中不到）：

```jsonl
{"id": "fact-01-<主题>", "question": "<问题>", "expectedFileNames": ["<已上传文件名>"], "expectedEvidence": ["<条款原文中的唯一短语>"], "referenceAnswer": "<结论 + 条款号>"}
{"id": "multi-01-<主题>", "question": "<跨文件问题>", "expectedFileNames": ["<文件A>", "<文件B>"], "expectedEvidence": ["<文件A中的短语>", "<文件B中的短语>"], "referenceAnswer": "<分别引用两篇的结论>"}
{"id": "region-01-<主题>", "question": "<点名地区的问题>", "expectedFileNames": ["<该地区适用文件>"], "expectedEvidence": ["<该地区条款短语>"], "referenceAnswer": "<适用结论 + 地区依据>"}
{"id": "version-01-<主题>", "question": "<当前可检索版本问题>", "expectedFileNames": ["<当前版本文件名>"], "expectedEvidence": ["<新版条款短语>"], "referenceAnswer": "<版本号 + 生效日期 + 效力状态 + 结论>"}
{"id": "conflict-01-<主题>", "question": "<冲突问题>", "expectedFileNames": ["<监管规定>", "<内部制度>"], "expectedEvidence": ["<监管条款短语>", "<制度条款短语>"], "referenceAnswer": "<冲突点 + 以哪个为准>"}
{"id": "noanswer-01-<主题>", "question": "<库内无答案的问题>", "referenceAnswer": "<说明知识库没有依据，不能给出结论>"}
{"id": "clarify-01-<主题>", "question": "<缺条件的问题>", "referenceAnswer": "<应先确认地区/产品/机构类型等条件>"}
{"id": "citation-01-<主题>", "question": "<要求给出条款依据的问题>", "expectedFileNames": ["<文件>"], "expectedEvidence": ["<条款短语>"], "referenceAnswer": "<结论，并引用 articlePath / clauseNo>"}
```

## 4. 数据集放置与运行

### 4.1 放置

- 方式一（classpath）：放到 `src/main/resources/eval/bank-compliance-v1.jsonl`，用 `classpath:eval/bank-compliance-v1.jsonl` 引用；**需要重新编译并让后端加载新 classpath**。
- 方式二（目录）：放到 `eval.dataset-dir` 下，默认是后端工作目录的 `eval-datasets/`，例如 `eval-datasets/bank-compliance-v1.jsonl`；不用编译，适合反复改标注。
- 文件名用 ASCII（`bank-compliance-v1.jsonl`），classpath 路径只接受 `[\w\-]+(/[\w\-]+)*\.jsonl`。
- 也可以用 JSON `jsonl` 字段直接贴内容，或 multipart 上传文件（≤5MB）。

### 4.2 先跑 retrievalOnly（不花生成/评委 Token）

```bash
curl -sS -X POST 'http://localhost:8081/api/v1/admin/eval/run' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "datasetPath": "classpath:eval/bank-compliance-v1.jsonl",
    "topK": 5,
    "label": "bank-compliance-retrieval",
    "retrievalOnly": true
  }'
```

这一步只跑混合检索并统计检索指标，用来确认：

- 文件名/MD5 写对，`expectedEvidence` 能在分块原文里命中；
- `noanswer-` 题目的返回条数（噪声）；
- 是否有题因为权限过滤根本检索不到文件（评测以发起评测的管理员身份检索，文件必须对该管理员可见：公开或在他所属组织内）。

### 4.3 再跑完整评测（生成 + 评委）

去掉 `retrievalOnly`（或传 `false`）：

```bash
curl -sS -X POST 'http://localhost:8081/api/v1/admin/eval/run' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "datasetPath": "classpath:eval/bank-compliance-v1.jsonl",
    "topK": 5,
    "label": "bank-compliance-full"
  }'
```

`topK` 默认 5，最大 20。同一个数据集改分块/检索参数前后各跑一次，用不同 `label` 区分。

### 4.4 查询进度与报告

```bash
GET /api/v1/admin/eval/runs              # 全部任务
GET /api/v1/admin/eval/runs/latest       # 最近一次
GET /api/v1/admin/eval/runs/{runId}      # 单次状态与汇总
GET /api/v1/admin/eval/runs/{runId}/report   # 完整报告（含逐条 cases）
POST /api/v1/admin/eval/runs/{runId}/human-reviews  # 录入人工判定
```

报告默认写到 `eval-reports/`。公开引用前把报告归档到 `docs/eval/reports/`，文件名保留日期、label 和 runId。

## 5. 数据准备清单

| 项 | 要求 | 上传时填写的合规字段 |
| --- | --- | --- |
| 公开监管规定 | 6-10 篇，覆盖银行/保险/反洗钱/消费者保护等主题；保留官方文号和生效日期 | `docType=监管规定`，`jurisdiction` 填国家/地区，`issuer` 填发布机构，`applicableInstitutions` 填适用机构（没有就留空），`effectiveAt` 填生效日期，`expiresAt`/`repealed` 按原文标注 |
| 合成内部制度 | 4-6 篇，虚构银行/保险公司名称，条款与监管规定部分一致、部分更严或冲突 | `docType=内部制度`，`issuer` 填虚构机构名 |
| 合成产品条款 | 3-5 篇，虚构产品名，包含费率、等待期、免责等可问事实 | `docType=产品条款`，`jurisdiction` 填销售地区 |
| 合成操作指引 | 2-3 篇，写清流程、时限、审批层级 | `docType=操作指引` |
| 版本数据 | 1-2 个文档各准备两个版本，旧版保留在库中作为干扰 | 新版本通过上传接口带 `documentId` 创建；当前 UI 暂无“上传新版本”入口（本阶段明确不做），需要先走分片上传再调 `POST /api/v1/upload/merge`，不要手工改库 |
| 地区数据 | 同一主题至少两个地区/辖区口径 | `jurisdiction` 必须区分，不能都填“中国” |
| 无答案题干扰 | 知识库里存在主题相近但不含答案的文件 | 不给期望文件 |
| Gold 台账 | 每题一行：caseId、类别、问题、期望文件（含版本+地区）、条款路径/条款号、是否当前可检索、`expectedEvidence`、`referenceAnswer`、标注人/日期 | 台账与 JSONL 分开保存，便于复核 |

禁止项：真实客户数据、真实保单/合同号、真实员工姓名、未公开的监管内部文件、把真实机构内部制度直接上传。

建集后先用 `GET /api/v1/documents/accessible` 或知识库列表核对每篇文件的合规列（文档类型/适用地区/发布机构/适用机构/生效日期/失效日期/效力状态）；旧文件这些列为空属于正常（未按合规流程上传），不要拿它们做合规题。

## 6. 建集与验收步骤

1. 上传全部语料，等列表“实际向量化”出现实际 Tokens（不是仅预估）。
2. 在知识库列表核对四列合规元数据；空值的旧文件只做干扰项，不进 gold。
3. 先写 3-5 题冒烟，`retrievalOnly=true` 跑通，确认文件名和证据短语能命中。
4. 按第 2 节配比扩到 50 题；每题同时写 JSONL 行和 Gold 台账。
5. `retrievalOnly` 回归：检查 Recall@K、MRR、候选命中、阈值丢弃、无答案题返回条数；修标注或补语料，不要为了指标改参考答案。
6. 完整评测：记录 accuracy、meanJudgeScore、逐题归因；抽 10-15 题人工复核，计算评委一致率和 kappa。
7. 把报告、Gold 台账、人工复核表一起归档；对外只引用本合规集重跑后的数字。

## 7. 指标口径

- `recallAtK`：分块级 Recall@K，标注了 `expectedEvidence` 时按分块判断。
- `docRecallAtK`：文件级 Recall@K，只用于对比，不能替代分块级口径。
- `mrr`、`ndcgAtK`：相关分块排得越靠前越高。
- `candidateHit` / `precisionAtK` / `irrelevantInTopK`：区分“召回没找到”和“精排/阈值丢掉了”。
- `unlabeled` 返回条数：无答案题每次返回多少条，用来观察噪声。
- `accuracy`、`meanJudgeScore`：只在评委成功打分的用例上计算；评委解析失败会单独标记。

条款引用类题目没有独立的结构化评分字段，引用是否正确由 `referenceAnswer` 中的条款路径/条款号约束，再通过评委判断；回答页面可在引用预览中核对 `articlePath`/`clauseNo` 是否展示。

## 8. 冒烟集说明

本文档只提供占位骨架，不提供可直接运行的 3-5 题冒烟集：冒烟集的 `expectedFileNames` 和 `expectedEvidence` 依赖你实际上传的文件与条款原文，提前写死就是伪造 gold。按第 3 节模板替换尖括号内容后，先取 3 条（条款事实、无答案、跨文档各 1 条）跑 `retrievalOnly`，通过后再扩到 50 题。
