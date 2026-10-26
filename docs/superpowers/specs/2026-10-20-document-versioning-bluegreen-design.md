# 业务文档版本化与蓝绿切换设计

- 日期：2026-10-20
- 状态：待用户审阅
- 范围：业务文档 / 内容版本 / 索引构建三层模型；新版本加工完成后再切换；旧版失败保留；检索按 active 范围过滤；最小 UI
- 关联：WP-1 复杂问题回答（`docs/superpowers/specs/2026-10-19-wp1-complex-question-answering-design.md`）

## 1. 背景与现状

当前文档身份和索引写入都以 `file_md5` 为中心：

- `file_upload` 唯一键是 `(file_md5, user_id)`（`docs/databases/ddl.sql:47`）；同名不同内容就是两份独立文档，没有“同一份业务文档”的概念。
- ES 文档 `_id = fileMd5_chunkId`（`VectorizationService.java:184`），同一 md5 重新加工会覆盖旧分块。
- `document_vectors` 唯一键是 `(file_md5, chunk_id)`（`models/DocumentVector.java:18-21`），同一 md5 的多次加工无法共存。
- 单文件重建 `DocumentService.reindexDocument` 先删别名内 ES、再删 `document_vectors`、再解析和向量化（`DocumentService.java:195-249`）；解析/向量化失败时旧内容从检索视角消失。
- 现有“索引版本”是 `knowledge_base_<provider_model_dims>`、`knowledge_base_legacy_<ts>` 和别名切换（`KnowledgeIndexNames.java:12-40`、`KnowledgeIndexManager.java:178-195`），服务的是 embedding 模型维度的全库重建；它不能表达单个业务文档的版本。
- ES mapping 里没有业务版本/状态字段；`modelVersion` 只是 embedding 模型轴（`client/EmbeddingClient.java:210-211`）。
- 前端文档列表没有版本概念；上传弹窗只有普通上传，没有“上传新版本”；引用详情只返回 fileMd5/fileName/page/anchor。

用户已确认的方向：明确区分“同一份业务文档”和“该文档的某个版本”；新版本完成加工后再切换；旧版在失败时仍可检索；索引版本与业务文档版本分别建模。

## 2. 目标与非目标

### 目标

1. 引入业务文档、内容版本、索引构建三层模型，三者不混用。
2. 新内容上传产生新的内容版本；reindex/retry 只新增索引构建，不增加内容版本号。
3. 新 build 先以独立 `buildId` 写入 ES；完成并通过分块数核对后，单事务切换 MySQL active 指针；失败不切换，旧 active 继续可检索。
4. 检索只返回 active build 和未退休的 legacy 数据；旧 build 留在 ES 中但不参与检索。
5. 已有旧数据不中断检索；第一次对旧文档上传新版本/重建时懒登记为 legacy 版本，不复制 ES 数据。
6. 最小 UI：文档列表按业务文档聚合、显示当前版本、支持“上传新版本”、显示处理状态、引用详情显示版本号。

### 非目标

- 不做生效时间/适用范围选择（后续）。
- 不做版本历史抽屉、指定版本回滚/激活、旧版本预览/下载。
- 不做证据充分性判断（WP-1 后续）。
- 不拆分 ChatHandler（WP-1 后续）。
- 不实现完整的删除墓碑/重试/权限一致性（WP-3）。
- 不修复共享 MD5 的多用户权限“最后写者胜”；本版对共享 MD5 的旧记录拒绝自动版本化。
- 不自动清理旧 build / 旧版本；先保留，后续加保留期。
- 不改全库 embedding 索引别名机制；它继续只服务 embedding 模型维度重建。

## 3. 核心不变量

1. 一个 `business_document` 只属于一个 owner；`owner_user_id` 与 `file_upload.user_id` 同一语义。
2. `document_version.version_no` 只在**内容变化**时递增；同一内容的重建不增加版本号。
3. `document_build.build_no` 在同一个 version 内每次加工递增；`build_id` 全库唯一，是 ES 过滤键。
4. `business_document.active_build_id` 必须属于 `active_version_id`；首个 build 成功前两者都可为空。
5. 一个 build 在 `ACTIVE` 之前不能参与检索；只有切换事务能把它变成 `ACTIVE`。
6. 切换前旧 active build 保持 `ACTIVE`；切换后旧 build 变 `SUPERSEDED`，但数据和记录保留。
7. 新版本默认继承当前 active version 的 `org_tag` / `is_public`；本版不允许在换版本时改权限。
8. 同一 document 的内容 md5 不允许重复出现（包括旧版本）；上传相同内容返回 409，不建新版本。
9. 检索过滤 = 权限过滤 AND（active build 集合 OR 未退休的 legacy 数据）。
10. 旧数据在第一次被版本化之前，不写入任何 build 字段，继续按 legacy 分支检索。

