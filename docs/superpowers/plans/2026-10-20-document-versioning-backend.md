# 业务文档版本化后端 Implementation Plan (Plan A)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地业务文档 / 内容版本 / 索引构建三层模型；新版本先写独立 build，加工完成且分块数核对通过后只切 MySQL active 指针；失败保留旧版可检索；检索按 active build + 未退休 legacy 过滤。

**Architecture:** 新增 `business_document`、`document_version`、`document_build` 三张表；`document_vectors` 与 ES 文档带 `buildId`。新 build 写 ES 时使用独立 `_id`，旧 build 不覆盖；`ActiveRetrievalScopeService` 提供 active build 集合与退休 legacy md5 集合，`HybridSearchService` 在权限过滤同一入口组合版本过滤。旧数据不回填 ES，只在第一次版本化时登记 legacy 版本，切换成功后把旧 md5 标为退休。

**Tech Stack:** Java 17、Spring Boot 3.4.2、Spring Data JPA、H2（测试）、MySQL 8、Elasticsearch Java Client、JUnit 5、Mockito、Maven。

**Spec:** `docs/superpowers/specs/2026-10-20-document-versioning-bluegreen-design.md`

## Global Constraints

- 不提交、不推送，除非用户在当次任务里明确要求（AGENTS.md）。每个任务末尾只做“运行测试 + 报告”的检查点。
- 不改前端；最小 UI 在 Plan B 单独执行。
- 不重启后端、不启动前端 dev server；只运行 `mvn` 测试和编译。
- 不改 embedding 全库索引别名机制；不改 RRF/重排/父子块主体。
- 新字段必须先写 ES mapping 再写入；禁止依赖动态映射。
- 旧数据不回填 `buildId`；legacy 分支必须继续可检索。
- 单问题/WP-1 的聊天行为不受影响；现有 20 个 WP-1 测试必须继续通过。
- 所有面向用户的错误文案使用中文；不添加 emoji。
- 不在任何文件、日志或回复里写入 `.env`、密钥和生产主机信息。

## File Structure

**新增模型/仓储**
- `src/main/java/com/yizhaoqi/smartpai/model/BusinessDocument.java`
- `src/main/java/com/yizhaoqi/smartpai/model/DocumentVersion.java`
- `src/main/java/com/yizhaoqi/smartpai/model/DocumentBuild.java`
- `src/main/java/com/yizhaoqi/smartpai/model/DocumentBuildStatus.java`
- `src/main/java/com/yizhaoqi/smartpai/model/DocumentBuildContext.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/BusinessDocumentRepository.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/DocumentVersionRepository.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/DocumentBuildRepository.java`

**新增服务**
- `src/main/java/com/yizhaoqi/smartpai/service/document/ActiveRetrievalScope.java`
- `src/main/java/com/yizhaoqi/smartpai/service/document/ActiveRetrievalScopeService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/document/DocumentRetrievalFilter.java`
- `src/main/java/com/yizhaoqi/smartpai/service/document/DocumentBuildService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/document/DocumentBuildRecoveryJob.java`
- `src/main/java/com/yizhaoqi/smartpai/service/document/DocumentVersionService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/document/DocumentLibraryService.java`

**修改**
- `src/main/java/com/yizhaoqi/smartpai/model/DocumentVector.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/DocumentVectorRepository.java`
- `src/main/java/com/yizhaoqi/smartpai/entity/EsDocument.java`
- `src/main/java/com/yizhaoqi/smartpai/entity/SearchResult.java`
- `src/main/resources/es-mappings/knowledge_base.json`
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/search/ParentContextExpander.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ElasticsearchService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/DocumentService.java`
- `src/main/java/com/yizhaoqi/smartpai/model/FileProcessingTask.java`
- `src/main/java/com/yizhaoqi/smartpai/service/reliability/FileProcessingOutboxService.java`
- `src/main/java/com/yizhaoqi/smartpai/consumer/FileProcessingConsumer.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java`
- `src/main/java/com/yizhaoqi/smartpai/controller/UploadController.java`
- `src/main/java/com/yizhaoqi/smartpai/controller/DocumentController.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`（引用版本字段）
- `src/main/java/com/yizhaoqi/smartpai/config/RedisConfig.java`
- `docs/databases/ddl.sql`
- `docs/databases/migrations/2026-10-20-document-versioning.sql`

---

## Task 1: 三层模型、Repository、ES 字段与迁移

**Files:**
- Create: the 4 model classes + 3 repositories
- Modify: `DocumentVector.java`, `DocumentVectorRepository.java`, `EsDocument.java`, `knowledge_base.json`, `ddl.sql`
- Create: `docs/databases/migrations/2026-10-20-document-versioning.sql`
- Test: `src/test/java/com/yizhaoqi/smartpai/model/DocumentVersionModelTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/entity/EsDocumentJsonTest.java`

**Interfaces:**
- Consumes: none.
- Produces:
  - Entities with the fields in spec 4.1-4.3.
  - `DocumentBuildStatus` enum.
  - `BusinessDocumentRepository.findForUpdate(String)`, `findActiveBuildIds()`.
  - `DocumentVersionRepository.findRetiredLegacyMd5s()`, `findByDocumentIdOrderByVersionNoDesc`, `findByDocumentIdAndFileMd5`.
  - `DocumentBuildRepository.findByBuildId`, `findByVersionIdOrderByBuildNoDesc`, `findByStatus`, `findFirstByVersionDocumentIdOrderByVersionVersionNoDescBuildNoDesc`.
  - `DocumentVector.buildId`; `DocumentVectorRepository.findByBuildIdOrderByChunkIdAsc`, `findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc`, `deleteByBuildId`, `countByBuildId`.
  - `EsDocument` fields `documentId/versionId/buildId/versionNo/buildNo`, serialized `public`.

- [ ] **Step 1: 写实体/仓储失败测试**

Create `DocumentVersionModelTest` with `@DataJpaTest` and `@AutoConfigureTestDatabase` (follow `FileUploadRepositoryReliabilityTest`). Assert:
- saving a `BusinessDocument` and reading it back;
- duplicate `(document_id, version_no)` throws `DataIntegrityViolationException`;
- duplicate `(document_id, file_md5)` throws;
- duplicate `(version_id, build_no)` throws;
- two `DocumentVector` rows with null `buildId` and same `(fileMd5, chunkId)` are allowed (legacy), but two with same non-null `buildId` and same `chunkId` throw;
- `findActiveBuildIds()` returns only non-deleted docs with active build.

- [ ] **Step 2: 写 ES 序列化/mapping 失败测试**

`EsDocumentJsonTest`:
- `new ObjectMapper().writeValueAsString(doc)` contains `"public":true`, `"documentId"`, `"versionId"`, `"buildId"`, `"versionNo"`, `"buildNo"`;
- read `src/main/resources/es-mappings/knowledge_base.json`, assert properties contain the new keys and `public` (not `isPublic`).

- [ ] **Step 3: 运行测试确认失败**

Run:
```bash
mvn -Dtest=DocumentVersionModelTest,EsDocumentJsonTest test
```
Expected: 编译失败（新类不存在）。

- [ ] **Step 4: 实现实体、枚举和仓储**

Create `DocumentBuildStatus.java`:
```java
package com.yizhaoqi.smartpai.model;

public enum DocumentBuildStatus {
    PENDING, PROCESSING, READY, ACTIVE, SUPERSEDED, FAILED, CANCELLED
}
```

Create `BusinessDocument.java`:
```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_document", indexes = {
        @Index(name = "idx_business_document_owner", columnList = "owner_user_id,deleted_at"),
        @Index(name = "idx_business_document_active_build", columnList = "active_build_id")
})
public class BusinessDocument {
    @Id
    @Column(name = "document_id", length = 36)
    private String documentId;

    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "active_version_id", length = 36)
    private String activeVersionId;

    @Column(name = "active_build_id", length = 36)
    private String activeBuildId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

