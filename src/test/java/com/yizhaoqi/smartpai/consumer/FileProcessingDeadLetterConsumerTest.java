package com.yizhaoqi.smartpai.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileProcessingDeadLetterRepository;
import com.yizhaoqi.smartpai.service.document.DocumentBuildService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 死信消费者：新死信落库后把带构建的任务对应构建标 FAILED；
 * 重复死信（已存在或并发写入冲突）不再改构建；legacy 任务保持原行为。
 */
class FileProcessingDeadLetterConsumerTest {

    private static final String DLT_TOPIC = "file-processing-dlt";

    private final FileProcessingDeadLetterRepository deadLetterRepository = mock(FileProcessingDeadLetterRepository.class);
    private final DocumentBuildService buildService = mock(DocumentBuildService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FileProcessingDeadLetterConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FileProcessingDeadLetterConsumer(deadLetterRepository, objectMapper, buildService);
        when(deadLetterRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void newDeadLetterMarksBuildFailedWithDltError() {
        consumer.onDeadLetter(record(5L, buildTask("b1"), "向量化多次超时"));

        verify(deadLetterRepository).save(any());
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(buildService).markFailed(eq("b1"), error.capture());
        assertTrue(error.getValue().contains("向量化多次超时"));
        assertTrue(error.getValue().contains("重试耗尽"));
    }

    @Test
    void duplicateDeadLetterDoesNotMarkBuildFailed() {
        when(deadLetterRepository.existsBySourceTopicAndSourcePartitionAndSourceOffset(DLT_TOPIC, 0, 5L))
                .thenReturn(true);

        consumer.onDeadLetter(record(5L, buildTask("b1"), "向量化多次超时"));

        verify(deadLetterRepository, never()).save(any());
        verifyNoInteractions(buildService);
    }

    @Test
    void concurrentDuplicateInsertDoesNotMarkBuildFailed() {
        when(deadLetterRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_dead_letter_source"));

        consumer.onDeadLetter(record(5L, buildTask("b1"), "向量化多次超时"));

        verifyNoInteractions(buildService);
    }

    @Test
    void legacyTaskWithoutBuildIsRecordedWithoutMarkingBuild() {
        consumer.onDeadLetter(record(5L, legacyTask(), "解析失败"));

        verify(deadLetterRepository).save(any());
        verifyNoInteractions(buildService);
    }

    @Test
    void blankBuildIdIsTreatedAsLegacyTask() {
        consumer.onDeadLetter(record(5L, buildTask("  "), "解析失败"));

        verify(deadLetterRepository).save(any());
        verifyNoInteractions(buildService);
    }

    @Test
    void markFailedTransitionGuardRejectionDoesNotBreakDeadLetterConsumption() {
        when(buildService.markFailed(eq("b1"), anyString())).thenReturn(false);

        consumer.onDeadLetter(record(5L, buildTask("b1"), "向量化多次超时"));

        verify(deadLetterRepository).save(any());
        verify(buildService).markFailed(eq("b1"), anyString());
    }

    @Test
    void longDltErrorIsTruncatedInBuildError() {
        String longError = "超时".repeat(800);

        consumer.onDeadLetter(record(5L, buildTask("b1"), longError));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(buildService).markFailed(eq("b1"), error.capture());
        assertTrue(error.getValue().length() <= 1000);
        assertTrue(error.getValue().contains("重试耗尽"));
    }

    private ConsumerRecord<String, Object> record(long offset, FileProcessingTask task, String dltError) {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(DLT_TOPIC, 0, offset, null, task);
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, dltError.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private FileProcessingTask buildTask(String buildId) {
        return new FileProcessingTask("md5", null, "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1",
                "d1", "v1", buildId, FileProcessingTask.BUILD_KIND_NEW_VERSION, 2, 3);
    }

    private FileProcessingTask legacyTask() {
        return new FileProcessingTask("md5", null, "a.pdf", "1", "TEAM", false,
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS, "1");
    }
}
