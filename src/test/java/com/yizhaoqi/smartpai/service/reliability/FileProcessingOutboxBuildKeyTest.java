package com.yizhaoqi.smartpai.service.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileProcessingOutbox;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileProcessingOutboxRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 4：outbox 的 Kafka 消息 key 必须按 build 隔离，同一版本内多次构建按 buildNo 顺序处理；
 * 旧任务没有 buildId 时继续用 fileMd5。
 */
class FileProcessingOutboxBuildKeyTest {

    private final FileProcessingOutboxRepository outboxRepository = mock(FileProcessingOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaConfig kafkaConfig = mock(KafkaConfig.class);
    private final FileProcessingOutboxService service = new FileProcessingOutboxService(
            outboxRepository, mock(FileUploadRepository.class), kafkaTemplate, kafkaConfig,
            new ObjectMapper(), new FileProcessingReliabilityProperties(), Runnable::run,
            mock(DocumentStatusNotifier.class));

    @Test
    void buildTaskUsesBuildIdAsMessageKey() {
        assertEquals("b1", service.messageKey(buildTask()));
    }

    @Test
    void legacyTaskFallsBackToFileMd5() {
        assertEquals("md5", service.messageKey(legacyTask()));
    }

    @Test
    void blankBuildIdFallsBackToFileMd5() {
        FileProcessingTask task = buildTask();
        task.setBuildId("  ");

        assertEquals("md5", service.messageKey(task));
    }

    @Test
    void legacyPayloadWithoutBuildFieldsDeserializesWithNulls() throws Exception {
        String legacyJson = "{\"fileMd5\":\"md5\",\"filePath\":null,\"fileName\":\"a.pdf\",\"userId\":\"1\","
                + "\"orgTag\":\"TEAM\",\"public\":false,\"taskType\":\"UPLOAD_PROCESS\",\"requesterId\":\"1\"}";

        FileProcessingTask task = new ObjectMapper().readValue(legacyJson, FileProcessingTask.class);

        assertEquals("md5", task.getFileMd5());
        assertEquals(null, task.getDocumentId());
        assertEquals(null, task.getVersionId());
        assertEquals(null, task.getBuildId());
        assertEquals(null, task.getBuildKind());
        assertEquals(null, task.getVersionNo());
        assertEquals(null, task.getBuildNo());
        assertEquals("md5", service.messageKey(task));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatchSendsBuildTaskWithBuildIdKey() throws Exception {
        FileProcessingOutbox outbox = pending(buildTask());
        when(outboxRepository.claim(eq(1L), eq(outbox.getNextAttemptAt()), any())).thenReturn(1);
        when(kafkaConfig.getFileProcessingTopic()).thenReturn("file-topic");
        AtomicReference<String> sentKey = new AtomicReference<>();
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaTemplate.OperationsCallback callback = invocation.getArgument(0);
            KafkaOperations operations = mock(KafkaOperations.class);
            when(operations.send(eq("file-topic"), any(), any())).thenAnswer(send -> {
                sentKey.set((String) send.getArgument(1));
                CompletableFuture<Object> future = CompletableFuture.completedFuture(null);
                return future;
            });
            return callback.doInOperations(operations);
        });

        assertTrue(service.dispatch(outbox));
        assertEquals("b1", sentKey.get());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatchSendsLegacyTaskWithFileMd5Key() throws Exception {
        FileProcessingOutbox outbox = pending(legacyTask());
        when(outboxRepository.claim(eq(1L), eq(outbox.getNextAttemptAt()), any())).thenReturn(1);
        when(kafkaConfig.getFileProcessingTopic()).thenReturn("file-topic");
        AtomicReference<String> sentKey = new AtomicReference<>();
        when(kafkaTemplate.executeInTransaction(any())).thenAnswer(invocation -> {
            KafkaTemplate.OperationsCallback callback = invocation.getArgument(0);
            KafkaOperations operations = mock(KafkaOperations.class);
            when(operations.send(eq("file-topic"), any(), any())).thenAnswer(send -> {
                sentKey.set((String) send.getArgument(1));
                CompletableFuture<Object> future = CompletableFuture.completedFuture(null);
                return future;
            });
            return callback.doInOperations(operations);
        });

        assertTrue(service.dispatch(outbox));
        assertEquals("md5", sentKey.get());
    }

    private FileProcessingOutbox pending(FileProcessingTask task) throws Exception {
        FileProcessingOutbox outbox = new FileProcessingOutbox();
        outbox.setId(1L);
        outbox.setFileMd5(task.getFileMd5());
        outbox.setTaskType(FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS);
        outbox.setPayload(new ObjectMapper().writeValueAsString(task));
        outbox.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        return outbox;
    }

    private static FileProcessingTask buildTask() {
        return new FileProcessingTask("md5", null, "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1",
                "d1", "v1", "b1", FileProcessingTask.BUILD_KIND_NEW_VERSION, 2, 3);
    }

    private static FileProcessingTask legacyTask() {
        return new FileProcessingTask("md5", null, "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1");
    }
}