Create `DocumentVersion.java`:
```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "document_version",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_document_version_no", columnNames = {"document_id", "version_no"}),
                @UniqueConstraint(name = "uk_document_version_md5", columnNames = {"document_id", "file_md5"})
        },
        indexes = {
                @Index(name = "idx_document_version_md5", columnList = "file_md5"),
                @Index(name = "idx_document_version_upload", columnList = "file_upload_id"),
                @Index(name = "idx_document_version_retired", columnList = "legacy_retired")
        })
public class DocumentVersion {
    @Id
    @Column(name = "version_id", length = 36)
    private String versionId;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "legacy_representation", nullable = false)
    private boolean legacyRepresentation = false;

    @Column(name = "legacy_retired", nullable = false)
    private boolean legacyRetired = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

Create `DocumentBuild.java`:
```java
package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "document_build",
        uniqueConstraints = @UniqueConstraint(name = "uk_document_build_no", columnNames = {"version_id", "build_no"}),
        indexes = {
                @Index(name = "idx_document_build_status", columnList = "status"),
                @Index(name = "idx_document_build_version", columnList = "version_id")
        })
public class DocumentBuild {
    @Id
    @Column(name = "build_id", length = 36)
    private String buildId;

    @Column(name = "version_id", nullable = false, length = 36)
    private String versionId;

    @Column(name = "build_no", nullable = false)
    private Integer buildNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentBuildStatus status;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
```

`DocumentBuildContext.java`:
```java
package com.yizhaoqi.smartpai.model;

public record DocumentBuildContext(String documentId, String versionId, String buildId,
                                   Integer versionNo, Integer buildNo) {
}
```

Repositories:
```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.BusinessDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BusinessDocumentRepository extends JpaRepository<BusinessDocument, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from BusinessDocument d where d.documentId = :documentId")
    Optional<BusinessDocument> findForUpdate(@Param("documentId") String documentId);

    @Query("select d.activeBuildId from BusinessDocument d where d.deletedAt is null and d.activeBuildId is not null")
    List<String> findActiveBuildIds();

