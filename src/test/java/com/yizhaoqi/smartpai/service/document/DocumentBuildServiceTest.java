package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * 构建状态机与切换事务：在 H2 上真实走仓储，ES 与检索范围服务用 mock。
 * 覆盖首个 build、同版本重建、新版本切换、删除文档、非 READY 状态与幂等切换。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DocumentBuildService.class)
class DocumentBuildServiceTest {

    @Autowired
    private DocumentBuildService buildService;

    @Autowired
    private BusinessDocumentRepository documentRepository;

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Autowired
    private DocumentBuildRepository buildRepository;

    @MockitoBean
    private ElasticsearchService elasticsearchService;

    @MockitoBean
    private ActiveRetrievalScopeService activeScopeService;

    @Test
    void firstBuildBecomesActiveAndUpdatesDocumentPointers() {
        BusinessDocument document = saveDocument("首个构建");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);

        assertEquals(1, buildService.nextBuildNo(version.getVersionId()));
        DocumentBuild build = buildService.createBuild(version.getVersionId(), 1);
        assertEquals(DocumentBuildStatus.PENDING, build.getStatus());
        assertEquals(1, build.getBuildNo());
        assertEquals(version.getVersionId(), build.getVersionId());
        assertNotNull(build.getCreatedAt());

        buildService.markProcessing(build.getBuildId());
        DocumentBuild processing = reload(build.getBuildId());
        assertEquals(DocumentBuildStatus.PROCESSING, processing.getStatus());
        assertNotNull(processing.getStartedAt());