## 4. 数据模型

### 4.1 `business_document`

| 列 | 类型 | 说明 |
|---|---|---|
| `document_id` | VARCHAR(36) PK | UUID |
| `owner_user_id` | VARCHAR(64) NOT NULL | 与 `file_upload.user_id` 同语义 |
| `title` | VARCHAR(255) NOT NULL | 默认首版 fileName；列表展示 |
| `active_version_id` | VARCHAR(36) NULL | 当前内容版本 |
| `active_build_id` | VARCHAR(36) NULL | 当前参与检索的构建 |
| `deleted_at` | DATETIME NULL | 软删除；检索/列表排除 |
| `created_at` / `updated_at` | DATETIME NOT NULL | |

索引：`(owner_user_id, deleted_at)`、`(active_build_id)`。

### 4.2 `document_version`

| 列 | 类型 | 说明 |
|---|---|---|
| `version_id` | VARCHAR(36) PK | UUID |
| `document_id` | VARCHAR(36) NOT NULL | 逻辑关联 |
| `version_no` | INT NOT NULL | 内容版本号，从 1 开始 |
| `file_md5` | CHAR(32) NOT NULL | 内容指纹 |
| `file_upload_id` | BIGINT NOT NULL | 关联上传/内容记录 |
| `file_name` | VARCHAR(255) NOT NULL | 该版本文件名 |
| `org_tag` | VARCHAR(255) NULL | 版本级权限快照 |
| `is_public` | BOOLEAN NOT NULL DEFAULT FALSE | 版本级权限快照 |
| `legacy_representation` | BOOLEAN NOT NULL DEFAULT FALSE | 该版本内容仍以旧 ES 分块表示 |
| `legacy_retired` | BOOLEAN NOT NULL DEFAULT FALSE | 旧分块已退休，不再参与检索 |
| `created_at` | DATETIME NOT NULL | |

唯一键：`(document_id, version_no)`、`(document_id, file_md5)`（同一文档不允许重复内容版本）；普通索引：`(file_md5)`、`(file_upload_id)`、`(legacy_retired)`。

### 4.3 `document_build`

| 列 | 类型 | 说明 |
|---|---|---|
| `build_id` | VARCHAR(36) PK | UUID，全库唯一，ES 过滤键 |
| `version_id` | VARCHAR(36) NOT NULL | 所属内容版本 |
| `build_no` | INT NOT NULL | 该版本内加工批次号，从 1 开始 |
| `status` | VARCHAR(16) NOT NULL | 状态机见 4.5 |
| `chunk_count` | INT NULL | 期望/实际分块数 |
| `error_message` | VARCHAR(1000) NULL | 失败原因 |
| `created_at` / `started_at` / `completed_at` | DATETIME NULL | |

唯一键：`(version_id, build_no)`；普通索引：`(status)`、`(version_id)`。

### 4.4 `document_vectors` 与 ES

`document_vectors`：

- 新增 `build_id VARCHAR(36) NULL`，普通索引。
- 唯一键由 `(file_md5, chunk_id)` 改为 `(build_id, chunk_id)`。
- 旧数据 `build_id` 为空；新 build 的行必须带 `build_id`。
- `file_md5` 保留，用于引用、legacy 父块回退和删除。

ES `EsDocument` / `knowledge_base.json` 新增：

- `documentId`、`versionId`、`buildId`：keyword。
- `versionNo`、`buildNo`：integer。
- 新 build 的 `_id = buildId + "_" + chunkId`；legacy 分块保持 `fileMd5_chunkId`。
- 新增字段必须显式写 mapping，禁止依赖动态映射。
- 同时修正现有 `isPublic` / `public` 命名错位：`EsDocument.isPublic` 用 `@JsonProperty("public")` 固定序列化名，mapping 声明 `public`；权限查询继续用 `public`。新增字段沿用同样的“显式序列化名 = mapping 名”规则。

### 4.5 build 状态机

```text
PENDING -> PROCESSING -> READY -> ACTIVE
                         |         |
                         v         v
                       FAILED   SUPERSEDED
PENDING/PROCESSING -> CANCELLED（文档被删除或构建被取消）
```

