package com.yizhaoqi.smartpai.service.reliability;

import com.yizhaoqi.smartpai.config.FileProcessingReliabilityProperties;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.document.DocumentBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 卡住任务的发现和恢复、废弃上传的清理。
 * <p>
 * 每类记录都是先查出来，再用"扫描时看到的心跳"做条件更新：扫描之后任务有了进展、或者另一个实例已经处理过，
 * 条件就不成立，影响行数为 0 直接跳过。多实例同时跑也不会重复投递或误删。
 * 带构建的任务按原构建重新投递或直接收尾，绝不退化成按 fileMd5 清理的 legacy 任务
 */
@Component
public class FileProcessingRecoveryJob {

    private static final Logger logger = LoggerFactory.getLogger(FileProcessingRecoveryJob.class);
    static final String LEGACY_TASK_MESSAGE = "升级前中断的历史任务，已停止自动重试，如需要请手动重试向量化";

    private final FileUploadRepository fileUploadRepository;
    private final DocumentBuildRepository buildRepository;
    private final DocumentVersionRepository versionRepository;
    private final FileProcessingOutboxService outboxService;
    private final UploadService uploadService;
    private final FileProcessingReliabilityProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final DocumentStatusNotifier statusNotifier;
    private final DocumentBuildService buildService;

    public FileProcessingRecoveryJob(FileUploadRepository fileUploadRepository,
                                     DocumentBuildRepository buildRepository,
                                     DocumentVersionRepository versionRepository,
                                     FileProcessingOutboxService outboxService,
                                     UploadService uploadService,
                                     FileProcessingReliabilityProperties properties,
                                     TransactionTemplate transactionTemplate,
                                     DocumentStatusNotifier statusNotifier,
                                     DocumentBuildService buildService) {
        this.fileUploadRepository = fileUploadRepository;
        this.buildRepository = buildRepository;
        this.versionRepository = versionRepository;
        this.outboxService = outboxService;
        this.uploadService = uploadService;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.statusNotifier = statusNotifier;
        this.buildService = buildService;
    }

    @Scheduled(fixedDelayString = "${file.processing.reliability.recovery-interval-ms:60000}",
            initialDelayString = "${file.processing.reliability.recovery-interval-ms:60000}")
    public void run() {
        if (!properties.isRecoveryEnabled()) {
            return;
        }
        runStep("向量化卡住恢复", this::recoverStaleVectorizations);
        runStep("合并卡住恢复", this::recoverStaleMerges);
        runStep("废弃上传清理", this::cleanupAbandonedUploads);
    }

    /**
     * 心跳超时的 PROCESSING：带构建的按构建状态收尾或按原构建重投；没有构建的沿用升级前的 legacy 处理
     */
    int recoverStaleVectorizations() {
        LocalDateTime now = now();
        LocalDateTime staleBefore = now.minusSeconds(properties.getVectorizationHeartbeatTimeoutSeconds());
        List<FileUpload> stale = fileUploadRepository.findStaleVectorizations(staleBefore, page());
        int handled = 0;
        for (FileUpload file : stale) {
            Optional<DocumentBuild> latestBuild = buildRepository
                    .findLatestForFileUpload(file.getId(), PageRequest.of(0, 1)).stream().findFirst();
            if (latestBuild.isPresent()) {
                if (recoverBuildBacked(file, latestBuild.get(), now)) {
                    handled++;
                }
                continue;
            }
            if (file.getProcessingStartedAt() == null) {
                // 没有认领时间说明是升级前旧代码留下的 PROCESSING：不知道卡了多久、用户还要不要，
                // 自动重投会一次性把几个月的历史任务全部捞回队列，挡住新上传。直接标失败，由用户决定是否重试
                if (fileUploadRepository.failStaleVectorization(file.getId(), file.getHeartbeatAt(), LEGACY_TASK_MESSAGE, now) == 1) {
                    handled++;
                    // 该分支按定义没有关联构建（有构建时走 recoverBuildBacked），有则一并收尾
                    markTerminalBuildFailed(latestBuild.orElse(null), LEGACY_TASK_MESSAGE);
                    statusNotifier.notifyChanged(file.getId());
                    logger.warn("升级前中断的历史向量化任务，已标记失败不再自动重投: fileMd5={}, fileUploadId={}, lastUpdated={}",
                            file.getFileMd5(), file.getId(), file.getMergedAt());
                }
                continue;
            }
            if (file.getRetryCount() >= properties.getMaxRetries()) {
                String message = String.format("处理超时，已自动重试 %d 次仍未完成，请手动重试", file.getRetryCount());
                if (fileUploadRepository.failStaleVectorization(file.getId(), file.getHeartbeatAt(), message, now) == 1) {
                    handled++;
                    markTerminalBuildFailed(latestBuild.orElse(null), message);
                    statusNotifier.notifyChanged(file.getId());
                    logger.error("[ALERT] 向量化任务多次卡住，已标记失败: fileMd5={}, fileUploadId={}, retryCount={}, lastHeartbeat={}",
                            file.getFileMd5(), file.getId(), file.getRetryCount(), file.getHeartbeatAt());
                }
                continue;
            }

            Boolean requeued = transactionTemplate.execute(status -> {
                if (fileUploadRepository.requeueStaleVectorization(file.getId(), file.getHeartbeatAt(), now) == 0) {
                    return false;
                }
                outboxService.append(buildRetryTask(file));
                return true;
            });
            if (Boolean.TRUE.equals(requeued)) {
                handled++;
                statusNotifier.notifyChanged(file.getId());
                logger.warn("向量化任务心跳超时，已改回待处理并重新投递: fileMd5={}, fileUploadId={}, retryCount={}, lastHeartbeat={}",
                        file.getFileMd5(), file.getId(), file.getRetryCount() + 1, file.getHeartbeatAt());
            }
        }
        return handled;
    }

