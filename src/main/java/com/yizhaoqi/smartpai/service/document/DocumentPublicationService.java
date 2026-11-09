package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.ComplianceValidityStatus;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.service.KnowledgeAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 发布状态操作：下架和恢复只改业务发布状态，不删除原文/版本/索引。
 */
@Service
public class DocumentPublicationService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentPublicationService.class);

    private final BusinessDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentBuildRepository buildRepository;
    private final DocumentBuildService buildService;
    private final KnowledgeAuditService auditService;

    public DocumentPublicationService(BusinessDocumentRepository documentRepository,
                                      DocumentVersionRepository versionRepository,
                                      DocumentBuildRepository buildRepository,
                                      DocumentBuildService buildService,
                                      KnowledgeAuditService auditService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.buildRepository = buildRepository;
        this.buildService = buildService;
        this.auditService = auditService;
    }

    @Transactional
    public PublicationStatus withdraw(String documentId, String requesterId) {
        BusinessDocument document = lockAlive(documentId);
        PublicationStatus before = document.getPublicationStatus();
        document.setPublicationStatus(PublicationStatus.WITHDRAWN);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
        buildService.invalidateScopeAfterCommit();
        auditService.record(requesterId, "DOCUMENT", documentId, "WITHDRAW", before.name(),
                PublicationStatus.WITHDRAWN.name());
        logger.info("文档已下架: documentId={}, requesterId={}", documentId, requesterId);
        return PublicationStatus.WITHDRAWN;
    }

    @Transactional
    public PublicationStatus restore(String documentId, String requesterId) {
        BusinessDocument document = lockAlive(documentId);
        if (document.getPublicationStatus() == PublicationStatus.PUBLISHED) {
            return PublicationStatus.PUBLISHED;
        }
        if (document.isMetadataRevisionPending() || document.getMetadataRevisionBuildId() != null) {
            // 修订失败时 pending 仍然保留；即使数据处于不一致状态，只要还登记着修订构建，
            // 就不能把修订前的旧 active 索引重新发布出去。
            throw new CustomException("元数据修订尚未完成，不能恢复发布", HttpStatus.CONFLICT);
        }

        DocumentBuild build = resolveCandidateBuild(document)
                .orElseThrow(() -> new CustomException("没有可用构建，不能恢复发布", HttpStatus.CONFLICT));
        if (build.getStatus() != com.yizhaoqi.smartpai.model.DocumentBuildStatus.ACTIVE) {
            throw new CustomException("当前构建尚未激活，不能恢复发布", HttpStatus.CONFLICT);
        }
        DocumentVersion version = versionRepository.findById(build.getVersionId())
                .orElseThrow(() -> new CustomException("构建对应内容版本不存在", HttpStatus.CONFLICT));
        ComplianceValidityStatus validity = ComplianceValidityStatus.resolve(
                version.getEffectiveAt(), version.getExpiresAt(), version.isRepealed(), LocalDate.now());
        if (validity == ComplianceValidityStatus.PENDING) {
            throw new CustomException("版本尚未到生效日期，不能恢复发布", HttpStatus.CONFLICT);
        }
        if (validity == ComplianceValidityStatus.EXPIRED || validity == ComplianceValidityStatus.REPEALED) {
            throw new CustomException("版本已失效或已废止，不能恢复发布", HttpStatus.CONFLICT);
        }

        PublicationStatus before = document.getPublicationStatus();
        document.setPublicationStatus(PublicationStatus.PUBLISHED);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
        buildService.invalidateScopeAfterCommit();
        auditService.record(requesterId, "DOCUMENT", documentId, "RESTORE", before.name(),
                PublicationStatus.PUBLISHED.name());
        logger.info("文档已恢复发布: documentId={}, buildId={}, requesterId={}",
                documentId, build.getBuildId(), requesterId);
        return PublicationStatus.PUBLISHED;
    }

    private Optional<DocumentBuild> resolveCandidateBuild(BusinessDocument document) {
        if (document.getActiveBuildId() != null && !document.getActiveBuildId().isBlank()) {
            return buildRepository.findById(document.getActiveBuildId());
        }
        return versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId()).stream()
                .findFirst()
                .flatMap(version -> buildRepository.findByVersionIdOrderByBuildNoDesc(version.getVersionId())
                        .stream().findFirst());
    }

    private BusinessDocument lockAlive(String documentId) {
        BusinessDocument document = documentRepository.findForUpdate(documentId)
                .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
        if (document.getDeletedAt() != null) {
            throw new CustomException("文档已删除", HttpStatus.NOT_FOUND);
        }
        return document;
    }
}
