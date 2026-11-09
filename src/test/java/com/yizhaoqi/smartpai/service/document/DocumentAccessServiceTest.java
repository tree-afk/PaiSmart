package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAccessServiceTest {

    private final DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
    private final BusinessDocumentRepository documentRepository = mock(BusinessDocumentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final DocumentAccessService service =
            new DocumentAccessService(versionRepository, documentRepository, userRepository);

    @Test
    void readerCanReadPublishedActiveVersion() {
        stubVersionAndDocument(PublicationStatus.PUBLISHED, "b1", LocalDate.now().minusDays(1), null, false);

        assertTrue(service.canRead("7", "md5-a"));
    }

    @Test
    void draftOrWithdrawnOrDeletedOrNoActiveBuildIsNotReadable() {
        assertFalse(service.canRead("7", "md5-a"), "没有版本记录");
        stubVersionAndDocument(PublicationStatus.DRAFT, "b1", LocalDate.now().minusDays(1), null, false);
        assertFalse(service.canRead("7", "md5-a"));
        stubVersionAndDocument(PublicationStatus.PUBLISHED, null, LocalDate.now().minusDays(1), null, false);
        assertFalse(service.canRead("7", "md5-a"));
        stubVersionAndDocument(PublicationStatus.PUBLISHED, "b1", LocalDate.now().plusDays(1), null, false);
        assertFalse(service.canRead("7", "md5-a"), "未来生效版本不可读");
        stubVersionAndDocument(PublicationStatus.PUBLISHED, "b1", LocalDate.now().minusDays(10),
                LocalDate.now().minusDays(1), false);
        assertFalse(service.canRead("7", "md5-a"), "已失效版本不可读");
    }

    @Test
    void readerCannotReadInactiveVersionOfPublishedDocument() {
        stubVersionAndDocument(PublicationStatus.PUBLISHED, "b1", LocalDate.now().minusDays(1), null, false);
        DocumentVersion pendingVersion = new DocumentVersion();
        pendingVersion.setVersionId("v2");
        pendingVersion.setDocumentId("doc-1");
        pendingVersion.setEffectiveAt(LocalDate.now().minusDays(1));
        when(versionRepository.findByFileMd5OrderByCreatedAtDesc("md5-v2"))
                .thenReturn(List.of(pendingVersion));

        assertFalse(service.canRead("7", "md5-v2"), "未激活的新版本不能通过 md5 读取");
    }

    @Test
    void sharedMd5IsReadableWhenAnyCopyIsStillPublishedAndActive() {
        DocumentVersion published = version("v1", "doc-published", LocalDate.now().minusDays(1));
        DocumentVersion draft = version("v2", "doc-draft", LocalDate.now().minusDays(1));
        when(versionRepository.findByFileMd5OrderByCreatedAtDesc("md5-shared"))
                .thenReturn(List.of(draft, published));
        when(versionRepository.findById("v1")).thenReturn(Optional.of(published));
        when(versionRepository.findById("v2")).thenReturn(Optional.of(draft));
        when(documentRepository.findById("doc-published"))
                .thenReturn(Optional.of(document("doc-published", PublicationStatus.PUBLISHED, "b1", "v1")));
        when(documentRepository.findById("doc-draft"))
                .thenReturn(Optional.of(document("doc-draft", PublicationStatus.DRAFT, "b2", "v2")));

        assertTrue(service.canRead("7", "md5-shared"), "较早的已发布副本不能被最新草稿遮住");
        assertTrue(service.canRead("7", "doc-published", "v1"));
        assertFalse(service.canRead("7", "doc-draft", "v2"), "精确引用仍必须指向 active 版本");
    }

    @Test
    void adminCanReadForManagement() {
        User admin = new User();
        admin.setId(1L);
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertTrue(service.canRead("admin", "md5-any"));
    }

    private void stubVersionAndDocument(PublicationStatus status, String activeBuildId,
                                        LocalDate effectiveAt, LocalDate expiresAt, boolean repealed) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId("v-active");
        version.setDocumentId("doc-1");
        version.setEffectiveAt(effectiveAt);
        version.setExpiresAt(expiresAt);
        version.setRepealed(repealed);
        when(versionRepository.findByFileMd5OrderByCreatedAtDesc("md5-a")).thenReturn(List.of(version));
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId("doc-1");
        document.setPublicationStatus(status);
        document.setActiveBuildId(activeBuildId);
        document.setActiveVersionId("v-active");
        when(documentRepository.findById("doc-1")).thenReturn(Optional.of(document));
    }

    private DocumentVersion version(String versionId, String documentId, LocalDate effectiveAt) {
        DocumentVersion version = new DocumentVersion();
        version.setVersionId(versionId);
        version.setDocumentId(documentId);
        version.setEffectiveAt(effectiveAt);
        return version;
    }

    private BusinessDocument document(String documentId, PublicationStatus status,
                                      String activeBuildId, String activeVersionId) {
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId(documentId);
        document.setPublicationStatus(status);
        document.setActiveBuildId(activeBuildId);
        document.setActiveVersionId(activeVersionId);
        return document;
    }
}
