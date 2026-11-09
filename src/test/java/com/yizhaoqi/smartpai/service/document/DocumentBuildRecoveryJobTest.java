package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * READY 构建恢复：只扫描超过宽限期的 READY 构建并重试切换；
 * 单条失败不影响其他构建，文档已删除时 activate 返回 CANCELLED 也不抛出。
 */
class DocumentBuildRecoveryJobTest {

    private final DocumentBuildRepository buildRepository = mock(DocumentBuildRepository.class);
    private final DocumentBuildService buildService = mock(DocumentBuildService.class);
    private final DocumentBuildRecoveryJob job = new DocumentBuildRecoveryJob(buildRepository, buildService);

    @Test
    void retriesReadyBuildsOlderThanGracePeriod() {
        DocumentBuild ready = readyBuild("build-1");
        when(buildRepository.findByStatusAndCompletedAtBefore(eq(DocumentBuildStatus.READY), any()))
                .thenReturn(List.of(ready));
        when(buildService.activate("build-1")).thenReturn(DocumentBuildService.BuildActivation.ACTIVE);

        job.retryReady();

        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(buildRepository).findByStatusAndCompletedAtBefore(eq(DocumentBuildStatus.READY), threshold.capture());
        assertTrue(threshold.getValue().isBefore(LocalDateTime.now()), "只重试超过宽限期的 READY 构建");
        verify(buildService).activate("build-1");
    }

    @Test
    void cancelledActivationForDeletedDocumentDoesNotThrow() {
        when(buildRepository.findByStatusAndCompletedAtBefore(eq(DocumentBuildStatus.READY), any()))
                .thenReturn(List.of(readyBuild("build-2")));
        when(buildService.activate("build-2")).thenReturn(DocumentBuildService.BuildActivation.CANCELLED);

        assertDoesNotThrow(job::retryReady);

        verify(buildService).activate("build-2");
    }

    @Test
    void staleActivationIsTerminalAndNotRetried() {
        when(buildRepository.findByStatusAndCompletedAtBefore(eq(DocumentBuildStatus.READY), any()))
                .thenReturn(List.of(readyBuild("build-5")));
        when(buildService.activate("build-5")).thenReturn(DocumentBuildService.BuildActivation.STALE);

        // STALE 是终态：服务侧已把构建标 SUPERSEDED，本轮不抛异常，下一轮扫描也不会再命中 READY
        assertDoesNotThrow(job::retryReady);

        verify(buildService).activate("build-5");
    }

    @Test
    void oneFailureDoesNotStopRemainingBuilds() {
        when(buildRepository.findByStatusAndCompletedAtBefore(eq(DocumentBuildStatus.READY), any()))
                .thenReturn(List.of(readyBuild("build-3"), readyBuild("build-4")));
        when(buildService.activate("build-3")).thenThrow(new IllegalStateException("版本缺失"));
        when(buildService.activate("build-4")).thenReturn(DocumentBuildService.BuildActivation.ACTIVE);

        assertDoesNotThrow(job::retryReady);

        verify(buildService).activate("build-3");
        verify(buildService).activate("build-4");
    }

    @Test
    void noReadyBuildsDoesNothing() {
        when(buildRepository.findByStatusAndCompletedAtBefore(eq(DocumentBuildStatus.READY), any()))
                .thenReturn(List.of());

        job.retryReady();

        verifyNoInteractions(buildService);
        verify(buildRepository, never()).findByStatus(DocumentBuildStatus.READY);
    }

    private DocumentBuild readyBuild(String buildId) {
        DocumentBuild build = new DocumentBuild();
        build.setBuildId(buildId);
        build.setVersionId("version-1");
        build.setBuildNo(1);
        build.setStatus(DocumentBuildStatus.READY);
        build.setCompletedAt(LocalDateTime.now().minusMinutes(5));
        return build;
    }
}