    /**
     * 带构建的任务按构建当前状态收尾：
     * PENDING/PROCESSING 按原构建重投；READY/ACTIVE/SUPERSEDED 说明数据已写完，只把文件标完成；
     * FAILED/CANCELLED 不重投，把文件标失败并把构建状态写进失败信息。
     * 文件状态条件更新返回 0（扫描后已有进展）时跳过，不重投也不通知
     */
    private boolean recoverBuildBacked(FileUpload file, DocumentBuild build, LocalDateTime now) {
        DocumentBuildStatus status = build.getStatus();
        if (status == DocumentBuildStatus.PENDING || status == DocumentBuildStatus.PROCESSING) {
            if (file.getRetryCount() >= properties.getMaxRetries()) {
                String message = String.format("处理超时，已自动重试 %d 次仍未完成，请手动重试", file.getRetryCount());
                if (!failStaleFile(file, message, now)) {
                    return false;
                }
                markTerminalBuildFailed(build, message);
                logger.error("[ALERT] 构建任务多次卡住，已标记文件失败: fileMd5={}, fileUploadId={}, buildId={}, retryCount={}",
                        file.getFileMd5(), file.getId(), build.getBuildId(), file.getRetryCount());
                return true;
            }
            DocumentVersion version = versionRepository.findById(build.getVersionId()).orElse(null);
            if (version == null) {
                logger.error("[ALERT] 构建对应版本不存在，已停止自动重试: fileMd5={}, fileUploadId={}, buildId={}, versionId={}",
                        file.getFileMd5(), file.getId(), build.getBuildId(), build.getVersionId());
                return failStaleFile(file, "构建对应版本不存在，已停止自动重试，请重新发起向量化", now);
            }
            Boolean requeued = transactionTemplate.execute(tx -> {
                if (fileUploadRepository.requeueStaleVectorization(file.getId(), file.getHeartbeatAt(), now) == 0) {
                    return false;
                }
                outboxService.append(buildRetryTask(file, version, build));
                return true;
            });
            if (!Boolean.TRUE.equals(requeued)) {
                return false;
            }
            statusNotifier.notifyChanged(file.getId());
            logger.warn("向量化任务心跳超时，已按原构建改回待处理并重新投递: fileMd5={}, fileUploadId={}, buildId={}, " +
                            "buildStatus={}, retryCount={}, lastHeartbeat={}",
                    file.getFileMd5(), file.getId(), build.getBuildId(), status, file.getRetryCount() + 1, file.getHeartbeatAt());
            return true;
        }
        if (status == DocumentBuildStatus.READY
                || status == DocumentBuildStatus.ACTIVE
                || status == DocumentBuildStatus.SUPERSEDED) {
            if (fileUploadRepository.completeStaleVectorization(file.getId(), file.getHeartbeatAt(), now) == 0) {
                return false;
            }
            statusNotifier.notifyChanged(file.getId());
            logger.warn("构建已完成，文件向量化直接标记完成且不重投: fileMd5={}, fileUploadId={}, buildId={}, buildStatus={}",
                    file.getFileMd5(), file.getId(), build.getBuildId(), status);
            return true;
        }
        if (status == DocumentBuildStatus.FAILED || status == DocumentBuildStatus.CANCELLED) {
            String message = String.format("构建状态为 %s，已停止自动重试，请重新发起向量化", status);
            return failStaleFile(file, message, now);
        }
        return false;
    }

    private boolean failStaleFile(FileUpload file, String message, LocalDateTime now) {
        if (fileUploadRepository.failStaleVectorization(file.getId(), file.getHeartbeatAt(), message, now) == 0) {
            return false;
        }
        statusNotifier.notifyChanged(file.getId());
        logger.warn("卡住的向量化任务已标记失败: fileMd5={}, fileUploadId={}, message={}",
                file.getFileMd5(), file.getId(), message);
        return true;
    }