    List<BusinessDocument> findByDeletedAtIsNull();
}
```

```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, String> {
    List<DocumentVersion> findByDocumentIdOrderByVersionNoDesc(String documentId);

    Optional<DocumentVersion> findByDocumentIdAndFileMd5(String documentId, String fileMd5);

    List<DocumentVersion> findByDocumentIdAndLegacyRepresentationTrue(String documentId);

    List<DocumentVersion> findByFileUploadId(Long fileUploadId);

    @Query("select v.fileMd5 from DocumentVersion v where v.legacyRetired = true")
    List<String> findRetiredLegacyMd5s();
}
```

```java
package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DocumentBuildRepository extends JpaRepository<DocumentBuild, String> {
    Optional<DocumentBuild> findByBuildId(String buildId);

    List<DocumentBuild> findByVersionIdOrderByBuildNoDesc(String versionId);

    List<DocumentBuild> findByStatus(DocumentBuildStatus status);

    List<DocumentBuild> findByStatusAndCompletedAtBefore(DocumentBuildStatus status, LocalDateTime before);

    Optional<DocumentBuild> findFirstByVersionDocumentIdOrderByVersionVersionNoDescBuildNoDesc(String documentId);
}
```

Update `DocumentVector.java`:
- add `@Column(name="build_id", length=36) private String buildId;`
- change the unique constraint to `uk_document_vectors_build_chunk` on `build_id, chunk_id`.

Update `DocumentVectorRepository.java`:
```java
List<DocumentVector> findByBuildIdOrderByChunkIdAsc(String buildId);
Slice<DocumentVector> findByBuildIdOrderByChunkIdAsc(String buildId, Pageable pageable);
List<DocumentVector> findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(String buildId, Integer from, Integer to);
long countByBuildId(String buildId);
@Transactional @Modifying
@Query(value = "DELETE FROM document_vectors WHERE build_id = ?1", nativeQuery = true)
void deleteByBuildId(String buildId);
```

Update `EsDocument.java`:
- add `@com.fasterxml.jackson.annotation.JsonProperty("public")` on `isPublic`;
- add fields `documentId`, `versionId`, `buildId`, `versionNo`, `buildNo` with Lombok @Data setters.

Update `knowledge_base.json`:
- replace `"isPublic"` with `"public"` (boolean);
- add keyword fields `documentId`, `versionId`, `buildId`; integer fields `versionNo`, `buildNo`.

- [ ] **Step 5: 写 DDL 和迁移**

Append three CREATE TABLE statements to `ddl.sql` (copy columns from spec 4.1-4.3, engine/charset consistent with existing tables). Change `document_vectors` in `ddl.sql` to include `build_id VARCHAR(36) DEFAULT NULL` and `UNIQUE KEY uk_document_vectors_build_chunk (build_id, chunk_id)` instead of the old md5 key.

Create `docs/databases/migrations/2026-10-20-document-versioning.sql`:
```sql
CREATE TABLE IF NOT EXISTS business_document (
  document_id VARCHAR(36) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  title VARCHAR(255) NOT NULL,
  active_version_id VARCHAR(36) DEFAULT NULL,
  active_build_id VARCHAR(36) DEFAULT NULL,
  deleted_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (document_id),
  KEY idx_business_document_owner (owner_user_id, deleted_at),
  KEY idx_business_document_active_build (active_build_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_version (
  version_id VARCHAR(36) NOT NULL,
  document_id VARCHAR(36) NOT NULL,
  version_no INT NOT NULL,
  file_md5 VARCHAR(32) NOT NULL,
  file_upload_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  org_tag VARCHAR(50) DEFAULT NULL,
  is_public BOOLEAN NOT NULL DEFAULT FALSE,
  legacy_representation BOOLEAN NOT NULL DEFAULT FALSE,
  legacy_retired BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL,
  PRIMARY KEY (version_id),
  UNIQUE KEY uk_document_version_no (document_id, version_no),
  UNIQUE KEY uk_document_version_md5 (document_id, file_md5),
  KEY idx_document_version_md5 (file_md5),
  KEY idx_document_version_upload (file_upload_id),
  KEY idx_document_version_retired (legacy_retired)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS document_build (
  build_id VARCHAR(36) NOT NULL,
  version_id VARCHAR(36) NOT NULL,
  build_no INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  chunk_count INT DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  created_at DATETIME NOT NULL,
  started_at DATETIME DEFAULT NULL,
  completed_at DATETIME DEFAULT NULL,
  PRIMARY KEY (build_id),
  UNIQUE KEY uk_document_build_no (version_id, build_no),
  KEY idx_document_build_status (status),
  KEY idx_document_build_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE document_vectors ADD COLUMN build_id VARCHAR(36) DEFAULT NULL;
ALTER TABLE document_vectors DROP INDEX uk_document_vectors_md5_chunk;
ALTER TABLE document_vectors ADD UNIQUE KEY uk_document_vectors_build_chunk (build_id, chunk_id);
```
If the old index is already absent, the migration is not re-runnable; document that it is a one-time migration. `LegacySchemaMigration` will guard the same operation for startup.

- [ ] **Step 6: 运行测试确认通过**

Run:
```bash
mvn -Dtest=DocumentVersionModelTest,EsDocumentJsonTest test
```
Expected: both pass.

- [ ] **Step 7: 编译检查**

Run: `mvn -q -DskipTests compile`
Expected: exit 0.

- [ ] **Step 8: 检查点**

运行测试并报告；不提交。

---

## Task 2: ActiveRetrievalScope、版本过滤、父块与引用字段

**Files:**
- Create: `ActiveRetrievalScope.java`, `ActiveRetrievalScopeService.java`, `DocumentRetrievalFilter.java`
- Modify: `HybridSearchService.java`, `SearchResult.java`, `ParentContextExpander.java`, `RedisConfig.java`
- Test: `ActiveRetrievalScopeServiceTest.java`, `DocumentRetrievalFilterTest.java`, `ParentContextExpanderBuildTest.java`, `HybridSearchServiceScopeTest.java`

**Interfaces:**
- Consumes: Task 1 repositories, `DocumentVectorRepository` build methods.
- Produces:
  - `record ActiveRetrievalScope(Set<String> activeBuildIds, Set<String> retiredLegacyMd5s)`.
  - `ActiveRetrievalScopeService.current()`, `invalidate()`, `invalidateAndBroadcast()`.
  - `DocumentRetrievalFilter.build(scope)`, `combine(permission, scopeFilter)`.
  - `SearchResult` fields `documentId/versionId/buildId/versionNo`; `withParentContext` copies them.
  - Parent expander queries by buildId with legacy fallback.

- [ ] **Step 1: 写 scope/filter 失败测试**

`ActiveRetrievalScopeServiceTest`:
- mock `BusinessDocumentRepository.findActiveBuildIds()` → `["b1"]`, `DocumentVersionRepository.findRetiredLegacyMd5s()` → `["md5-old"]`; `current()` returns both sets;
- second `current()` does not call repositories again (cached);
- `invalidate()` causes reload;
- a fake clock/TTL is optional; test invalidation, not time.

`DocumentRetrievalFilterTest`:
- empty scope → `build` returns null;
- active builds only → query bool has a `terms` on `buildId`;
- retired legacy only → query bool has the legacy branch with `must_not exists(buildId)` and `must_not terms(fileMd5, retired)`;
- both → both should clauses; `combine(permission, null)` returns permission.

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ActiveRetrievalScopeServiceTest,DocumentRetrievalFilterTest test`
Expected: compile failure (new classes).

- [ ] **Step 3: 实现 scope/filter**

`ActiveRetrievalScope.java`:
```java
package com.yizhaoqi.smartpai.service.document;

import java.util.Set;

public record ActiveRetrievalScope(Set<String> activeBuildIds, Set<String> retiredLegacyMd5s) {
    public ActiveRetrievalScope {
        activeBuildIds = activeBuildIds == null ? Set.of() : Set.copyOf(activeBuildIds);
        retiredLegacyMd5s = retiredLegacyMd5s == null ? Set.of() : Set.copyOf(retiredLegacyMd5s);
    }

    public boolean isEmpty() {
        return activeBuildIds.isEmpty() && retiredLegacyMd5s.isEmpty();
    }
}
```

`ActiveRetrievalScopeService.java`:
```java
package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;

@Service
public class ActiveRetrievalScopeService implements MessageListener {

    public static final String CHANNEL = "paismart:active-scope";

    private final BusinessDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final StringRedisTemplate redisTemplate;
    private final long ttlNanos;

    private volatile Snapshot snapshot;

    public ActiveRetrievalScopeService(BusinessDocumentRepository documentRepository,
                                       DocumentVersionRepository versionRepository,
                                       StringRedisTemplate redisTemplate,
                                       @Value("${document.version.scope-ttl-seconds:5}") long ttlSeconds) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.redisTemplate = redisTemplate;
        this.ttlNanos = Math.max(ttlSeconds, 1L) * 1_000_000_000L;
    }

    public ActiveRetrievalScope current() {
        Snapshot current = snapshot;
        long now = System.nanoTime();
        if (current != null && now - current.loadedAtNanos() < ttlNanos) {
            return current.scope();
        }
        synchronized (this) {
            current = snapshot;
            now = System.nanoTime();
            if (current != null && now - current.loadedAtNanos() < ttlNanos) {
                return current.scope();
            }
            ActiveRetrievalScope loaded = new ActiveRetrievalScope(
                    new LinkedHashSet<>(documentRepository.findActiveBuildIds()),
                    new LinkedHashSet<>(versionRepository.findRetiredLegacyMd5s()));
            snapshot = new Snapshot(loaded, now);
            return loaded;
        }
    }

    public void invalidate() {
        snapshot = null;
    }

    public void invalidateAndBroadcast() {
        invalidate();
        try {
            redisTemplate.convertAndSend(CHANNEL, "refresh");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        invalidate();
    }

    private record Snapshot(ActiveRetrievalScope scope, long loadedAtNanos) {
    }
}
```

`DocumentRetrievalFilter.java`:
```java
package com.yizhaoqi.smartpai.service.document;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.List;

public final class DocumentRetrievalFilter {

    private DocumentRetrievalFilter() {
    }

    public static Query build(ActiveRetrievalScope scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        return Query.of(q -> q.bool(b -> {
            if (!scope.activeBuildIds().isEmpty()) {
                b.should(s -> s.terms(t -> t.field("buildId").terms(tv -> tv.value(values(scope.activeBuildIds())))));
            }
            b.should(s -> s.bool(legacy -> {
                legacy.mustNot(m -> m.exists(e -> e.field("buildId")));
                if (!scope.retiredLegacyMd5s().isEmpty()) {
                    legacy.mustNot(m -> m.terms(t -> t.field("fileMd5").terms(tv -> tv.value(values(scope.retiredLegacyMd5s())))));
                }
                return legacy;
            }));
            return b.minimumShouldMatch("1");
        }));
    }

    public static Query combine(Query permission, Query scopeFilter) {
        if (scopeFilter == null) {
            return permission;
        }
        if (permission == null) {
            return scopeFilter;
        }
        return Query.of(q -> q.bool(b -> b.must(permission).must(scopeFilter)));
    }

    private static List<FieldValue> values(Iterable<String> values) {
        java.util.ArrayList<FieldValue> result = new java.util.ArrayList<>();
        values.forEach(value -> result.add(FieldValue.of(value)));
        return result;
    }
}
```

Register the listener in `RedisConfig.documentStatusListenerContainer`:
```java
container.addMessageListener(activeRetrievalScopeService, new ChannelTopic(ActiveRetrievalScopeService.CHANNEL));
```
Add the service parameter to the bean method.

- [ ] **Step 4: 接入 HybridSearchService**

In `HybridSearchService.search(...)`:
- inject `ActiveRetrievalScopeService activeRetrievalScopeService` via constructor.
- after `permissionFilter = buildPermissionFilter(...)`, add:
```java
Query scopeFilter = DocumentRetrievalFilter.build(activeRetrievalScopeService.current());
Query effectiveFilter = DocumentRetrievalFilter.combine(permissionFilter, scopeFilter);
```
- pass `effectiveFilter` to `hybridSearch` and the fallback `safeTextOnlySearch`.
- The no-permission `search(query, topK)` also builds the scope filter and combines with null.
- Keep `buildPermissionFilter` unchanged for existing tests.
- Update every `new HybridSearchService(...)` construction in tests to pass a mock `ActiveRetrievalScopeService` whose `current()` returns an empty scope; otherwise the existing retrieval tests will not compile.

- [ ] **Step 5: 扩展 SearchResult 与 EsDocument 映射**

In `SearchResult`, add fields:
```java
private String documentId;
private String versionId;
private String buildId;
private Integer versionNo;
```
Update `withParentContext` to copy them. In `HybridSearchService.toSearchResult(EsDocument, ...)`, set them from the ES doc. In `ParentContextExpander`, use `hit.getBuildId()`:
```java
if (hit.getBuildId() != null) {
    window = documentVectorRepository.findByBuildIdAndChunkIdBetweenOrderByChunkIdAsc(
            hit.getBuildId(), hit.getChunkId() - neighbours, hit.getChunkId() + neighbours);
} else {
    window = documentVectorRepository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAsc(
            hit.getFileMd5(), hit.getChunkId() - neighbours, hit.getChunkId() + neighbours);
}
```
The `ParentBlock` key uses `hit.getBuildId() != null ? hit.getBuildId() : hit.getFileMd5()` instead of fileMd5, and `covers` compares the same key plus chunk range. Keep legacy behavior.

- [ ] **Step 6: 写父块/过滤接入测试**

`ParentContextExpanderBuildTest`:
- build hit with buildId → verify repository build query and not the fileMd5 query;
- legacy hit without buildId → verify fileMd5 query;
- two hits in the same build share a parent block; a hit from another build does not reuse it.

`HybridSearchServiceScopeTest`:
- construct a `HybridSearchService` with mocked dependencies and a scope service returning `{"b1"}`; call `buildBm25Request`/`buildKnnRequest` indirectly? The filter is built in `search`, not in the request builders. Test `DocumentRetrievalFilter` directly; for integration, mock ES client and verify the search request filter contains the scope. If too heavy, assert `buildBm25Request` with the combined filter includes it. The plan can use the filter unit test as the gate and rely on the existing HybridSearch tests for regression.

- [ ] **Step 7: 运行测试**

Run:
```bash
mvn -Dtest=ActiveRetrievalScopeServiceTest,DocumentRetrievalFilterTest,ParentContextExpanderBuildTest test
mvn -Dtest=HybridSearchServiceTest,RrfFusionTest test
```
Expected: new tests pass; existing retrieval tests pass (legacy filter empty in those tests; mock scope service returns empty).

- [ ] **Step 8: 检查点**

运行测试并报告；不提交。

---

## Task 3: Build 状态机、切换事务与 READY 恢复

**Files:**
- Create: `DocumentBuildService.java`, `DocumentBuildRecoveryJob.java`
- Modify: `ElasticsearchService.java` (countByBuildId/deleteByBuildId), `ActiveRetrievalScopeService` usage, `DocumentBuildRepository`
- Test: `DocumentBuildServiceTest.java`, `DocumentBuildSwitchTest.java`, `DocumentBuildRecoveryJobTest.java`

**Interfaces:**
- Consumes: Task 1 entities/repos, Task 2 scope service.
- Produces:
  - `DocumentBuild createBuild(String versionId, int buildNo)`; `int nextBuildNo(String versionId)`.
  - `void markProcessing(String buildId)`, `void markFailed(String buildId, String error)`, `void markReady(String buildId, int chunkCount)`, `void markCancelled(String buildId)`.
  - `boolean chunkCountMatches(String buildId, int expectedChunks)`; `BuildActivation activate(String buildId)`.
  - `long ElasticsearchService.countByBuildId(String buildId)`; `void deleteByBuildId(String index, String buildId)`.
  - `DocumentBuildRecoveryJob` scheduled READY retry.

- [ ] **Step 1: 写状态机/切换失败测试**

`DocumentBuildServiceTest`:
- create a document/version/build; markProcessing; markReady; activate:
  - first build becomes ACTIVE; document active pointers set; no old build;
  - a second build on the same version becomes ACTIVE; first becomes SUPERSEDED; `version_no` unchanged;
  - a new version build becomes ACTIVE; old version's build SUPERSEDED; legacy version `legacy_retired=true`;
  - deleted document → CANCELLED, pointers unchanged;
  - activate a non-READY build → no change/exception;
  - activate twice is idempotent (second returns ACTIVE without changing old build).

- [ ] **Step 2: 写 count mismatch 失败测试**

`DocumentBuildSwitchTest`:
- mock `ElasticsearchService.countByBuildId` returns 2 when expected 3 → `chunkCountMatches` returns false; the caller marks FAILED; old active unchanged;
- returns 3 → READY + ACTIVE;
- ES count error → FAILED, old active unchanged.

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -Dtest=DocumentBuildServiceTest,DocumentBuildSwitchTest test`
Expected: compile failure.

- [ ] **Step 4: 实现 ElasticsearchService 方法**

Add:
```java
public long countByBuildId(String buildId) {
    try {
        CountResponse response = esClient.count(c -> c
                .index(KnowledgeIndexNames.ALIAS)
                .query(q -> q.term(t -> t.field("buildId").value(buildId))));
        return response.count();
    } catch (Exception e) {
        throw new RuntimeException("统计构建分块数失败", e);
    }
}

public void deleteByBuildId(String index, String buildId) {
    try {
        esClient.deleteByQuery(d -> d
                .index(index)
                .allowNoIndices(true)
                .ignoreUnavailable(true)
                .query(q -> q.term(t -> t.field("buildId").value(buildId))));
    } catch (Exception e) {
        throw new RuntimeException("删除构建数据失败", e);
    }
}
```
Use existing imports; follow `deleteByFileMd5` style.

- [ ] **Step 5: 实现 DocumentBuildService**

Key code:
```java
@Service
public class DocumentBuildService {

    private final DocumentBuildRepository buildRepository;
    private final DocumentVersionRepository versionRepository;
    private final BusinessDocumentRepository documentRepository;
    private final ElasticsearchService elasticsearchService;
    private final ActiveRetrievalScopeService activeScopeService;

    // constructor...

    @Transactional
    public DocumentBuild createBuild(String versionId, int buildNo) {
        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + versionId));
        DocumentBuild build = new DocumentBuild();
        build.setBuildId(UUID.randomUUID().toString());
        build.setVersionId(version.getVersionId());
        build.setBuildNo(buildNo);
        build.setStatus(DocumentBuildStatus.PENDING);
        build.setCreatedAt(LocalDateTime.now());
        return buildRepository.save(build);
    }

    public int nextBuildNo(String versionId) {
        return buildRepository.findByVersionIdOrderByBuildNoDesc(versionId).stream()
                .findFirst().map(b -> b.getBuildNo() + 1).orElse(1);
    }

    @Transactional
    public void markProcessing(String buildId) {
        DocumentBuild build = require(buildId);
        build.setStatus(DocumentBuildStatus.PROCESSING);
        build.setStartedAt(LocalDateTime.now());
        buildRepository.save(build);
    }

    @Transactional
    public void markReady(String buildId, int chunkCount) {
        DocumentBuild build = require(buildId);
        build.setStatus(DocumentBuildStatus.READY);
        build.setChunkCount(chunkCount);
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
    }

    @Transactional
    public void markFailed(String buildId, String error) {
        DocumentBuild build = require(buildId);
        build.setStatus(DocumentBuildStatus.FAILED);
        build.setErrorMessage(truncate(error));
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
    }

    @Transactional
    public void markCancelled(String buildId) {
        DocumentBuild build = require(buildId);
        build.setStatus(DocumentBuildStatus.CANCELLED);
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
    }

    public boolean chunkCountMatches(String buildId, int expectedChunks) {
        long actual = elasticsearchService.countByBuildId(buildId);
        return expectedChunks > 0 && actual == expectedChunks;
    }

    @Transactional
    public BuildActivation activate(String buildId) {
        DocumentBuild build = buildRepository.findById(buildId)
                .orElseThrow(() -> new IllegalArgumentException("构建不存在: " + buildId));
        if (build.getStatus() == DocumentBuildStatus.ACTIVE) {
            return BuildActivation.ACTIVE;
        }
        if (build.getStatus() != DocumentBuildStatus.READY) {
            return BuildActivation.NOT_READY;
        }
        DocumentVersion version = versionRepository.findById(build.getVersionId())
                .orElseThrow(() -> new IllegalStateException("构建对应版本不存在: " + build.getVersionId()));
        BusinessDocument document = documentRepository.findForUpdate(version.getDocumentId())
                .orElseThrow(() -> new IllegalStateException("业务文档不存在: " + version.getDocumentId()));
        if (document.getDeletedAt() != null) {
            build.setStatus(DocumentBuildStatus.CANCELLED);
            build.setCompletedAt(LocalDateTime.now());
            buildRepository.save(build);
            return BuildActivation.CANCELLED;
        }
        String previousBuildId = document.getActiveBuildId();
        if (previousBuildId != null && !previousBuildId.equals(buildId)) {
            buildRepository.findById(previousBuildId).ifPresent(previous -> {
                previous.setStatus(DocumentBuildStatus.SUPERSEDED);
                buildRepository.save(previous);
            });
        }
        build.setStatus(DocumentBuildStatus.ACTIVE);
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
        document.setActiveVersionId(version.getVersionId());
        document.setActiveBuildId(buildId);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
        for (DocumentVersion legacy : versionRepository.findByDocumentIdAndLegacyRepresentationTrue(document.getDocumentId())) {
            legacy.setLegacyRetired(true);
            versionRepository.save(legacy);
        }
        invalidateAfterCommit();
        return BuildActivation.ACTIVE;
    }

    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    activeScopeService.invalidateAndBroadcast();
                }
            });
        } else {
            activeScopeService.invalidateAndBroadcast();
        }
    }

    public enum BuildActivation { ACTIVE, NOT_READY, CANCELLED }
}
```
- `createBuild` takes buildNo; the caller computes `nextBuildNo`. To avoid races, the unique `(version_id, build_no)` protects; a DataIntegrityViolation can retry. The VersionService will lock the document/version before creating. The plan can add `nextBuildNo` in the same transaction.
- `markReady` sets `completedAt`; the recovery job scans READY with `completedAt < threshold`.
- `activate` uses a `@Transactional`; the `verifyAndActivate` calls `markReady`/`activate` within the same bean, which bypasses the proxy! This is a problem: `verifyAndActivate` is a public method in the same class; calling `markReady`/`activate` directly won't start new transactions. The `@Transactional` on `activate` won't apply if called internally. The plan should split the orchestration to a separate component or use `TransactionTemplate`. Simplest: make `verifyAndActivate` not `@Transactional` and call it from the consumer, but the internal calls still bypass. Use `TransactionTemplate` or move `activate` to a separate bean. Better: have the consumer call `buildService.markReady(buildId, actual)` then `buildService.activate(buildId)`; both are separate proxied calls. The count verification can be a method `verifyChunkCount(buildId, expected)` returning boolean and marking FAILED; no internal activation. The plan's `verifyAndActivate` should be a **consumer-level orchestration**:
  ```java
  public boolean verifyChunkCount(String buildId, int expected) { ... markFailed ... return true/false; }
  ```
  The consumer: `if (buildService.verifyChunkCount(...)) { buildService.markReady(...); buildService.activate(...); }`. The recovery job retries READY. This avoids self-invocation. Update the plan code accordingly. The spec's "completeBuild" can be realized by the consumer.
  - The `markReady` and `activate` are separate transactions; if activation fails, READY remains. Good.
  - The count verification calls ES and marks FAILED; no transaction issue (markFailed is a proxied call? Within the same class, `verifyChunkCount` calling `markFailed` bypasses; make `verifyChunkCount` not call `markFailed` internally; return a result and let the consumer call `markFailed`. Or use `TransactionTemplate`. Simpler: `verifyChunkCount` just returns actual/expected; the consumer handles. The plan can define:
    ```java
    public boolean chunkCountMatches(String buildId, int expected) { return expected>0 && es.countByBuildId(buildId)==expected; }
    ```
    The consumer: if false → markFailed; else markReady; activate. This is clean. The tests.
  - The plan's code must reflect this. We'll write the final plan accordingly.
- The `DocumentBuildRecoveryJob`:
  ```java
  @Scheduled(fixedDelayString = "${document.version.ready-recovery-ms:60000}")
  public void retryReady() {
    for (DocumentBuild b : buildRepo.findByStatusAndCompletedAtBefore(READY, now.minusSeconds(60))) {
       try { buildService.activate(b.getBuildId()); } catch (Exception e) { log... }
    }
  }
  ```
  - The job also can scan PROCESSING older than a threshold; optional. The plan can include READY only; the consumer failure marks FAILED.

- [ ] **Step 6: 写恢复测试**

`DocumentBuildRecoveryJobTest`: mock repo returns a READY build; verify `buildService.activate` called; a CANCELLED result doesn't throw.

- [ ] **Step 7: 运行测试**

Run:
```bash
mvn -Dtest=DocumentBuildServiceTest,DocumentBuildSwitchTest,DocumentBuildRecoveryJobTest test
mvn -q -DskipTests compile
```
Expected: pass/compile.

- [ ] **Step 8: 检查点**

---

## Task 4: 入库流水线按 build 写入、核对与切换

**Files:**
- Modify: `FileProcessingTask.java`, `FileProcessingOutboxService.java`, `FileProcessingConsumer.java`, `ParseService.java`, `VectorizationService.java`, `DocumentService.java`
- Test: `FileProcessingConsumerBuildTest.java`, `VectorizationServiceBuildTest.java`, `ParseServiceBuildTest.java`, `FileProcessingOutboxBuildKeyTest.java`
- Update existing tests: `FileProcessingConsumerIdempotencyTest`, `VectorizationServiceTest`, `FileProcessingOutboxServiceTest`.

**Interfaces:**
- Consumes: Task 3 build service; Task 2 filter.
- Produces:
  - `FileProcessingTask` fields `documentId/versionId/buildId/buildKind/versionNo`.
  - `DocumentBuildContext`.
  - `ParseService.parseAndSave(..., DocumentBuildContext)`; `VectorizationService.vectorizeWithUsage(..., DocumentBuildContext, Runnable)`.
  - `DocumentService.clearBuildData(buildId)`.
  - Consumer: clear by build → parse/vectorize with build → lease complete → count check → ready → activate.

- [ ] **Step 1: 写任务/outbox 失败测试**

`FileProcessingOutboxBuildKeyTest`:
- a task with buildId `b1` is sent with Kafka key `b1`; a legacy task without buildId uses fileMd5. Mock `KafkaTemplate`/`executeInTransaction` or inspect the outbox dispatch logic. Follow the existing outbox test style; if the Kafka template is hard to mock, test a package-private `messageKey(task)` helper.

- [ ] **Step 2: 写 vectorization/parse 失败测试**

`VectorizationServiceBuildTest`:
- mock repository `findByBuildIdOrderByChunkIdAsc` returns 2 chunks; the generated ES docs have `_id = buildId_chunkId`, `buildId/documentId/versionId/versionNo`; `bulkIndex` receives them;
- legacy context null uses fileMd5 and old `_id`.

`ParseServiceBuildTest` (or extend existing ParseServiceTest):
- parse with a build context writes `document_vectors.build_id`; legacy parse leaves it null.

- [ ] **Step 3: 写 consumer 失败测试**

`FileProcessingConsumerBuildTest`:
- task with buildId; vectorization result 2; ES count 2; verify clearBuildData(buildId), parse/vectorize with context, lease.complete, build markReady + activate; no clearIndexedData(fileMd5);
- count mismatch → build FAILED, no activate;
- vectorization throws → build FAILED, lease.fail.

- [ ] **Step 4: 运行测试确认失败**

Run:
```bash
mvn -Dtest=FileProcessingOutboxBuildKeyTest,VectorizationServiceBuildTest,ParseServiceBuildTest,FileProcessingConsumerBuildTest test
```
Expected: compile failure.

- [ ] **Step 5: 实现 task/context**

Add fields to `FileProcessingTask` with getters/setters (Lombok @Data):
```java
public static final String BUILD_KIND_NEW_DOCUMENT = "NEW_DOCUMENT";
public static final String BUILD_KIND_NEW_VERSION = "NEW_VERSION";
public static final String BUILD_KIND_REINDEX = "REINDEX";
public static final String BUILD_KIND_LEGACY_PROMOTION = "LEGACY_PROMOTION";

