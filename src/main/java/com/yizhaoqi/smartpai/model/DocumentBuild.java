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
