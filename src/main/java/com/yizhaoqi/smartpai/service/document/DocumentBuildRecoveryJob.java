package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * READY 构建的兜底恢复：消费者在 markReady 之后、activate 之前中断时，
 * 定时重试切换。切换本身幂等；文档已删除时 activate 会把构建标 CANCELLED。
 */
@Component
public class DocumentBuildRecoveryJob {

    private static final Logger logger = LoggerFactory.getLogger(DocumentBuildRecoveryJob.class);
    private static final long READY_GRACE_SECONDS = 60;

    private final DocumentBuildRepository buildRepository;
    private final DocumentBuildService buildService;

    public DocumentBuildRecoveryJob(DocumentBuildRepository buildRepository,
                                    DocumentBuildService buildService) {
        this.buildRepository = buildRepository;
        this.buildService = buildService;
    }

    @Scheduled(fixedDelayString = "${document.version.ready-recovery-ms:60000}")
    public void retryReady() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(READY_GRACE_SECONDS);
        List<DocumentBuild> readyBuilds =
                buildRepository.findByStatusAndCompletedAtBefore(DocumentBuildStatus.READY, threshold);
        for (DocumentBuild build : readyBuilds) {
            try {
                DocumentBuildService.BuildActivation result = buildService.activate(build.getBuildId());
                if (result == DocumentBuildService.BuildActivation.PENDING_EFFECTIVE) {
                    logger.debug("READY 构建尚未到业务生效日期: buildId={}", build.getBuildId());
                } else {
                    logger.info("重试切换 READY 构建: buildId={}, 结果={}", build.getBuildId(), result);
                }
            } catch (Exception e) {
                logger.warn("重试切换 READY 构建失败: buildId={}", build.getBuildId(), e);
            }
        }
    }
}
