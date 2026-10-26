package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.ComplianceMetadataPatch;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.reliability.FileProcessingOutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 业务文档 / 内容版本 / 索引构建的创建入口。
 * <p>
 * 上传合并后由 {@link #createAndEnqueue} 在同一事务内完成“选目标 → 建文档/版本/构建 → 写 outbox”；
 * 首次上传建 document + version1 + build1；新版本在行锁内分配 version_no，权限继承当前 active 版本；
 * legacy 旧数据只登记 version1 的 legacy 表示（不复制 ES 数据，active_build_id 保持为空，且每个上传记录只会登记一次）；
 * reindex/retry 对文档当前 active 版本或最新版本追加 build_no，不改变 version_no。
 * 新构建统一以 PENDING 状态落库，何时参与检索由 {@link DocumentBuildService#activate(String)} 决定。
 */
@Service
public class DocumentVersionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentVersionService.class);

    public static final String DUPLICATE_CONTENT_MESSAGE = "内容与现有版本相同，未创建新版本";
    public static final String SHARED_MD5_MESSAGE = "该文件内容与多个用户共享，无法自动版本化，请另存为新文档";
    private static final String NOT_ACTIVE_VERSION_MESSAGE = "该文件既不是当前版本也不是最新版本，无法重建索引";

    private final BusinessDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final DocumentBuildRepository buildRepository;
    private final FileUploadRepository fileUploadRepository;
    private final UserRepository userRepository;
    private final DocumentBuildService buildService;
    private final FileProcessingOutboxService fileProcessingOutboxService;

    public DocumentVersionService(BusinessDocumentRepository documentRepository,
                                  DocumentVersionRepository versionRepository,
                                  DocumentBuildRepository buildRepository,
                                  FileUploadRepository fileUploadRepository,
                                  UserRepository userRepository,
                                  DocumentBuildService buildService,
                                  FileProcessingOutboxService fileProcessingOutboxService) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.buildRepository = buildRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.userRepository = userRepository;
        this.buildService = buildService;
        this.fileProcessingOutboxService = fileProcessingOutboxService;
    }

    /**
     * 合并完成后的唯一入口：解析目标（documentId → 新版本；legacyFileMd5 → 幂等 legacy 登记 + 新版本；
     * 都不带 → 首个文档），创建文档/版本/构建，并在同一事务内写 outbox。
     * outbox 写入失败会连同版本/构建一起回滚，不会留下没有任务的待处理构建。
     * <p>
     * 入口先对 file_upload 行加悲观写锁再查已有版本：同一上传的并发恢复/重复合并会被串行化，
     * 后到者在锁内看到已提交的版本后直接返回，不会再建第二个文档、版本或 outbox 任务。
     * file_upload_id 唯一键保留为兜底，不靠捕获约束冲突继续事务。
     */
    @Transactional
    public VersionBuild createAndEnqueue(FileUpload fileUpload, String userId, String filePath,
                                         String documentId, String legacyFileMd5) {
        return createAndEnqueue(fileUpload, userId, filePath, documentId, legacyFileMd5, ComplianceMetadata.empty());
    }

    /**
     * 带合规元数据的合并入口；未按合规流程上传的请求传 {@link ComplianceMetadata#empty()}。
     * 首传把元数据写到 version1；新版本只继承未填的分类字段，生效日期未填时按本次上传日期补齐。
     */
    @Transactional
    public VersionBuild createAndEnqueue(FileUpload fileUpload, String userId, String filePath,
                                         String documentId, String legacyFileMd5, ComplianceMetadata compliance) {
        FileUpload lockedUpload = lockOwnedCompletedUpload(fileUpload, userId);

        Optional<DocumentVersion> existingVersion =
                versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(lockedUpload.getId());
        if (existingVersion.isPresent()) {
            DocumentVersion existing = existingVersion.get();
            logger.info("上传记录已关联内容版本，按幂等返回: fileUploadId={}, documentId={}, versionId={}",
                    lockedUpload.getId(), existing.getDocumentId(), existing.getVersionId());
            return toVersionBuild(existing, latestBuild(existing.getVersionId()));
        }

        VersionBuild versionBuild;
        String buildKind;
        if (hasText(documentId)) {
            versionBuild = createNewVersion(documentId, lockedUpload, userId, compliance);
            buildKind = FileProcessingTask.BUILD_KIND_NEW_VERSION;
        } else if (hasText(legacyFileMd5)) {
            FileUpload legacyFileUpload = fileUploadRepository
                    .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(legacyFileMd5, userId)
                    .orElseThrow(() -> new CustomException("旧文档不存在", HttpStatus.NOT_FOUND));
            VersionBuild legacyVersion = promoteLegacy(legacyFileUpload, userId);
            versionBuild = createNewVersion(legacyVersion.documentId(), lockedUpload, userId, compliance);
            buildKind = FileProcessingTask.BUILD_KIND_NEW_VERSION;
        } else {
            versionBuild = createFirstDocument(lockedUpload, userId, compliance);
            buildKind = FileProcessingTask.BUILD_KIND_NEW_DOCUMENT;
        }

        FileProcessingTask task = new FileProcessingTask(
                lockedUpload.getFileMd5(),
                filePath,
                lockedUpload.getFileName(),
                lockedUpload.getUserId(),
                versionBuild.orgTag(),
                versionBuild.isPublic(),
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                userId,
                versionBuild.documentId(),
                versionBuild.versionId(),
                versionBuild.buildId(),
                buildKind,
                versionBuild.versionNo(),
                versionBuild.buildNo(),
                versionBuild.compliance().docType(),
                versionBuild.compliance().jurisdiction(),
                versionBuild.compliance().issuer(),
                versionBuild.compliance().wireEffectiveAt(),
                versionBuild.compliance().applicableInstitutions(),
                versionBuild.compliance().wireExpiresAt(),
                versionBuild.compliance().repealed()
        );
        fileProcessingOutboxService.enqueueVectorization(lockedUpload, task, true);
        logger.info("已创建内容版本并提交处理任务: documentId={}, versionId={}, buildId={}, buildKind={}, fileMd5={}",
                versionBuild.documentId(), versionBuild.versionId(), versionBuild.buildId(), buildKind,
                lockedUpload.getFileMd5());
        return versionBuild;
    }

    /**
     * 合并前的目标校验：在改上传状态、合并分片之前就把归属/软删除/重复内容/共享 md5/旧文件未完成等
     * 拒绝掉，避免合并成功后才创建版本失败，把上传记录留在无法恢复的已完成状态。
     */
    @Transactional(readOnly = true)
    public void validateUploadTarget(FileUpload fileUpload, String userId, String documentId, String legacyFileMd5) {
        if (fileUpload == null || !fileUpload.getUserId().equals(userId)) {
            throw new CustomException("没有权限操作此文件", HttpStatus.FORBIDDEN);
        }
        if (hasText(documentId)) {
            BusinessDocument document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
            if (document.getDeletedAt() != null) {
                throw new CustomException("文档已删除，无法上传新版本", HttpStatus.NOT_FOUND);
            }
            if (!document.getOwnerUserId().equals(userId) && !isFileManager(userId)) {
                throw new CustomException("没有权限为该文档上传新版本", HttpStatus.FORBIDDEN);
            }
            versionRepository.findByDocumentIdAndFileMd5(documentId, fileUpload.getFileMd5())
                    .ifPresent(existing -> {
                        throw new CustomException(DUPLICATE_CONTENT_MESSAGE, HttpStatus.CONFLICT);
                    });
            return;
        }
        if (!hasText(legacyFileMd5)) {
            return;
        }
        if (legacyFileMd5.equals(fileUpload.getFileMd5())) {
            throw new CustomException("新旧文件内容相同，未创建新版本", HttpStatus.CONFLICT);
        }
        FileUpload legacyUpload = fileUploadRepository
                .findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(legacyFileMd5, userId)
                .orElseThrow(() -> new CustomException("旧文档不存在", HttpStatus.NOT_FOUND));
        if (!legacyUpload.getUserId().equals(userId) && !isFileManager(userId)) {
            throw new CustomException("没有权限操作此文件", HttpStatus.FORBIDDEN);
        }
        if (legacyUpload.getStatus() != FileUpload.STATUS_COMPLETED) {
            throw new CustomException("旧文档尚未完成处理，无法创建新版本", HttpStatus.CONFLICT);
        }
        if (fileUploadRepository.countByFileMd5(legacyFileMd5) > 1) {
            throw new CustomException(SHARED_MD5_MESSAGE, HttpStatus.CONFLICT);
        }
        // 旧文件已登记过文档版本时，新文件内容与该文档任一版本相同必须在合并前拒绝，
        // 否则合并成 COMPLETED 后每次恢复都会 409，留下永远无法版本化的上传记录
        versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(legacyUpload.getId())
                .flatMap(legacyVersion -> versionRepository
                        .findByDocumentIdAndFileMd5(legacyVersion.getDocumentId(), fileUpload.getFileMd5()))
                .ifPresent(duplicate -> {
                    throw new CustomException(DUPLICATE_CONTENT_MESSAGE, HttpStatus.CONFLICT);
                });
    }

    /**
     * 首次上传：创建业务文档、version_no=1 的内容版本与 build_no=1 的构建。
     * active 指针保持为空，首个构建切换成功后才可检索；权限取本次上传的文件。
     * 仅供 {@link #createAndEnqueue} 在已开启的事务内调用。
     */
    VersionBuild createFirstDocument(FileUpload fileUpload, String userId) {
        return createFirstDocument(fileUpload, userId, ComplianceMetadata.empty());
    }

    /**
     * 首次上传（带合规元数据）：创建业务文档、version_no=1 的内容版本与 build_no=1 的构建。
     * active 指针保持为空，首个构建切换成功后才可检索；权限取本次上传的文件。
     * 仅供 {@link #createAndEnqueue} 在已开启的事务内调用。
     */
    VersionBuild createFirstDocument(FileUpload fileUpload, String userId, ComplianceMetadata compliance) {
        ComplianceMetadata metadata = compliance == null ? ComplianceMetadata.empty() : compliance;
        LocalDateTime now = LocalDateTime.now();
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setOwnerUserId(userId);
        document.setTitle(fileUpload.getFileName());
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentRepository.save(document);

        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(document.getDocumentId());
        version.setVersionNo(1);
        version.setFileMd5(fileUpload.getFileMd5());
        version.setFileUploadId(fileUpload.getId());
        version.setFileName(fileUpload.getFileName());
        version.setOrgTag(fileUpload.getOrgTag());
        version.setPublic(fileUpload.isPublic());
        version.setDocType(metadata.docType());
        version.setJurisdiction(metadata.jurisdiction());
        version.setJurisdictionCode(metadata.jurisdictionCode());
        version.setIssuer(metadata.issuer());
        version.setApplicableInstitutions(metadata.applicableInstitutions());
        version.setEffectiveAt(metadata.effectiveAt());
        version.setExpiresAt(metadata.expiresAt());
        version.setRepealed(metadata.repealed());
        version.setLegacyRepresentation(false);
        version.setLegacyRetired(false);
        version.setCreatedAt(now);
        versionRepository.save(version);

        DocumentBuild build = buildService.createBuild(version.getVersionId(), 1);
        logger.info("已创建首版业务文档: documentId={}, versionId={}, buildId={}, fileMd5={}",
                document.getDocumentId(), version.getVersionId(), build.getBuildId(), version.getFileMd5());
        return toVersionBuild(version, build);
    }

    /**
     * 上传新版本：锁定业务文档，校验归属与软删除，拒绝与该文档任一历史版本相同的内容；
     * version_no 取当前最大值 +1，权限沿用当前 active 版本，旧 active 指针与构建完全不动。
     */
    VersionBuild createNewVersion(String documentId, FileUpload fileUpload, String userId) {
        return createNewVersion(documentId, fileUpload, userId, ComplianceMetadata.empty());
    }

    /**
     * 上传新版本（带合规元数据）：锁定业务文档，校验归属与软删除，拒绝与该文档任一历史版本相同的内容；
     * version_no 取当前最大值 +1，权限沿用当前 active 版本，旧 active 指针与构建完全不动。
     * 分类字段未填时继承当前 active 版本，生效日期未填时保持为空（效力待确认）。
     */
    VersionBuild createNewVersion(String documentId, FileUpload fileUpload, String userId, ComplianceMetadata compliance) {
        BusinessDocument document = documentRepository.findForUpdate(documentId)
                .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
        if (document.getDeletedAt() != null) {
            throw new CustomException("文档已删除，无法上传新版本", HttpStatus.NOT_FOUND);
        }
        if (!document.getOwnerUserId().equals(userId) && !isFileManager(userId)) {
            throw new CustomException("没有权限为该文档上传新版本", HttpStatus.FORBIDDEN);
        }
        versionRepository.findByDocumentIdAndFileMd5(documentId, fileUpload.getFileMd5())
                .ifPresent(existing -> {
                    throw new CustomException(DUPLICATE_CONTENT_MESSAGE, HttpStatus.CONFLICT);
                });

        DocumentVersion activeVersion = resolveActiveVersion(document);
        int nextVersionNo = nextVersionNo(documentId);
        ComplianceMetadata inherited = (compliance == null ? ComplianceMetadata.empty() : compliance)
                .inheritFrom(complianceOf(activeVersion));

        LocalDateTime now = LocalDateTime.now();
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(documentId);
        version.setVersionNo(nextVersionNo);
        version.setFileMd5(fileUpload.getFileMd5());
        version.setFileUploadId(fileUpload.getId());
        version.setFileName(fileUpload.getFileName());
        version.setOrgTag(activeVersion.getOrgTag());
        version.setPublic(activeVersion.isPublic());
        version.setDocType(inherited.docType());
        version.setJurisdiction(inherited.jurisdiction());
        version.setJurisdictionCode(inherited.jurisdictionCode());
        version.setIssuer(inherited.issuer());
        version.setApplicableInstitutions(inherited.applicableInstitutions());
        version.setEffectiveAt(inherited.effectiveAt());
        version.setExpiresAt(inherited.expiresAt());
        version.setRepealed(inherited.repealed());
        version.setLegacyRepresentation(false);
        version.setLegacyRetired(false);
        version.setCreatedAt(now);
        versionRepository.save(version);

        return createBuildLocked(document, version, userId, FileProcessingTask.BUILD_KIND_NEW_VERSION);
    }

    /**
     * legacy 懒迁移：仅登记旧文件为 version_no=1 的 legacy 表示，active_version_id 指向它，
     * active_build_id 为空且不创建构建；旧 ES 分块继续按 legacy 分支参与检索。
     * <p>
     * 先锁 file_upload 行再查已有版本：同一上传的并发登记被串行化，后到者在锁内看到已提交版本后直接返回，
     * 不会为同一旧文件建第二个业务文档。file_upload_id 唯一键只作为兜底，不再捕获约束冲突继续本事务。
     */
    @Transactional
    public VersionBuild promoteLegacy(FileUpload fileUpload, String userId) {
        FileUpload lockedUpload = lockCompletedUploadForRequester(fileUpload, userId);

        Optional<DocumentVersion> existing =
                versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(lockedUpload.getId());
        if (existing.isPresent()) {
            return toVersionBuild(existing.get(), latestBuild(existing.get().getVersionId()));
        }
        if (fileUploadRepository.countByFileMd5(lockedUpload.getFileMd5()) > 1) {
            throw new CustomException(SHARED_MD5_MESSAGE, HttpStatus.CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setOwnerUserId(lockedUpload.getUserId());
        document.setTitle(lockedUpload.getFileName());
        document.setCreatedAt(now);
        document.setUpdatedAt(now);

        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(document.getDocumentId());
        version.setVersionNo(1);
        version.setFileMd5(lockedUpload.getFileMd5());
        version.setFileUploadId(lockedUpload.getId());
        version.setFileName(lockedUpload.getFileName());
        version.setOrgTag(lockedUpload.getOrgTag());
        version.setPublic(lockedUpload.isPublic());
        version.setLegacyRepresentation(true);
        version.setLegacyRetired(false);
        version.setCreatedAt(now);

        // 行锁内先落版本行，约束冲突（列锁被其他实例绕过时）直接抛出让整个事务回滚
        versionRepository.saveAndFlush(version);

        document.setActiveVersionId(version.getVersionId());
        document.setActiveBuildId(null);
        documentRepository.save(document);

        logger.info("已登记 legacy 旧文档: documentId={}, versionId={}, fileMd5={}",
                document.getDocumentId(), version.getVersionId(), version.getFileMd5());
        return toVersionBuild(version, null);
    }

    /**
     * 对指定内容版本新建构建，允许它是文档的当前 active 版本，或尚未切换的最新版本
     * （v2 构建失败而 v1 仍 active 时，重试 v2 需要在最新版本上新建构建）；更早的历史版本返回明确冲突。
     * 文档所有者与管理员都可以发起。
     */
    @Transactional
    public VersionBuild createBuildForActiveVersion(String versionId, String requesterId, String kind) {
        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new CustomException("内容版本不存在", HttpStatus.NOT_FOUND));
        BusinessDocument document = documentRepository.findForUpdate(version.getDocumentId())
                .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
        requireNotDeleted(document);
        if (!document.getOwnerUserId().equals(requesterId) && !isFileManager(requesterId)) {
            throw new CustomException("没有权限为该文档创建构建", HttpStatus.FORBIDDEN);
        }
        FileUpload upload = requireCompletedUploadForRequester(version.getFileUploadId(), requesterId);
        requireActiveOrLatestVersion(document, version);
        ensureMetadataRevisionRebuildable(document, version);
        VersionBuild versionBuild = createBuildLocked(document, version, requesterId, kind);
        adoptRevisionBuild(document, versionBuild.buildId());
        logger.info("已为当前或最新版本创建构建: fileUploadId={}, buildId={}", upload.getId(), versionBuild.buildId());
        return versionBuild;
    }

    /**
     * 规格 7.3 的显式重建入口：校验文档/版本归属与上传记录后，对请求的内容版本建构建并写 outbox。
     * 不再要求它是 active 版本：任何 owner/admin 可见的版本都可以显式重建，
     * 由切换时的构建新旧比较把迟到的旧构建标 SUPERSEDED。
     */
    @Transactional
    public VersionBuild createBuildAndEnqueueForVersion(String documentId, String versionId, String requesterId) {
        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new CustomException("内容版本不存在", HttpStatus.NOT_FOUND));
        if (!version.getDocumentId().equals(documentId)) {
            throw new CustomException("内容版本不属于该文档", HttpStatus.NOT_FOUND);
        }
        BusinessDocument document = documentRepository.findForUpdate(documentId)
                .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
        requireNotDeleted(document);
        if (!document.getOwnerUserId().equals(requesterId) && !isFileManager(requesterId)) {
            throw new CustomException("没有权限为该文档创建构建", HttpStatus.FORBIDDEN);
        }
        FileUpload upload = requireCompletedUploadForRequester(version.getFileUploadId(), requesterId);
        ensureMetadataRevisionRebuildable(document, version);

        VersionBuild versionBuild = createBuildLocked(document, version, requesterId,
                FileProcessingTask.BUILD_KIND_REINDEX);
        adoptRevisionBuild(document, versionBuild.buildId());
        enqueueVersionTask(upload, documentId, version, versionBuild, requesterId,
                FileProcessingTask.TASK_TYPE_REINDEX, FileProcessingTask.BUILD_KIND_REINDEX);
        logger.info("已提交文档版本重建任务: documentId={}, versionId={}, buildId={}, requesterId={}",
                documentId, versionId, versionBuild.buildId(), requesterId);
        return versionBuild;
    }

    /**
     * 元数据修订：锁业务文档 → 更新版本元数据 → 标记 revision pending/DRAFT → 创建新构建。
     * 旧 active 构建保留，但文档在重建完成前不参与检索；重建成功后由 activate 恢复发布。
     */
    @Transactional
    public VersionBuild updateMetadataAndRebuild(String documentId, ComplianceMetadata compliance, String requesterId) {
        return updateMetadataAndRebuild(documentId, ComplianceMetadataPatch.full(compliance), requesterId);
    }

    @Transactional
    public VersionBuild updateMetadataAndRebuild(String documentId, ComplianceMetadataPatch patch, String requesterId) {
        if (patch == null || patch.isEmpty()) {
            throw new CustomException("没有可修改的资料信息", HttpStatus.BAD_REQUEST);
        }
        BusinessDocument document = documentRepository.findForUpdate(documentId)
                .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
        requireNotDeleted(document);
        ensureMetadataRevisionRetryable(document);
        DocumentVersion version = resolveMetadataVersion(document);
        FileUpload upload = fileUploadRepository.findById(version.getFileUploadId())
                .orElseThrow(() -> new CustomException("文件不存在", HttpStatus.NOT_FOUND));
        requireCompleted(upload);

        ComplianceMetadata merged = patch.applyTo(complianceOf(version));
        if (merged.effectiveAt() != null && merged.expiresAt() != null
                && merged.expiresAt().isBefore(merged.effectiveAt())) {
            throw new CustomException("失效日期不能早于生效日期", HttpStatus.BAD_REQUEST);
        }
        applyCompliance(version, merged);
        versionRepository.save(version);

        if (document.getPublicationStatus() == PublicationStatus.PUBLISHED) {
            document.setPublicationStatus(PublicationStatus.DRAFT);
        }
        document.setMetadataRevisionPending(true);
        documentRepository.save(document);

        VersionBuild versionBuild = createBuildLocked(document, version, requesterId,
                FileProcessingTask.BUILD_KIND_REINDEX);
        document.setMetadataRevisionBuildId(versionBuild.buildId());
        documentRepository.save(document);
        enqueueVersionTask(upload, documentId, version, versionBuild, requesterId,
                FileProcessingTask.TASK_TYPE_REINDEX, FileProcessingTask.BUILD_KIND_REINDEX);
        buildService.invalidateScopeAfterCommit();
        logger.info("已提交元数据修订构建: documentId={}, versionId={}, buildId={}, requesterId={}",
                documentId, version.getVersionId(), versionBuild.buildId(), requesterId);
        return versionBuild;
    }

    /**
     * 修订构建失败/取消后保持 pending，只允许重新提交元数据来生成新的修订构建；
     * 构建仍在进行时拒绝并发修订。pending 但没有登记 buildId 的旧数据也允许重新提交。
     */
    private void ensureMetadataRevisionRetryable(BusinessDocument document) {
        if (!document.isMetadataRevisionPending()) {
            return;
        }
        DocumentBuild revisionBuild = revisionBuild(document);
        if (revisionBuild != null && !isReplaceableRevisionStatus(revisionBuild.getStatus())) {
            throw new CustomException("该文档已有未完成的元数据修订", HttpStatus.CONFLICT);
        }
    }

    /**
     * 重试/重建只允许接替已经失败的修订构建，并且必须还是同一个内容版本；
     * 修订仍在进行时不能并发创建新的构建。
     */
    private void ensureMetadataRevisionRebuildable(BusinessDocument document, DocumentVersion version) {
        if (!document.isMetadataRevisionPending()) {
            return;
        }
        DocumentBuild revisionBuild = revisionBuild(document);
        if (revisionBuild == null) {
            throw new CustomException("元数据修订状态异常，请重新提交资料信息", HttpStatus.CONFLICT);
        }
        if (!isReplaceableRevisionStatus(revisionBuild.getStatus())) {
            throw new CustomException("该文档已有未完成的元数据修订", HttpStatus.CONFLICT);
        }
        if (!revisionBuild.getVersionId().equals(version.getVersionId())) {
            throw new CustomException("请先完成该文档的元数据修订", HttpStatus.CONFLICT);
        }
    }

    private void adoptRevisionBuild(BusinessDocument document, String buildId) {
        if (document.isMetadataRevisionPending()) {
            document.setMetadataRevisionBuildId(buildId);
            documentRepository.save(document);
        }
    }

    private DocumentBuild revisionBuild(BusinessDocument document) {
        if (!hasText(document.getMetadataRevisionBuildId())) {
            return null;
        }
        return buildRepository.findById(document.getMetadataRevisionBuildId()).orElse(null);
    }

    /**
     * READY 只是加工完成、尚未切换 active。未来生效的修订会长期停在 READY，
     * 必须允许文件管理者重新提交元数据来替换它；旧 READY 构建会在下次切换尝试时取消。
     */
    private boolean isReplaceableRevisionStatus(DocumentBuildStatus status) {
        return status == DocumentBuildStatus.FAILED
                || status == DocumentBuildStatus.CANCELLED
                || status == DocumentBuildStatus.READY;
    }

    private DocumentVersion resolveMetadataVersion(BusinessDocument document) {
        if (hasText(document.getActiveVersionId())) {
            return versionRepository.findById(document.getActiveVersionId())
                    .orElseThrow(() -> new CustomException("active 内容版本不存在", HttpStatus.NOT_FOUND));
        }
        return versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId()).stream()
                .findFirst()
                .orElseThrow(() -> new CustomException("文档还没有内容版本", HttpStatus.NOT_FOUND));
    }

    private void applyCompliance(DocumentVersion version, ComplianceMetadata metadata) {
        version.setDocType(metadata.docType());
        version.setJurisdiction(metadata.jurisdiction());
        version.setJurisdictionCode(metadata.jurisdictionCode());
        version.setIssuer(metadata.issuer());
        version.setApplicableInstitutions(metadata.applicableInstitutions());
        version.setEffectiveAt(metadata.effectiveAt());
        version.setExpiresAt(metadata.expiresAt());
        version.setRepealed(metadata.repealed());
    }

    private void enqueueVersionTask(FileUpload upload, String documentId, DocumentVersion version,
                                    VersionBuild versionBuild, String requesterId,
                                    String taskType, String buildKind) {
        FileProcessingTask task = new FileProcessingTask(
                version.getFileMd5(),
                null,
                version.getFileName(),
                upload.getUserId(),
                version.getOrgTag(),
                version.isPublic(),
                taskType,
                requesterId,
                documentId,
                version.getVersionId(),
                versionBuild.buildId(),
                buildKind,
                versionBuild.versionNo(),
                versionBuild.buildNo(),
                version.getDocType(),
                version.getJurisdiction(),
                version.getIssuer(),
                version.getEffectiveAt() == null ? null : version.getEffectiveAt().toString(),
                version.getApplicableInstitutions(),
                version.getExpiresAt() == null ? null : version.getExpiresAt().toString(),
                version.isRepealed()
        );
        fileProcessingOutboxService.enqueueVectorization(upload, task, true);
    }

    /**
     * 上传记录对应的最新内容版本与构建；merge 幂等返回与重建定位都用它。
     * 尚未关联版本的旧文件返回空。
     */
    @Transactional(readOnly = true)
    public Optional<VersionBuild> findByFileUploadId(Long fileUploadId) {
        if (fileUploadId == null) {
            return Optional.empty();
        }
        return versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(fileUploadId)
                .map(version -> toVersionBuild(version, latestBuild(version.getVersionId())));
    }

    private VersionBuild createBuildLocked(BusinessDocument document, DocumentVersion version,
                                           String requesterId, String kind) {
        int buildNo = buildService.nextBuildNo(version.getVersionId());
        DocumentBuild build = buildService.createBuild(version.getVersionId(), buildNo);
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
        logger.info("已创建内容版本构建: documentId={}, versionId={}, versionNo={}, buildId={}, buildNo={}, kind={}, requesterId={}",
                document.getDocumentId(), version.getVersionId(), version.getVersionNo(),
                build.getBuildId(), build.getBuildNo(), kind, requesterId);
        return toVersionBuild(version, build);
    }

    /**
     * 合并入口的上传记录必须属于调用者（上传只允许本人合并），并在行锁内复核状态。
     */
    private FileUpload lockOwnedCompletedUpload(FileUpload fileUpload, String requesterId) {
        FileUpload locked = lockUpload(fileUpload);
        if (!locked.getUserId().equals(requesterId)) {
            throw new CustomException("没有权限操作此文件", HttpStatus.FORBIDDEN);
        }
        requireCompleted(locked);
        return locked;
    }

    /**
     * 重建/懒迁移入口的上传记录允许所有者或管理员操作。
     */
    private FileUpload lockCompletedUploadForRequester(FileUpload fileUpload, String requesterId) {
        FileUpload locked = lockUpload(fileUpload);
        requireUploadVisibleTo(locked, requesterId);
        return locked;
    }

    private FileUpload lockUpload(FileUpload fileUpload) {
        if (fileUpload == null || fileUpload.getId() == null) {
            throw new CustomException("文件不存在", HttpStatus.NOT_FOUND);
        }
        return fileUploadRepository.findByIdForUpdate(fileUpload.getId())
                .orElseThrow(() -> new CustomException("文件不存在", HttpStatus.NOT_FOUND));
    }

    private FileUpload requireCompletedUploadForRequester(Long fileUploadId, String requesterId) {
        FileUpload upload = fileUploadRepository.findById(fileUploadId)
                .orElseThrow(() -> new CustomException("文件不存在", HttpStatus.NOT_FOUND));
        requireUploadVisibleTo(upload, requesterId);
        return upload;
    }

    private void requireUploadVisibleTo(FileUpload upload, String requesterId) {
        if (!upload.getUserId().equals(requesterId) && !isFileManager(requesterId)) {
            throw new CustomException("没有权限操作此文件", HttpStatus.FORBIDDEN);
        }
        requireCompleted(upload);
    }

    private void requireCompleted(FileUpload upload) {
        if (upload.getStatus() != FileUpload.STATUS_COMPLETED) {
            throw new CustomException("文件尚未完成合并，无法创建构建", HttpStatus.CONFLICT);
        }
    }

    /**
     * 允许当前 active 版本或最新版本（v2 构建失败、v1 仍 active 时重试 v2）；更早的版本拒绝。
     */
    private void requireActiveOrLatestVersion(BusinessDocument document, DocumentVersion version) {
        if (document.getActiveVersionId() != null
                && document.getActiveVersionId().equals(version.getVersionId())) {
            return;
        }
        boolean latest = versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId())
                .stream().findFirst()
                .map(latestVersion -> latestVersion.getVersionId().equals(version.getVersionId()))
                .orElse(false);
        if (!latest) {
            throw new CustomException(NOT_ACTIVE_VERSION_MESSAGE, HttpStatus.CONFLICT);
        }
    }

    /**
     * 解析请求者是管理员：JWT 的 userId 既可能是数字主键也可能是用户名，两种都按库内角色判断。
     */
    private boolean isFileManager(String requesterId) {
        if (!hasText(requesterId)) {
            return false;
        }
        try {
            return userRepository.findById(Long.parseLong(requesterId))
                    .map(user -> user.getKnowledgeRole() == com.yizhaoqi.smartpai.model.KnowledgeRole.FILE_MANAGER)
                    .orElse(false);
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(requesterId)
                    .map(user -> user.getKnowledgeRole() == com.yizhaoqi.smartpai.model.KnowledgeRole.FILE_MANAGER)
                    .orElse(false);
        }
    }

    private DocumentVersion resolveActiveVersion(BusinessDocument document) {
        if (document.getActiveVersionId() != null) {
            return versionRepository.findById(document.getActiveVersionId())
                    .orElseThrow(() -> new CustomException("当前内容版本不存在", HttpStatus.CONFLICT));
        }
        return versionRepository.findByDocumentIdOrderByVersionNoDesc(document.getDocumentId()).stream()
                .findFirst()
                .orElseThrow(() -> new CustomException("文档没有可用的内容版本", HttpStatus.CONFLICT));
    }

    private int nextVersionNo(String documentId) {
        return versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId).stream()
                .findFirst()
                .map(version -> version.getVersionNo() + 1)
                .orElse(1);
    }

    private void requireNotDeleted(BusinessDocument document) {
        if (document.getDeletedAt() != null) {
            throw new CustomException("文档已删除，无法创建构建", HttpStatus.NOT_FOUND);
        }
    }

    private DocumentBuild latestBuild(String versionId) {
        return buildRepository.findByVersionIdOrderByBuildNoDesc(versionId).stream()
                .findFirst()
                .orElse(null);
    }

    private VersionBuild toVersionBuild(DocumentVersion version, DocumentBuild build) {
        return new VersionBuild(version.getDocumentId(), version.getVersionId(),
                build == null ? null : build.getBuildId(), version.getVersionNo(),
                build == null ? null : build.getBuildNo(), version.getOrgTag(), version.isPublic(),
                version.getFileMd5(), version.getFileName(), complianceOf(version));
    }

    private static ComplianceMetadata complianceOf(DocumentVersion version) {
        return new ComplianceMetadata(version.getDocType(), version.getJurisdiction(),
                version.getIssuer(), version.getEffectiveAt(),
                version.getApplicableInstitutions(), version.getExpiresAt(), version.isRepealed());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record VersionBuild(String documentId, String versionId, String buildId,
                               Integer versionNo, Integer buildNo, String orgTag, boolean isPublic,
                               String fileMd5, String fileName, ComplianceMetadata compliance) {

        public VersionBuild(String documentId, String versionId, String buildId,
                            Integer versionNo, Integer buildNo, String orgTag, boolean isPublic,
                            String fileMd5, String fileName) {
            this(documentId, versionId, buildId, versionNo, buildNo, orgTag, isPublic, fileMd5, fileName,
                    ComplianceMetadata.empty());
        }
    }
}