private String documentId;
private String versionId;
private String buildId;
private String buildKind;
private Integer versionNo;
private Integer buildNo;
```
Add an explicit full constructor (use this order in Task 5 and the consumer):
```java
public FileProcessingTask(String fileMd5, String filePath, String fileName, String userId,
                          String orgTag, boolean isPublic, String taskType, String requesterId,
                          String documentId, String versionId, String buildId, String buildKind,
                          Integer versionNo, Integer buildNo) {
    this.fileMd5 = fileMd5;
    this.filePath = filePath;
    this.fileName = fileName;
    this.userId = userId;
    this.orgTag = orgTag;
    this.isPublic = isPublic;
    this.taskType = taskType;
    this.requesterId = requesterId;
    this.documentId = documentId;
    this.versionId = versionId;
    this.buildId = buildId;
    this.buildKind = buildKind;
    this.versionNo = versionNo;
    this.buildNo = buildNo;
}
```
Keep the explicit 8-arg legacy constructor delegating with nulls. Remove `@AllArgsConstructor` if it conflicts with the explicit constructors, or keep it and use the 14-arg constructor; the plan uses the explicit full constructor.
- `FileProcessingOutboxService` Kafka key:
```java
String key = task.getBuildId() == null || task.getBuildId().isBlank()
        ? task.getFileMd5() : task.getBuildId();