    /**
     * 文件已按失败终态条件更新成功后，把对应的最新构建标 FAILED。ACTIVE / SUPERSEDED / CANCELLED
     * 会被构建状态机守卫拒绝，只记日志，不能影响文件收尾结果；没有构建的 legacy 任务直接跳过
     */
    private void markTerminalBuildFailed(DocumentBuild build, String message) {
        if (build == null) {
            return;
        }
        try {
            if (!buildService.markFailed(build.getBuildId(), message)) {
                logger.warn("构建当前状态不允许标记失败（可能已切换或取消），跳过: buildId={}, status={}",
                        build.getBuildId(), build.getStatus());
            }
        } catch (Exception e) {
            logger.warn("标记构建失败时出错，忽略: buildId={}, error={}", build.getBuildId(), e.getMessage());
        }
    }

    /**
     * 合并中超时：合并成功前分片不会删除，改回上传中让用户重新发起合并
     */
    int recoverStaleMerges() {
        LocalDateTime now = now();
        LocalDateTime staleBefore = now.minusSeconds(properties.getMergeTimeoutSeconds());
        List<FileUpload> stale = fileUploadRepository.findStaleByUploadStatus(FileUpload.STATUS_MERGING, staleBefore, page());
        int handled = 0;
        for (FileUpload file : stale) {
            int updated = fileUploadRepository.resetStaleMerge(
                    file.getId(), FileUpload.STATUS_MERGING, FileUpload.STATUS_UPLOADING, file.getHeartbeatAt(), now);
            if (updated == 1) {
                handled++;
                logger.warn("合并超时，已改回上传中: fileMd5={}, userId={}, fileUploadId={}, mergeStartedAt={}",
                        file.getFileMd5(), file.getUserId(), file.getId(), file.getHeartbeatAt());
            }
        }
        return handled;
    }

    /**
     * 长时间没有新分片的上传：先条件删除记录，删成功再清理分片对象、分片记录和 bitmap
     */
    int cleanupAbandonedUploads() {
        LocalDateTime staleBefore = now().minusHours(properties.getAbandonedUploadHours());
        List<FileUpload> stale = fileUploadRepository.findStaleByUploadStatus(FileUpload.STATUS_UPLOADING, staleBefore, page());
        int handled = 0;
        for (FileUpload file : stale) {
            if (fileUploadRepository.deleteAbandonedUpload(file.getId(), FileUpload.STATUS_UPLOADING, file.getHeartbeatAt()) == 0) {
                continue;
            }
            handled++;
            uploadService.cleanupAbandonedChunks(file.getFileMd5(), file.getUserId());
            logger.info("已清理废弃上传: fileMd5={}, userId={}, fileName={}, lastHeartbeat={}",
                    file.getFileMd5(), file.getUserId(), file.getFileName(), file.getHeartbeatAt());
        }
        return handled;
    }

    /**
     * 重新投递统一走上传处理任务，文件从 MinIO 的合并对象读取，不依赖会过期的预签名 URL
     */
    private FileProcessingTask buildRetryTask(FileUpload file) {
        return new FileProcessingTask(
                file.getFileMd5(),
                null,
                file.getFileName(),
                file.getUserId(),
                file.getOrgTag(),
                file.isPublic(),
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                file.getUserId()
        );
    }

    /**
     * 带构建的任务必须重投原来的构建上下文：否则同 md5 的重建会被当成 legacy 任务，
     * 按 fileMd5 清掉旧 active 构建的索引和分块
     */
    private FileProcessingTask buildRetryTask(FileUpload file, DocumentVersion version, DocumentBuild build) {
        return new FileProcessingTask(
                file.getFileMd5(),
                null,
                file.getFileName(),
                file.getUserId(),
                version.getOrgTag(),
                version.isPublic(),
                FileProcessingTask.TASK_TYPE_UPLOAD_PROCESS,
                file.getUserId(),
                version.getDocumentId(),
                version.getVersionId(),
                build.getBuildId(),
                FileProcessingTask.BUILD_KIND_REINDEX,
                version.getVersionNo(),
                build.getBuildNo(),
                version.getDocType(),
                version.getJurisdiction(),
                version.getIssuer(),
                version.getEffectiveAt() == null ? null : version.getEffectiveAt().toString(),
                version.getApplicableInstitutions(),
                version.getExpiresAt() == null ? null : version.getExpiresAt().toString(),
                version.isRepealed()
        );
    }

    private void runStep(String name, java.util.function.IntSupplier step) {
        try {
            int handled = step.getAsInt();
            if (handled > 0) {
                logger.info("{}完成，本轮处理 {} 条", name, handled);
            }
        } catch (Exception e) {
            logger.error("{}执行失败: {}", name, e.getMessage(), e);
        }
    }

    private PageRequest page() {
        return PageRequest.of(0, Math.max(properties.getRecoveryBatchSize(), 1));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
