package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户对某次回答的反馈，badcase 回流的入口。
 * 提交时尽量从会话记录里快照问题和回答，避免生成状态清理后无法还原上下文。
 */
@Data
@Entity
@Table(name = "answer_feedback", indexes = {
        @Index(name = "idx_answer_feedback_status", columnList = "status, created_at"),
        @Index(name = "idx_answer_feedback_conversation", columnList = "conversation_id")
})
public class AnswerFeedback {

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_CONVERTED = "CONVERTED";
    public static final String STATUS_IGNORED = "IGNORED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "generation_id", length = 64)
    private String generationId;

    /** GOOD / BAD */
    @Column(nullable = false, length = 16)
    private String rating;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "LONGTEXT")
    private String question;

    @Column(columnDefinition = "LONGTEXT")
    private String answer;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 64)
    private String reviewer;

    /** 转成评测用例后保存的 JSONL 行，导出时直接复用 */
    @Column(name = "eval_case_json", columnDefinition = "LONGTEXT")
    private String evalCaseJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null || status.isBlank()) {
            status = STATUS_NEW;
        }
    }
}
