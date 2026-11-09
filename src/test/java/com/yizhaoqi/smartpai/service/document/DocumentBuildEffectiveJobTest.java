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
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DocumentBuildService.class, DocumentBuildEffectiveJob.class})
class DocumentBuildEffectiveJobTest {

    @Autowired
    private DocumentBuildEffectiveJob job;

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
    void activatesReadyBuildWhenEffectiveDateHasArrived() {
        BusinessDocument document = saveDocument("到期生效");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1);
        version.setEffectiveAt(LocalDate.now());
        versionRepository.save(version);
        DocumentBuild build = readyBuild(version);

        job.activateDueBuilds();

        assertEquals(DocumentBuildStatus.ACTIVE, buildRepository.findById(build.getBuildId()).orElseThrow().getStatus());
        BusinessDocument switched = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertEquals(build.getBuildId(), switched.getActiveBuildId());
        assertEquals(version.getVersionId(), switched.getActiveVersionId());
    }

    @Test
    void leavesFutureEffectiveReadyBuildUntouched() {
        BusinessDocument document = saveDocument("未来生效");
        DocumentVersion version = saveVersion(document.getDocumentId(), 1);
        version.setEffectiveAt(LocalDate.now().plusDays(1));
        versionRepository.save(version);
        DocumentBuild build = readyBuild(version);

        job.activateDueBuilds();

        assertEquals(DocumentBuildStatus.READY, buildRepository.findById(build.getBuildId()).orElseThrow().getStatus());
        BusinessDocument unchanged = documentRepository.findById(document.getDocumentId()).orElseThrow();
        assertNull(unchanged.getActiveBuildId());
    }

    private DocumentBuild readyBuild(DocumentVersion version) {
        DocumentBuild build = buildService.createBuild(version.getVersionId(), 1);
        buildService.markProcessing(build.getBuildId());
        buildService.markReady(build.getBuildId(), 2);
        return build;
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
        version.setFileUploadId((long) -Math.abs(UUID.randomUUID().hashCode() + 100_000));
        version.setFileName("v" + versionNo + ".pdf");
        version.setCreatedAt(LocalDateTime.now());
        return versionRepository.save(version);
    }
}
