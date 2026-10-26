package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.ChunkInfo;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.KnowledgeAuditService;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 删除的版本生命周期：停用业务文档、取消未完成构建、提交后失效检索范围，再按构建/版本做尽力而为的清理。
 * H2 上真实走仓储与构建状态机，ES / 检索范围 / MinIO / 旧清理服务用 mock。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DocumentBuildService.class)
class DocumentDeletionServiceTest {

    @Autowired
    private DocumentBuildService buildService;

    @Autowired
    private BusinessDocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Autowired
    private DocumentBuildRepository buildRepository;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private ElasticsearchService elasticsearchService;

    @MockitoBean
    private ActiveRetrievalScopeService activeScopeService;

    @MockitoBean
    private KnowledgeAuditService auditService;

    private final DocumentService documentService = mock(DocumentService.class);
    private final MinioClient minioClient = mock(MinioClient.class);

    private DocumentDeletionService deletionService;

    @BeforeEach
    void setUp() {
        deletionService = newDeletionService(buildService);
    }

    @Test
    void versionedDeleteDisablesDocumentAndCancelsOnlyUnfinishedBuilds() {
        FileUpload upload = saveUpload("md5-v1", "owner-1", "v1.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);

        DocumentBuild superseded = readyBuild(version);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(superseded.getBuildId()));
        DocumentBuild active = readyBuild(version);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(active.getBuildId()));
        DocumentBuild pending = pendingBuild(version);
        DocumentBuild processing = processingBuild(version);
        DocumentBuild ready = readyBuild(version);

        deletionService.deleteByFileMd5("md5-v1", "owner-1");

        BusinessDocument deleted = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertNotNull(deleted.getDeletedAt(), "业务文档必须被软删除");

        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(superseded.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(active.getBuildId()).getStatus(), "ACTIVE 不可取消");
        assertCancelled(pending.getBuildId());
        assertCancelled(processing.getBuildId());
        assertCancelled(ready.getBuildId());

