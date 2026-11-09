package com.yizhaoqi.smartpai.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.FileProcessingDeadLetter;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.FileProcessingDeadLetterRepository;
import com.yizhaoqi.smartpai.service.document.DocumentBuildService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 死信主题消费者：重试耗尽的文件处理任务落库并告警，供排查和人工重放。
 * 按死信记录在主题中的位置去重，重复消费不会重复落库，也不会重复改构建状态；
 * 新落库的带构建任务会把对应构建标 FAILED，避免重试耗尽后构建一直停在 PROCESSING
 */
@Component
public class FileProcessingDeadLetterConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingDeadLetterConsumer.class);

    private final FileProcessingDeadLetterRepository deadLetterRepository;
    private final ObjectMapper objectMapper;
    private final DocumentBuildService buildService;

    public FileProcessingDeadLetterConsumer(FileProcessingDeadLetterRepository deadLetterRepository,
                                            ObjectMapper objectMapper,
                                            DocumentBuildService buildService) {
        this.deadLetterRepository = deadLetterRepository;
        this.objectMapper = objectMapper;
        this.buildService = buildService;
    }

    @KafkaListener(id = "file-processing-dlt-listener",
            topics = "#{kafkaConfig.getFileProcessingDltTopic()}",
            groupId = "#{kafkaConfig.getDeadLetterGroupId()}",
            containerFactory = "deadLetterListenerContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, Object> record) {
        if (deadLetterRepository.existsBySourceTopicAndSourcePartitionAndSourceOffset(
                record.topic(), record.partition(), record.offset())) {
            return;
        }

        FileProcessingDeadLetter deadLetter = new FileProcessingDeadLetter();
        deadLetter.setSourceTopic(record.topic());
        deadLetter.setSourcePartition(record.partition());
        deadLetter.setSourceOffset(record.offset());
        deadLetter.setErrorMessage(truncate(headerValue(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)));
        Object value = record.value();
        if (value instanceof FileProcessingTask task) {
            deadLetter.setFileMd5(task.getFileMd5());
            deadLetter.setTaskType(task.getTaskType());
        }
        deadLetter.setPayload(serialize(value));

        try {
            deadLetterRepository.save(deadLetter);
        } catch (DataIntegrityViolationException duplicate) {
            return;
        }
        // 只有真正落库的新死信才代表重试耗尽：把带构建的任务对应构建标 FAILED（状态机守卫拒绝 ACTIVE/SUPERSEDED）
        if (value instanceof FileProcessingTask task) {
            markBuildFailed(task, deadLetter.getErrorMessage());
        }
        logger.error("[ALERT] 文件处理任务重试耗尽进入死信队列: fileMd5={}, taskType={}, dltOffset={}-{}, error={}",
                deadLetter.getFileMd5(), deadLetter.getTaskType(), record.partition(), record.offset(), deadLetter.getErrorMessage());
    }

    /**
     * 重试已耗尽，构建只能标 FAILED；空白 buildId 按 legacy 任务处理，不碰构建状态。
     * 标记失败只记日志，不能影响死信落库结果
     */
    private void markBuildFailed(FileProcessingTask task, String dltError) {
        String buildId = task.getBuildId();
        if (buildId == null || buildId.isBlank()) {
            return;
        }
        String error = dltError == null || dltError.isBlank()
                ? "文件处理重试耗尽，已进入死信队列"
                : truncate("文件处理重试耗尽，已进入死信队列: " + dltError);
        try {
            if (!buildService.markFailed(buildId, error)) {
                logger.warn("构建当前状态不允许标记失败（可能已切换或取消），跳过: buildId={}, fileMd5={}",
                        buildId, task.getFileMd5());
            }
        } catch (Exception e) {
            logger.warn("标记构建失败时出错，忽略: buildId={}, error={}", buildId, e.getMessage());
        }
    }

    private String headerValue(ConsumerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