        buildService.markReady(build.getBuildId(), 3);
        DocumentBuild ready = reload(build.getBuildId());
        assertEquals(DocumentBuildStatus.READY, ready.getStatus());
        assertEquals(3, ready.getChunkCount());
        assertNotNull(ready.getCompletedAt());

        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(build.getBuildId()));

        DocumentBuild active = reload(build.getBuildId());
        assertEquals(DocumentBuildStatus.ACTIVE, active.getStatus());
        BusinessDocument switched = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(version.getVersionId(), switched.getActiveVersionId());
        assertEquals(build.getBuildId(), switched.getActiveBuildId());
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.PUBLISHED, switched.getPublicationStatus());
        assertFalse(switched.isMetadataRevisionPending());
        // 首个构建没有旧 build 需要淘汰
        assertTrue(buildRepository.findByVersionIdOrderByBuildNoDesc(version.getVersionId()).stream()
                .filter(other -> !other.getBuildId().equals(build.getBuildId()))
                .noneMatch(other -> other.getStatus() == DocumentBuildStatus.SUPERSEDED));
    }

    @Test
    void secondBuildOnSameVersionSupersedesFirstWithoutVersionBump() {
        BusinessDocument document = saveDocument("同版本重建");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild first = readyAndActivate(version, 3);

        assertEquals(2, buildService.nextBuildNo(version.getVersionId()));
        DocumentBuild second = readyAndActivate(version, 5);

        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(first.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(second.getBuildId()).getStatus());
        BusinessDocument switched = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(version.getVersionId(), switched.getActiveVersionId());
        assertEquals(second.getBuildId(), switched.getActiveBuildId());
        // 重建不改变内容版本号
        assertEquals(1, versionRepository.findById(version.getVersionId()).orElseThrow().getVersionNo());
    }

    @Test
    void buildOnNewVersionSupersedesOldBuildAndRetiresLegacyRepresentation() {
        BusinessDocument document = saveDocument("新版本切换");
        DocumentVersion legacy = saveVersion(document.getDocumentId(), 1, true);
        DocumentVersion oldVersion = saveVersion(document.getDocumentId(), 2, false);
        DocumentVersion newVersion = saveVersion(document.getDocumentId(), 3, false);

        DocumentBuild oldBuild = readyAndActivate(oldVersion, 2);
        DocumentBuild newBuild = readyAndActivate(newVersion, 4);

        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(oldBuild.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(newBuild.getBuildId()).getStatus());
        BusinessDocument switched = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(newVersion.getVersionId(), switched.getActiveVersionId());
        assertEquals(newBuild.getBuildId(), switched.getActiveBuildId());
        // 切换事务同时退休 legacy 表示，旧 md5 分块退出检索
        assertTrue(versionRepository.findById(legacy.getVersionId()).orElseThrow().isLegacyRetired());
        assertFalse(versionRepository.findById(oldVersion.getVersionId()).orElseThrow().isLegacyRetired());
        assertFalse(versionRepository.findById(newVersion.getVersionId()).orElseThrow().isLegacyRetired());
    }

    @Test
    void deletedDocumentCancelsBuildAndKeepsActivePointers() {
        BusinessDocument document = saveDocument("删除中的文档");
        DocumentVersion activeVersion = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild activeBuild = readyAndActivate(activeVersion, 2);

        DocumentVersion newVersion = saveVersion(document.getDocumentId(), 2, false);
        DocumentBuild candidate = buildService.createBuild(newVersion.getVersionId(), 1);
        buildService.markProcessing(candidate.getBuildId());
        buildService.markReady(candidate.getBuildId(), 2);

        document.setDeletedAt(LocalDateTime.now());
        documentRepository.save(document);

        assertEquals(DocumentBuildService.BuildActivation.CANCELLED, buildService.activate(candidate.getBuildId()));

        DocumentBuild cancelled = reload(candidate.getBuildId());
        assertEquals(DocumentBuildStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getCompletedAt());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(activeVersion.getVersionId(), unchanged.getActiveVersionId());
        assertEquals(activeBuild.getBuildId(), unchanged.getActiveBuildId());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(activeBuild.getBuildId()).getStatus());
    }

    @Test
    void activateNonReadyBuildReturnsNotReadyWithoutSwitching() {
        BusinessDocument document = saveDocument("未就绪构建");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);

        DocumentBuild pending = buildService.createBuild(version.getVersionId(), 1);
        assertEquals(DocumentBuildService.BuildActivation.NOT_READY, buildService.activate(pending.getBuildId()));
        assertEquals(DocumentBuildStatus.PENDING, reload(pending.getBuildId()).getStatus());

        buildService.markProcessing(pending.getBuildId());
        assertEquals(DocumentBuildService.BuildActivation.NOT_READY, buildService.activate(pending.getBuildId()));
        assertEquals(DocumentBuildStatus.PROCESSING, reload(pending.getBuildId()).getStatus());

        BusinessDocument untouched = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertNull(untouched.getActiveVersionId());
        assertNull(untouched.getActiveBuildId());
    }

    @Test
    void activateTwiceIsIdempotentAndLeavesSupersededBuildUntouched() {
        BusinessDocument document = saveDocument("幂等切换");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild first = readyAndActivate(version, 2);
        DocumentBuild second = readyAndActivate(version, 3);

        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(second.getBuildId()));

        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(first.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(second.getBuildId()).getStatus());
        BusinessDocument switched = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(version.getVersionId(), switched.getActiveVersionId());
        assertEquals(second.getBuildId(), switched.getActiveBuildId());
    }

    @Test
    void lateActivationOfOlderVersionIsStaleAndKeepsNewerActive() {
        BusinessDocument document = saveDocument("乱序激活");
        DocumentVersion lateVersion = saveVersion(document.getDocumentId(), 2, false);
        DocumentVersion currentVersion = saveVersion(document.getDocumentId(), 3, false);
        DocumentBuild newer = readyAndActivate(currentVersion, 3);

        DocumentBuild late = buildService.createBuild(lateVersion.getVersionId(), 1);
        assertTrue(buildService.markProcessing(late.getBuildId()));
        assertTrue(buildService.markReady(late.getBuildId(), 2));

        assertEquals(DocumentBuildService.BuildActivation.STALE, buildService.activate(late.getBuildId()));

        // 迟到的旧版本构建被终止，active 指针仍指向 v3
        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(late.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(newer.getBuildId()).getStatus());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(currentVersion.getVersionId(), unchanged.getActiveVersionId());
        assertEquals(newer.getBuildId(), unchanged.getActiveBuildId());
    }

    @Test
    void activatingAlreadyActiveBuildIsIdempotent() {
        BusinessDocument document = saveDocument("同构建幂等");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild build = readyAndActivate(version, 2);

        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(build.getBuildId()));

        assertEquals(DocumentBuildStatus.ACTIVE, reload(build.getBuildId()).getStatus());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(build.getBuildId(), unchanged.getActiveBuildId());
    }

    @Test
    void illegalTransitionsAreRejectedAndLeaveStatusUntouched() {
        BusinessDocument document = saveDocument("非法迁移");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild superseded = readyAndActivate(version, 2);
        DocumentBuild active = readyAndActivate(version, 3);

        assertFalse(buildService.markProcessing(superseded.getBuildId()));
        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(superseded.getBuildId()).getStatus());

        assertFalse(buildService.markReady(active.getBuildId(), 9));
        assertFalse(buildService.markFailed(active.getBuildId(), "迟到的失败"));
        assertFalse(buildService.markCancelled(active.getBuildId()));

        DocumentBuild activeReloaded = reload(active.getBuildId());
        assertEquals(DocumentBuildStatus.ACTIVE, activeReloaded.getStatus());
        assertEquals(3, activeReloaded.getChunkCount());
        assertNull(activeReloaded.getErrorMessage());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(active.getBuildId(), unchanged.getActiveBuildId());
    }

    @Test
    void repeatedMarkCallsForSameStateAreIdempotent() {
        BusinessDocument document = saveDocument("重复投递");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild build = buildService.createBuild(version.getVersionId(), 1);

        assertTrue(buildService.markProcessing(build.getBuildId()));
        assertTrue(buildService.markProcessing(build.getBuildId()));
        assertEquals(DocumentBuildStatus.PROCESSING, reload(build.getBuildId()).getStatus());

        assertTrue(buildService.markReady(build.getBuildId(), 4));
        assertTrue(buildService.markReady(build.getBuildId(), 99));
        DocumentBuild ready = reload(build.getBuildId());
        assertEquals(DocumentBuildStatus.READY, ready.getStatus());
        assertEquals(4, ready.getChunkCount(), "重复 markReady 不覆盖第一次的分块数");

        assertTrue(buildService.markFailed(build.getBuildId(), "boom"));
        assertTrue(buildService.markFailed(build.getBuildId(), "other"));
        assertEquals(DocumentBuildStatus.FAILED, reload(build.getBuildId()).getStatus());

        DocumentBuild cancelled = buildService.createBuild(version.getVersionId(), 2);
        assertTrue(buildService.markCancelled(cancelled.getBuildId()));
        assertTrue(buildService.markCancelled(cancelled.getBuildId()));
        assertEquals(DocumentBuildStatus.CANCELLED, reload(cancelled.getBuildId()).getStatus());
    }

    @Test
    void markFailedTruncatesErrorAndMarkCancelledRecordsCompletion() {
        BusinessDocument document = saveDocument("失败与取消");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);

        DocumentBuild failed = buildService.createBuild(version.getVersionId(), 1);
        buildService.markFailed(failed.getBuildId(), "错".repeat(1200));
        DocumentBuild failedReloaded = reload(failed.getBuildId());
        assertEquals(DocumentBuildStatus.FAILED, failedReloaded.getStatus());
        assertEquals(1000, failedReloaded.getErrorMessage().length());
        assertNotNull(failedReloaded.getCompletedAt());

        DocumentBuild cancelled = buildService.createBuild(version.getVersionId(), 2);
        buildService.markCancelled(cancelled.getBuildId());
        DocumentBuild cancelledReloaded = reload(cancelled.getBuildId());
        assertEquals(DocumentBuildStatus.CANCELLED, cancelledReloaded.getStatus());
        assertNotNull(cancelledReloaded.getCompletedAt());
    }

    @Test
    void futureEffectiveBuildStaysReadyAndDoesNotReplaceOldActive() {
        BusinessDocument document = saveDocument("未来生效新版");
        DocumentVersion oldVersion = saveVersion(document.getDocumentId(), 1, false);
        oldVersion.setEffectiveAt(LocalDate.now().minusDays(1));
        versionRepository.save(oldVersion);
        DocumentBuild oldBuild = readyAndActivate(oldVersion, 2);

        DocumentVersion futureVersion = saveVersion(document.getDocumentId(), 2, false);
        futureVersion.setEffectiveAt(LocalDate.now().plusDays(1));
        versionRepository.save(futureVersion);
        DocumentBuild futureBuild = buildService.createBuild(futureVersion.getVersionId(), 1);
        buildService.markProcessing(futureBuild.getBuildId());
        buildService.markReady(futureBuild.getBuildId(), 3);

        assertEquals(DocumentBuildService.BuildActivation.PENDING_EFFECTIVE,
                buildService.activate(futureBuild.getBuildId()));

        assertEquals(DocumentBuildStatus.READY, reload(futureBuild.getBuildId()).getStatus(),
                "构建就绪但不能提前切到业务生效");
        assertEquals(DocumentBuildStatus.ACTIVE, reload(oldBuild.getBuildId()).getStatus());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(oldVersion.getVersionId(), unchanged.getActiveVersionId(), "过渡期旧版继续服务");
        assertEquals(oldBuild.getBuildId(), unchanged.getActiveBuildId());
    }

    @Test
    void unconfirmedNewVersionDoesNotReplaceConfirmedActiveVersion() {
        BusinessDocument document = saveDocument("待确认新版");
        DocumentVersion activeVersion = saveVersion(document.getDocumentId(), 1, false);
        activeVersion.setEffectiveAt(LocalDate.now().minusDays(1));
        versionRepository.save(activeVersion);
        DocumentBuild activeBuild = readyAndActivate(activeVersion, 2);

        DocumentVersion unconfirmedVersion = saveVersion(document.getDocumentId(), 2, false);
        unconfirmedVersion.setEffectiveAt(null);
        versionRepository.save(unconfirmedVersion);
        DocumentBuild candidate = buildService.createBuild(unconfirmedVersion.getVersionId(), 1);
        buildService.markProcessing(candidate.getBuildId());
        buildService.markReady(candidate.getBuildId(), 3);

        assertEquals(DocumentBuildService.BuildActivation.PENDING_EFFECTIVE,
                buildService.activate(candidate.getBuildId()));

        assertEquals(DocumentBuildStatus.READY, reload(candidate.getBuildId()).getStatus());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(activeVersion.getVersionId(), unchanged.getActiveVersionId());
        assertEquals(activeBuild.getBuildId(), unchanged.getActiveBuildId());
    }

    @Test
    void withdrawnDocumentStaysWithdrawnAfterBuildActivation() {
        BusinessDocument document = saveDocument("已下架");
        document.setPublicationStatus(com.yizhaoqi.smartpai.model.PublicationStatus.WITHDRAWN);
        documentRepository.save(document);
        DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);
        DocumentBuild build = buildService.createBuild(version.getVersionId(), 1);
        buildService.markProcessing(build.getBuildId());
        buildService.markReady(build.getBuildId(), 2);

        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(build.getBuildId()));

        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(com.yizhaoqi.smartpai.model.PublicationStatus.WITHDRAWN, unchanged.getPublicationStatus(),
                "构建完成或定时任务不能自动解除下架");
        assertEquals(build.getBuildId(), unchanged.getActiveBuildId());
    }

    @Test
    void expiredOrRepealedVersionIsCancelledInsteadOfActivated() {
        BusinessDocument document = saveDocument("已失效版本");
        DocumentVersion expired = saveVersion(document.getDocumentId(), 1, false);
        expired.setEffectiveAt(LocalDate.now().minusDays(10));
        expired.setExpiresAt(LocalDate.now().minusDays(1));
        versionRepository.save(expired);

        DocumentBuild build = buildService.createBuild(expired.getVersionId(), 1);
        buildService.markProcessing(build.getBuildId());
        buildService.markReady(build.getBuildId(), 2);

        assertEquals(DocumentBuildService.BuildActivation.CANCELLED,
                buildService.activate(build.getBuildId()));

        assertEquals(DocumentBuildStatus.CANCELLED, reload(build.getBuildId()).getStatus());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertNull(unchanged.getActiveBuildId());
    }

    @Test
    void expiredBuildIsNotActivatedEvenWhenNoOldVersionExists() {
        BusinessDocument document = saveDocument("废止版本");
        DocumentVersion repealed = saveVersion(document.getDocumentId(), 1, false);
        repealed.setEffectiveAt(LocalDate.now().minusDays(10));
        repealed.setRepealed(true);
        versionRepository.save(repealed);

        DocumentBuild build = buildService.createBuild(repealed.getVersionId(), 1);
        buildService.markProcessing(build.getBuildId());
        buildService.markReady(build.getBuildId(), 2);

        assertEquals(DocumentBuildService.BuildActivation.CANCELLED,
                buildService.activate(build.getBuildId()));
        assertEquals(DocumentBuildStatus.CANCELLED, reload(build.getBuildId()).getStatus());
    }

    @Test
    void createBuildRejectsUnknownVersion() {
        assertThrows(IllegalArgumentException.class, () -> buildService.createBuild("missing-version", 1));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void activateInvalidatesScopeAfterCommit() {
        // 本用例不参与测试事务回滚，先清掉其他用例可能已提交的构建/版本/文档，避免 fixture 上传ID冲突
        buildRepository.deleteAll();
        versionRepository.deleteAll();
        documentRepository.deleteAll();

        try {
            BusinessDocument document = saveDocument("切换后失效范围");
            DocumentVersion version = saveVersion(document.getDocumentId(), 1, false);

            readyAndActivate(version, 2);

            verify(activeScopeService).invalidateAndBroadcast();
        } finally {
            // 提交的数据会留在共享 H2 中，结束后清理，避免影响其他测试类的精确集合断言
            buildRepository.deleteAll();
            versionRepository.deleteAll();
            documentRepository.deleteAll();
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong FIXTURE_UPLOAD_ID =
            new java.util.concurrent.atomic.AtomicLong(-100000);

    private DocumentBuild readyAndActivate(DocumentVersion version, int chunkCount) {
        int buildNo = buildService.nextBuildNo(version.getVersionId());
        DocumentBuild build = buildService.createBuild(version.getVersionId(), buildNo);
        buildService.markProcessing(build.getBuildId());
        buildService.markReady(build.getBuildId(), chunkCount);
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(build.getBuildId()));
        return build;
    }

    private DocumentBuild reload(String buildId) {
        return buildRepository.findById(buildId).orElseThrow();
    }

    private BusinessDocument saveDocument(String title) {
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(UUID.randomUUID().toString());
        document.setOwnerUserId("user-1");
        document.setTitle(title);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        return documentRepository.save(document);
    }

    private DocumentVersion saveVersion(String documentId, int versionNo, boolean legacyRepresentation) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setFileMd5(UUID.randomUUID().toString().replace("-", ""));
        // file_upload_id 在版本表上唯一；用独立递减值避免已提交测试数据与后续用例冲突
        version.setFileUploadId(FIXTURE_UPLOAD_ID.decrementAndGet());
        version.setFileName("v" + versionNo + ".pdf");
        // 默认给已确认的生效日期；需要“效力待确认”的用例显式覆盖为 null
        version.setEffectiveAt(LocalDate.now().minusDays(1));
        version.setLegacyRepresentation(legacyRepresentation);
        version.setCreatedAt(LocalDateTime.now());
        return versionRepository.save(version);
    }
}