operations.send(topic, key, task)
```
Add a package-private `String messageKey(FileProcessingTask task)` for tests.

- [ ] **Step 6: 实现 parse/vectorization 上下文**

`ParseService`:
- add overload with `DocumentBuildContext context`; the existing method delegates with null.
- The context is passed through the parsing methods to `saveChildChunks`; set `vector.setBuildId(context == null ? null : context.buildId())`.
- The methods that call `saveChildChunks` (PDF/text paths) need the context parameter; follow the existing signatures.

`VectorizationService`:
- add overload:
```java
public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag,
                                                   boolean isPublic, String requesterId,
                                                   DocumentBuildContext context, Runnable onBatchIndexed)
```
- The existing overloads delegate with `context=null`.
- In the loop:
```java
Slice<DocumentVector> slice = context != null && context.buildId() != null
    ? documentVectorRepository.findByBuildIdOrderByChunkIdAsc(context.buildId(), page)
    : documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5, page);
```
- `embedAndIndexBatch` add context; id:
```java
String id = context != null && context.buildId() != null
    ? context.buildId() + "_" + chunk.getChunkId()
    : fileMd5 + "_" + chunk.getChunkId();
EsDocument doc = new EsDocument(id, ...);
if (context != null) {
    doc.setDocumentId(context.documentId());
    doc.setVersionId(context.versionId());
    doc.setBuildId(context.buildId());
    doc.setVersionNo(context.versionNo());
    doc.setBuildNo(buildNo); // pass buildNo in context or look up
}
```
- The spec's `DocumentBuildContext` currently has versionNo but not buildNo. Add `buildNo` to the context:
```java
public record DocumentBuildContext(String documentId, String versionId, String buildId,
                                   Integer versionNo, Integer buildNo) {}
