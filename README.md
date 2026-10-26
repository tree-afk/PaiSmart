派聪明（PaiSmart）是一个企业级的 AI 知识库管理系统，采用检索增强生成（RAG）技术，提供智能文档处理和检索能力。

核心技术栈包括 ElasticSearch、Kafka、WebSocket、Spring Security、Docker、MySQL 和 Redis。

它的目标是帮助企业和个人更高效地管理和利用知识库中的信息，支持多租户架构，允许用户通过自然语言查询知识库，并获得基于自身文档的 AI 生成响应。

![派聪明多模块架构](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102133.png)

系统允许用户：

- 上传和管理各种类型的文档
- 自动处理和索引文档内容
- 使用自然语言查询知识库
- 接收基于自身文档的 AI 生成响应

用到的技术栈包括，先说后端的：

+ 框架 : Spring Boot 3.4.2 (Java 17)
+ 数据库 : MySQL 8.0
+ ORM : Spring Data JPA
+ 缓存 : Redis
+ 搜索引擎 : Elasticsearch 8.10.0
+ 消息队列 : Apache Kafka
+ 文件存储 : MinIO
+ 文档解析 : Apache Tika
+ 安全认证 : Spring Security + JWT
+ AI集成 : DeepSeek API/本地 Ollama+豆包 Embedding
+ 实时通信 : WebSocket
+ 依赖管理 : Maven
+ 响应式编程 : WebFlux

后端的整体项目结构：

```bash
src/main/java/com/yizhaoqi/smartpai/
├── SmartPaiApplication.java      # 主应用程序入口
├── client/                       # 外部API客户端
├── config/                       # 配置类
├── consumer/                     # Kafka消费者
├── controller/                   # REST API端点
├── entity/                       # 数据实体
├── exception/                    # 自定义异常
├── handler/                      # WebSocket处理器
├── model/                        # 领域模型
├── repository/                   # 数据访问层
├── service/                      # 业务逻辑
└── utils/                        # 工具类
```

再说前端的，包括：

+ 框架 : Vue 3 + TypeScript
+ 构建工具 : Vite
+ UI组件 : Naive UI
+ 状态管理 : Pinia
+ 路由 : Vue Router
+ 样式 : UnoCSS + SCSS
+ 图标 : Iconify
+ 包管理 : pnpm

前端的整体项目结构：

```bash
frontend/
├── packages/           # 可重用模块
├── public/             # 静态资源
├── src/                # 主应用程序代码
│   ├── assets/         # SVG图标，图片
│   ├── components/     # Vue组件
│   ├── layouts/        # 页面布局
│   ├── router/         # 路由配置
│   ├── service/        # API集成
│   ├── store/          # 状态管理
│   ├── views/          # 页面组件
│   └── ...            # 其他工具和配置
└── ...               # 构建配置文件
```

## 派聪明的成绩

派聪明是 9 月份上线的，截止到目前，已经取得了非常瞩目的成绩，我这里晒一下哈。


