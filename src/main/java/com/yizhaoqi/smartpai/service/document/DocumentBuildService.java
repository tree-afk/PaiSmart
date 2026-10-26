package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.ComplianceValidityStatus;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 构建状态机与唯一切换入口。
 * <p>
 * 状态流转：PENDING → PROCESSING → READY → ACTIVE；失败标 FAILED，文档删除或主动取消标 CANCELLED，
 * 被同文档更新的 active build 取代后标 SUPERSEDED。mark* 方法先悲观锁构建行再校验来源状态，
 * 只接受规格 4.5 的合法迁移并返回是否生效（同状态重复投递视为幂等成功）；
 * 只有 {@link #activate(String)} 能改变检索口径：它在事务内先锁构建、再锁业务文档，确认候选构建严格新于
 * 当前 active 后才更新指针并退休 legacy 表示，提交后才失效检索范围缓存。
 * 分块数核对、标 READY、切换由调用方（消费者）按序调用，避免同类自调用绕过事务代理。
 */
@Service
public class DocumentBuildService {

    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final DocumentBuildRepository buildRepository;
    private final DocumentVersionRepository versionRepository;
    private final BusinessDocumentRepository documentRepository;
    private final ElasticsearchService elasticsearchService;
    private final ActiveRetrievalScopeService activeScopeService;

    public DocumentBuildService(DocumentBuildRepository buildRepository,
                                DocumentVersionRepository versionRepository,
                                BusinessDocumentRepository documentRepository,
                                ElasticsearchService elasticsearchService,
                                ActiveRetrievalScopeService activeScopeService) {
        this.buildRepository = buildRepository;
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.elasticsearchService = elasticsearchService;
        this.activeScopeService = activeScopeService;
    }

    /**
     * 在指定内容版本下创建一个待处理的构建批次。buildNo 由调用方先取 {@link #nextBuildNo(String)}；
     * 并发重复由唯一键 (version_id, build_no) 兜底。
     */
    @Transactional
    public DocumentBuild createBuild(String versionId, int buildNo) {
        DocumentVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + versionId));
        DocumentBuild build = new DocumentBuild();
        build.setBuildId(UUID.randomUUID().toString());
        build.setVersionId(version.getVersionId());
        build.setBuildNo(buildNo);
        build.setStatus(DocumentBuildStatus.PENDING);
        build.setCreatedAt(LocalDateTime.now());
        return buildRepository.save(build);
    }

    /**
     * 该版本下一次构建的批次号，从 1 开始
     */
    public int nextBuildNo(String versionId) {
        return buildRepository.findByVersionIdOrderByBuildNoDesc(versionId).stream()
                .findFirst().map(build -> build.getBuildNo() + 1).orElse(1);
    }

    /**
     * PENDING → PROCESSING；已是 PROCESSING 时幂等返回 true。
     * 其余状态（含 ACTIVE / SUPERSEDED / CANCELLED）返回 false，不修改任何字段。
     */
    @Transactional
    public boolean markProcessing(String buildId) {
        DocumentBuild build = lock(buildId);
        if (build.getStatus() == DocumentBuildStatus.PROCESSING) {
            return true;
        }
        if (build.getStatus() != DocumentBuildStatus.PENDING) {
            return false;
        }
        build.setStatus(DocumentBuildStatus.PROCESSING);
        build.setStartedAt(LocalDateTime.now());
        buildRepository.save(build);
        return true;
    }

    /**
     * 分块数核对通过后 PROCESSING → READY；已是 READY 时幂等返回 true。
     * 其余状态返回 false，不修改任何字段。切换由 {@link #activate(String)} 在独立事务中完成。
     */
    @Transactional
    public boolean markReady(String buildId, int chunkCount) {
        DocumentBuild build = lock(buildId);
        if (build.getStatus() == DocumentBuildStatus.READY) {
            return true;
        }
        if (build.getStatus() != DocumentBuildStatus.PROCESSING) {
            return false;
        }
        build.setStatus(DocumentBuildStatus.READY);
        build.setChunkCount(chunkCount);
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
        return true;
    }

    /**
     * PENDING / PROCESSING / READY → FAILED；已是 FAILED 时幂等返回 true。
     * ACTIVE / SUPERSEDED / CANCELLED 返回 false，不修改任何字段。
     */
    @Transactional
    public boolean markFailed(String buildId, String error) {
        DocumentBuild build = lock(buildId);
        if (build.getStatus() == DocumentBuildStatus.FAILED) {
            return true;
        }
        if (build.getStatus() != DocumentBuildStatus.PENDING
                && build.getStatus() != DocumentBuildStatus.PROCESSING
                && build.getStatus() != DocumentBuildStatus.READY) {
            return false;
        }
        build.setStatus(DocumentBuildStatus.FAILED);
        build.setErrorMessage(truncate(error));
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
        // 元数据修订失败不能解除限制：旧 active 索引仍是修订前的元数据，
        // 必须重新提交修订或重试同一个修订版本，不能退回旧索引发布。
        return true;
    }

    /**
     * PENDING / PROCESSING / READY → CANCELLED；已是 CANCELLED 时幂等返回 true。
     * ACTIVE / SUPERSEDED / FAILED 返回 false，不修改任何字段。
     */
    @Transactional
    public boolean markCancelled(String buildId) {
        DocumentBuild build = lock(buildId);
        if (build.getStatus() == DocumentBuildStatus.CANCELLED) {
            return true;
        }
        if (build.getStatus() != DocumentBuildStatus.PENDING
                && build.getStatus() != DocumentBuildStatus.PROCESSING
                && build.getStatus() != DocumentBuildStatus.READY) {
            return false;
        }
        build.setStatus(DocumentBuildStatus.CANCELLED);
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
        return true;
    }

    /**
     * 读取构建当前状态，不修改任何字段；构建不存在时返回 null
     */
    public DocumentBuildStatus statusOf(String buildId) {
        return buildRepository.findById(buildId)
                .map(DocumentBuild::getStatus)
                .orElse(null);
    }

    /**
     * 已就绪或已到终态的构建不再需要写数据：消费前用它拦掉迟到/重复的消息，
     * 避免为已切换或已失败的构建重新清理、解析和向量化
     */
    public boolean shouldSkipProcessing(DocumentBuildStatus status) {
        return status == DocumentBuildStatus.READY
                || status == DocumentBuildStatus.ACTIVE
                || status == DocumentBuildStatus.SUPERSEDED
                || status == DocumentBuildStatus.FAILED
                || status == DocumentBuildStatus.CANCELLED;
    }

    /**
     * 分块数核对：ES 中该构建的分块数与本次向量化结果一致且大于 0 才标 READY 并切换；
     * 不一致只把该构建标 FAILED，旧 active 保持不动
     */
    public boolean chunkCountMatches(String buildId, int expectedChunks) {
        long actual = elasticsearchService.countByBuildId(buildId);
        return expectedChunks > 0 && actual == expectedChunks;
    }

    /**
     * 唯一的 active 指针切换路径：先锁构建并复核状态，再锁业务文档、淘汰旧 active build、更新指针、
     * 退休 legacy 表示，并在事务提交后失效检索范围缓存。
     * <p>
     * 已 ACTIVE 的构建重复调用直接返回 {@link BuildActivation#ACTIVE}（幂等）；非 READY 构建返回
     * {@link BuildActivation#NOT_READY}。未来生效的版本保持 READY，返回
     * {@link BuildActivation#PENDING_EFFECTIVE}，旧 active 继续服务；已失效/废止版本标 CANCELLED。
     * 若候选构建并不严格新于当前 active（迟到的旧版本或旧批次），标 SUPERSEDED 并返回
     * {@link BuildActivation#STALE}，active 指针保持不动。
     */
    @Transactional
    public BuildActivation activate(String buildId) {
        DocumentBuild build = lock(buildId);
        if (build.getStatus() == DocumentBuildStatus.ACTIVE) {
            return BuildActivation.ACTIVE;
        }
        if (build.getStatus() != DocumentBuildStatus.READY) {
            return BuildActivation.NOT_READY;
        }
        DocumentVersion version = versionRepository.findById(build.getVersionId())
                .orElseThrow(() -> new IllegalStateException("构建对应版本不存在: " + build.getVersionId()));
        BusinessDocument document = documentRepository.findForUpdate(version.getDocumentId())
                .orElseThrow(() -> new IllegalStateException("业务文档不存在: " + version.getDocumentId()));
        if (document.getDeletedAt() != null) {
            build.setStatus(DocumentBuildStatus.CANCELLED);
            build.setCompletedAt(LocalDateTime.now());
            buildRepository.save(build);
            return BuildActivation.CANCELLED;
        }
        boolean metadataRevisionUnresolved = document.isMetadataRevisionPending()
                || document.getMetadataRevisionBuildId() != null;
        if (metadataRevisionUnresolved && !buildId.equals(document.getMetadataRevisionBuildId())) {
            // 元数据修订只认登记的那一个构建：更早的重建任务、其他版本构建都不能
            // 在修订完成前接管 active 指针，也不能把文档改回 PUBLISHED。
            if (cancelStaleRevisionBuild(build, document)) {
                return BuildActivation.CANCELLED;
            }
            return BuildActivation.BLOCKED_BY_METADATA_REVISION;
        }
        String previousBuildId = document.getActiveBuildId();
        ComplianceValidityStatus validity = ComplianceValidityStatus.resolve(
                version.getEffectiveAt(), version.getExpiresAt(), version.isRepealed(), LocalDate.now());
        if (validity == ComplianceValidityStatus.PENDING) {
            // 构建已经 READY，但业务生效日期未到：保留 READY，旧 active 继续服务，等定时任务到点切换
            return BuildActivation.PENDING_EFFECTIVE;
        }
        if (validity == ComplianceValidityStatus.UNCONFIRMED
                && document.getActiveBuildId() != null
                && !version.getVersionId().equals(document.getActiveVersionId())) {
            // 已有生效版本时，新的“效力待确认”版本不能直接替换旧版；首个版本或同版本重建仍可启用
            return BuildActivation.PENDING_EFFECTIVE;
        }
        if (validity == ComplianceValidityStatus.EXPIRED || validity == ComplianceValidityStatus.REPEALED) {
            build.setStatus(DocumentBuildStatus.CANCELLED);
            build.setCompletedAt(LocalDateTime.now());
            buildRepository.save(build);
            return BuildActivation.CANCELLED;
        }
        if (previousBuildId != null && !previousBuildId.equals(buildId)) {
            Optional<DocumentBuild> previous = buildRepository.findById(previousBuildId);
            if (previous.isPresent()) {
                if (!isNewerThan(build, version, previous.get())) {
                    // 迟到的旧构建不能覆盖更新的 active：标 SUPERSEDED 后终止，指针不动
                    build.setStatus(DocumentBuildStatus.SUPERSEDED);
                    buildRepository.save(build);
                    return BuildActivation.STALE;
                }
                DocumentBuild previousBuild = previous.get();
                previousBuild.setStatus(DocumentBuildStatus.SUPERSEDED);
                buildRepository.save(previousBuild);
            }
        }
        build.setStatus(DocumentBuildStatus.ACTIVE);
        build.setCompletedAt(LocalDateTime.now());
        buildRepository.save(build);
        document.setActiveVersionId(version.getVersionId());
        document.setActiveBuildId(buildId);
        // 构建成功不等于永久可检索：首次上传/元数据修订成功时发布；已下架文档保持下架
        if (document.getPublicationStatus() == com.yizhaoqi.smartpai.model.PublicationStatus.DRAFT) {
            document.setPublicationStatus(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED);
        }
        if (buildId.equals(document.getMetadataRevisionBuildId())) {
            // 只有登记的修订构建成功切换，才解除修订限制
            document.setMetadataRevisionPending(false);
            document.setMetadataRevisionBuildId(null);
        }
        document.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(document);
        for (DocumentVersion legacy : versionRepository.findByDocumentIdAndLegacyRepresentationTrue(document.getDocumentId())) {
            legacy.setLegacyRetired(true);
            versionRepository.save(legacy);
        }
        invalidateAfterCommit();
        return BuildActivation.ACTIVE;
    }

    /**
     * 已被新修订替换的旧构建（同版本 READY）不再有机会生效，直接取消，避免恢复任务反复重试。
     * 不同版本的构建可能是新版本上传，保持 READY 等修订完成后再切换。
     */
    private boolean cancelStaleRevisionBuild(DocumentBuild candidate, BusinessDocument document) {
        if (document.getMetadataRevisionBuildId() == null) {
            return false;
        }
        DocumentBuild registered = buildRepository.findById(document.getMetadataRevisionBuildId())
                .orElse(null);
        if (registered == null || !registered.getVersionId().equals(candidate.getVersionId())) {
            return false;
        }
        candidate.setStatus(DocumentBuildStatus.CANCELLED);
        candidate.setCompletedAt(LocalDateTime.now());
        buildRepository.save(candidate);
        return true;
    }

    private DocumentBuild lock(String buildId) {
        return buildRepository.findForUpdate(buildId)
                .orElseThrow(() -> new IllegalArgumentException("构建不存在: " + buildId));
    }

    /**
     * 候选构建是否严格新于当前 active：先比版本号，再比版本内批次号。
     * 当前 active 指向的版本行缺失时无法比较，按可切换处理，保持既有语义。
     */
    private boolean isNewerThan(DocumentBuild candidate, DocumentVersion candidateVersion, DocumentBuild active) {
        Optional<DocumentVersion> activeVersion = versionRepository.findById(active.getVersionId());
        if (activeVersion.isEmpty()) {
            return true;
        }
        int candidateVersionNo = candidateVersion.getVersionNo();
        int activeVersionNo = activeVersion.get().getVersionNo();
        if (candidateVersionNo != activeVersionNo) {
            return candidateVersionNo > activeVersionNo;
        }
        return candidate.getBuildNo() > active.getBuildNo();
    }

    private String truncate(String error) {
        if (error == null || error.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    activeScopeService.invalidateAndBroadcast();
                }
            });
        } else {
            activeScopeService.invalidateAndBroadcast();
        }
    }

    /**
     * 发布状态/元数据变化后让检索范围在事务提交后立即失效，避免缓存里继续放行旧状态。
     */
    public void invalidateScopeAfterCommit() {
        invalidateAfterCommit();
    }

    public enum BuildActivation {
        ACTIVE,
        NOT_READY,
        PENDING_EFFECTIVE,
        STALE,
        CANCELLED,
        BLOCKED_BY_METADATA_REVISION
    }
}