```
Update Task 1's definition. The ES `buildNo` field. The plan must be consistent; update the context.
- The `prepareChunkContexts`/fileName unchanged.

- [ ] **Step 7: 实现 clearBuildData 与 consumer**

`DocumentService`:
```java
public void clearBuildData(String buildId) {
    elasticsearchService.deleteByBuildId(KnowledgeIndexNames.ALIAS, buildId);
    documentVectorRepository.deleteByBuildId(buildId);
}
```
Keep `clearIndexedData(fileMd5)` for legacy.

`FileProcessingConsumer` constructor add `DocumentBuildService buildService`.
In `processTask`:
```java
DocumentBuildContext context = task.getBuildId() == null ? null
    : new DocumentBuildContext(task.getDocumentId(), task.getVersionId(), task.getBuildId(),
                               task.getVersionNo(), task.getBuildNo());
...
if (task.getBuildId() != null) {
    buildService.markProcessing(task.getBuildId());
    documentService.clearBuildData(task.getBuildId());
} else {
    documentService.clearIndexedData(task.getFileMd5());
}
parseService.parseAndSave(task.getFileMd5(), fileStream, task.getUserId(),
        task.getOrgTag(), task.isPublic(), context);
...
vectorizeWithUsage(..., context, heartbeat)
if (leaseService.complete(lease, result)) {
    if (task.getBuildId() != null) {
        if (buildService.chunkCountMatches(task.getBuildId(), result.actualChunkCount())) {
            buildService.markReady(task.getBuildId(), result.actualChunkCount());
            buildService.activate(task.getBuildId());
        } else {
            buildService.markFailed(task.getBuildId(), "分块数核对失败");
        }
    }
    publishVectorized(task.getFileMd5()); // Task 6 再把 documentId/versionId/buildId 加进事件，供冲突检测排除同文档旧版本
}
```
- On catch `Exception`: if buildId != null, `buildService.markFailed(buildId, e.getMessage())`; then lease.fail. On `VectorizationLeaseLostException`: do not mark failed; the build is still PROCESSING; the next consumer will reprocess; log. The file lease recovery handles.
- `publishVectorized` add context to the event (Task 6 conflict).
- The `lease.complete` currently sets the file upload COMPLETED; the build activation follows. If activation fails, the build is READY and the recovery job retries.
- The `FileProcessingTask` `buildNo` field; add.

- [ ] **Step 8: 运行测试与回归**

Run:
```bash
mvn -Dtest=FileProcessingOutboxBuildKeyTest,VectorizationServiceBuildTest,ParseServiceBuildTest,FileProcessingConsumerBuildTest test
mvn -Dtest=FileProcessingConsumerIdempotencyTest,VectorizationServiceTest,FileProcessingOutboxServiceTest,FileProcessingRecoveryJobTest test
mvn -q -DskipTests compile
```
Expected: new pass; existing updated pass.

- [ ] **Step 9: 检查点**

---

## Task 5: 首次上传、新版本、legacy 懒迁移、reindex/retry

**Files:**
- Create: `DocumentVersionService.java`
- Modify: `UploadController.java` (MergeRequest + merge flow), `DocumentService.java` (reindex/retry), `DocumentController.java` (reindex/retry endpoints)
- Test: `DocumentVersionServiceTest.java`, `UploadNewVersionTest.java`, `LegacyPromotionTest.java`

**Interfaces:**
- Consumes: Task 1-4 entities, build service, outbox, upload service.
- Produces:
  - `DocumentVersionService.createFirstDocument(FileUpload, userId)`.
  - `DocumentVersionService.createNewVersion(String documentId, FileUpload, userId)`.
  - `DocumentVersionService.promoteLegacy(FileUpload, userId)`.
  - `DocumentVersionService.createBuildForActiveVersion(String documentId, String requesterId, String kind)`.
  - `VersionBuild` record `(documentId, versionId, buildId, versionNo, buildNo, orgTag, isPublic, fileMd5, fileName)`.

- [ ] **Step 1: 写版本服务失败测试**

`DocumentVersionServiceTest`:
- first document: creates doc/version1/build1, active null; permission from file.
- new version: version_no+1, permission inherited from active version, new md5; duplicate md5 → 409-like exception; owner mismatch → exception; deleted doc → exception.
- legacy promotion: creates version1 `legacy_representation=true`, active_version_id set, active_build_id null; shared md5 (fileUploadRepository.countByFileMd5 > 1) → exception; no ES writes.
- reindex build: active version gets build_no+1; version_no unchanged.
- concurrent version numbers: unique key + document lock; test two sequential calls.

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=DocumentVersionServiceTest test`
Expected: compile failure.

- [ ] **Step 3: 实现 DocumentVersionService**