![面渣逆袭+派聪明 拿下招银网络+科大讯飞](https://cdn.tobebetterjavaer.com/paicoding/fb5db62ab92092e2d74a4916b6a45710.png)


![派聪明拿到的日常实习](https://cdn.tobebetterjavaer.com/paicoding/da01a535b091c5ebeed70bf9a08a90c6.png)


![派聪明拿下合合信息](https://cdn.tobebetterjavaer.com/paicoding/3518a76f439c325de8df763482cbebc4.png)


![派聪明拿下小红书](https://cdn.tobebetterjavaer.com/paicoding/7bed4d34460749d68db9c0fcbc4621a8.png)


![派聪明拿下网易](https://cdn.tobebetterjavaer.com/paicoding/5f227edcb38ffe41aea8fc0880f64cad.png)

说句真心话，看到这，就可以无脑冲这个项目了，因为这些，还只是冰山一角。扫下面的优惠券（或者长按自动识别）解锁派聪明源码和教程吧，[星球](https://javabetter.cn/zhishixingqiu/)目前定价 159 元/年，优惠完只需要 129 元，每天不到 0.35 元，绝对的超值。

![派聪明优惠券](https://cdn.tobebetterjavaer.com/paicoding/97601d7a337d7d944b02bb4a79cd6430.png)

>派聪明如何写到简历上：[https://paicoding.com/column/10/2](https://paicoding.com/column/10/2)



## 核心功能

这里我先带大家了解一下什么是派聪明，我为什么要做派聪明这个企业级的 RAG 知识库？派聪明这个 AI 项目能让大家学到什么？以及如何解锁派聪明的源码仓库和教程？

![派聪明的聊天助手：会依据知识库进行问答](https://cdn.tobebetterjavaer.com/paicoding/2550c873a349d8bee29d46400f12ce76.png)

![派聪明的架构概览](https://cdn.tobebetterjavaer.com/stutymore/README-20250730101618.png)

### 知识库管理

派聪明提供了完整的文档上传与解析功能，支持文件分片上传和断点续传，并支持标签进行组织管理。文档可以是公开的，也可以是私有的，并且可以与特定的组织标签关联，以便更好地进行权限分类。

![派聪明文档处理](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102808.png)

### AI驱动的RAG实现

派聪明的核心是 RAG 实现：

![派聪明聊天交互](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102837.png)

- 将上传的文档进行语义分块
- 调用豆包 Embedding 模型为每个文本块生成高维向量
- 将向量存储到 ElasticSearch 以支持语义搜索和关键词搜索
- 可以根据用户的查询检索相关文档
- 为 LLM 提供完整的上下文，从而生成更准确、基于文档的响应内容

### 上下文检索与父子检索

分块切小了，检索更精确，但一块文本离开原文可能读不懂，比如只写着“营收比上季度增长 3%”，没说是哪家公司、哪个季度。派聪明在入库和问答两头各补一步。

**上下文检索（入库时，默认关闭）**：参考 Anthropic 2024 年 9 月公开的 Contextual Retrieval。向量化之前，让 LLM 给每个分块写一两句上下文说明，交代它出自哪份文档、属于哪个部分。

- 文档可能比模型上下文长得多，所以只给有界窗口：文档标题 + 本批分块前后各 `neighbour-chunks` 个相邻分块，总长度不超过 `max-window-chars`。
- 每 `chunks-per-request` 个相邻分块合并成一次 LLM 调用，同一段窗口只发送一次；最多 `concurrency` 个调用同时在途，调用方为 `system-contextual`，受全局 LLM Token 预算约束，触发限流时按配置退避重试。system 提示词固定、user 消息以文档标题开头，DeepSeek、通义千问等接口的自动前缀缓存可以命中。
- 说明写入 `document_vectors.chunk_context`。embedding 用“说明 + 空行 + 原文”；ES 里 `textContent` 仍存原文，说明单独存 `chunkContext` 字段，BM25 用 `combined_fields` 把两个字段当成一个字段打分；rerank 也看“说明 + 原文”。展示、引用、证据片段只用原文。
- 某一批调用失败或模型漏掉某个分块，只记日志，这些分块按原文入索引，不影响入库。已有说明的分块不会重复生成。
- embedding 模型重建（`/api/v1/admin/knowledge-index/rebuild`）复用 MySQL 里已生成的说明，不重新调用 LLM；单文件重建索引会重新解析、重新生成说明。给已有知识库补说明，对文件逐个重建索引即可。
- 启动时会给已有的线上索引追加 `chunkContext` 字段（ES 允许给已有索引加新字段，不需要重建），切换别名或回滚到旧索引前也会先补齐。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `knowledge.contextual-retrieval.enabled` | `false` | 总开关（`KNOWLEDGE_CONTEXTUAL_RETRIEVAL_ENABLED`） |
| `knowledge.contextual-retrieval.chunks-per-request` | `8` | 一次 LLM 调用处理的相邻分块数 |
| `knowledge.contextual-retrieval.neighbour-chunks` | `2` | 窗口在本批分块前后各带几个相邻分块 |
| `knowledge.contextual-retrieval.max-window-chars` | `6000` | 送给 LLM 的窗口最大字符数 |
| `knowledge.contextual-retrieval.max-context-chars` | `200` | 单个分块说明的最大字符数 |
| `knowledge.contextual-retrieval.concurrency` | `2` | 同时在途的 LLM 调用数（所有文件共享） |
| `knowledge.contextual-retrieval.rate-limit-retries` / `rate-limit-backoff-seconds` | `2` / `10` | 触发全局 LLM 预算限流时的重试 |

**父子检索（问答时，默认开启）**：检索仍然在小分块上做，命中以后把分块连同同一文件里前后相邻的分块（父块）交给模型。

- 父块从命中分块开始向后、向前交替扩展，最多各 `neighbour-chunks` 个，单个父块不超过 `max-chars-per-hit`，相邻分块之间的切分重叠会去掉。
- 多个命中落在同一个父块里时，父块只在第一次出现时给出，后面的编号只附命中分块并注明“上下文已在 [n] 中给出”；所有父块的总长度受 `max-total-chars` 约束，用完后剩余命中只给小分块。
- 引用编号、页码、锚点和证据片段仍然指向命中的小分块。开启引用校验时，按模型实际看到的父块判断引用是否被支撑。
- `search_knowledge`、`generate_summary` 两个工具和离线评测的生成、评委环节用同一套父块，检索指标仍按小分块计算。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `search.parent-context.enabled` | `true` | 总开关（`SEARCH_PARENT_CONTEXT_ENABLED`） |
| `search.parent-context.neighbour-chunks` | `1` | 命中分块前后各最多扩展几个相邻分块 |
| `search.parent-context.max-chars-per-hit` | `1500` | 单个父块最大字符数 |
| `search.parent-context.max-total-chars` | `6000` | 一次检索所有父块的总字符数上限 |

### 企业级多租户

派聪明通过组织标签支持多租户架构。每个用户可以创建或加入一个或多个组织，每个组织可以拥有独立的知识库和文档管理。这样，企业可以在同一系统中管理多个团队或部门的知识库，而无需担心数据混淆或权限问题。

![派聪明的安全架构](https://cdn.tobebetterjavaer.com/stutymore/README-20250730103118.png)

### 实时通信

系统采用 WebSocket 技术，提供用户与 AI 系统之间的实时交互，支持响应式聊天界面，便于知识检索和 AI 互动。

### 文档冲突检测

完全相同的文件在上传时按 MD5 去重。内容冲突（两篇文档对同一个配置项给了不同的值）在文件向量化完成后异步检测，检测失败只记日志，不影响入库。

- 取新文件前 `max-chunks-per-file` 个分块，逐块在 `knowledge_base` 别名上做 KNN，只在同一可见范围内找其他文件的分块（同一上传者、公开文档、同组织标签，复用检索的权限过滤）。
- 向量余弦相似度达到 `similarity-threshold`，归一化（全半角、大小写、空白、标点）后文本不完全相同，把数字、版本号、日期替换成占位符后文本仍高度相似，且两侧抽出的关键值集合不同，才记为疑似冲突。
- 可选开启 LLM 二次确认，所有候选合并成一次调用，调用失败时保留规则结果。
- 疑似冲突写入 `document_conflicts` 表，同一分块对已有 PENDING 或 IGNORED 记录时不会重复写入。系统不会自动删除或覆盖任何文档，由管理员决定保留哪一篇。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `knowledge.conflict.enabled` | `true` | 总开关（`KNOWLEDGE_CONFLICT_ENABLED`） |
| `knowledge.conflict.similarity-threshold` | `0.9` | 分块向量余弦相似度阈值 |
| `knowledge.conflict.masked-text-similarity-threshold` | `0.8` | 屏蔽关键值后的文本相似度阈值 |
| `knowledge.conflict.max-chunks-per-file` | `200` | 每个新文件最多检查的分块数 |
| `knowledge.conflict.neighbors-per-chunk` | `3` | 每个分块取几个最相似的其他文件分块 |
| `knowledge.conflict.max-conflicts-per-file` | `50` | 单个文件一次最多记录的疑似冲突数 |
| `knowledge.conflict.llm-confirm.enabled` | `false` | LLM 二次确认（`KNOWLEDGE_CONFLICT_LLM_CONFIRM_ENABLED`） |

管理员接口（`/api/v1/admin/**` 仅 ADMIN 可访问）：

- `GET /api/v1/admin/document-conflicts?status=PENDING&page=1&size=20` 分页查看疑似冲突
- `POST /api/v1/admin/document-conflicts/{id}/resolve`，请求体 `{"action": "KEEP_NEW", "note": "..."}`。`KEEP_NEW` 通过现有删除流程删掉旧文档，`KEEP_EXISTING` 删掉新文档，`IGNORED` 只标记不是冲突。被删除文件参与的其他待处理冲突会标记为 `OBSOLETE`。

### 离线 RAG 评测

评测把检索和生成分开看，回答错了能分清是没搜到，还是搜到了没用对。

- 数据集为 JSONL，每行一个用例：`{"question": "...", "expectedFileMd5s": ["..."], "expectedFileNames": ["..."], "expectedEvidence": ["..."], "referenceAnswer": "..."}`，期望文档 MD5 和文件名至少填一个才计算检索指标。`expectedEvidence` 可选，填答案分块里一定会出现的短语，命中分块包含任意一个才算相关（分块级）；不填时同一文件的任何分块都算命中（文件级），一份大文档里召回了不相干的分块也算对，Recall 会虚高。匹配前对分块和短语都做 NFKC 归一化并去掉空白，PDF 抽出的康熙部首（如 `⼤`）也能匹配。用短语而不是分块编号，是为了调整分块策略后标注依然有效。
- 内置两份数据集：`sample-eval.jsonl` 是 5 条示例；`paismart-eval-v1.jsonl` 是 50 条正式测评集，覆盖单点事实、同义改写、多跳综合、资料冲突、私有文档、跨文档、无答案七类题型（id 前缀即题型），全部带分块级标注。`scripts/eval_compare.py` 按题型拆分报告，并把多份报告并排对比。
- 检索以发起评测的管理员身份调用线上同一条混合检索链路，计算 Recall@K（前 K 条里出现任一相关分块即命中）、文件级 Recall（`docRecallAtK`，用来和分块级对比）和 MRR，并记录检索模式（`HYBRID` / `HYBRID_RRF` / `TEXT_ONLY`）。检索的问题向量化计入该管理员的 Embedding 查询限流（`embedding-query-request`），每日上限调得太低时检索会降级为 `TEXT_ONLY`，看报告的检索模式统计就能发现。
- 生成沿用问答的提示词约定（`ai.prompt.rules` + 检索片段块），用非流式调用得到回答。
- 另起一次 LLM 调用做评委，对照参考答案和检索证据输出 `{"score": 1-5, "correct": true/false, "reason": "..."}`。评委提示词把所有字段当作数据，不因回答更长而加分；输出解析失败的用例，以及检索没召回答案分块、评委却判对的用例（`MISS_BUT_CORRECT`，人工校准发现评委在这类用例上偏宽松）一律标记人工复核，另按 `eval.human-review-sample-rate` 随机抽样人工复核。
- 推理模型的思考过程也计入 `max_tokens`，预算用完时回答为空；生成和评委输出为空时都会重试一次，因长度截断（`finish_reason=length`）的把预算翻倍。报告的 `config.llm` 记录本次用的主模型。
- 报告给出 Recall@K、MRR、评委平均分、准确率和问题归因：`PROMPT_OR_MODEL`（检索命中但回答错，查提示词或模型），`RETRIEVAL_MISS`（检索未命中，查分块和检索），以及逐条明细。报告以 JSON 写入 `eval.output-dir`，文件名带 `label`，调整分块前后各跑一次、用不同 label 区分即可对比。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `eval.output-dir` | `eval-reports` | 报告输出目录（`EVAL_OUTPUT_DIR`） |
| `eval.dataset-dir` | `eval-datasets` | `datasetPath` 只能指向该目录下的文件，或 `classpath:eval/xxx.jsonl` |
| `eval.human-review-sample-rate` | `0.1` | 随机抽样人工复核比例 |
| `eval.random-seed` | 空 | 固定后两次评测抽到同一批用例 |
| `eval.default-top-k` / `eval.max-top-k` | `5` / `20` | 检索条数 |
| `eval.max-cases` | `500` | 单次评测最多用例数 |
| `eval.generation-max-tokens` / `eval.judge-max-tokens` | `4000` / `2000` | 生成和评委的最大输出 tokens；推理模型的思考过程也计入，给小了会只思考不输出 |
| `eval.max-retry-tokens` | `8000` | 输出为空时重试一次，因长度截断的把预算翻倍，但不超过该值 |
| `eval.generation-timeout-seconds` / `eval.judge-timeout-seconds` | `90` / `60` | 生成和评委的超时 |
| `eval.requester-id` | `system-eval` | 生成和评委调用的请求方，以 system 开头不占用户配额 |
| `eval.human-agreement.min-agreement-rate` | `0.8` | 评委与人工一致率低于该值时告警（`EVAL_HUMAN_AGREEMENT_MIN_RATE`） |
| `eval.human-agreement.min-kappa` | 空 | Cohen's kappa 下限，为空时不检查 |
| `eval.human-agreement.min-comparable-cases` | `5` | 可比较用例少于该数量时只提示样本不足 |

管理员接口：

- `POST /api/v1/admin/eval/run`（JSON）：`{"datasetPath": "classpath:eval/sample-eval.jsonl", "topK": 5, "label": "chunk-512"}`，也可以用 `jsonl` 字段直接传内容
- `POST /api/v1/admin/eval/run`（multipart）：`file` 为 JSONL 文件，可带 `topK`、`label` 参数
- `GET /api/v1/admin/eval/runs`、`GET /api/v1/admin/eval/runs/latest`、`GET /api/v1/admin/eval/runs/{runId}` 查看进度、汇总指标和报告路径
- `GET /api/v1/admin/eval/runs/{runId}/report` 获取完整报告 JSON，录入过人工判定时附带 `humanReview`
- `POST /api/v1/admin/eval/runs/{runId}/human-reviews`：录入人工判定，`{"reviews": [{"caseId": "c1", "correct": true, "score": 4, "note": "..."}]}`，`score`（1-5）和 `note` 可选，同一用例重复提交以最后一次为准
- `GET /api/v1/admin/eval/runs/{runId}/human-reviews` 查看人工判定和评委与人工一致率

同一时间只运行一个评测任务，任务状态保存在内存里（最近 20 个），报告文件保存在磁盘上。

**评委校准**：评委模型有自己的偏差，要定期抽一批让人工也判一遍。人工判定保存在报告旁边的 `{报告文件名}.human-reviews.json` 里，报告文件本身不改，重启后按 runId 在 `eval.output-dir` 里找回。只比较评委和人工都给出 `correct` 的用例，计算一致率和 Cohen's kappa（扣掉碰巧一致的部分，正确率很高或很低时一致率会虚高）；人工也打了分的用例额外给出评委分与人工分的平均绝对差。结果挂在任务状态的 `humanAgreement` 上：一致率低于 `min-agreement-rate`（或 kappa 低于 `min-kappa`）时 `status` 为 `NEEDS_RECALIBRATION` 并带 `warning`，说明评分标准要改或者评委模型已经不可靠，本次准确率和平均分不能直接采信，`disagreementCaseIds` 列出分歧用例。

## 前置环境

在开始之前，请确保已安装以下软件：

- Java 17
- Maven 3.8.6 或更高版本
- Node.js 18.20.0 或更高版本
- pnpm 8.7.0 或更高版本
- MySQL 8.0
- Elasticsearch 8.10.0
- MinIO 8.5.12
- Kafka 3.2.1
- Redis 7.0.11
- Docker（可选，用于运行 Redis、MinIO、Elasticsearch 和 Kafka 等服务）

## 架构设计

派聪明的架构具备一个现代化的、云原生应用程序的特点，具有清晰的关注点分离、可扩展的组件和与 AI 技术的集成。模块化设计允许随着技术的发展，特别是快速变化的 AI 集成领域，未来可以扩展和替换单个组件。

![派聪明的系统概述](https://cdn.tobebetterjavaer.com/stutymore/README-20250730102655.png)

控制层用于处理 HTTP 请求，验证输入，管理请求/响应格式化，并将业务逻辑委托给服务层。控制器按领域功能组织。遵循 RESTful 设计原则，集成了性能监控和日志记录，用于跟踪 API 使用和故障排除。

```java
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    @Autowired
    private DocumentService documentService;
    
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        // 参数验证和委托给服务
        documentService.deleteDocument(fileMd5);
        // 响应处理
    }
}
```

服务层主要用来处理应用的业务逻辑，具有事务感知能力，能够处理跨越多个数据源的操作。

```java
@Service
public class DocumentService {
    @Autowired
    private FileUploadRepository fileUploadRepository;
    
    @Autowired
    private MinioClient minioClient;
    
    @Autowired
    private ElasticsearchService elasticsearchService;
    
    @Transactional
    public void deleteDocument(String fileMd5) {
        // 文档删除的业务逻辑
        // 协调多个仓储和系统
    }
}
```

数据访问层使用 Spring Data JPA 进行数据库操作，提供了对 MySQL 的 CRUD 操作。

```java
@Repository
public interface FileUploadRepository extends JpaRepository<FileUpload, Long> {
    Optional<FileUpload> findByFileMd5(String fileMd5);
    
    @Query("SELECT f FROM FileUpload f WHERE f.userId = :userId OR f.isPublic = true OR (f.orgTag IN :orgTagList AND f.isPublic = false)")
    List<FileUpload> findAccessibleFilesWithTags(@Param("userId") String userId, @Param("orgTagList") List<String> orgTagList);
}
```

实体层由映射到数据库表的 JPA 实体以及用于 API 请求和响应的 DTO（数据传输对象）组成。

```java
@Entity
public class FileUpload {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String fileMd5;
    private String fileName;
    private String userId;
    private boolean isPublic;
    private String orgTag;
    // 其他字段和方法
}
```

## 环境变量与新的启动方式

现在本地开发建议按“准备 `.env` -> 启基础服务 -> 启后端 -> 启前端”的顺序启动，不再需要把一堆环境变量手动 export 到终端。

### 1. 准备项目根目录 `.env`

项目根目录的 `.env` 用于保存后端本地运行和部署相关配置。首次使用时先复制模板：

```bash
cp .env.example .env
```

后端启动时会通过 `DotenvEnvironmentPostProcessor` 自动读取项目根目录 `.env`，所以无论是 IDE 直接运行 `SmartPaiApplication`，还是在项目根目录执行 `mvn spring-boot:run`，都会优先使用这里的配置。

`.env` 里当前主要有三类配置：

- 应用运行配置：MySQL、Redis、Kafka、MinIO、Elasticsearch、JWT、AI Provider 等
- 初始化与安全配置：如 `ADMIN_BOOTSTRAP_*`、`APP_AUTH_REGISTRATION_MODE`、`SECURITY_ALLOWED_ORIGINS`
- 前端部署配置：如 `DEPLOY_SERVER_HOST`、`DEPLOY_SERVER_USER`、`DEPLOY_SERVER_KEY`、`DEPLOY_TARGET_DIR`、`DEPLOY_HEALTHCHECK_URL`

几个关键项建议优先确认：

- `SPRING_PROFILES_ACTIVE=dev`：本地源码启动默认使用 `dev`
- `SPRING_DATASOURCE_*`、`SPRING_DATA_REDIS_*`：数据库和 Redis 连接
- `SPRING_KAFKA_BOOTSTRAP_SERVERS`、`MINIO_*`、`ELASTICSEARCH_*`：基础依赖地址
- `JWT_SECRET_KEY`：必须是 Base64 字符串，可用 `openssl rand -base64 32` 生成
- `ADMIN_BOOTSTRAP_ENABLED`：仅首次创建管理员时临时改为 `true`，创建完成后改回 `false`

说明：

- `.env` 是后端和部署脚本共用的根配置
- 前端自己的 Vite 变量仍放在 `frontend/.env`、`frontend/.env.test`、`frontend/.env.prod`
- `pnpm run dev` 实际使用的是 `vite --mode test`，默认会读取 `frontend/.env.test`

### 2. 启动本地基础服务

`infra.sh` 是现在推荐的本地基础设施启动入口，用来统一管理 `minio`、`kafka`、`elasticsearch`。

### `infra.sh`

用于在本机启动、停止和查看基础依赖服务，目前支持 `minio`、`kafka`、`elasticsearch`。

```bash
# 启动全部基础服务
./infra.sh start

# 启动指定服务
./infra.sh start minio kafka

# 查看状态
./infra.sh status

# 查看某个服务日志
./infra.sh logs elasticsearch

# 输出本地访问地址
./infra.sh urls
```

如果只想启动部分依赖，也可以按服务名传参：

```bash
./infra.sh start minio kafka
```

### 3. 启动后端

基础服务就绪后，在项目根目录启动 Spring Boot：

```bash
mvn spring-boot:run
```

也可以直接在 IDE 中运行 `src/main/java/com/yizhaoqi/smartpai/SmartPaiApplication.java`，效果一样，都会自动读取根目录 `.env`。

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm run dev
```

前端开发默认访问 `http://localhost:8081/api/v1`，对应配置在 `frontend/.env.test`。

### 5. 服务器脚本启动

如果是服务器上用 jar 包方式运行，可以参考根目录的 `launch.sh.example`。建议先复制成你自己的启动脚本，再按需调整 JDK、Maven 和 jar 名称。脚本支持先加载指定 `.env`，再执行 `start`、`restart`、`stop`、`status`、`logs` 等命令。

```bash
cp launch.sh.example launch.sh
chmod +x launch.sh

# 使用默认 .env 启动
./launch.sh start

# 使用指定环境文件启动
./launch.sh start -e .env.prod
```

其中：

- `start`：会先 `git pull`，再重新打包并启动
- `restart`：直接重启现有 jar
- `status` / `logs`：查看进程状态和日志

脚本默认带上以下 JVM 参数，自行编写启动命令或 Docker 镜像时也建议保留：

- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./logs`：发生 OOM 时自动生成堆转储文件，用来定位是哪个对象撑爆了内存（可用 `HEAP_DUMP_PATH` 覆盖目录）
- `-XX:+ExitOnOutOfMemoryError`：OOM 后直接退出，交给外部守护进程重启
- `-XX:G1PeriodicGCInterval=30000`：空闲时定期 GC，让文件处理背压（堆使用率超过 `file.parsing.max-memory-threshold` 时暂停 Kafka 消费）能及时恢复

### 6. 前端部署脚本

`deploy-front.sh` 用于构建前端、打 zip 包、上传到服务器，并在远端替换 `/home/www/PaiSmart-Front/dist`。脚本会自动读取根目录 `.env` 中的部署配置。

```bash
# 直接构建并部署前端
./deploy-front.sh
```

部署脚本默认会执行这些步骤：

- 进入 `frontend` 执行 `pnpm build`
- 打包 `dist` 为 zip 文件并上传到服务器
- 删除远端旧的 `dist` 目录并解压新包
- 检查远端 `dist/index.html` 是否存在
- 请求 `DEPLOY_HEALTHCHECK_URL` 做健康检查

如果只想复用已有的前端构建产物，可以在执行时跳过构建：

```bash
DEPLOY_SKIP_BUILD=1 ./deploy-front.sh
```

## 八、解锁派聪明源码+教程

那这次为了避免盗版，这次的代码仓库采用的是邀请制，加入星球后，在星球第一个置顶帖【球友必看】中获取邀请链接，审核通过后即可查看。

![派聪明的源码申请](https://cdn.tobebetterjavaer.com/paicoding/0abd7b441b744b33d48277be776e58cc.png)

派聪明的教程，这次托管在技术派教程上，之前只要在技术派上绑定过星球的成员编号，均可以解锁查看。

>派聪明教程地址：https://paicoding.com/column/10/1

![派聪明教程](https://cdn.tobebetterjavaer.com/paicoding/a157a62358a6b3c2dab478988143271a.png)

并且了照顾大家的阅读习惯，我们也会在星球里第一时间同步。

![星球付费专栏](https://cdn.tobebetterjavaer.com/paicoding/d2c867d82d57ef1560fed6267eb02590.png)


加入[「二哥的编程星球」](https://javabetter.cn/zhishixingqiu/)后，你还可以享受以下专属内容服务：

- 1、**付费文档:** 派聪明 RAG、[微服务 PmHub](https://laigeoffer.cn/pmhub/learn/)、[前后端分离技术派](https://javabetter.cn/zhishixingqiu/paicoding.html)、轮子 MYDB、入门编程喵、AI+MCP 的校招派等项目配套的 60 万+ 字教程查看权限
- 2、**简历修改**: 提供价值超 600 元的[简历修改服务](https://javabetter.cn/zhishixingqiu/jianli.html)，附赠星球 5000+优质简历模板可供参考
- 3、**专属问答**: 向二哥和星球嘉宾发起 1v1 提问，内容不限于 offer 选择、学习路线、职业规划等
- 4、**面试指南**: 获取针对校招、社招的 40 万+字面试求职攻略《[Java 面试指南](https://javabetter.cn/zhishixingqiu/mianshi.html)》，以及二哥的 LeetCode 刷题笔记、一灰的职场进阶之路、华为 OD 题库
- 5、**学习环境:** 打造一个沉浸式的学习环境，有一种高考冲刺、大学考研的氛围

截止到 2025 年 07 月 31 日，已经有 9000+ 球友加入星球了，很多小伙伴在认真学习项目之后，都成功拿到了心仪的校招或者社招 offer，我就随便举两个例子。

![美团快手 TP-LINK 拼多多](https://cdn.tobebetterjavaer.com/stutymore/readme-20250703180225.png)

![阿里云荣耀字节](https://cdn.tobebetterjavaer.com/stutymore/readme-20250703180738.png)


目前，派聪明这个项目也收尾了，大家可以放心冲 😊。并且一次购买不需要额外付费，即可获取星球的所有付费资料，帮助你少走弯路，提高学习的效率。直接微信扫下面这个优惠券即可加入。

![派聪明优惠券](https://cdn.tobebetterjavaer.com/paicoding/97601d7a337d7d944b02bb4a79cd6430.png)

> 步骤 ①：微信扫描上方二维码，点击「加入知识星球」按钮

> 步骤 ②：访问星球置顶帖球友必看：[https://t.zsxq.com/11rEo9Pdu](https://t.zsxq.com/11rEo9Pdu)，获取项目的源码和配套教程

加入星球需要多少钱呢？星球目前定价 159 元，限时优惠 30 元，目前只需要 129 元就可以加入。

0 人的时候优惠完 69 元，1000 人的时候 79 元，2000 人的时候 89 元，3000 人的时候 99 元，5000 人的时候是 119 元，后面肯定还会继续涨。

付费社群我加入了很多，但从未见过比这更低价格，提供更多服务的社群，光派聪明这个项目的就能让你值回票价。

多说一句，任何时候，技术都是我们程序员的安身立命之本，如果你能认认真真跟完派聪明的源码和教程，相信你的编程功底会提升一大截。

再给大家展示一下派聪明教程的部分目录吧，真的是满满的诚意和干货。

![派聪明整体设计方案](https://cdn.tobebetterjavaer.com/paicoding/6b670c22740e9e7b3dfae35fd646196e.png)

![派聪明 prompt](https://cdn.tobebetterjavaer.com/paicoding/1e5e0055300a70a4cb83791f889bec20.png)

![派聪明教程目录](https://cdn.tobebetterjavaer.com/stutymore/readme-20250106103555.png)


之前就有球友反馈说，“**二哥，你这套教程如果让培训机构来卖，1999 元都算少！**

讲真心话，这个价格也不会持续很久，星球已经 9000 人了，马上 10000 人会迎来一波新的涨价（169 元），所以早买早享受，不要等，想好了就去冲，错过不能说后悔一辈子，但至少会有遗憾。


![球友们加入星球后的真实反馈](https://cdn.tobebetterjavaer.com/paicoding/0d2b52387576b0884e832c05594fc9de.png)

我们的代码，严格按照大厂的标准来，无论是整体的架构，还是具体的细节，都是无可挑剔的学习对象。

![派聪明的代码细节](https://cdn.tobebetterjavaer.com/paicoding/e946bb63f1fe5279888bb7f1fcb649b0.png)

之前曾有球友问我：“二哥，你的星球怎么不定价 199、299、399 啊，我感觉星球提供的价值远超这个价格啊。”

答案很明确，我有自己的原则，**拒绝割韭菜，用心做内容，能帮一个是一个**。

![我愿意给大家最真诚的服务](https://cdn.tobebetterjavaer.com/paicoding/e946bb63f1fe5279888bb7f1fcb649b0.png)

不为别的，为的就是给所有人提供一个可持续的学习环境。当然了，随着人数的增多，二哥付出的精力越来越多，星球也会涨价，今天这批 30 元的优惠券不仅 2025 年最大的优惠力度，也是 2026 年最大的优惠力度，现在入手就是最划算的，再犹豫就只能等着涨价了。

想想，QQ 音乐听歌连续包年需要 **88 元**，腾讯视频连续包年需要 **178 元**，腾讯体育包年 **233 元**。我相信，二哥编程星球回馈给你的，将是 10 倍甚至百倍的价值。

最后，希望小伙伴们，能紧跟我们的步伐！不要掉队。今年，和二哥一起翻身、一起逆袭、一起晋升、一起拿高薪 offer！

那无论你是社招还是校招，我们都希望你通过派聪明这个项目，能提升自己的简历含金量，拿到更好的 offer，也能更加从容的应对面试中各种 AI 相关的考察。

冲。
