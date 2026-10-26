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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 分块数核对与切换：核对不一致或 ES 异常时调用方把构建标失败，旧 active 保持不变；
 * 核对通过后 READY -> ACTIVE，旧 build 变 SUPERSEDED。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DocumentBuildService.class)
class DocumentBuildSwitchTest {

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
    void countMismatchFailsBuildAndKeepsOldActive() {
        Fixture fixture = activeFixture();
        DocumentBuild candidate = candidateBuild(fixture);

        when(elasticsearchService.countByBuildId(candidate.getBuildId())).thenReturn(2L);

        assertFalse(buildService.chunkCountMatches(candidate.getBuildId(), 3));
        // 消费者侧编排：核对失败后把构建标 FAILED，而不是切换
        buildService.markFailed(candidate.getBuildId(), "分块数核对不一致：期望 3，实际 2");

        assertEquals(DocumentBuildStatus.FAILED, reload(candidate.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(fixture.activeBuild().getBuildId()).getStatus());
        BusinessDocument document = documentRepository.findById(fixture.document().getDocumentId()).orElseThrow();
        assertEquals(fixture.activeVersion().getVersionId(), document.getActiveVersionId());
        assertEquals(fixture.activeBuild().getBuildId(), document.getActiveBuildId());
    }

    @Test
    void matchingCountMarksReadyAndActivatesBuild() {
        Fixture fixture = activeFixture();
        DocumentBuild candidate = candidateBuild(fixture);

        when(elasticsearchService.countByBuildId(candidate.getBuildId())).thenReturn(3L);

        assertTrue(buildService.chunkCountMatches(candidate.getBuildId(), 3));
        buildService.markReady(candidate.getBuildId(), 3);
        assertEquals(DocumentBuildStatus.READY, reload(candidate.getBuildId()).getStatus());
        assertEquals(DocumentBuildService.BuildActivation.ACTIVE, buildService.activate(candidate.getBuildId()));

        assertEquals(DocumentBuildStatus.ACTIVE, reload(candidate.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.SUPERSEDED, reload(fixture.activeBuild().getBuildId()).getStatus());
        BusinessDocument document = documentRepository.findById(fixture.document().getDocumentId()).orElseThrow();
        assertEquals(fixture.candidateVersion().getVersionId(), document.getActiveVersionId());
        assertEquals(candidate.getBuildId(), document.getActiveBuildId());
    }

    @Test
    void countQueryErrorFailsBuildAndKeepsOldActive() {
        Fixture fixture = activeFixture();
        DocumentBuild candidate = candidateBuild(fixture);

        when(elasticsearchService.countByBuildId(candidate.getBuildId()))
                .thenThrow(new RuntimeException("统计构建分块数失败", new RuntimeException("es down")));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> buildService.chunkCountMatches(candidate.getBuildId(), 3));
        assertEquals("统计构建分块数失败", error.getMessage());
        // 消费者侧编排：ES 异常同样把构建标 FAILED，不进入切换
        buildService.markFailed(candidate.getBuildId(), error.getMessage());

        assertEquals(DocumentBuildStatus.FAILED, reload(candidate.getBuildId()).getStatus());
        assertEquals(DocumentBuildStatus.ACTIVE, reload(fixture.activeBuild().getBuildId()).getStatus());
        BusinessDocument document = documentRepository.findById(fixture.document().getDocumentId()).orElseThrow();
        assertEquals(fixture.activeBuild().getBuildId(), document.getActiveBuildId());
    }

    @Test
    void zeroExpectedChunksNeverMatches() {
        Fixture fixture = activeFixture();
        DocumentBuild candidate = candidateBuild(fixture);

        when(elasticsearchService.countByBuildId(candidate.getBuildId())).thenReturn(0L);

        assertFalse(buildService.chunkCountMatches(candidate.getBuildId(), 0));
        assertEquals(DocumentBuildStatus.PROCESSING, reload(candidate.getBuildId()).getStatus());
        BusinessDocument document = documentRepository.findById(fixture.document().getDocumentId()).orElseThrow();
        assertEquals(fixture.activeBuild().getBuildId(), document.getActiveBuildId());
    }

    private Fixture activeFixture() {
        BusinessDocument document = saveDocument("切换核对");
        DocumentVersion activeVersion = saveVersion(document.getDocumentId(), 1);
        DocumentBuild activeBuild = readyAndActivate(activeVersion, 2);
        DocumentVersion candidateVersion = saveVersion(document.getDocumentId(), 2);
        return new Fixture(document, activeVersion, activeBuild, candidateVersion);
    }

    private DocumentBuild candidateBuild(Fixture fixture) {
        DocumentBuild build = buildService.createBuild(fixture.candidateVersion().getVersionId(), 1);
        buildService.markProcessing(build.getBuildId());
        return build;
    }

    private DocumentBuild readyAndActivate(DocumentVersion version, int chunkCount) {
        DocumentBuild build = buildService.createBuild(version.getVersionId(), buildService.nextBuildNo(version.getVersionId()));
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

    private DocumentVersion saveVersion(String documentId, int versionNo) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(UUID.randomUUID().toString());
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setFileMd5(UUID.randomUUID().toString().replace("-", ""));
        version.setFileUploadId((long) versionNo);
        version.setFileName("v" + versionNo + ".pdf");
        version.setEffectiveAt(LocalDate.now().minusDays(1));
        version.setCreatedAt(LocalDateTime.now());
        return versionRepository.save(version);
    }

    private record Fixture(BusinessDocument document,
                           DocumentVersion activeVersion,
                           DocumentBuild activeBuild,
                           DocumentVersion candidateVersion) {
    }
}