- `PENDING`：已建 build，等消费。
- `PROCESSING`：消费者已认领，解析/向量化中。
- `READY`：向量化完成且分块数核对通过，等待切换。
- `ACTIVE`：切换事务成功，参与检索。
- `SUPERSEDED`：被同文档更新的 active build 取代，数据保留但不检索。
- `FAILED`：解析/向量化/核对失败，旧 active 不变。
- `CANCELLED`：文档删除或主动取消。

### 4.6 检索范围（ActiveRetrievalScope）

检索范围由两部分组成，一起缓存：

```text
activeBuildIds       = business_document.active_build_id
                       WHERE deleted_at IS NULL
retiredLegacyMd5s    = document_version.file_md5
                       WHERE legacy_representation = true
                         AND legacy_retired = true
```

过滤语义：

```text
permissionFilter
AND (
  buildId IN activeBuildIds
  OR (
    buildId 不存在
    AND fileMd5 NOT IN retiredLegacyMd5s
  )
)
```

如果没有 active build 且没有退休 legacy（空库或全旧数据），省略版本过滤，保持旧行为。

## 5. 加工、切换与失败保留

### 5.1 首次上传（新业务文档）

1. 现有分片上传/合并流程不变。
2. 合并事务内创建 `business_document`、`document_version(version_no=1, legacy_representation=false)`、`document_build(build_no=1, PENDING)`。
3. outbox task 带 `documentId/versionId/buildId/buildKind=NEW_DOCUMENT`；Kafka key 改为 `buildId`。
4. 消费者写 ES 时使用新 `_id`，文档字段带 `documentId/versionId/buildId/versionNo/buildNo`。
5. 切换成功后文档才可检索；首个 build 失败时文档存在但不可检索，列表显示失败。

### 5.2 上传新版本（已有文档）

1. 前端在文档行点“上传新版本”，上传请求合并时带 `documentId`。
2. 后端校验：文档属于当前用户、未删除；新 md5 不等于该文档任一历史版本的 md5（相同内容返回 409）。
3. 锁定 `business_document` 行，`version_no = 当前最大 version_no + 1`；权限继承当前 active version。
4. 创建新 version + build(PENDING)，旧 active version/build 完全不动。
5. 消费者写新 build；解析/向量化失败只把新 build 标 FAILED，旧 active 不变。
6. 成功后切换：新 build ACTIVE、旧 build SUPERSEDED、文档 active 指针更新；旧版本数据保留。

### 5.3 reindex / 重试向量化

- 针对当前 active version 新建一个 build，`build_no +1`，`version_no` 不变。
- 旧 build 继续 ACTIVE，直到新 build 完成并切换。
- 用户对 FAILED build 点重试：新建 build；崩溃恢复：复用原 build 重新投递。
- 现有同步 `reindexDocument` 改为“建 build + 写 outbox + 返回 buildId”的异步接口；不再在请求线程里先删数据。
- 前端本版没有 reindex 按钮；后端接口和测试保留。

### 5.4 完成核对与切换

消费者在向量化完成后：

1. 调用 `DocumentBuildService.completeBuild(buildId, expectedChunkCount)`。
2. 核对 ES 中 `buildId` 的分块数 == 预期分块数，且 >0。
3. 不一致：build 标 FAILED，记录原因，旧 active 不动。
4. 一致：先把 build 标 READY（提交），再进入切换事务。
5. 切换事务：
   - `SELECT ... FOR UPDATE` 锁 `business_document`；
   - 若文档已删除：build 标 CANCELLED，不切换；
   - 校验 build 仍属于该文档且状态为 READY；
   - 旧 active build 标 SUPERSEDED；
   - 新 build 标 ACTIVE；
   - 更新 `active_version_id`、`active_build_id`；
   - 若文档存在 `legacy_representation=true` 的版本，同一事务把该版本 `legacy_retired=true`；
   - 提交后失效 ActiveRetrievalScope 缓存并发布失效消息。
5. 切换只改 MySQL，不做 ES 的 active 写操作；失败回滚时旧 active 指针天然保持。

### 5.5 失败、恢复与幂等

