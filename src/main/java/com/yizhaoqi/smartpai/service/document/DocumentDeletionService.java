package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.KnowledgeAuditService;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 文档删除入口：按上传记录判断是否已版本化，版本化文档分两段提交——先在一个只锁 business_document 的短事务里
 * 写 deletedAt 并失效检索范围，再在一个只锁 document_build 的独立事务里取消 PENDING/PROCESSING/READY 构建，
 * 最后做尽力而为的物理清理；未版本化的旧文件继续走原有的 MD5 清理。
 * <p>
 * 两段之间不持有对方的行锁，避免与 {@link DocumentBuildService#activate(String)}（先锁构建、再锁文档）
 * 形成相反的加锁顺序而互相死锁。停用阶段提交后，并发开始的 activate 会读到 deletedAt 并返回 CANCELLED；
 * 已经在停用前完成的切换不影响删除结果：文档已删除、检索范围已排除，清理阶段会删掉该构建的数据。
 * 停用阶段只改业务状态，不删除任何索引/向量/对象存储数据，因此清理失败不会让已删除的文档重新可检索。
 */
@Service
public class DocumentDeletionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentDeletionService.class);
    private static final String UPLOAD_BUCKET = "uploads";
    private static final String MERGED_OBJECT_PREFIX = "merged/";

    private final FileUploadRepository fileUploadRepository;
    private final DocumentVersionRepository versionRepository;
    private final BusinessDocumentRepository documentRepository;
    private final DocumentBuildRepository buildRepository;
    private final DocumentBuildService buildService;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final KnowledgeAuditService auditService;
    private final ActiveRetrievalScopeService activeScopeService;
    private final ElasticsearchService elasticsearchService;
    private final DocumentVectorRepository documentVectorRepository;
    private final ChunkInfoRepository chunkInfoRepository;
    private final MinioClient minioClient;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public DocumentDeletionService(FileUploadRepository fileUploadRepository,
                                   DocumentVersionRepository versionRepository,
                                   BusinessDocumentRepository documentRepository,
                                   DocumentBuildRepository buildRepository,
                                   DocumentBuildService buildService,
                                   UserRepository userRepository,
                                   DocumentService documentService,
                                   KnowledgeAuditService auditService,
                                   ActiveRetrievalScopeService activeScopeService,
                                   ElasticsearchService elasticsearchService,
                                   DocumentVectorRepository documentVectorRepository,
                                   ChunkInfoRepository chunkInfoRepository,
                                   MinioClient minioClient,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager) {
        this.fileUploadRepository = fileUploadRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.buildRepository = buildRepository;
        this.buildService = buildService;
        this.userRepository = userRepository;
        this.documentService = documentService;
        this.auditService = auditService;
        this.activeScopeService = activeScopeService;
        this.elasticsearchService = elasticsearchService;
        this.documentVectorRepository = documentVectorRepository;
        this.chunkInfoRepository = chunkInfoRepository;
        this.minioClient = minioClient;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 删除指定用户的一份文件：优先按 md5+用户找到自己的上传记录及其内容版本。
     * 有版本时按业务文档停用；没有版本时保持旧的按 MD5 清理行为。
     * 管理员可以删除其他用户的文件，普通用户只能删除自己的文件。
     * 上传记录已被上一次删除清理掉时，按仍保留的内容版本判断幂等，避免重复删除报错。
     */
    public void deleteByFileMd5(String fileMd5, String requesterId) {
        Optional<FileUpload> owned =
                fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, requesterId);
        if (owned.isPresent()) {
            Optional<DocumentVersion> version =
                    versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(owned.get().getId());
            if (version.isPresent()) {
                deleteVersionedDocument(version.get(), fileMd5, requesterId);
            } else {
                logger.info("文件未关联内容版本，按 MD5 清理: fileMd5={}, requesterId={}", fileMd5, requesterId);
                documentService.deleteLegacyDocument(fileMd5, owned.get().getUserId());
                auditService.record(requesterId, "FILE", fileMd5, "DELETE", null, "DELETED");
            }
            return;
        }

        // 没有自己的上传记录：按 md5 定点查版本判断是否已经删除，避免共享 MD5 或其他上传记录干扰幂等
        if (findAlreadyDeletedVersion(fileMd5, requesterId).isPresent()) {
            logger.info("业务文档已删除且上传记录已清理，按幂等返回: fileMd5={}, requesterId={}",
                    fileMd5, requesterId);
            return;
        }

        Optional<FileUpload> latest = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
        if (latest.isEmpty()) {
            throw new CustomException("文档不存在", HttpStatus.NOT_FOUND);
        }
        if (!isAdmin(requesterId)) {
            throw new CustomException("没有权限删除此文档", HttpStatus.FORBIDDEN);
        }
        Optional<DocumentVersion> version =
                versionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(latest.get().getId());
        if (version.isEmpty()) {
            logger.info("管理员删除未版本化文件，按 MD5 清理: fileMd5={}, requesterId={}", fileMd5, requesterId);
            documentService.deleteLegacyDocument(fileMd5, latest.get().getUserId());
            auditService.record(requesterId, "FILE", fileMd5, "DELETE", null, "DELETED");
            return;
        }
        deleteVersionedDocument(version.get(), fileMd5, requesterId);
    }

    /**
     * 删除编排：停用（只锁业务文档的独立事务）→ 检索范围失效 → 取消未完成构建（只锁构建行的独立事务）
     * → 物理清理。任何一段都不在同一事务里同时持有业务文档锁和构建行锁。
     */
    private void deleteVersionedDocument(DocumentVersion versioned, String fileMd5, String requesterId) {
        String documentId = markDeleted(versioned.getDocumentId(), requesterId);
        if (documentId == null) {
            logger.info("业务文档已处于删除状态，按幂等返回: documentId={}, fileMd5={}",
                    versioned.getDocumentId(), fileMd5);
            return;
        }

        // 停用事务已提交：legacy 表示必须在检索范围失效前退休，否则没有 buildId 的旧分块仍在范围内
        try {
            retireLegacyRepresentations(documentId);
        } catch (Exception e) {
            logger.warn("退休 legacy 表示失败，继续失效检索范围并清理: documentId={}", documentId, e);
        }
        activeScopeService.invalidateAndBroadcast();

        // 取消阶段使用独立事务，只锁构建行，不与停用阶段的文档行锁叠加
        cancelUnfinishedBuilds(documentId);

        // 文档已停用，清理尽量做完；失败只记日志，不能把删除结果变成失败
        try {
            DeletionPlan plan = planCleanup(documentId);
            cleanUp(plan);
            logger.info("文档删除完成: documentId={}, fileMd5={}, versions={}, builds={}",
                    documentId, fileMd5, plan.versions().size(), plan.buildIds().size());
        } catch (Exception e) {
            logger.warn("文档已删除，物理清理出现异常，保留删除状态: documentId={}", documentId, e);
        }
    }

    /**
     * 停用阶段（独立短事务）：只悲观锁 business_document 行，校验权限后写 deletedAt 并提交。
     * 已删除的文档返回 null 表示幂等；此事务不读取、不锁定任何构建行。
     */
    private String markDeleted(String documentId, String requesterId) {
        return transactionTemplate.execute(status -> {
            BusinessDocument document = documentRepository.findForUpdate(documentId)
                    .orElseThrow(() -> new CustomException("业务文档不存在", HttpStatus.NOT_FOUND));
            if (!document.getOwnerUserId().equals(requesterId) && !isAdmin(requesterId)) {
                throw new CustomException("没有权限删除此文档", HttpStatus.FORBIDDEN);
            }
            if (document.getDeletedAt() != null) {
                return null;
            }
            document.setDeletedAt(LocalDateTime.now());
            documentRepository.saveAndFlush(document);
            auditService.record(requesterId, "DOCUMENT", documentId, "DELETE",
                    document.getPublicationStatus() == null ? null : document.getPublicationStatus().name(),
                    "DELETED");
            return document.getDocumentId();
        });
    }

    /**
     * 退休该文档下仍生效的 legacy 表示（独立事务，只写 document_version 行，不锁业务文档或构建行）。
     * legacy 分块没有 buildId，只能通过退休 md5 退出检索范围。
     */
    private void retireLegacyRepresentations(String documentId) {
        transactionTemplate.executeWithoutResult(status -> {
            for (DocumentVersion version : versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId)) {
                if (version.isLegacyRepresentation() && !version.isLegacyRetired()) {
                    version.setLegacyRetired(true);
                    versionRepository.save(version);
                }
            }
        });
    }

    /**
     * 取消阶段（独立事务）：只通过 {@link DocumentBuildService#markCancelled(String)} 锁构建行，
     * 不读取业务文档、不持有文档行锁。取消事务失败只回滚本次取消，已经提交的删除状态保持不变。
     */
    private void cancelUnfinishedBuilds(String documentId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (DocumentVersion version : versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId)) {
                    for (DocumentBuild build : buildRepository.findByVersionIdOrderByBuildNoDesc(version.getVersionId())) {
                        cancelUnfinishedBuild(build);
                    }
                }
            });
        } catch (Exception e) {
            // 文档已经删除，activate 会拒绝已删除文档的构建；这里不能因为取消失败让请求失败
            logger.warn("取消未完成构建失败，文档保持删除状态: documentId={}", documentId, e);
        }
    }

    /**
     * 只取消 PENDING/PROCESSING/READY；ACTIVE/SUPERSEDED/FAILED/CANCELLED 永不取消。
     * 状态机拒绝（并发下已被切换）时记录日志，不强行改状态。
     */
    private void cancelUnfinishedBuild(DocumentBuild build) {
        DocumentBuildStatus status = build.getStatus();
        if (status != DocumentBuildStatus.PENDING
                && status != DocumentBuildStatus.PROCESSING
                && status != DocumentBuildStatus.READY) {
            logger.info("删除文档时保留不可取消的构建: buildId={}, status={}", build.getBuildId(), status);
            return;
        }
        if (!buildService.markCancelled(build.getBuildId())) {
            logger.warn("删除文档时取消构建被状态机拒绝: buildId={}, status={}",
                    build.getBuildId(), buildService.statusOf(build.getBuildId()));
        }
    }

    /**
     * 上传记录已随上一次删除清理时，用仍保留的文档版本判断重复删除：
     * 按 file_md5 走索引定点查询版本，不扫描整库文档；只认已软删除且属于请求者（或请求者为管理员）的业务文档。
     */
    private Optional<DocumentVersion> findAlreadyDeletedVersion(String fileMd5, String requesterId) {
        boolean admin = isAdmin(requesterId);
        List<DocumentVersion> versions = entityManager.createQuery(
                        "select v from DocumentVersion v where v.fileMd5 = :fileMd5", DocumentVersion.class)
                .setParameter("fileMd5", fileMd5)
                .getResultList();
        for (DocumentVersion version : versions) {
            Optional<BusinessDocument> document = documentRepository.findById(version.getDocumentId());
            if (document.isEmpty() || document.get().getDeletedAt() == null) {
                continue;
            }
            if (!document.get().getOwnerUserId().equals(requesterId) && !admin) {
                continue;
            }
            return Optional.of(version);
        }
        return Optional.empty();
    }

    /**
     * 清理计划：取消阶段结束后读取该文档的全部版本与构建，供事务外的物理清理使用。
     */
    private DeletionPlan planCleanup(String documentId) {
        List<VersionCleanup> versions = new ArrayList<>();
        List<String> buildIds = new ArrayList<>();
        for (DocumentVersion version : versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId)) {
            for (DocumentBuild build : buildRepository.findByVersionIdOrderByBuildNoDesc(version.getVersionId())) {
                buildIds.add(build.getBuildId());
            }
            versions.add(new VersionCleanup(
                    version.getFileUploadId(), version.getFileMd5(), version.isLegacyRepresentation()));
        }
        return new DeletionPlan(documentId, buildIds, versions);
    }

    /**
     * 物理清理：逐构建删 ES 与向量，逐版本删上传记录、分片元数据、预览缓存和不再被引用的 MinIO 对象。
     * legacy 表示的分块没有 buildId，md5 不再被共享时按 fileMd5 清理其旧的 ES/向量数据。
     * 所有异常只记日志，不重新启用业务文档。
     */
    private void cleanUp(DeletionPlan plan) {
        for (String buildId : plan.buildIds()) {
            deleteBuildData(buildId);
        }
        for (VersionCleanup version : plan.versions()) {
            try {
                cleanUpVersion(version);
            } catch (Exception e) {
                logger.warn("清理版本残留失败，继续处理其他版本: fileUploadId={}", version.fileUploadId(), e);
            }
        }
    }

    private void deleteBuildData(String buildId) {
        try {
            elasticsearchService.deleteByBuildId(KnowledgeIndexNames.ALIAS, buildId);
        } catch (Exception e) {
            logger.warn("删除构建 ES 分块失败，继续清理: buildId={}", buildId, e);
        }
        try {
            documentVectorRepository.deleteByBuildId(buildId);
        } catch (Exception e) {
            logger.warn("删除构建向量记录失败，继续清理: buildId={}", buildId, e);
        }
    }

    private void cleanUpVersion(VersionCleanup version) {
        Optional<FileUpload> upload = fileUploadRepository.findById(version.fileUploadId());
        long references = fileUploadRepository.countByFileMd5(version.fileMd5());
        // 只有当前上传记录是唯一引用时才删除共享的 merged/{md5} 对象与 legacy 数据
        boolean lastReference = upload.isPresent() ? references <= 1 : references == 0;

        if (version.legacyRepresentation()) {
            if (lastReference) {
                deleteLegacyRepresentationData(version.fileMd5());
            } else {
                logger.info("同一 MD5 仍被其他上传记录引用，保留 legacy 索引与向量: fileMd5={}", version.fileMd5());
            }
        }

        if (upload.isPresent()) {
            FileUpload file = upload.get();
            try {
                fileUploadRepository.delete(file);
            } catch (Exception e) {
                logger.warn("删除上传记录失败，继续清理: fileUploadId={}", version.fileUploadId(), e);
            }
            try {
                chunkInfoRepository.deleteByFileMd5AndUserId(file.getFileMd5(), file.getUserId());
            } catch (Exception e) {
                logger.warn("删除分片元数据失败，继续清理: fileMd5={}, userId={}",
                        file.getFileMd5(), file.getUserId(), e);
            }
        } else {
            logger.warn("版本对应的上传记录已不存在，跳过上传与分片清理: fileUploadId={}", version.fileUploadId());
        }

        try {
            documentService.invalidatePdfSinglePagePreviewCache(version.fileMd5());
        } catch (Exception e) {
            logger.warn("删除 PDF 预览缓存失败，继续清理: fileMd5={}", version.fileMd5(), e);
        }

        if (lastReference) {
            removeMergedObject(version.fileMd5());
        } else {
            logger.info("同一 MD5 仍被其他上传记录引用，保留 MinIO 合并文件: fileMd5={}", version.fileMd5());
        }
    }

    /**
     * legacy 表示的分块没有 buildId，只能按 fileMd5 清理；调用方必须已确认 md5 不再被共享。
     * 使用全索引模式，避免回滚索引里的旧分块让已删除内容复活。
     */
    private void deleteLegacyRepresentationData(String fileMd5) {
        try {
            elasticsearchService.deleteByFileMd5(fileMd5);
        } catch (Exception e) {
            logger.warn("删除 legacy ES 分块失败，继续清理: fileMd5={}", fileMd5, e);
        }
        try {
            documentVectorRepository.deleteByFileMd5(fileMd5);
        } catch (Exception e) {
            logger.warn("删除 legacy 向量记录失败，继续清理: fileMd5={}", fileMd5, e);
        }
    }

    private void removeMergedObject(String fileMd5) {
        String objectName = MERGED_OBJECT_PREFIX + fileMd5;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(UPLOAD_BUCKET)
                    .object(objectName)
                    .build());
            logger.info("已删除 MinIO 合并文件: {}", objectName);
        } catch (Exception e) {
            logger.warn("删除 MinIO 合并文件失败，忽略: fileMd5={}", fileMd5, e);
        }
    }

    /**
     * 解析请求者是否为文件管理者：JWT 的 userId 可能是数字主键或用户名。
     */
    private boolean isAdmin(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            return false;
        }
        try {
            return userRepository.findById(Long.parseLong(requesterId))
                    .map(user -> user.getRole() == com.yizhaoqi.smartpai.model.User.Role.ADMIN)
                    .orElse(false);
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(requesterId)
                    .map(user -> user.getRole() == com.yizhaoqi.smartpai.model.User.Role.ADMIN)
                    .orElse(false);
        }
    }

    private record VersionCleanup(Long fileUploadId, String fileMd5, boolean legacyRepresentation) {
    }

    private record DeletionPlan(String documentId, List<String> buildIds, List<VersionCleanup> versions) {
    }
}
