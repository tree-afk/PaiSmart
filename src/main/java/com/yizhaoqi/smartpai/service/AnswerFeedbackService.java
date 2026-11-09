package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.RagEvalProperties;
import com.yizhaoqi.smartpai.model.AnswerFeedback;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.repository.AnswerFeedbackRepository;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
/**
 * 回答反馈的持久化与 badcase 回流：
 * 反馈先落 MySQL（快照问题/回答），管理员复核后补 gold 并转成 JSONL 评测用例，
 * 追加到 eval.dataset-dir，下一次评测即可回归。
 */
@Service
public class AnswerFeedbackService {

    private static final Logger logger = LoggerFactory.getLogger(AnswerFeedbackService.class);
    private static final Set<String> ALL_STATUSES = Set.of(
            AnswerFeedback.STATUS_NEW, AnswerFeedback.STATUS_REVIEWED,
            AnswerFeedback.STATUS_CONVERTED, AnswerFeedback.STATUS_IGNORED);
    private static final Set<String> MANUAL_STATUSES = Set.of(
            AnswerFeedback.STATUS_NEW, AnswerFeedback.STATUS_REVIEWED, AnswerFeedback.STATUS_IGNORED);
    private static final int MAX_PAGE_SIZE = 100;

    private final AnswerFeedbackRepository feedbackRepository;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;
    private final Path datasetDir;

    public AnswerFeedbackService(AnswerFeedbackRepository feedbackRepository,
                                 ConversationRepository conversationRepository,
                                 ObjectMapper objectMapper,
                                 RagEvalProperties evalProperties) {
        this.feedbackRepository = feedbackRepository;
        this.conversationRepository = conversationRepository;
        this.objectMapper = objectMapper;
        this.datasetDir = Paths.get(evalProperties.getDatasetDir()).toAbsolutePath().normalize();
    }

    public AnswerFeedback record(String userId, String conversationId, String generationId, String rating, String reason) {
        AnswerFeedback feedback = new AnswerFeedback();
        feedback.setUserId(userId);
        feedback.setConversationId(blankToNull(conversationId));
        feedback.setGenerationId(blankToNull(generationId));
        feedback.setRating(normalizeRating(rating));
        feedback.setReason(blankToNull(reason));
        feedback.setStatus(AnswerFeedback.STATUS_NEW);
        if (feedback.getConversationId() != null) {
            try {
                Conversation conversation = conversationRepository
                        .findFirstByConversationIdOrderByTimestampDesc(feedback.getConversationId());
                if (conversation != null) {
                    feedback.setQuestion(conversation.getQuestion());
                    feedback.setAnswer(conversation.getAnswer());
                }
            } catch (Exception e) {
                // 反馈本身不能因为关联会话失败而丢失，管理员可以按 conversationId 人工找回
                logger.warn("反馈关联会话失败: conversationId={}, error={}",
                        feedback.getConversationId(), e.getMessage());
            }
        }
        return feedbackRepository.save(feedback);
    }

    public Page<AnswerFeedback> list(String status, int page, int size) {
        int normalizedPage = Math.max(page, 1) - 1;
        int normalizedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(normalizedPage, normalizedSize);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus == null || "ALL".equalsIgnoreCase(normalizedStatus)) {
            return feedbackRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return feedbackRepository.findByStatusOrderByCreatedAtDesc(normalizeStatus(normalizedStatus), pageable);
    }

    public AnswerFeedback updateStatus(Long id, String status, String note, String reviewer) {
        AnswerFeedback feedback = require(id);
        feedback.setStatus(normalizeManualStatus(status));
        feedback.setNote(blankToNull(note));
        feedback.setReviewer(blankToNull(reviewer));
        feedback.setReviewedAt(LocalDateTime.now());
        return feedbackRepository.save(feedback);
    }

    /**
     * @param referenceAnswer 人工补的参考答案；没有它不能转成评测用例
     * @param expectedFileMd5s / expectedFileNames 至少给一个才会计算检索指标
     * @param expectedEvidence 可选，分块级金标短语
     */
    public ConvertResult convert(Long id,
                                 String referenceAnswer,
                                 List<String> expectedFileMd5s,
                                 List<String> expectedFileNames,
                                 List<String> expectedEvidence,
                                 String reviewer) {
        AnswerFeedback feedback = require(id);
        if (feedback.getQuestion() == null || feedback.getQuestion().isBlank()) {
            throw new IllegalArgumentException("反馈没有关联到问题，不能转成评测用例");
        }
        if (referenceAnswer == null || referenceAnswer.isBlank()) {
            throw new IllegalArgumentException("referenceAnswer 不能为空");
        }

        Map<String, Object> evalCase = new LinkedHashMap<>();
        evalCase.put("id", "badcase-" + feedback.getId());
        evalCase.put("question", feedback.getQuestion());
        evalCase.put("referenceAnswer", referenceAnswer.trim());
        putIfNotEmpty(evalCase, "expectedFileMd5s", expectedFileMd5s);
        putIfNotEmpty(evalCase, "expectedFileNames", expectedFileNames);
        putIfNotEmpty(evalCase, "expectedEvidence", expectedEvidence);

        String json;
        try {
            json = objectMapper.writeValueAsString(evalCase);
        } catch (Exception e) {
            throw new IllegalStateException("序列化评测用例失败: " + e.getMessage(), e);
        }

        Path target = datasetDir.resolve("badcase-" + LocalDate.now() + ".jsonl");
        try {
            Files.createDirectories(datasetDir);
            Files.writeString(target, json + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("写入评测集失败: " + e.getMessage(), e);
        }

        feedback.setEvalCaseJson(json);
        feedback.setStatus(AnswerFeedback.STATUS_CONVERTED);
        feedback.setReviewer(blankToNull(reviewer));
        feedback.setReviewedAt(LocalDateTime.now());
        feedbackRepository.save(feedback);
        logger.info("反馈已转为评测用例: feedbackId={}, file={}", feedback.getId(), target);
        return new ConvertResult(feedback.getId(), target.toString(), json);
    }

    /**
     * 导出已转换的评测用例；默认只导出 CONVERTED，避免把没补 gold 的反馈混进评测集
     */
    public String export(String status) {
        String normalized = blankToNull(status) == null
                ? AnswerFeedback.STATUS_CONVERTED
                : normalizeStatus(status);
        List<AnswerFeedback> feedbacks = feedbackRepository.findByStatusOrderByCreatedAtAsc(normalized);
        List<String> lines = feedbacks.stream()
                .map(AnswerFeedback::getEvalCaseJson)
                .filter(json -> json != null && !json.isBlank())
                .toList();
        return lines.isEmpty() ? "" : String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    public AnswerFeedback require(Long id) {
        return feedbackRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("反馈不存在: " + id));
    }

    private static void putIfNotEmpty(Map<String, Object> target, String key, List<String> values) {
        if (values != null) {
            List<String> cleaned = values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            if (!cleaned.isEmpty()) {
                target.put(key, cleaned);
            }
        }
    }

    private static String normalizeRating(String rating) {
        String normalized = rating == null ? "" : rating.trim().toUpperCase(Locale.ROOT);
        if ("GOOD".equals(normalized) || "BAD".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("rating 只允许 good 或 bad");
    }

    private static String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALL_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status 只允许 NEW / REVIEWED / CONVERTED / IGNORED");
        }
        return normalized;
    }

    private static String normalizeManualStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!MANUAL_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("人工状态只允许 NEW / REVIEWED / IGNORED；转评测集请用 convert");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ConvertResult(Long feedbackId, String file, String json) {
    }
}
