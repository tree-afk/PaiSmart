package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_document", indexes = {
        @Index(name = "idx_business_document_owner", columnList = "owner_user_id,deleted_at"),
        @Index(name = "idx_business_document_active_build", columnList = "active_build_id")
})
public class BusinessDocument {
    @Id
    @Column(name = "document_id", length = 36)
    private String documentId;

    @Column(name = "owner_user_id", nullable = false, length = 64)
    private String ownerUserId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "active_version_id", length = 36)
    private String activeVersionId;

    @Column(name = "active_build_id", length = 36)
    private String activeBuildId;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 16)
    private PublicationStatus publicationStatus = PublicationStatus.DRAFT;

    @Column(name = "metadata_revision_pending", nullable = false)
    private boolean metadataRevisionPending = false;

    @Column(name = "metadata_revision_build_id", length = 36)
    private String metadataRevisionBuildId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
