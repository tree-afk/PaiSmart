package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentBuild;
import com.yizhaoqi.smartpai.model.DocumentBuildStatus;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentBuildRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.service.KnowledgeAuditService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPublicationServiceTest {

    private final BusinessDocumentRepository documentRepository = mock(BusinessDocumentRepository.class);
    private final DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
    private final DocumentBuildRepository buildRepository = mock(DocumentBuildRepository.class);
    private final DocumentBuildService buildService = mock(DocumentBuildService.class);
    private final KnowledgeAuditService auditService = mock(KnowledgeAuditService.class);
    private final DocumentPublicationService service = new DocumentPublicationService(
            documentRepository, versionRepository, buildRepository, buildService, auditService);

    @Test
    void withdrawOnlyChangesPublicationStatusAndAudits() {
        BusinessDocument document = document("b1", PublicationStatus.PUBLISHED);
        when(documentRepository.findForUpdate("doc-1")).thenReturn(Optional.of(document));

        assertEquals(PublicationStatus.WITHDRAWN, service.withdraw("doc-1", "manager"));

        assertEquals(PublicationStatus.WITHDRAWN, document.getPublicationStatus());
        verify(documentRepository).save(document);
        verify(buildService).invalidateScopeAfterCommit();
        verify(auditService).record("manager", "DOCUMENT", "doc-1", "WITHDRAW", "PUBLISHED", "WITHDRAWN");
    }

    @Test
    void restoreRequiresAnActiveBuildAndValidVersion() {
        BusinessDocument document = document("b1", PublicationStatus.WITHDRAWN);
        when(documentRepository.findForUpdate("doc-1")).thenReturn(Optional.of(document));
        DocumentBuild build = build("b1", "v1", DocumentBuildStatus.ACTIVE);
        when(buildRepository.findById("b1")).thenReturn(Optional.of(build));
        DocumentVersion version = version("v1", LocalDate.now().minusDays(1), null, false);
        when(versionRepository.findById("v1")).thenReturn(Optional.of(version));

        assertEquals(PublicationStatus.PUBLISHED, service.restore("doc-1", "manager"));
        assertEquals(PublicationStatus.PUBLISHED, document.getPublicationStatus());
        verify(auditService).record("manager", "DOCUMENT", "doc-1", "RESTORE", "WITHDRAWN", "PUBLISHED");
    }

    @Test
    void restoreRejectsIncompleteMetadataRevision() {
        BusinessDocument document = document("b1", PublicationStatus.WITHDRAWN);
        document.setMetadataRevisionPending(true);
        when(documentRepository.findForUpdate("doc-1")).thenReturn(Optional.of(document));

        CustomException error = assertThrows(CustomException.class,
                () -> service.restore("doc-1", "manager"));

        assertEquals(409, error.getStatus().value());
    }

    @Test
    void restoreRejectsRegisteredRevisionBuildEvenWhenPendingFlagWasCleared() {
        BusinessDocument document = document("b1", PublicationStatus.WITHDRAWN);
        document.setMetadataRevisionPending(false);
        document.setMetadataRevisionBuildId("b-revision");
        when(documentRepository.findForUpdate("doc-1")).thenReturn(Optional.of(document));

        CustomException error = assertThrows(CustomException.class,
                () -> service.restore("doc-1", "manager"));

        assertEquals(409, error.getStatus().value());
        assertEquals(PublicationStatus.WITHDRAWN, document.getPublicationStatus());
    }

    @Test
    void restoreDoesNotFallBackToOlderBuildWhenLatestIsUnusable() {
        BusinessDocument document = document("b-old", PublicationStatus.WITHDRAWN);
        when(documentRepository.findForUpdate("doc-1")).thenReturn(Optional.of(document));
        DocumentBuild build = build("b-old", "v1", DocumentBuildStatus.FAILED);
        when(buildRepository.findById("b-old")).thenReturn(Optional.of(build));

        CustomException error = assertThrows(CustomException.class,
                () -> service.restore("doc-1", "manager"));

        assertEquals(409, error.getStatus().value());
        assertEquals(PublicationStatus.WITHDRAWN, document.getPublicationStatus());
    }

    private BusinessDocument document(String activeBuildId, PublicationStatus status) {
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId("doc-1");
        document.setOwnerUserId("owner");
        document.setTitle("doc");
        document.setActiveBuildId(activeBuildId);
        document.setActiveVersionId("v1");
        document.setPublicationStatus(status);
        return document;
    }

    private DocumentBuild build(String buildId, String versionId, DocumentBuildStatus status) {
        DocumentBuild build = new DocumentBuild();
        build.setBuildId(buildId);
        build.setVersionId(versionId);
        build.setStatus(status);
        return build;
    }

    private DocumentVersion version(String versionId, LocalDate effectiveAt, LocalDate expiresAt, boolean repealed) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocumentId("doc-1");
        version.setEffectiveAt(effectiveAt);
        version.setExpiresAt(expiresAt);
        version.setRepealed(repealed);
        return version;
    }
}
