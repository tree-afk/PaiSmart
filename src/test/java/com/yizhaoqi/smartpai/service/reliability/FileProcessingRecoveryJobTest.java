package com.yizhaoqi.smartpai.service.reliability;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.document.DocumentBuildService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 恢复任务：带构建的卡住任务按原构建重投或按构建状态收尾，没有构建的任务保持 legacy 行为。
 */
class FileProcessingRecoveryJobTest {

    private final FileUploadRepository repository = mock(FileUploadRepository.class);
    private final DocumentBuildRepository buildRepository = mock(DocumentBuildRepository.class);
    private final DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
    private final FileProcessingOutboxService outboxService = mock(FileProcessingOutboxService.class);
    private final UploadService uploadService = mock(UploadService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final FileProcessingReliabilityProperties properties = new FileProcessingReliabilityProperties();
    private final DocumentStatusNotifier statusNotifier = mock(DocumentStatusNotifier.class);
    private final DocumentBuildService buildService = mock(DocumentBuildService.class);
    private FileProcessingRecoveryJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        when(buildRepository.findLatestForFileUpload(any(), any())).thenReturn(List.of());
        job = new FileProcessingRecoveryJob(repository, buildRepository, versionRepository, outboxService,
                uploadService, properties, transactionTemplate, statusNotifier, buildService);
    }