- 解析/向量化失败：build FAILED、文件处理状态 FAILED、旧 active 不动；新 build 的 ES/向量数据可保留供排查，不参与检索。
- 进程崩溃：文件处理租约恢复任务把文件处理状态改回 PENDING 并重投同一 build；消费者先按 `buildId` 清掉本次构建的残留，再重跑。
- 用户重试 FAILED build：创建新 build（build_no+1），不覆盖失败记录。
- 重复 outbox/Kafka 投递：build 状态 + 文件租约双重幂等；已 ACTIVE/SUPERSEDED/FAILED 的 build 不重复切换。
- 计数核对使用 ES count by `buildId` + 预期分块数；预期分块数来自本次解析/向量化结果。
- 旧 build 永不自动删除；回滚/清理是后续能力。

### 5.6 旧数据懒迁移（legacy）

不复制、不回填旧 ES 分块。第一次对旧文档上传新版本或重建时：

1. 创建 `business_document`，`document_version(version_no=1, legacy_representation=true, legacy_retired=false)`，权限快照取旧 `file_upload`。
2. 文档 `active_version_id` 指向该 legacy version，`active_build_id` 为空；旧分块继续走“buildId 缺失且 md5 未退休”的 legacy 分支检索。
3. 同一个 md5 被多个用户共享的旧记录拒绝自动版本化：返回明确错误，要求另存为新文档。判定依据是 `file_upload` 同 md5 存在多条记录。
4. 新版本 build 成功后，切换事务同时把该 legacy version 的 `legacy_retired=true`；active 范围缓存更新后，旧 md5 分块不再参与检索。
5. 新版本失败：`legacy_retired` 保持 false，旧文档继续可检索。
6. legacy 版本的旧分块没有 buildId；其父块扩展在未退休时按 `fileMd5` 回退，退休后不再被检索，不参与新 build 的父块扩展。

## 6. 检索与引用集成

### 6.1 ActiveRetrievalScope 服务

- `ActiveRetrievalScopeService` 提供 `ActiveRetrievalScope current()`：
  - 查询 `business_document.active_build_id`（未删除）与 `document_version.legacy_retired=true` 的 `file_md5`；
  - 进程内缓存，TTL 5 秒；切换/删除后主动失效；
  - 多实例通过 Redis 频道 `paismart:active-scope` 发布失效消息，各实例清本地缓存；
  - 缓存失效到重新加载之间，旧范围仍可用：旧 active 继续可查，新 build 最多延迟 5 秒可见，不会出现“新旧都没有”。
- 空范围时省略版本过滤；有范围时在 `HybridSearchService` 的统一过滤入口组合。

### 6.2 过滤点

- `HybridSearchService` 的 BM25、KNN 预过滤、纯文本回退三路共用同一过滤入口（当前 `HybridSearchService.java:113-132` 的 `permissionFilter` 处）。
- `ConflictCandidateFinder` 使用 active 范围，并排除同一 `documentId` 的分块；否则新版本会与自己的旧版本判冲突。
- `AgentToolRegistry.knowledge_stats` 的片段数改为 active+legacy 范围计数，避免把 SUPERSEDED 分块算进去。
- 离线评测走同一 `HybridSearchService`，legacy 数据结果保持不变；版本化后的文档只返回 active build。

### 6.3 父块扩展

- `SearchResult` 增加 `documentId/versionId/buildId/versionNo`。
- `ParentContextExpander` 有 `buildId` 时按 `(build_id, chunk_id)` 查相邻块；legacy 结果无 buildId，按 `(file_md5, chunk_id)` 回退。
- 父块只来自同一 build，禁止跨版本拼接。

### 6.4 引用

- `ReferenceInfo` / 引用详情增加 `documentId/versionId/versionNo/buildId`；前端引用预览显示 `vN`。
- 引用详情仍先做文件可访问性校验；版本化文档的引用优先按 active/版本权限检查，旧引用按原 fileMd5 校验（完整权限一致性留 WP-3）。
- 引用号 `[N]` 的编号与历史逻辑不变。

## 7. API 与最小 UI

### 7.1 文档列表

新增 `GET /api/v1/documents/library`：每个业务文档一行 + 每份未版本化旧文档一行。

`DocumentLibraryItem`：

```text
documentId            // legacy 行为空
title
legacy                // true = 未版本化
fileMd5               // active version 的 md5，或 legacy 的 md5
fileName
versionNo             // legacy 行为空/1
versionId
buildId               // legacy 行为空
buildStatus           // 展示值：ACTIVE / PROCESSING / FAILED / READY / LEGACY（LEGACY 表示没有 build 的未版本化行，不是 DB 状态）
latestAttemptStatus   // 比 active 更新的版本/构建的最新状态，没有则空
latestAttemptVersionNo
errorMessage
orgTag / isPublic
createdAt / updatedAt
```

