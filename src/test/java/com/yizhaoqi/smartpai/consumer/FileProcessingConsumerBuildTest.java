package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.exception.InsufficientBalanceException;
import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.DocumentBuildContext;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import com.yizhaoqi.smartpai.service.conflict.DocumentVectorizedEvent;
import com.yizhaoqi.smartpai.service.document.DocumentBuildService;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLease;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseService;
import io.minio.GetObjectResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 4：带 buildId 的任务先按构建状态预检，按构建清理、解析、向量化；
 * 计数核对通过后才标 READY、写文件完成并切换；切换异常保持 READY，绝不把就绪构建标 FAILED。
 */
class FileProcessingConsumerBuildTest {

    private final ParseService parseService = mock(ParseService.class);
    private final VectorizationService vectorizationService = mock(VectorizationService.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final VectorizationLeaseService leaseService = mock(VectorizationLeaseService.class);
    private final UploadService uploadService = mock(UploadService.class);
    private final DocumentBuildService buildService = mock(DocumentBuildService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final VectorizationLease lease = new VectorizationLease(1L, "md5", LocalDateTime.now());
    private static final ComplianceMetadata COMPLIANCE = new ComplianceMetadata(
            "REGULATION", "全国", "国家金融监督管理总局", LocalDate.of(2026, 10, 1));
    private final DocumentBuildContext context = new DocumentBuildContext("d1", "v1", "b1", 2, 3, COMPLIANCE);

    private FileProcessingConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        consumer = new FileProcessingConsumer(parseService, vectorizationService, documentService,
                leaseService, uploadService, buildService);
        ReflectionTestUtils.setField(consumer, "eventPublisher", eventPublisher);
        when(uploadService.getMergedFileStream("md5")).thenReturn(mock(GetObjectResponse.class));
        when(leaseService.claim(any())).thenReturn(Optional.of(lease));
        when(buildService.markProcessing("b1")).thenReturn(true);
        when(buildService.markReady("b1", 2)).thenReturn(true);
    }

    @Test
    void buildTaskClearsByBuildAndSwitchesOnlyAfterChunkCountCheck() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class))).thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(true);
        when(leaseService.complete(lease, result)).thenReturn(true);
        when(buildService.activate("b1")).thenReturn(DocumentBuildService.BuildActivation.ACTIVE);

        consumer.processTask(buildTask());

        InOrder inOrder = inOrder(buildService, documentService, parseService, vectorizationService, leaseService);
        inOrder.verify(buildService).statusOf("b1");
        inOrder.verify(buildService).markProcessing("b1");
        inOrder.verify(documentService).clearBuildData("b1");
        inOrder.verify(parseService).parseAndSave(eq("md5"), any(), eq("1"), eq("TEAM"), eq(false), eq(context));
        inOrder.verify(leaseService).heartbeat(lease);
        inOrder.verify(vectorizationService).vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                eq(context), any(Runnable.class));
        inOrder.verify(buildService).chunkCountMatches("b1", 2);
        inOrder.verify(buildService).markReady("b1", 2);
        inOrder.verify(leaseService).complete(lease, result);
        inOrder.verify(buildService).activate("b1");

        verify(documentService, never()).clearIndexedData(anyString());
        verify(buildService, never()).markFailed(anyString(), any());
        verify(eventPublisher).publishEvent(new DocumentVectorizedEvent("md5", "d1"));
    }

    @Test
    void chunkCountMismatchMarksBuildFailedAndFailsFileWithoutActivation() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class))).thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(false);

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verify(buildService).markFailed("b1", "分块数核对失败");
        verify(leaseService).fail(eq(lease), any(IllegalStateException.class));
        verify(leaseService, never()).complete(any(), any());
        verify(buildService, never()).markReady(anyString(), anyInt());
        verify(buildService, never()).activate(anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void retryableFailureKeepsBuildProcessingFailsLeaseAndRethrowsForKafkaRetry() throws Exception {
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class)))
                .thenThrow(new RuntimeException("向量化超时"));

        assertThrows(RuntimeException.class, () -> consumer.processTask(buildTask()));

        // 可重试异常：构建保持 PROCESSING，不能提前标 FAILED；文件租约标失败后仍可被下次投递认领
        verify(buildService, never()).markFailed(anyString(), any());
        verify(leaseService).fail(eq(lease), any(RuntimeException.class));
        verify(buildService, never()).chunkCountMatches(anyString(), anyInt());
    }

    @Test
    void redeliveryAfterRetryableFailureIsNotSkippedAndCanMarkProcessingAgain() throws Exception {
        when(buildService.statusOf("b1")).thenReturn(DocumentBuildStatus.PROCESSING);
        when(buildService.shouldSkipProcessing(DocumentBuildStatus.PROCESSING)).thenReturn(false);
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class)))
                .thenThrow(new RuntimeException("向量化超时"))
                .thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(true);
        when(leaseService.complete(lease, result)).thenReturn(true);
        when(buildService.activate("b1")).thenReturn(DocumentBuildService.BuildActivation.ACTIVE);

        assertThrows(RuntimeException.class, () -> consumer.processTask(buildTask()));
        // 第二次投递：构建仍是 PROCESSING，不被预检跳过，markProcessing 幂等通过后可以重新处理
        consumer.processTask(buildTask());

        verify(buildService, times(2)).markProcessing("b1");
        verify(buildService, never()).markFailed(anyString(), any());
        verify(leaseService, times(2)).claim(any());
        verify(buildService).markReady("b1", 2);
    }

    @Test
    void insufficientBalanceMarksBuildFailedFailsLeaseAndDoesNotRethrow() throws Exception {
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class)))
                .thenThrow(new RuntimeException("向量化失败",
                        new InsufficientBalanceException("Embedding Token 余额不足")));

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verify(buildService).markFailed(eq("b1"), anyString());
        verify(leaseService).fail(eq(lease), any(RuntimeException.class));
        verify(buildService, never()).chunkCountMatches(anyString(), anyInt());
    }

    @Test
    void readyOrTerminalBuildIsSkippedBeforeClaimWithoutChangingFileStatus() {
        when(buildService.statusOf("b1")).thenReturn(DocumentBuildStatus.ACTIVE);
        when(buildService.shouldSkipProcessing(DocumentBuildStatus.ACTIVE)).thenReturn(true);

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verifyNoInteractions(leaseService, parseService, vectorizationService, documentService);
        verify(buildService, never()).markProcessing(anyString());
        verify(buildService, never()).markFailed(anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void terminalBuildDetectedAfterClaimReleasesLeaseWithoutFailing() {
        when(buildService.statusOf("b1")).thenReturn(DocumentBuildStatus.PENDING);
        when(buildService.markProcessing("b1")).thenReturn(false);

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        // 并发改状态：只结束认领，不改写文件状态，由恢复任务按构建结果收尾
        verify(leaseService).release(lease);
        verify(leaseService, never()).fail(any(), any());
        verifyNoInteractions(parseService, vectorizationService);
        verify(documentService, never()).clearBuildData(anyString());
        verify(documentService, never()).clearIndexedData(anyString());
        verify(buildService, never()).markFailed(anyString(), any());
        verify(buildService, never()).markReady(anyString(), anyInt());
        verify(buildService, never()).activate(anyString());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void activationExceptionLeavesBuildReadyAndDoesNotMarkFailed() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class))).thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(true);
        when(leaseService.complete(lease, result)).thenReturn(true);
        when(buildService.activate("b1")).thenThrow(new IllegalStateException("切换失败"));

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verify(buildService).markReady("b1", 2);
        // 切换异常必须保持 READY 交给恢复任务，不能标 FAILED
        verify(buildService, never()).markFailed(anyString(), any());
        verify(leaseService, never()).fail(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void leaseCompleteFalseAfterMarkReadyDoesNotActivate() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class))).thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(true);
        when(leaseService.complete(lease, result)).thenReturn(false);

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verify(buildService).markReady("b1", 2);
        verify(buildService, never()).activate(anyString());
        verify(buildService, never()).markFailed(anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void markReadyFalseDoesNotActivate() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class))).thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(true);
        when(buildService.markReady("b1", 2)).thenReturn(false);
        when(leaseService.complete(lease, result)).thenReturn(true);

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verify(buildService, never()).activate(anyString());
        verify(buildService, never()).markFailed(anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void inactiveActivationResultDoesNotPublish() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(DocumentBuildContext.class), any(Runnable.class))).thenReturn(result);
        when(buildService.chunkCountMatches("b1", 2)).thenReturn(true);
        when(leaseService.complete(lease, result)).thenReturn(true);
        when(buildService.activate("b1")).thenReturn(DocumentBuildService.BuildActivation.CANCELLED);

        assertDoesNotThrow(() -> consumer.processTask(buildTask()));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void legacyTaskKeepsFileMd5CleanupAndLegacyVectorization() throws Exception {
        VectorizationService.VectorizationUsageResult result = new VectorizationService.VectorizationUsageResult(10, 2, "m");
        when(vectorizationService.vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(Runnable.class))).thenReturn(result);
        when(leaseService.complete(lease, result)).thenReturn(true);

        consumer.processTask(legacyTask());

        verify(documentService).clearIndexedData("md5");
        verify(documentService, never()).clearBuildData(anyString());
        verify(parseService).parseAndSave(eq("md5"), any(), eq("1"), eq("TEAM"), eq(false));
        verify(vectorizationService).vectorizeWithUsage(eq("md5"), eq("1"), eq("TEAM"), eq(false), eq("1"),
                any(Runnable.class));
        verifyNoInteractions(buildService);
        verify(eventPublisher).publishEvent(new DocumentVectorizedEvent("md5"));
    }

    private FileProcessingTask buildTask() {
        return new FileProcessingTask("md5", "https://expired.example.com/merged/md5", "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1",
                "d1", "v1", "b1", FileProcessingTask.BUILD_KIND_NEW_VERSION, 2, 3,
                "REGULATION", "全国", "国家金融监督管理总局", "2026-10-01");
    }

    @Test
    void oldTaskJsonWithoutComplianceFieldsDeserializesWithEmptyMetadata() throws Exception {
        String json = "{\"fileMd5\":\"md5\",\"filePath\":null,\"fileName\":\"a.pdf\",\"userId\":\"1\","
                + "\"orgTag\":\"TEAM\",\"taskType\":\"UPLOAD_PROCESS\",\"requesterId\":\"1\","
                + "\"documentId\":\"d1\",\"versionId\":\"v1\",\"buildId\":\"b1\",\"buildKind\":\"NEW_VERSION\","
                + "\"versionNo\":2,\"buildNo\":3,\"public\":false}";

        FileProcessingTask task = new ObjectMapper().readValue(json, FileProcessingTask.class);

        assertNull(task.getDocType());
        assertNull(task.getJurisdiction());
        assertNull(task.getIssuer());
        assertNull(task.getEffectiveAt());
        assertTrue(task.complianceMetadata().isEmpty());
        assertEquals("b1", task.getBuildId());
    }

    private FileProcessingTask legacyTask() {
        return new FileProcessingTask("md5", "https://expired.example.com/merged/md5", "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1");
    }
}
