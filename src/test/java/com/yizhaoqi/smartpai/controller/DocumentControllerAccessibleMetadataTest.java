package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /documents/accessible 在原有可见性列表上补充最新内容版本的标识与合规元数据；
 * 旧文件（legacy）没有版本记录时字段保持 null，列表权限与过滤不变。
 */
class DocumentControllerAccessibleMetadataTest {

    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentVersionRepository documentVersionRepository = mock(DocumentVersionRepository.class);
    private final BusinessDocumentRepository businessDocumentRepository = mock(BusinessDocumentRepository.class);
    private final OrganizationTagRepository organizationTagRepository = mock(OrganizationTagRepository.class);

    private DocumentController documentController;

    @BeforeEach
    void setUp() {
        documentController = new DocumentController();
        ReflectionTestUtils.setField(documentController, "documentService", documentService);
        ReflectionTestUtils.setField(documentController, "documentVersionRepository", documentVersionRepository);
        ReflectionTestUtils.setField(documentController, "businessDocumentRepository", businessDocumentRepository);
        ReflectionTestUtils.setField(documentController, "organizationTagRepository", organizationTagRepository);
    }

    @Test
    void accessibleListIncludesLatestVersionComplianceMetadata() {
        FileUpload versioned = upload(1L, "md5-1", "regulation.pdf");
        FileUpload legacy = upload(2L, "md5-2", "legacy.docx");
        when(documentService.getFileManagerFiles()).thenReturn(List.of(versioned, legacy));
        when(documentVersionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(1L))
                .thenReturn(Optional.of(version("doc-1", "ver-1", 2, "REGULATION",
                        "全国", "国家金融监督管理总局", LocalDate.of(2025, 3, 1))));
        when(documentVersionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(2L))
                .thenReturn(Optional.empty());
        BusinessDocument document = new BusinessDocument();
        document.setDocumentId("doc-1");
        document.setPublicationStatus(PublicationStatus.PUBLISHED);
        when(businessDocumentRepository.findById("doc-1")).thenReturn(Optional.of(document));

        var response = documentController.getAccessibleFiles("user-1", "TEAM_A", null, null);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(200, body.get("code"));
        List<?> rows = (List<?>) body.get("data");
        assertEquals(2, rows.size(), "列表行数不变");

        Map<?, ?> versionedRow = (Map<?, ?>) rows.get(0);
        assertEquals("md5-1", versionedRow.get("fileMd5"));
        assertEquals("regulation.pdf", versionedRow.get("fileName"));
        assertEquals("doc-1", versionedRow.get("documentId"));
        assertEquals("ver-1", versionedRow.get("versionId"));
        assertEquals(2, versionedRow.get("versionNo"));
        assertEquals("REGULATION", versionedRow.get("docType"));
        assertEquals("全国", versionedRow.get("jurisdiction"));
        assertEquals("NATIONAL", versionedRow.get("jurisdictionCode"));
        assertEquals("国家金融监督管理总局", versionedRow.get("issuer"));
        assertEquals("商业银行", versionedRow.get("applicableInstitutions"));
        assertEquals(LocalDate.of(2025, 3, 1), versionedRow.get("effectiveAt"));
        assertEquals(LocalDate.of(2027, 12, 31), versionedRow.get("expiresAt"));
        assertEquals(false, versionedRow.get("repealed"));
        assertEquals("EFFECTIVE", versionedRow.get("validityStatus"));
        assertEquals("已到生效日期", versionedRow.get("validityLabel"));
        assertEquals("PUBLISHED", versionedRow.get("publicationStatus"));
        assertEquals(false, versionedRow.get("metadataRevisionPending"));

        Map<?, ?> legacyRow = (Map<?, ?>) rows.get(1);
        assertEquals("md5-2", legacyRow.get("fileMd5"));
        assertTrue(legacyRow.containsKey("documentId"));
        assertNull(legacyRow.get("documentId"));
        assertNull(legacyRow.get("versionId"));
        assertNull(legacyRow.get("versionNo"));
        assertNull(legacyRow.get("docType"));
        assertNull(legacyRow.get("jurisdiction"));
        assertNull(legacyRow.get("jurisdictionCode"));
        assertNull(legacyRow.get("issuer"));
        assertNull(legacyRow.get("applicableInstitutions"));
        assertNull(legacyRow.get("effectiveAt"));
        assertNull(legacyRow.get("expiresAt"));
        assertNull(legacyRow.get("repealed"));
        assertNull(legacyRow.get("validityStatus"));
        assertNull(legacyRow.get("publicationStatus"));

        verify(documentService).getFileManagerFiles();
        verify(documentVersionRepository).findFirstByFileUploadIdOrderByVersionNoDesc(1L);
        verify(documentVersionRepository).findFirstByFileUploadIdOrderByVersionNoDesc(2L);
    }

    @Test
    void accessibleListKeepsExistingResponseShapeAndMetasForUploadWithoutId() {
        FileUpload uploading = upload(null, "md5-3", "uploading.pdf");
        when(documentService.getFileManagerFiles()).thenReturn(List.of(uploading));

        var response = documentController.getAccessibleFiles("user-1", "TEAM_A", 1, 10);

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> page = (Map<?, ?>) body.get("data");
        List<?> rows = (List<?>) page.get("content");
        assertEquals(1, rows.size());
        Map<?, ?> row = (Map<?, ?>) rows.get(0);
        assertEquals("uploading.pdf", row.get("fileName"));
        assertNull(row.get("documentId"));
        assertNull(row.get("docType"));
        verify(documentVersionRepository, never()).findFirstByFileUploadIdOrderByVersionNoDesc(null);
    }

    private FileUpload upload(Long id, String md5, String fileName) {
        FileUpload upload = new FileUpload();
        upload.setId(id);
        upload.setFileMd5(md5);
        upload.setFileName(fileName);
        upload.setUserId("user-1");
        upload.setStatus(FileUpload.STATUS_COMPLETED);
        return upload;
    }

    private DocumentVersion version(String documentId, String versionId, int versionNo, String docType,
                                    String jurisdiction, String issuer, LocalDate effectiveAt) {
        DocumentVersion version = new DocumentVersion();
        version.setDocumentId(documentId);
        version.setVersionId(versionId);
        version.setVersionNo(versionNo);
        version.setFileMd5("md5-1");
        version.setFileUploadId(1L);
        version.setFileName("regulation.pdf");
        version.setDocType(docType);
        version.setJurisdiction(jurisdiction);
        version.setJurisdictionCode("NATIONAL");
        version.setIssuer(issuer);
        version.setApplicableInstitutions("商业银行");
        version.setEffectiveAt(effectiveAt);
        version.setExpiresAt(LocalDate.of(2027, 12, 31));
        version.setRepealed(false);
        version.setCreatedAt(java.time.LocalDateTime.now());
        return version;
    }
}