- 版本化文档按 active version 的权限过滤；legacy 行按 `file_upload` 权限过滤。
- 列表按 `documentId`（legacy 按 fileMd5）排序/展示；旧版本不作为独立行。
- 旧 `/documents/accessible` 保留给预览/兼容调用。

### 7.2 上传新版本

- 现有 `/upload/merge` 增加可选字段：`documentId`（版本化文档）或 `legacyFileMd5`（首次版本化旧文档）。
- 前端上传弹窗新增“新版本模式”：固定目标文档，file picker，权限只读继承；上传 store 在 merge 请求里带目标字段。
- 相同内容返回 409：“内容与现有版本相同，未创建新版本”。
- 分片上传接口不变；分片仍按 md5 隔离。

### 7.3 reindex / retry / delete

- reindex：`POST /api/v1/documents/{documentId}/versions/{versionId}/builds`（异步，返回 buildId）。旧按 fileMd5 的同步 reindex 保留兼容入口但内部改为建 build。
- retry：现有 `POST /documents/{fileMd5}/vectorization/retry` 扩展为对 active version 建新 build；失败版本的重试也建新 build。
- delete：新增 `DELETE /api/v1/documents/{documentId}`，删整个业务文档（所有版本/构建）；legacy 的按 fileMd5 删除保留。本版沿用 best-effort 清理，不实现墓碑/重试（WP-3）。

### 7.4 状态推送

现有 `document_status` 事件增加：`documentId/versionId/versionNo/buildId/buildStatus`。前端按 documentId 匹配行；legacy 按 fileMd5 匹配。轮询兜底改为刷新 library 列表。

### 7.5 最小 UI

- 文档列表：标题/文件名、版本（`vN` 或“未版本化”）、组织/公开、更新时间、构建状态；行内“上传新版本”“删除”“预览”。
- 新版本处理中：列表行显示“新版本处理中”；失败显示“新版本失败，当前仍使用 vN”。
- 引用预览：标题旁显示 `vN`。
- 不做版本历史抽屉、回滚/激活按钮、旧版本下载。
- 前端类型：`Api.Document.LibraryItem`、`Upload.Merge`、`ReferenceDetail`、`Chat.ReferenceEvidence` 增加版本字段。

## 8. 迁移与上线

### 8.1 MySQL

- 新增三张表；`document_vectors` 加 `build_id`，删除 `uk_document_vectors_md5_chunk`，新增 `uk_document_vectors_build_chunk`。
- 更新 `docs/databases/ddl.sql`（新库）和新增 `docs/databases/migrations/2026-10-20-document-versioning.sql`（老库）。
- `LegacySchemaMigration` 增加 MySQL 上的唯一键迁移，保证 `ddl-auto=update` 不会漏掉唯一键替换；H2 由实体注解建表。
- 不给旧数据回填 document/version/build；旧数据保持 legacy。
- 唯一键替换后，legacy 行 `build_id` 为空，MySQL 唯一键允许多个 NULL；legacy 数据不再由新代码写入，部署顺序保证旧代码不会在迁移后继续插入同 md5 分块。

### 8.2 ES

- 更新 `knowledge_base.json`：新增 `documentId/versionId/buildId/versionNo/buildNo`，把 `isPublic` 改为实际序列化的 `public`。
- `ensureTemplateFields` 在启动/切别名时补齐字段；新字段必须先有 mapping 再写入。
- 新 build 用 `buildId_chunkId` 作为 `_id`；legacy 分块保持原 `_id`，不回填。
- 现有 embedding 全库重建继续使用物理索引/别名；重建时从 `document_vectors` 复制 `documentId/versionId/buildId`，legacy 行为空。

### 8.3 部署顺序与回滚

1. 先执行 MySQL 迁移和 ES mapping 补齐。
2. 再发布后端代码；旧数据仍按 legacy 检索。
3. 最后发布前端列表/上传新版本。
4. 回滚代码时新表/新列可保留；旧代码不读新字段，legacy 数据仍可检索。切换过的文档在旧代码下会把新旧 build 一起检索，回滚需把 active 指针切回旧版本或暂停版本化上传；这是已知回滚限制。

## 9. 测试

### 9.1 单测/集成

