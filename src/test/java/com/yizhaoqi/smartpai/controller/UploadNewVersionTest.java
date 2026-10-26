package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.ComplianceMetadata;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import io.minio.GetObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /upload/merge 的版本化分支：首传、带 documentId 的新版本、legacy 目标都只调用版本服务的
 * createAndEnqueue，由同一事务完成版本/构建/outbox；重复合并已完成文件按已有版本幂等返回。
 */
class UploadNewVersionTest {

    @Mock
    private UploadService uploadService;

    @Mock
    private FileUploadRepository fileUploadRepository;

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
        ReflectionTestUtils.setField(uploadController, "parseService", parseService);
        ReflectionTestUtils.setField(uploadController, "documentVersionService", documentVersionService);
    }

    @Test
    void firstUploadCreatesDocumentVersionOneAndBuildOne() throws Exception {
        FileUpload fileUpload = prepareMerge("md5-new", "new.pdf");
        DocumentVersionService.VersionBuild versionBuild = new DocumentVersionService.VersionBuild(
                "doc-1", "ver-1", "build-1", 1, 1, "VERSION_TEAM", true, "md5-new", "new.pdf",
                ComplianceMetadata.empty());
        when(documentVersionService.createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-new", null, null, ComplianceMetadata.empty())).thenReturn(versionBuild);

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-new", "new.pdf", null, null), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("https://example.com/merged/md5-new", data.get("object_url"));
        assertEquals("doc-1", data.get("documentId"));
        assertEquals("ver-1", data.get("versionId"));
        assertEquals("build-1", data.get("buildId"));
        assertEquals(1, data.get("versionNo"));
        assertEquals(1, data.get("buildNo"));

        verify(documentVersionService).createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-new", null, null, ComplianceMetadata.empty());
        verify(documentVersionService).validateUploadTarget(fileUpload, "1", null, null);
    }

    @Test
    void uploadWithDocumentIdCreatesNextVersionThroughSingleServiceCall() throws Exception {
        FileUpload fileUpload = prepareMerge("md5-v2", "v2.pdf");
        DocumentVersionService.VersionBuild versionBuild = new DocumentVersionService.VersionBuild(
                "doc-9", "ver-2", "build-2", 2, 1, "V1_TEAM", false, "md5-v2", "v2.pdf",
                ComplianceMetadata.empty());
        when(documentVersionService.createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-v2", "doc-9", null, ComplianceMetadata.empty()))
                .thenReturn(versionBuild);

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-v2", "v2.pdf", "doc-9", null), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("doc-9", data.get("documentId"));
        assertEquals("ver-2", data.get("versionId"));
        assertEquals("build-2", data.get("buildId"));
        assertEquals(2, data.get("versionNo"));

        verify(documentVersionService).createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-v2", "doc-9", null, ComplianceMetadata.empty());
        verify(documentVersionService, never()).promoteLegacy(any(FileUpload.class), anyString());
    }

    @Test
    void uploadWithLegacyFileMd5DelegatesPromotionToSingleServiceCall() throws Exception {
        FileUpload fileUpload = prepareMerge("md5-new", "new.pdf");
        DocumentVersionService.VersionBuild versionBuild = new DocumentVersionService.VersionBuild(
                "doc-9", "ver-2", "build-2", 2, 1, "OLD_TEAM", true, "md5-new", "new.pdf",
                ComplianceMetadata.empty());
        when(documentVersionService.createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-new", null, "old-md5", ComplianceMetadata.empty()))
                .thenReturn(versionBuild);

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-new", "new.pdf", null, "old-md5"), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("doc-9", data.get("documentId"));
        assertEquals("ver-2", data.get("versionId"));
        assertEquals("build-2", data.get("buildId"));

        verify(documentVersionService).createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-new", null, "old-md5", ComplianceMetadata.empty());
    }

    @Test
    void mergeRejectsDocumentIdAndLegacyFileMd5Together() {
        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-new", "new.pdf", "doc-1", "old-md5"), "1");

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, response.getBody().get("code"));
        verifyNoInteractions(documentVersionService, uploadService);
    }

    @Test
    void mergeRejectsLegacyFileWithSameContent() {
        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("same-md5", "same.pdf", null, "same-md5"), "1");

        assertEquals(409, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("新旧文件内容相同"));
        verifyNoInteractions(documentVersionService, uploadService);
    }

    @Test
    void mergePassesNormalizedComplianceMetadataToVersionService() throws Exception {
        FileUpload fileUpload = prepareMerge("md5-compliance", "compliance.pdf");
        ComplianceMetadata expected = new ComplianceMetadata("REGULATION", "全国", "国家金融监督管理总局",
                LocalDate.of(2026, 10, 1));
        when(documentVersionService.createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-compliance", null, null, expected)).thenReturn(
                new DocumentVersionService.VersionBuild("doc-c", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-compliance", "compliance.pdf", expected));

        var response = uploadController.mergeFile(new UploadController.MergeRequest(
                        "md5-compliance", "compliance.pdf", null, null,
                        "regulation", " 全国 ", "国家金融监督管理总局", "2026-10-01"), "1");

        assertEquals(200, response.getStatusCode().value());
        verify(documentVersionService).createAndEnqueue(fileUpload, "1",
                "https://example.com/merged/md5-compliance", null, null, expected);
    }

    @Test
    void mergeRejectsUnknownDocTypeBeforeTouchingServices() {
        var response = uploadController.mergeFile(new UploadController.MergeRequest(
                "md5-new", "new.pdf", null, null, "UNKNOWN", null, null, null), "1");

        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("文档类型不合法"));
        verifyNoInteractions(documentVersionService, uploadService);
    }

    @Test
    void mergeRejectsInvalidEffectiveAtBeforeTouchingServices() {
        var response = uploadController.mergeFile(new UploadController.MergeRequest(
                "md5-new", "new.pdf", null, null, "REGULATION", "全国", "某银行", "2026/10/01"), "1");

        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("生效日期格式不正确"));
        verifyNoInteractions(documentVersionService, uploadService);
    }

    @Test
    void mergeStopsBeforeWritingWhenPreValidationFails() throws Exception {
        FileUpload fileUpload = prepareMerge("md5-new", "new.pdf");
        org.mockito.Mockito.doThrow(new com.yizhaoqi.smartpai.exception.CustomException(
                        "内容与现有版本相同，未创建新版本", org.springframework.http.HttpStatus.CONFLICT))
                .when(documentVersionService)
                .validateUploadTarget(fileUpload, "1", "doc-9", null);

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-new", "new.pdf", "doc-9", null), "1");

        assertEquals(409, response.getStatusCode().value());
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verify(documentVersionService, never()).createAndEnqueue(any(), anyString(), anyString(), any(), any(),
                any(ComplianceMetadata.class));
    }

    @Test
    void repeatedMergeOfCompletedFileReturnsExistingVersionBuild() throws Exception {
        FileUpload completed = completedUpload(7L, "md5-done", "done.pdf");
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-done", "1"))
                .thenReturn(Optional.of(completed));
        when(uploadService.generateMergedObjectUrl("md5-done")).thenReturn("https://example.com/merged/md5-done");
        when(documentVersionService.findByFileUploadId(7L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-3", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-done", "done.pdf", ComplianceMetadata.empty())));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-done", "done.pdf", null, null), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("https://example.com/merged/md5-done", data.get("object_url"));
        assertEquals("doc-3", data.get("documentId"));
        assertEquals("ver-1", data.get("versionId"));
        assertEquals("build-1", data.get("buildId"));
        assertEquals(1, data.get("versionNo"));

        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
        verify(documentVersionService, never()).createAndEnqueue(any(), anyString(), anyString(), any(), any(),
                any(ComplianceMetadata.class));
    }

    @Test
    void repeatedMergeWithMatchingDocumentReturnsExistingIds() throws Exception {
        FileUpload completed = completedUpload(7L, "md5-done", "done.pdf");
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-done", "1"))
                .thenReturn(Optional.of(completed));
        when(uploadService.generateMergedObjectUrl("md5-done")).thenReturn("https://example.com/merged/md5-done");
        when(documentVersionService.findByFileUploadId(7L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-3", "ver-2", "build-2", 2, 1,
                        "TEAM_A", false, "md5-done", "done.pdf", ComplianceMetadata.empty())));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-done", "done.pdf", "doc-3", null), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("doc-3", data.get("documentId"));
        assertEquals("ver-2", data.get("versionId"));
        assertEquals("build-2", data.get("buildId"));
        verify(documentVersionService, never()).createAndEnqueue(any(), anyString(), anyString(), any(), any(),
                any(ComplianceMetadata.class));
    }

    @Test
    void repeatedMergeWithMismatchedDocumentReturnsConflict() throws Exception {
        FileUpload completed = completedUpload(7L, "md5-done", "done.pdf");
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-done", "1"))
                .thenReturn(Optional.of(completed));
        when(documentVersionService.findByFileUploadId(7L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-3", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-done", "done.pdf", ComplianceMetadata.empty())));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-done", "done.pdf", "doc-other", null), "1");

        assertEquals(409, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("message")).contains("已关联其他文档"));
        verify(documentVersionService, never()).createAndEnqueue(any(), anyString(), anyString(), any(), any(),
                any(ComplianceMetadata.class));
    }

    @Test
    void completedUploadWithoutVersionRecoversThroughCreateAndEnqueue() throws Exception {
        FileUpload completed = completedUpload(7L, "md5-recover", "recover.pdf");
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-recover", "1"))
                .thenReturn(Optional.of(completed));
        when(uploadService.generateMergedObjectUrl("md5-recover"))
                .thenReturn("https://example.com/merged/md5-recover");
        when(documentVersionService.findByFileUploadId(7L)).thenReturn(Optional.empty());
        when(documentVersionService.createAndEnqueue(completed, "1",
                "https://example.com/merged/md5-recover", null, null, ComplianceMetadata.empty())).thenReturn(
                new DocumentVersionService.VersionBuild("doc-4", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-recover", "recover.pdf", ComplianceMetadata.empty()));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-recover", "recover.pdf", null, null), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("doc-4", data.get("documentId"));
        assertEquals("ver-1", data.get("versionId"));
        assertEquals("build-1", data.get("buildId"));
        verify(documentVersionService).createAndEnqueue(completed, "1",
                "https://example.com/merged/md5-recover", null, null, ComplianceMetadata.empty());
        verify(uploadService, never()).mergeChunks(anyString(), anyString(), anyString());
    }

    @Test
    void missingVersionRecoveryKeepsLegacyTargetInSingleCall() throws Exception {
        FileUpload completed = completedUpload(7L, "md5-legacy-new", "legacy-new.pdf");
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-legacy-new", "1"))
                .thenReturn(Optional.of(completed));
        when(uploadService.generateMergedObjectUrl("md5-legacy-new"))
                .thenReturn("https://example.com/merged/md5-legacy-new");
        when(documentVersionService.findByFileUploadId(7L)).thenReturn(Optional.empty());
        when(documentVersionService.createAndEnqueue(completed, "1",
                "https://example.com/merged/md5-legacy-new", null, "old-md5", ComplianceMetadata.empty())).thenReturn(
                new DocumentVersionService.VersionBuild("doc-5", "ver-2", "build-2", 2, 1,
                        "OLD_TEAM", true, "md5-legacy-new", "legacy-new.pdf", ComplianceMetadata.empty()));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-legacy-new", "legacy-new.pdf", null, "old-md5"), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("doc-5", data.get("documentId"));
        assertEquals(2, data.get("versionNo"));
        verify(documentVersionService).createAndEnqueue(completed, "1",
                "https://example.com/merged/md5-legacy-new", null, "old-md5", ComplianceMetadata.empty());
    }

    private FileUpload prepareMerge(String md5, String fileName) throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setId(10L);
        fileUpload.setFileMd5(md5);
        fileUpload.setFileName(fileName);
        fileUpload.setUserId("1");
        fileUpload.setOrgTag("UPLOAD_TEAM");
        fileUpload.setPublic(false);
        fileUpload.setStatus(FileUpload.STATUS_UPLOADING);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(md5, "1"))
                .thenReturn(Optional.of(fileUpload));
        when(uploadService.getUploadedChunks(md5, "1")).thenReturn(List.of(0));
        when(uploadService.getTotalChunks(md5, "1")).thenReturn(1);
        when(fileUploadRepository.updateStatusIfCurrent(eq(10L), eq(FileUpload.STATUS_UPLOADING),
                eq(FileUpload.STATUS_MERGING), any(LocalDateTime.class))).thenReturn(1);
        when(uploadService.mergeChunks(md5, fileName, "1")).thenReturn("https://example.com/merged/" + md5);
        when(uploadService.getMergedFileStream(md5)).thenReturn(mock(GetObjectResponse.class));
        when(parseService.estimateEmbeddingUsage(any(InputStream.class)))
                .thenReturn(new ParseService.EmbeddingEstimate(100, 10));
        when(fileUploadRepository.save(any(FileUpload.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return fileUpload;
    }

    private FileUpload completedUpload(Long id, String md5, String fileName) {
        FileUpload completed = new FileUpload();
        completed.setId(id);
        completed.setFileMd5(md5);
        completed.setFileName(fileName);
        completed.setUserId("1");
        completed.setStatus(FileUpload.STATUS_COMPLETED);
        return completed;
    }
}
