package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 业务生效时间到达后的切换任务：构建 READY 只表示加工完成，未来生效的版本要等到 effectiveAt
 * 才替换 active 指针。已失效/废止的版本不在候选内。
 */
@Component
public class DocumentBuildEffectiveJob {

    private static final Logger logger = LoggerFactory.getLogger(DocumentBuildEffectiveJob.class);

    private final DocumentBuildRepository buildRepository;
    private final DocumentBuildService buildService;

    public DocumentBuildEffectiveJob(DocumentBuildRepository buildRepository,
                                     DocumentBuildService buildService) {
        this.buildRepository = buildRepository;
        this.buildService = buildService;
    }

    @Scheduled(fixedDelayString = "${document.version.effective-scan-ms:300000}")
    public void activateDueBuilds() {
        List<DocumentBuild> dueBuilds = buildRepository.findReadyEffective(
                DocumentBuildStatus.READY, LocalDate.now());
        for (DocumentBuild build : dueBuilds) {
            try {
                DocumentBuildService.BuildActivation result = buildService.activate(build.getBuildId());
                if (result == DocumentBuildService.BuildActivation.ACTIVE) {
                    logger.info("业务生效时间已到，切换构建: buildId={}", build.getBuildId());
                } else {
                    logger.debug("业务生效任务跳过构建: buildId={}, result={}", build.getBuildId(), result);
                }
            } catch (Exception e) {
                logger.warn("业务生效任务切换构建失败，保留 READY 等待下次重试: buildId={}", build.getBuildId(), e);
            }
        }
    }
}