- 状态机：PENDING→PROCESSING→READY→ACTIVE；旧 build SUPERSEDED；失败不切换；计数不一致 FAILED。
- 切换事务：首次 build、新版本、reindex 不改 version_no、重复切换幂等、文档删除时 CANCELLED。
- 检索范围：active build 命中、inactive build 排除、legacy 未退休命中、legacy 退休排除、空范围旧行为不变。
- 父块扩展：同 build 取相邻块；legacy 回退；不跨 build。
- 冲突检测：同 documentId 的旧版本不参与候选。
- 懒迁移：旧文档首次上传新版本后 legacy 版本登记、切换后退休；失败保持可检索；共享 md5 拒绝。
- 上传：相同内容 409；新版本 version_no+1；权限继承；并发 merge 不重复建版本。
- 列表/引用：library 聚合、legacy 行、版本号、失败最新尝试；引用详情版本字段。
- 现有回归：WP-1 的 20 个测试、检索/引用/评测在 legacy 数据上结果不变。

### 9.2 迁移

- 在临时 MySQL 8 库执行迁移脚本：四张表/列/唯一键正确；老 `document_vectors` 行 build_id 为空。
- H2 `create-drop` 下实体建表通过。
- ES mapping 补齐测试：新字段 mapping 存在且类型正确；`public` 与查询一致。

### 9.3 手工验收（服务可用后）

1. 上传一份新文档 → 首版 build 完成 → 列表显示 v1，检索可命中。
2. 对同一文档上传新版本 → 处理期间旧版仍可命中；成功后列表 v2，检索只命中 v2；旧引用详情显示 v1。
3. 让新版本解析/向量化失败 → 列表显示“新版本失败，当前仍使用 vN”，旧版检索不受影响。
4. reindex 当前版本 → version_no 不变，build_no 增加；成功后 active build 切换。
5. 对旧数据文档上传新版本 → 懒迁移成功；共享 md5 旧记录被拒绝。
6. 前端 eslint/typecheck；WP-1 的浏览器验收也一并补跑。

## 10. 风险与限制

- **active 范围 terms 规模**：active build + retired legacy md5 集合随文档数增长；当前 KB 规模可接受。超过万级文档需改为 ES `active` 标记或独立 active 索引。
- **缓存可见性**：切换后最多 5 秒（或一次失效消息）内其他实例仍可能返回旧 build；旧版始终可用，不会出现空窗。
- **旧 build 存储**：旧版本/构建不自动删除，ES/MySQL 存储会增长；后续加保留期/清理任务。
- **共享 MD5**：旧记录被多个用户共享时拒绝自动版本化；新建文档不受影响。
- **legacy 退休**：切换后旧 md5 分块仍在 ES 中，只是被过滤；不做物理清理。
- **删除一致性**：本版仍是 best-effort；WP-3 再处理墓碑、ES 清理重试、在途消费者互斥。
- **权限变更**：版本继承权限，本版不做换版本改权限；旧引用按 fileMd5 校验，完整权限一致性留 WP-3。
- **回滚限制**：代码回滚后新旧 build 可能同时被检索；需要切回 active 指针或暂停版本化上传。

## 11. 后续（不在本版）

- WP-3：删除/权限一致性、墓碑、ES 清理重试、在途消费者互斥。
- 生效时间/适用范围与版本选择。
- 版本历史、回滚/激活旧版本、旧版本预览。
- 证据充分性判断（检索执行记录 → 去重证据 → 充分性 → 缺用户条件/缺资料）。
- ChatHandler 拆分：问答流程编排、单次运行状态、子问题/证据判断。
- 旧 build 保留期与自动清理。
- 共享 MD5 多用户权限模型。

## 12. 实现顺序（给计划用）

1. 表/实体/仓储 + `document_vectors.build_id` + ES 字段与 mapping。
2. `ActiveRetrievalScopeService` + 检索过滤 + 父块/统计/冲突集成。
3. build 状态机、outbox task 字段、消费者按 build 写入、计数核对。
4. 切换事务 + 缓存失效 + 状态推送。
5. 首次上传/新版本/reindex/retry API + legacy 懒迁移。
6. library/引用 API + 最小前端。
7. 迁移脚本、H2/MySQL 验证、回归与手工验收。

## 13. 明确不改变

- 现有分片上传、MinIO 路径、Kafka outbox/死信/心跳恢复机制的主体。
- embedding 索引别名/物理索引切换机制。
- 检索融合（RRF/重排/父子块）主体；只加 active 过滤与 buildId 父块查询。
- WP-1 的计划/澄清/覆盖说明行为。