        verify(activeScopeService).invalidateAndBroadcast();
        verify(auditService).record("owner-1", "DOCUMENT", document.getDocumentId(), "DELETE", "PUBLISHED", "DELETED");
    }

    @Test
    void deletedDocumentIsExcludedFromActiveRetrievalScope() {
        FileUpload upload = saveUpload("md5-scope", "owner-1", "scope.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild active = readyBuild(version);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(active.getBuildId()));

        ActiveRetrievalScopeService before = new ActiveRetrievalScopeService(
                documentRepository, versionRepository, mock(StringRedisTemplate.class), 60);
        assertTrue(before.current().activeBuildIds().contains(active.getBuildId()));

        deletionService.deleteByFileMd5("md5-scope", "owner-1");

        ActiveRetrievalScopeService after = new ActiveRetrievalScopeService(
                documentRepository, versionRepository, mock(StringRedisTemplate.class), 60);
        Set<String> activeBuildIds = after.current().activeBuildIds();
        assertFalse(activeBuildIds.contains(active.getBuildId()), "已删除文档的 active build 不能继续参与检索");
    }

    @Test
    void legacyRepresentationVersionIsRetiredSoDeletedChunksLeaveTheScope() {
        FileUpload upload = saveUpload("md5-legacy-rep", "owner-1", "old.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, true);

        deletionService.deleteByFileMd5("md5-legacy-rep", "owner-1");

        assertTrue(versionRepository.findById(version.getVersionId()).orElseThrow().isLegacyRetired());
        ActiveRetrievalScopeService reloaded = new ActiveRetrievalScopeService(
                documentRepository, versionRepository, mock(StringRedisTemplate.class), 60);
        assertTrue(reloaded.current().isEmpty(), "共享库不再用 legacy 分支；删除后不应有 active build");
        assertFalse(reloaded.current().retiredLegacyMd5s().contains("md5-legacy-rep"),
                "retired md5 不再参与检索范围");
    }

    @Test
    void cleanupDeletesBuildDataUploadChunkPreviewAndMergedObject() throws Exception {
        FileUpload upload = saveUpload("md5-clean", "owner-1", "clean.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild pending = pendingBuild(version);
        DocumentBuild processing = processingBuild(version);
        saveVector(pending.getBuildId(), "md5-clean");
        saveVector(processing.getBuildId(), "md5-clean");
        saveChunk("md5-clean", "owner-1");

        deletionService.deleteByFileMd5("md5-clean", "owner-1");

        verify(elasticsearchService).deleteByBuildId(KnowledgeIndexNames.ALIAS, pending.getBuildId());
        verify(elasticsearchService).deleteByBuildId(KnowledgeIndexNames.ALIAS, processing.getBuildId());
        assertEquals(0, documentVectorRepository.countByBuildId(pending.getBuildId()));
        assertEquals(0, documentVectorRepository.countByBuildId(processing.getBuildId()));

        assertTrue(fileUploadRepository.findById(upload.getId()).isEmpty(), "版本对应的上传记录必须删除");
        assertTrue(chunkInfoRepository.findByFileMd5AndUserIdOrderByChunkIndexAsc("md5-clean", "owner-1").isEmpty());
        verify(documentService).invalidatePdfSinglePagePreviewCache("md5-clean");
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void sharedMd5KeepsMergedObjectUntilTheLastUploadIsDeleted() throws Exception {
        FileUpload first = saveUpload("md5-shared", "owner-1", "shared.pdf");
        BusinessDocument firstDocument = saveDocument("owner-1");
        DocumentVersion firstVersion = saveVersion(firstDocument.getDocumentId(), 1, first, false);
        pendingBuild(firstVersion);

        FileUpload second = saveUpload("md5-shared", "owner-2", "shared.pdf");
        BusinessDocument secondDocument = saveDocument("owner-2");
        DocumentVersion secondVersion = saveVersion(secondDocument.getDocumentId(), 1, second, false);
        pendingBuild(secondVersion);

        deletionService.deleteByFileMd5("md5-shared", "owner-1");

        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        assertTrue(fileUploadRepository.findById(first.getId()).isEmpty());
        assertTrue(fileUploadRepository.findById(second.getId()).isPresent(), "共享 MD5 的其他上传记录必须保留");

        // 共享 MD5 仍有别人的上传记录时，重复删除也必须按幂等返回而不是删掉别人的文档
        clearInvocations(elasticsearchService, activeScopeService, minioClient, documentService);
        deletionService.deleteByFileMd5("md5-shared", "owner-1");
        verifyNoInteractions(elasticsearchService, activeScopeService, minioClient, documentService);

        deletionService.deleteByFileMd5("md5-shared", "owner-2");

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
        assertTrue(fileUploadRepository.findById(second.getId()).isEmpty());
    }

    @Test
    void inFlightBuildCannotBecomeReadyOrActiveAfterDelete() {
        FileUpload upload = saveUpload("md5-inflight", "owner-1", "inflight.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild processing = processingBuild(version);

        deletionService.deleteByFileMd5("md5-inflight", "owner-1");

        assertFalse(buildService.markReady(processing.getBuildId(), 3), "已取消的构建不能回到 READY");
        assertEquals(DocumentBuildService.BuildActivation.NOT_READY, buildService.activate(processing.getBuildId()));
        assertEquals(DocumentBuildStatus.CANCELLED, reload(processing.getBuildId()).getStatus());
        assertNull(documentRepository.findById(document.getDocumentId()).orElseThrow().getActiveBuildId());
    }

    @Test
    void lateReadyBuildOfDeletedDocumentIsCancelledOnActivate() {
        FileUpload upload = saveUpload("md5-late", "owner-1", "late.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);

        deletionService.deleteByFileMd5("md5-late", "owner-1");

        // 模拟删除事务期间并发产生、未被取消到的 READY 构建
        DocumentBuild late = readyBuild(version);
        assertEquals(DocumentBuildService.BuildActivation.CANCELLED, buildService.activate(late.getBuildId()));

        assertEquals(DocumentBuildStatus.CANCELLED, reload(late.getBuildId()).getStatus());
        BusinessDocument deleted = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertNull(deleted.getActiveVersionId());
        assertNull(deleted.getActiveBuildId());
    }

    @Test
    void legacyUploadWithoutVersionDelegatesToMd5Cleanup() {
        FileUpload upload = saveUpload("md5-legacy", "owner-1", "legacy.pdf");
        long documentsBefore = documentRepository.count();

        deletionService.deleteByFileMd5("md5-legacy", "owner-1");

        verify(documentService).deleteLegacyDocument("md5-legacy", "owner-1");
        assertEquals(documentsBefore, documentRepository.count(), "旧文件删除不能创建业务文档");
        assertTrue(versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(upload.getId()).isEmpty());
        verifyNoInteractions(elasticsearchService, activeScopeService, minioClient);
    }

    @Test
    void fileManagerCanDeleteAnotherUsersVersionedDocument() {
        User admin = new User();
        admin.setUsername("manager-delete-test");
        admin.setPassword("secret");
        admin.setRole(User.Role.USER);
        admin.setKnowledgeRole(com.yizhaoqi.smartpai.model.KnowledgeRole.FILE_MANAGER);
        userRepository.saveAndFlush(admin);

        FileUpload upload = saveUpload("md5-admin", "owner-1", "admin.pdf");
        BusinessDocument document = saveDocument("owner-1");
        prepareVersionWithBuild(upload, document);

        deletionService.deleteByFileMd5("md5-admin", "manager-delete-test");

        assertNotNull(documentRepository.findById(document.getDocumentId()).orElseThrow().getDeletedAt());
    }

    @Test
    void foreignUserCannotDeleteAnotherUsersDocument() {
        FileUpload upload = saveUpload("md5-foreign", "owner-1", "foreign.pdf");
        BusinessDocument document = saveDocument("owner-1");
        prepareVersionWithBuild(upload, document);

        CustomException error = assertThrows(CustomException.class,
                () -> deletionService.deleteByFileMd5("md5-foreign", "intruder"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertNull(documentRepository.findById(document.getDocumentId()).orElseThrow().getDeletedAt());
        verifyNoInteractions(elasticsearchService, activeScopeService, minioClient);
    }

    @Test
    void secondDeleteAfterCleanupReturnsIdempotently() throws Exception {
        FileUpload upload = saveUpload("md5-twice", "owner-1", "twice.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild pending = pendingBuild(version);

        deletionService.deleteByFileMd5("md5-twice", "owner-1");
        assertEquals(0, fileUploadRepository.countByFileMd5("md5-twice"));

        clearInvocations(elasticsearchService, activeScopeService, minioClient, documentService);

        deletionService.deleteByFileMd5("md5-twice", "owner-1");

        assertEquals(DocumentBuildStatus.CANCELLED, reload(pending.getBuildId()).getStatus());
        verifyNoInteractions(elasticsearchService, activeScopeService, minioClient, documentService);
    }

    @Test
    void alreadyDeletedDocumentSkipsCancelAndCleanup() throws Exception {
        FileUpload upload = saveUpload("md5-preset", "owner-1", "preset.pdf");
        BusinessDocument document = saveDocument("owner-1");
        document.setDeletedAt(LocalDateTime.now());
        documentRepository.saveAndFlush(document);
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild pending = pendingBuild(version);

        deletionService.deleteByFileMd5("md5-preset", "owner-1");

        assertEquals(DocumentBuildStatus.PENDING, reload(pending.getBuildId()).getStatus());
        verifyNoInteractions(elasticsearchService, activeScopeService, minioClient, documentService);
        assertTrue(fileUploadRepository.findById(upload.getId()).isPresent(), "幂等返回不能再做清理");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void scopeInvalidationRunsAfterTheDisableTransactionCommits() {
        FileUpload upload = saveUpload("md5-commit", "owner-1", "commit.pdf");
        BusinessDocument document = saveDocument("owner-1");
        prepareVersionWithBuild(upload, document);
        String documentId = document.getDocumentId();

        doAnswer(invocation -> {
            BusinessDocument committed = documentRepository.findById(documentId).orElseThrow();
            assertNotNull(committed.getDeletedAt(), "检索范围失效必须在停用事务提交之后执行");
            return null;
        }).when(activeScopeService).invalidateAndBroadcast();

        deletionService.deleteByFileMd5("md5-commit", "owner-1");

        verify(activeScopeService).invalidateAndBroadcast();
        assertNotNull(documentRepository.findById(documentId).orElseThrow().getDeletedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancelFailureRollsBackOnlyTheCancelTransactionAndKeepsDocumentDeleted() {
        FileUpload upload = saveUpload("md5-cancel-fail", "owner-1", "cancel-fail.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild first = pendingBuild(version);
        DocumentBuild second = pendingBuild(version);
        String documentId = document.getDocumentId();

        DocumentBuildService failingBuildService = new DocumentBuildService(
                buildRepository, versionRepository, documentRepository, elasticsearchService, activeScopeService) {
            @Override
            public boolean markCancelled(String buildId) {
                // 取消阶段按 buildNo 倒序处理：先成功取消 second，再在 first 上失败，验证取消事务整体回滚
                if (buildId.equals(first.getBuildId())) {
                    throw new IllegalStateException("取消构建失败");
                }
                return super.markCancelled(buildId);
            }
        };

        newDeletionService(failingBuildService).deleteByFileMd5("md5-cancel-fail", "owner-1");

        assertNotNull(documentRepository.findById(documentId).orElseThrow().getDeletedAt(),
                "取消阶段失败不能回滚已经提交的删除状态");
        assertEquals(DocumentBuildStatus.PENDING, reload(first.getBuildId()).getStatus(),
                "取消事务失败时本次取消必须整体回滚");
        assertEquals(DocumentBuildStatus.PENDING, reload(second.getBuildId()).getStatus());
        verify(activeScopeService).invalidateAndBroadcast();
        verify(elasticsearchService).deleteByBuildId(KnowledgeIndexNames.ALIAS, first.getBuildId());
        verify(elasticsearchService).deleteByBuildId(KnowledgeIndexNames.ALIAS, second.getBuildId());
        assertTrue(fileUploadRepository.findById(upload.getId()).isEmpty(), "文档删除后仍要继续物理清理");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void markDeletedCommitsBeforeCancellationWithoutHoldingDocumentLock() {
        FileUpload upload = saveUpload("md5-phase", "owner-1", "phase.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        DocumentBuild pending = pendingBuild(version);
        String documentId = document.getDocumentId();
        AtomicReference<Boolean> cancellationRan = new AtomicReference<>(false);

        DocumentBuildService observing = new DocumentBuildService(
                buildRepository, versionRepository, documentRepository, elasticsearchService, activeScopeService) {
            @Override
            public boolean markCancelled(String buildId) {
                BusinessDocument committed = documentRepository.findById(documentId).orElseThrow();
                assertNotNull(committed.getDeletedAt(), "取消构建时 deletedAt 必须已经在独立事务中提交");

                TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.executeWithoutResult(status -> {
                    // 停用事务已经结束；若取消阶段仍持有文档行锁，这里的 FOR UPDATE 会等锁超时
                    BusinessDocument locked = documentRepository.findForUpdate(documentId).orElseThrow();
                    assertNotNull(locked.getDeletedAt());
                });

                cancellationRan.set(true);
                return super.markCancelled(buildId);
            }
        };

        newDeletionService(observing).deleteByFileMd5("md5-phase", "owner-1");

        assertTrue(cancellationRan.get(), "取消阶段必须执行");
        assertEquals(DocumentBuildStatus.CANCELLED, reload(pending.getBuildId()).getStatus());
        assertNotNull(documentRepository.findById(documentId).orElseThrow().getDeletedAt());
        verify(activeScopeService).invalidateAndBroadcast();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activationStartingAfterDeleteIsRejectedWithCancelled() {
        FileUpload oldUpload = saveUpload("md5-race-old", "owner-1", "race-old.pdf");
        BusinessDocument document = saveDocument("owner-1");
        DocumentVersion oldVersion = saveVersion(document.getDocumentId(), 1, oldUpload, false);
        DocumentBuild active = readyBuild(oldVersion);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(active.getBuildId()));

        FileUpload newUpload = saveUpload("md5-race-new", "owner-1", "race-new.pdf");
        DocumentVersion newVersion = saveVersion(document.getDocumentId(), 2, newUpload, false);
        DocumentBuild ready = readyBuild(newVersion);
        String documentId = document.getDocumentId();
        AtomicReference<DocumentBuildService.BuildActivation> activation = new AtomicReference<>();

        DocumentBuildService racing = new DocumentBuildService(
                buildRepository, versionRepository, documentRepository, elasticsearchService, activeScopeService) {
            @Override
            public boolean markCancelled(String buildId) {
                if (buildId.equals(ready.getBuildId()) && activation.get() == null) {
                    // 删除的停用阶段已经提交；此时才开始的切换必须看到 deletedAt 并返回 CANCELLED
                    activation.set(buildService.activate(buildId));
                }
                return super.markCancelled(buildId);
            }
        };

        newDeletionService(racing).deleteByFileMd5("md5-race-new", "owner-1");

        assertEquals(DocumentBuildService.BuildActivation.CANCELLED, activation.get(),
                "删除后开始的切换必须被拒绝");
        assertEquals(DocumentBuildStatus.CANCELLED, reload(ready.getBuildId()).getStatus());
        BusinessDocument deleted = documentRepository.findById(documentId).orElseThrow();
        assertNotNull(deleted.getDeletedAt());
        assertEquals(active.getBuildId(), deleted.getActiveBuildId(), "被拒绝的切换不能替换原有 active 指针");
    }

    @Test
    void legacyRepresentationCleanupDeletesLegacyEsAndVectorRowsWhenMd5NotShared() {
        FileUpload upload = saveUpload("md5-legacy-rows", "owner-1", "legacy-rows.pdf");
        BusinessDocument document = saveDocument("owner-1");
        saveVersion(document.getDocumentId(), 1, upload, true);
        saveLegacyVector("md5-legacy-rows", "owner-1");

        deletionService.deleteByFileMd5("md5-legacy-rows", "owner-1");

        verify(elasticsearchService).deleteByFileMd5("md5-legacy-rows");
        assertEquals(0, documentVectorRepository.countByFileMd5("md5-legacy-rows"),
                "md5 不再共享时必须删除 legacy 向量行");
        assertTrue(fileUploadRepository.findById(upload.getId()).isEmpty());
    }

    @Test
    void sharedMd5LegacyRepresentationKeepsLegacyEsAndVectorRows() throws Exception {
        FileUpload first = saveUpload("md5-legacy-shared", "owner-1", "legacy-shared.pdf");
        BusinessDocument firstDocument = saveDocument("owner-1");
        saveVersion(firstDocument.getDocumentId(), 1, first, true);
        saveLegacyVector("md5-legacy-shared", "owner-1");

        FileUpload second = saveUpload("md5-legacy-shared", "owner-2", "legacy-shared.pdf");
        BusinessDocument secondDocument = saveDocument("owner-2");
        saveVersion(secondDocument.getDocumentId(), 1, second, false);

        deletionService.deleteByFileMd5("md5-legacy-shared", "owner-1");

        verify(elasticsearchService, never()).deleteByFileMd5(anyString());
        assertEquals(1, documentVectorRepository.countByFileMd5("md5-legacy-shared"),
                "共享 MD5 的 legacy 向量行不能被删除");
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    private DocumentDeletionService newDeletionService(DocumentBuildService buildServiceToUse) {
        return new DocumentDeletionService(fileUploadRepository, versionRepository, documentRepository,
                buildRepository, buildServiceToUse, userRepository, documentService, auditService,
                activeScopeService, elasticsearchService, documentVectorRepository, chunkInfoRepository,
                minioClient, entityManager, transactionManager);
    }

    private void prepareVersionWithBuild(FileUpload upload, BusinessDocument document) {
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, upload, false);
        pendingBuild(version);
    }

    private FileUpload saveUpload(String md5, String userId, String fileName) {
        FileUpload upload = new FileUpload();
        upload.setFileMd5(md5);
        upload.setUserId(userId);
        upload.setFileName(fileName);
        upload.setTotalSize(1024L);
        upload.setStatus(FileUpload.STATUS_COMPLETED);
        upload.setOrgTag("TEAM_A");
        return fileUploadRepository.saveAndFlush(upload);
    }

    private BusinessDocument saveDocument(String ownerUserId) {
        LocalDateTime now = LocalDateTime.now();
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setOwnerUserId(ownerUserId);
        document.setTitle("测试文档");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return documentRepository.saveAndFlush(document);
    }

    private DocumentVersion saveVersion(String documentId, int versionNo, FileUpload upload, boolean legacy) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setFileMd5(upload.getFileMd5());
        version.setFileUploadId(upload.getId());
        version.setFileName(upload.getFileName());
        version.setOrgTag(upload.getOrgTag());
        version.setPublic(upload.isPublic());
        version.setLegacyRepresentation(legacy);
        version.setLegacyRetired(false);
        version.setCreatedAt(LocalDateTime.now());
        return versionRepository.saveAndFlush(version);
    }

    private DocumentBuild pendingBuild(DocumentVersion version) {
        return buildService.createBuild(version.getVersionId(), buildService.nextBuildNo(version.getVersionId()));
    }

    private DocumentBuild processingBuild(DocumentVersion version) {
        DocumentBuild build = pendingBuild(version);
        assertTrue(buildService.markProcessing(build.getBuildId()));
        return build;
    }

    private DocumentBuild readyBuild(DocumentVersion version) {
        DocumentBuild build = processingBuild(version);
        assertTrue(buildService.markReady(build.getBuildId(), 2));
        return build;
    }

    private void saveVector(String buildId, String fileMd5) {
        DocumentVector vector = new DocumentVector();
        vector.setBuildId(buildId);
        vector.setFileMd5(fileMd5);
        vector.setChunkId(1);
        vector.setUserId("owner-1");
        vector.setPublic(false);
        documentVectorRepository.saveAndFlush(vector);
    }

    private void saveLegacyVector(String fileMd5, String userId) {
        DocumentVector vector = new DocumentVector();
        vector.setFileMd5(fileMd5);
        vector.setChunkId(1);
        vector.setUserId(userId);
        vector.setPublic(false);
        documentVectorRepository.saveAndFlush(vector);
    }

    private void saveChunk(String fileMd5, String userId) {
        ChunkInfo chunk = new ChunkInfo();
        chunk.setFileMd5(fileMd5);
        chunk.setUserId(userId);
        chunk.setChunkIndex(0);
        chunk.setChunkMd5("chunk-md5");
        chunk.setStoragePath("chunks/0");
        chunkInfoRepository.saveAndFlush(chunk);
    }

    private DocumentBuild reload(String buildId) {
        return buildRepository.findById(buildId).orElseThrow();
    }

    private void assertCancelled(String buildId) {
        DocumentBuild build = reload(buildId);
        assertEquals(DocumentBuildStatus.CANCELLED, build.getStatus());
        assertNotNull(build.getCompletedAt());
    }
}
