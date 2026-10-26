package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "document_version",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_document_version_no", columnNames = {"document_id", "version_no"}),
                @UniqueConstraint(name = "uk_document_version_md5", columnNames = {"document_id", "file_md5"}),
                // 一个上传记录只能登记到一个内容版本，legacy 懒迁移据此保持幂等
                @UniqueConstraint(name = "uk_document_version_upload", columnNames = {"file_upload_id"})
        },
        indexes = {
                @Index(name = "idx_document_version_md5", columnList = "file_md5"),
                @Index(name = "idx_document_version_retired", columnList = "legacy_retired")
        })
public class DocumentVersion {
    @Id
    @Column(name = "version_id", length = 36)
    private String versionId;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "file_upload_id", nullable = false)
    private Long fileUploadId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "org_tag", length = 50)
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    // 合规文档元数据；旧版本与未按合规流程上传的文档保持为空
    @Column(name = "doc_type", length = 32)
    private String docType;

    @Column(name = "jurisdiction", length = 100)
    private String jurisdiction;

    @Column(name = "jurisdiction_code", length = 64)
    private String jurisdictionCode;

    @Column(name = "issuer", length = 255)
    private String issuer;

    // 资料适用于哪些机构/主体；与 issuer（谁发布）分开
    @Column(name = "applicable_institutions", length = 512)
    private String applicableInstitutions;

    @Column(name = "effective_at")
    private LocalDate effectiveAt;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "repealed", nullable = false)
    private boolean repealed = false;

    @Column(name = "legacy_representation", nullable = false)
    private boolean legacyRepresentation = false;

    @Column(name = "legacy_retired", nullable = false)
    private boolean legacyRetired = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