Use UUIDs, `LocalDateTime.now()`, `BusinessDocumentRepository.findForUpdate`, `DocumentVersionRepository`, `DocumentBuildRepository`, `FileUploadRepository`.
Key methods:
```java
@Transactional
public VersionBuild createFirstDocument(FileUpload fileUpload, String userId) {
    BusinessDocument document = new BusinessDocument();
    document.setDocumentId(UUID.randomUUID().toString());
    document.setOwnerUserId(userId);
    document.setTitle(fileUpload.getFileName());
    document.setCreatedAt(now);
    document.setUpdatedAt(now);
    documentRepository.save(document);

    DocumentVersion version = new DocumentVersion();
    version.setVersionId(UUID.randomUUID().toString());
    version.setDocumentId(document.getDocumentId());
    version.setVersionNo(1);
    version.setFileMd5(fileUpload.getFileMd5());
    version.setFileUploadId(fileUpload.getId());
    version.setFileName(fileUpload.getFileName());
    version.setOrgTag(fileUpload.getOrgTag());
    version.setPublic(fileUpload.isPublic());
    version.setCreatedAt(now);
    versionRepository.save(version);

    DocumentBuild build = buildService.createBuild(version.getVersionId(), 1);
    return new VersionBuild(document.getDocumentId(), version.getVersionId(), build.getBuildId(),
            version.getVersionNo(), build.getBuildNo(), version.getOrgTag(), version.isPublic(),
            version.getFileMd5(), version.getFileName());
}

public record VersionBuild(String documentId, String versionId, String buildId,
                           Integer versionNo, Integer buildNo, String orgTag, boolean isPublic,
                           String fileMd5, String fileName) {
}
```
- `createNewVersion`:
  - `documentRepository.findForUpdate(documentId)`, owner check, deleted check.
  - `versionRepository.findByDocumentIdAndFileMd5(documentId, fileUpload.getFileMd5())` present → throw `CustomException("内容与现有版本相同，未创建新版本", CONFLICT)`.
  - active version = find by `document.getActiveVersionId()`; if null, use latest version or fail.
  - `nextVersionNo = versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId).get(0).getVersionNo()+1`.
  - permission from active version; version/build; buildNo = buildService.nextBuildNo(versionId).
  - return.
- `promoteLegacy`:
  - `fileUploadRepository.countByFileMd5(fileMd5) > 1` → throw conflict.
  - create document (title=fileName), version1 legacy_representation=true, active_version_id=version1, active_build_id=null; no build.
  - return `VersionBuild` with buildId null.
  - If the document is being promoted and then a new version is created, the new version service sees active version legacy and version_no 2.
- `createBuildForActiveVersion`:
  - doc for update; active version; new build no; build; return `VersionBuild` with the active version's file/perms.
- The `VersionBuild` record in the service.

- [ ] **Step 4: 接入 UploadController**

Change `MergeRequest`:
```java
public record MergeRequest(String fileMd5, String fileName, String documentId, String legacyFileMd5) {}
```
At merge time after `fileUpload` and estimate:
```java
VersionBuild versionBuild;
if (request.documentId() != null && !request.documentId().isBlank()) {
    versionBuild = documentVersionService.createNewVersion(request.documentId(), fileUpload, userId);
} else if (request.legacyFileMd5() != null && !request.legacyFileMd5().isBlank()) {
    FileUpload legacy = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(request.legacyFileMd5(), userId)
            .orElseThrow(() -> new CustomException("旧文档不存在", HttpStatus.NOT_FOUND));
    VersionBuild legacyVersion = documentVersionService.promoteLegacy(legacy, userId);
    versionBuild = documentVersionService.createNewVersion(legacyVersion.documentId(), fileUpload, userId);
} else {
    versionBuild = documentVersionService.createFirstDocument(fileUpload, userId);
}
```
`promoteLegacy` returns a `VersionBuild` with `buildId` null (the legacy representation has no build); the upload flow uses its `documentId` to create the new content version.
- The task:
```java
FileProcessingTask task = new FileProcessingTask(
        request.fileMd5(), objectUrl, request.fileName(), fileUpload.getUserId(),
        versionBuild.orgTag(), versionBuild.isPublic(), FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
        userId, versionBuild.documentId(), versionBuild.versionId(), versionBuild.buildId(),
        FILE_KIND, versionBuild.versionNo(), versionBuild.buildNo());
```
- The task's fileMd5 is the new file; the version's permission is inherited. The fileUpload's org/public may differ; the task uses the version.
- The outbox enqueue; the response includes documentId/versionId/buildId/versionNo.
- The merge idempotency: if `fileUpload.status == COMPLETED` and a version already exists for this md5/document, return the existing version build; add a lookup `versionRepository.findByFileUploadId(fileUpload.getId())` and if present, return it. The current early return at line 296 should return the existing version info, not just merged. The plan can add a helper `findOrCreateFirst/ExistingVersion`.
- The legacy `legacyFileMd5` must differ from the new file md5; if same, reject (no content change).

- [ ] **Step 5: 接入 reindex/retry**

`DocumentService.enqueueAsyncVectorizationRetry`:
- find fileUpload; find version by `fileUploadId` (for the active/file version); if no version, promote legacy (if unique) or create first? A legacy retry should create a first document/version/build (promotion) and process. Use `DocumentVersionService`.
- create a new build for the active version; task with the build context; outbox; return.
- `DocumentController.retryVectorization` response can include buildId.
- `DocumentController.reindex` (synchronous) change to find the file/active version, create a build, outbox, return 202 with buildId. The existing `DocumentService.reindexDocument` can remain unused or be deleted; the plan says remove its call from the controller and add a new async method. The old tests for reindex update.
- The `FileProcessingTask` for reindex has the same fileMd5; buildId; version; permission from the version.
- The outbox key buildId.

- [ ] **Step 6: 运行测试**

Run:
```bash
mvn -Dtest=DocumentVersionServiceTest,UploadNewVersionTest,LegacyPromotionTest test
mvn -Dtest=FileProcessingOutboxBuildKeyTest,FileProcessingConsumerBuildTest test
mvn -q -DskipTests compile
```
Expected: pass.

- [ ] **Step 7: 检查点**

---

## Task 6: 文档列表、引用版本、冲突排除、统计、迁移回归

**Files:**
- Create: `DocumentLibraryService.java`, `DocumentLibraryItem.java` (or record in the service)
- Modify: `DocumentController.java` (library/reference-detail/delete), `ChatHandler.java` (ReferenceInfo version fields), `AgentToolRegistry.java` (stats), `ConflictCandidateFinder.java`/`DocumentVectorizedEvent.java`/`DocumentConflictDetectionService.java` (self-conflict + active filter), `DocumentStatusNotifier.java` (build event)
- Test: `DocumentLibraryServiceTest.java`, `ReferenceVersionTest.java`, `ConflictSelfVersionTest.java`, `DocumentStatsScopeTest.java`
- Modify: `docs/databases/ddl.sql`/migration if not done; `LegacySchemaMigration` for the unique key
- Test: existing 20 WP-1 tests + full retrieval/consumer tests.

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces:
  - `GET /api/v1/documents/library` with `DocumentLibraryItem`.
  - Reference detail version fields.
  - Conflict candidate exclusion of the same `documentId`.
  - Stats active+legacy.
  - Migration startup guard.

- [ ] **Step 1: 写 library/引用/冲突失败测试**

- `DocumentLibraryServiceTest`: versioned doc row uses active version permission; legacy row appears; old versions not rows; latest failed attempt status; deleted doc excluded.
- `ReferenceVersionTest`: a SearchResult with version fields produces ReferenceInfo with versionNo/buildId; reference detail response includes them; legacy nulls.
- `ConflictSelfVersionTest`: candidate finder excludes chunks with the same documentId; active filter includes only active builds; legacy included.
- `DocumentStatsScopeTest`: count excludes inactive builds.

- [ ] **Step 2: 实现 library**

