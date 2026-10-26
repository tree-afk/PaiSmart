package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.FileTypeValidationService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class UploadControllerTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private FileTypeValidationService fileTypeValidationService;

    @Mock
    private ParseService parseService;

    @Mock
    private DocumentVersionService documentVersionService;

    private UploadController uploadController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadController = new UploadController(uploadService);
        ReflectionTestUtils.setField(uploadController, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadController, "fileTypeValidationService", fileTypeValidationService);
        ReflectionTestUtils.setField(uploadController, "parseService", parseService);
        ReflectionTestUtils.setField(uploadController, "documentVersionService", documentVersionService);
        when(fileTypeValidationService.getSupportedFileTypes()).thenReturn(Set.of("pdf"));
    }

    @Test
    void uploadChunkUsesSharedOrgTagAndPublicFlagForFileManager() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        when(fileTypeValidationService.validateFileType("test.pdf"))
                .thenReturn(new FileTypeValidationService.FileTypeValidationResult(true, "ok", "PDF文档", "pdf"));
        when(uploadService.getUploadedChunks("md5", "1")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks("md5", "1")).thenReturn(1);

        var response = uploadController.uploadChunk(
                "md5",
                0,
                20L * 1024 * 1024,
                "test.pdf",
                1,
                "REMOVED_TEAM",
                false,
                file,
                "1"
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("uploaded", List.of(0), "progress", 100.0d), response.getBody().get("data"));
        verify(uploadService).uploadChunk("md5", 0, 20L * 1024 * 1024, "test.pdf", file,
                "DEFAULT", true, "1");
    }

    @Test
    void testMergeFileReturnsExistingResultWhenAlreadyCompleted() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setFileName("test.pdf");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(uploadService.generateMergedObjectUrl("md5")).thenReturn("https://example.com/merged/md5");
        when(documentVersionService.findByFileUploadId(null)).thenReturn(Optional.empty());
        when(documentVersionService.createAndEnqueue(fileUpload, "1", "https://example.com/merged/md5", null, null,
                com.yizhaoqi.smartpai.model.ComplianceMetadata.empty()))
                .thenReturn(new DocumentVersionService.VersionBuild("doc-recovered", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5", "test.pdf"));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5", "test.pdf", null, null), "1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("文件已完成合并", response.getBody().get("message"));
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("https://example.com/merged/md5", data.get("object_url"));
        assertEquals("doc-recovered", data.get("documentId"));
        assertEquals("ver-1", data.get("versionId"));
        assertEquals("build-1", data.get("buildId"));
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verify(documentVersionService).createAndEnqueue(
                fileUpload, "1", "https://example.com/merged/md5", null, null,
                com.yizhaoqi.smartpai.model.ComplianceMetadata.empty());
    }
    @Test
    void mergeCarriesValidityAndApplicabilityMetadata() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5-validity");
        fileUpload.setFileName("validity.pdf");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-validity", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(uploadService.generateMergedObjectUrl("md5-validity"))
                .thenReturn("https://example.com/merged/md5-validity");
        when(documentVersionService.findByFileUploadId(null)).thenReturn(Optional.empty());
        when(documentVersionService.createAndEnqueue(eq(fileUpload), eq("1"),
                eq("https://example.com/merged/md5-validity"), isNull(), isNull(),
                any(ComplianceMetadata.class)))
                .thenReturn(new DocumentVersionService.VersionBuild("doc-1", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-validity", "validity.pdf"));

        var response = uploadController.mergeFile(new UploadController.MergeRequest(
                "md5-validity", "validity.pdf", null, null,
                "REGULATION", "上海市", "国家金融监督管理总局", "2026-01-01",
                "商业银行、保险公司", "2026-12-31", false), "1");

        assertEquals(200, response.getStatusCode().value());
        org.mockito.ArgumentCaptor<ComplianceMetadata> captor =
                org.mockito.ArgumentCaptor.forClass(ComplianceMetadata.class);
        verify(documentVersionService).createAndEnqueue(eq(fileUpload), eq("1"),
                eq("https://example.com/merged/md5-validity"), isNull(), isNull(), captor.capture());
        ComplianceMetadata metadata = captor.getValue();
        assertEquals("REGULATION", metadata.docType());
        assertEquals("上海", metadata.jurisdiction());
        assertEquals("上海", metadata.jurisdictionCode());
        assertEquals("商业银行、保险公司", metadata.applicableInstitutions());
        assertEquals(java.time.LocalDate.of(2026, 12, 31), metadata.expiresAt());
        assertEquals(false, metadata.repealed());
    }

    @Test
    void mergeRejectsExpiryBeforeEffectiveDate() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5-invalid-date");
        fileUpload.setFileName("invalid.pdf");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-invalid-date", "1"))
                .thenReturn(Optional.of(fileUpload));

        var response = uploadController.mergeFile(new UploadController.MergeRequest(
                "md5-invalid-date", "invalid.pdf", null, null,
                "REGULATION", "全国", "监管总局", "2026-12-01",
                null, "2026-01-01", false), "1");

        assertEquals(400, response.getStatusCode().value());
        verify(documentVersionService, never()).createAndEnqueue(any(), anyString(), anyString(),
                any(), any(), any(ComplianceMetadata.class));
    }

}
