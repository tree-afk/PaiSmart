package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.ComplianceValidityStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.service.KnowledgePermissionService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 引用原文/预览的读取条件：已登录用户只能读取共享库中已发布、有 active build 且日期可检索的版本；
 * 文件管理者为了管理可以读取未发布版本。
 */
@Service
public class DocumentAccessService {

    private final DocumentVersionRepository versionRepository;
    private final BusinessDocumentRepository documentRepository;
    private final KnowledgePermissionService permissionService;

    public DocumentAccessService(DocumentVersionRepository versionRepository,
                                 BusinessDocumentRepository documentRepository,
                                 KnowledgePermissionService permissionService) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.permissionService = permissionService;
    }

    public boolean canRead(String requesterId, String fileMd5) {
        if (requesterId == null || requesterId.isBlank() || fileMd5 == null || fileMd5.isBlank()) {
            return false;
        }
        if (permissionService.isFileManager(requesterId)) {
            return true;
        }
        // 同一 MD5 可能出现在多个业务文档中：任一副本仍是已发布 active 版本即可读原文。
        return versionRepository.findByFileMd5OrderByCreatedAtDesc(fileMd5).stream()
                .anyMatch(this::isReadableActiveVersion);
    }

    /**
     * 引用详情携带精确 documentId/versionId 时，只认这条引用对应的版本；
     * 不能因为同一 MD5 在别处发布了就放行这条未激活引用。
     */
    public boolean canRead(String requesterId, String documentId, String versionId) {
        if (requesterId == null || requesterId.isBlank()
                || documentId == null || documentId.isBlank()
                || versionId == null || versionId.isBlank()) {
            return false;
        }
        if (permissionService.isFileManager(requesterId)) {
            return true;
        }
        return versionRepository.findById(versionId)
                .filter(version -> documentId.equals(version.getDocumentId()))
                .map(this::isReadableActiveVersion)
                .orElse(false);
    }

    private boolean isReadableActiveVersion(DocumentVersion version) {
        Optional<BusinessDocument> document =
                documentRepository.findById(version.getDocumentId());
        if (document.isEmpty()
                || document.get().getDeletedAt() != null
                || document.get().getPublicationStatus() != PublicationStatus.PUBLISHED
                || document.get().getActiveBuildId() == null
                || !version.getVersionId().equals(document.get().getActiveVersionId())) {
            // 文档已发布不代表任意版本可读：新版本构建完成并切换前，阅读者只能读取 active 版本
            return false;
        }
        ComplianceValidityStatus validity = ComplianceValidityStatus.resolve(
                version.getEffectiveAt(), version.getExpiresAt(),
                version.isRepealed(), LocalDate.now());
        return validity == ComplianceValidityStatus.EFFECTIVE
                || validity == ComplianceValidityStatus.UNCONFIRMED;
    }
}