`DocumentLibraryItem` record:
```java
public record DocumentLibraryItem(
        String documentId, String title, boolean legacy, String fileMd5, String fileName,
        Integer versionNo, String versionId, String buildId, String buildStatus,
        String latestAttemptStatus, Integer latestAttemptVersionNo, String errorMessage,
        String orgTag, boolean isPublic, LocalDateTime createdAt, LocalDateTime updatedAt) {}
```
`DocumentLibraryService`:
- load non-deleted documents; for each, find active version/build; filter by owner/public/orgTag (effective tags from `OrgTagCacheService` or the existing `getUserEffectiveOrgTags`; inject the same service used by DocumentController).
- load accessible legacy file_upload rows via `FileUploadRepository.findAccessibleFilesWithTags`; exclude those with a `DocumentVersion` by fileUploadId (query).
- latest attempt: `documentBuildRepository.findFirstByVersionDocumentIdOrderByVersionVersionNoDescBuildNoDesc`.
- Return a list sorted by updatedAt desc.
- `DocumentController` add `@GetMapping("/library")`; return the list. Reuse the existing auth context/permission helpers.

- [ ] **Step 3: 引用版本与删除文档**

- Add `documentId/versionId/versionNo/buildId` to `ChatHandler.ReferenceInfo` and `buildReferenceInfo` from `SearchResult`.
- Add to `DocumentController.getReferenceDetail` response.
- `ChatHandler.getReferenceDetail` returns the new fields; existing tests update.
- Add `DocumentLibraryService.deleteDocument(documentId, userId)`:
  - owner/admin check; set `deletedAt`; save;
  - `activeScopeService.invalidateAndBroadcast()`;
  - best-effort for each version/build: `elasticsearchService.deleteByBuildId(ALIAS, buildId)`, `documentVectorRepository.deleteByBuildId(buildId)`;
  - legacy fileMd5 cleanup keeps the existing `DocumentService.deleteDocument(fileMd5)` path.
- Add `@DeleteMapping("/{documentId}")` in `DocumentController` calling the service; keep the legacy `DELETE /{fileMd5}` route. Return 200 even if external cleanup fails, but log; full retry is WP-3.
- The frontend is Plan B; backend only.

- [ ] **Step 4: 冲突与统计**

- `DocumentVectorizedEvent` add `documentId/versionId/buildId`; the consumer `publishVectorized` passes them.
- `ConflictCandidateFinder` query:
  - load active scope; combine the version filter (Task 2) with the permission filter.
  - exclude candidates whose `documentId` equals the current document: add a `must_not term(documentId, currentDocumentId)` if the current documentId is non-null. The finder needs the current documentId; the detection listener gets it from the event.
  - For legacy docs, documentId null; no exclusion.
- `AgentToolRegistry.executeKnowledgeStats`: count active/legacy via an ES count with the scope filter, or use `DocumentLibraryService` counts. Keep it simple: use a count query with the scope filter; if the scope empty, use the existing stats. Add a method `countRetrievableChunks`.
- `DocumentStatusNotifier`: add `notifyBuildChanged(buildId)` that loads the build/version/document and publishes a payload with `documentId/versionId/versionNo/buildId/status`; call it from `DocumentBuildService` after markReady/activate/fail? The build service is in `service.document`; injecting the notifier could create a cycle (notifier depends on FileUploadRepository only; no cycle). Add calls in `markReady`/`markFailed`/`activate` after commit. The existing legacy status notifier remains.
- `RedisConfig` already registers the notifier and active scope listener.

- [ ] **Step 5: 迁移与回归**

- Add a `LegacySchemaMigration` MySQL guard for the `document_vectors` unique key (drop old/add new if needed) and new tables? `ddl-auto=update` creates tables/columns; the unique key must be migrated. Add a method similar to the existing `LegacySchemaMigration` pattern, with an `information_schema` check. Test? The existing migration test may be H2; the guard should skip non-MySQL.
- Run the full targeted suite:
```bash
mvn -Dtest=DocumentVersionModelTest,EsDocumentJsonTest,ActiveRetrievalScopeServiceTest,DocumentRetrievalFilterTest,ParentContextExpanderBuildTest,DocumentBuildServiceTest,DocumentBuildSwitchTest,DocumentBuildRecoveryJobTest,FileProcessingOutboxBuildKeyTest,VectorizationServiceBuildTest,ParseServiceBuildTest,FileProcessingConsumerBuildTest,DocumentVersionServiceTest,UploadNewVersionTest,LegacyPromotionTest,DocumentLibraryServiceTest,ReferenceVersionTest,ConflictSelfVersionTest,DocumentStatsScopeTest test
mvn -Dtest=QuestionPlanTest,AgentToolRegistryPlanClarificationTest,ChatHandlerPlanCoverageTest,ChatHandlerClarificationTest,ChatHandlerPlanValidationTest,LlmProviderRouterPromptTest,ChatHandlerDeadlineTest,ChatHandlerReferenceNumberingTest,HybridSearchServiceTest,RrfFusionTest test
mvn -q -DskipTests compile
```
Expected: all pass; compile exit 0.
- MySQL migration verification: if a temporary MySQL is available, run the migration; if not, report the unverified migration as a gap. Do not start MySQL/Docker without the user's permission.

- [ ] **Step 6: 检查点与报告**

Report test counts, migration status, and known gaps (real ES/MySQL not run; frontend Plan B; WP-1 browser acceptance pending).

---

## Self-Review

**Spec coverage**
- 4.1-4.3 tables/entities: Task 1.
- 4.4 document_vectors/ES fields: Task 1.
- 4.5 build state machine: Task 3.
- 4.6 active scope/filter: Task 2.
- 5.1 first upload: Task 5.
- 5.2 new version: Task 5.
- 5.3 reindex/retry: Task 5.
- 5.4 count check/switch/legacy retirement: Task 3 + Task 4.
- 5.5 failure/recovery/idempotency: Task 3/4.
- 5.6 legacy lazy promotion: Task 5.
- 6.1 scope cache: Task 2.
- 6.2 filter/conflict/stats: Task 2/6.
- 6.3 parent expander: Task 2.
- 6.4 references: Task 6.
- 7.1 library: Task 6.
- 7.2 upload new version: Task 5.
- 7.3 reindex/retry/delete: Task 5/6 (delete minimal; the exact document delete endpoint is Task 6).
- 7.4 status events: Task 6.
- 7.5 minimal UI: Plan B (explicitly out of Plan A).
- 8.1 MySQL migration: Task 1/6.
- 8.2 ES mapping: Task 1.
- 8.3 deployment order/rollback: documented in spec; Plan A reports.
- 9 tests: tasks.
- 10 risks: documented.
- 11/12/13: out of scope/handled.

**Placeholder scan**
- No TBD/TODO. Existing-code modifications are specified with method names/signatures; new classes have code. The delete endpoint details are minimal (Task 6); add a concrete method in Task 6 if needed.

**Type consistency**
- `DocumentBuildContext(documentId, versionId, buildId, versionNo, buildNo)`.
- `DocumentBuildStatus` enum.
- `VersionBuild` record fields used by UploadController; the plan must define the exact record in Task 5.
- `ActiveRetrievalScope(Set<String>, Set<String>)`.
- `DocumentRetrievalFilter.build/combine`.
- `DocumentBuildService.chunkCountMatches/markReady/activate`.
- `FileProcessingTask` new fields.
- `SearchResult` new fields.
- `DocumentLibraryItem` record.
- `ReferenceInfo` new fields.

**Known gaps**
- The exact `DocumentController` delete endpoint and `ReferenceInfo` modifications are described; the implementer must follow existing patterns.
- The `ConflictCandidateFinder` signature changes are described; Task 6 must read the current code.
- The `LegacySchemaMigration` guard must be written against the existing MySQL-only pattern.
- Real ES/MySQL integration is not run; tests use mocks/H2. The plan's final report must state this.
- Plan B (frontend) is separate.
