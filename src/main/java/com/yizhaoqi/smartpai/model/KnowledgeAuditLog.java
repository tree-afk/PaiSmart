package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 轻量角色/文件发布操作记录，不引入通用审计平台。
 */
@Data
@Entity
@Table(name = "knowledge_audit_log", indexes = {
        @Index(name = "idx_knowledge_audit_target", columnList = "target_type,target_id"),
        @Index(name = "idx_knowledge_audit_created", columnList = "created_at")
})
public class KnowledgeAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false, length = 64)
    private String actorUserId;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 64)
    private String targetId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "before_value", length = 255)
    private String beforeValue;

    @Column(name = "after_value", length = 255)
    private String afterValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