    @Test
    void staleTaskUnderRetryLimitIsRequeuedThroughOutbox() {
        FileUpload stale = stale(1);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        ArgumentCaptor<FileProcessingTask> task = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(outboxService).append(task.capture());
        assertEquals("md5", task.getValue().getFileMd5());
        // 重新投递不带预签名 URL，消费者直接读 MinIO 合并对象
        assertNull(task.getValue().getFilePath());
        // 没有构建的任务保持 8 参 legacy 任务
        assertNull(task.getValue().getBuildId());
        assertNull(task.getValue().getBuildKind());
        assertNull(task.getValue().getDocType());
        assertNull(task.getValue().getEffectiveAt());
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void pendingBuildIsRequeuedWithFullBuildContext() {
        FileUpload stale = stale(0);
        DocumentBuild build = build("b1", "v1", DocumentBuildStatus.PENDING, 3);
        DocumentVersion version = version();
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class))).thenReturn(List.of(build));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(version));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        ArgumentCaptor<FileProcessingTask> task = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(outboxService).append(task.capture());
        FileProcessingTask requeued = task.getValue();
        assertEquals("md5", requeued.getFileMd5());
        assertNull(requeued.getFilePath());
        assertEquals("d1", requeued.getDocumentId());
        assertEquals("v1", requeued.getVersionId());
        assertEquals("b1", requeued.getBuildId());
        assertEquals(FileProcessingTask.BUILD_KIND_REINDEX, requeued.getBuildKind());
        assertEquals(2, requeued.getVersionNo());
        assertEquals(3, requeued.getBuildNo());
        assertEquals("TEAM", requeued.getOrgTag());
        assertTrue(requeued.isPublic());
        assertEquals(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, requeued.getTaskType());
        assertEquals("INTERNAL_POLICY", requeued.getDocType());
        assertEquals("北京", requeued.getJurisdiction());
        assertEquals("某银行", requeued.getIssuer());
        assertEquals("2026-05-01", requeued.getEffectiveAt());
        assertEquals(new ComplianceMetadata("INTERNAL_POLICY", "北京", "某银行", LocalDate.of(2026, 5, 1)),
                requeued.complianceMetadata());
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void processingBuildIsRequeuedWithFullBuildContext() {
        FileUpload stale = stale(0);
        DocumentBuild build = build("b1", "v1", DocumentBuildStatus.PROCESSING, 3);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class))).thenReturn(List.of(build));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(version()));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        ArgumentCaptor<FileProcessingTask> task = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(outboxService).append(task.capture());
        assertEquals("b1", task.getValue().getBuildId());
        assertEquals(FileProcessingTask.BUILD_KIND_REINDEX, task.getValue().getBuildKind());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentBuildStatus.class, names = {"READY", "ACTIVE", "SUPERSEDED"})
    void finishedBuildMarksFileCompletedWithoutRequeue(DocumentBuildStatus status) {
        FileUpload stale = stale(0);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(build("b1", "v1", status, 3)));
        when(repository.completeStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        verify(repository).completeStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any());
        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
        verify(repository, never()).failStaleVectorization(any(), any(), anyString(), any());
        // ACTIVE / SUPERSEDED 等已生效构建不能被恢复任务标失败
        verify(buildService, never()).markFailed(anyString(), anyString());
        verify(outboxService, never()).append(any());
        verify(statusNotifier).notifyChanged(7L);
    }

    @ParameterizedTest
    @EnumSource(value = DocumentBuildStatus.class, names = {"FAILED", "CANCELLED"})
    void failedOrCancelledBuildMarksFileFailedWithoutRequeue(DocumentBuildStatus status) {
        FileUpload stale = stale(0);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(build("b1", "v1", status, 3)));
        when(repository.failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(repository).failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), message.capture(), any());
        assertTrue(message.getValue().contains(status.name()));
        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
        verify(repository, never()).completeStaleVectorization(any(), any(), any());
        verify(buildService, never()).markFailed(anyString(), anyString());
        verify(outboxService, never()).append(any());
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void conditionalUpdateLosingRaceWithBuildDoesNotRequeueOrNotify() {
        FileUpload stale = stale(0);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(build("b1", "v1", DocumentBuildStatus.PROCESSING, 3)));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(version()));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(0);

        assertEquals(0, job.recoverStaleVectorizations());

        verify(outboxService, never()).append(any());
        verify(statusNotifier, never()).notifyChanged(any());
    }

    @Test
    void legacyTaskWithoutClaimTimeIsFailedInsteadOfRequeued() {
        FileUpload legacy = stale(0);
        legacy.setProcessingStartedAt(null);
        legacy.setHeartbeatAt(null);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(legacy));
        when(repository.failStaleVectorization(eq(7L), eq(null), eq(FileProcessingRecoveryJob.LEGACY_TASK_MESSAGE), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());

        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
        verify(outboxService, never()).append(any());
        // 升级前内存里的历史任务没有构建可标
        verifyNoInteractions(buildService);
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void requeueLosingTheRaceDoesNotPublish() {
        FileUpload stale = stale(0);
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(repository.requeueStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), any())).thenReturn(0);

        assertEquals(0, job.recoverStaleVectorizations());
        verify(outboxService, never()).append(any());
    }

    @Test
    void taskOverRetryLimitIsMarkedFailedInsteadOfRequeued() {
        FileUpload stale = stale(properties.getMaxRetries());
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(repository.failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any())).thenReturn(1);

        assertEquals(1, job.recoverStaleVectorizations());
        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
        verify(outboxService, never()).append(any());
        // legacy 任务没有构建，只标文件失败
        verifyNoInteractions(buildService);
    }

    @Test
    void buildOverRetryLimitMarksLatestBuildFailedAfterFileUpdate() {
        FileUpload stale = stale(properties.getMaxRetries());
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(build("b1", "v1", DocumentBuildStatus.PROCESSING, 3)));
        when(repository.failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any())).thenReturn(1);
        when(buildService.markFailed(eq("b1"), anyString())).thenReturn(true);

        assertEquals(1, job.recoverStaleVectorizations());

        // 先成功条件更新文件状态，再把最新构建按状态机守卫标 FAILED
        InOrder inOrder = inOrder(repository, buildService);
        inOrder.verify(repository).failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any());
        inOrder.verify(buildService).markFailed(eq("b1"), anyString());
        verify(statusNotifier).notifyChanged(7L);
    }

    @Test
    void buildOverRetryLimitDoesNotMarkBuildWhenFileUpdateLosesRace() {
        FileUpload stale = stale(properties.getMaxRetries());
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(build("b1", "v1", DocumentBuildStatus.PROCESSING, 3)));
        when(repository.failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any())).thenReturn(0);

        assertEquals(0, job.recoverStaleVectorizations());

        verifyNoInteractions(buildService);
        verify(statusNotifier, never()).notifyChanged(any());
    }

    @Test
    void buildOverRetryLimitKeepsFileFailureWhenTransitionGuardRejects() {
        FileUpload stale = stale(properties.getMaxRetries());
        when(repository.findStaleVectorizations(any(), any())).thenReturn(List.of(stale));
        when(buildRepository.findLatestForFileUpload(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(build("b1", "v1", DocumentBuildStatus.PROCESSING, 3)));
        when(repository.failStaleVectorization(eq(7L), eq(stale.getHeartbeatAt()), anyString(), any())).thenReturn(1);
        // 扫描后构建被并发切成 ACTIVE / SUPERSEDED 时，状态机守卫返回 false，只记日志
        when(buildService.markFailed(eq("b1"), anyString())).thenReturn(false);

        assertEquals(1, job.recoverStaleVectorizations());

        verify(buildService).markFailed(eq("b1"), anyString());
        verify(statusNotifier).notifyChanged(7L);
        verify(repository, never()).requeueStaleVectorization(any(), any(), any());
    }

    @Test
    void abandonedUploadChunksAreCleanedOnlyAfterRecordDeleteSucceeds() {
        FileUpload kept = stale(0);
        kept.setStatus(FileUpload.STATUS_UPLOADING);
        FileUpload abandoned = stale(0);
        abandoned.setId(8L);
        abandoned.setFileMd5("md5-2");
        abandoned.setStatus(FileUpload.STATUS_UPLOADING);
        when(repository.findStaleByUploadStatus(eq(FileUpload.STATUS_UPLOADING), any(), any())).thenReturn(List.of(kept, abandoned));
        when(repository.deleteAbandonedUpload(7L, FileUpload.STATUS_UPLOADING, kept.getHeartbeatAt())).thenReturn(0);
        when(repository.deleteAbandonedUpload(8L, FileUpload.STATUS_UPLOADING, abandoned.getHeartbeatAt())).thenReturn(1);

        assertEquals(1, job.cleanupAbandonedUploads());
        verify(uploadService).cleanupAbandonedChunks("md5-2", "1");
        verify(uploadService, never()).cleanupAbandonedChunks("md5", "1");
    }

    private FileUpload stale(int retryCount) {
        FileUpload file = new FileUpload();
        file.setId(7L);
        file.setFileMd5("md5");
        file.setFileName("a.pdf");
        file.setUserId("1");
        file.setOrgTag("TEAM");
        file.setStatus(FileUpload.STATUS_COMPLETED);
        file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);
        file.setHeartbeatAt(LocalDateTime.now().minusHours(1));
        file.setProcessingStartedAt(LocalDateTime.now().minusHours(2));
        file.setRetryCount(retryCount);
        return file;
    }

    private DocumentVersion version() {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId("v1");
        version.setDocumentId("d1");
        version.setVersionNo(2);
        version.setFileMd5("md5");
        version.setFileUploadId(7L);
        version.setFileName("a.pdf");
        version.setOrgTag("TEAM");
        version.setPublic(true);
        version.setDocType("INTERNAL_POLICY");
        version.setJurisdiction("北京");
        version.setIssuer("某银行");
        version.setEffectiveAt(LocalDate.of(2026, 5, 1));
        return version;
    }

    private DocumentBuild build(String buildId, String versionId, DocumentBuildStatus status, int buildNo) {
        DocumentBuild build = new DocumentBuild();
        build.setBuildId(buildId);
        build.setVersionId(versionId);
        build.setBuildNo(buildNo);
        build.setStatus(status);
        return build;
    }
}
