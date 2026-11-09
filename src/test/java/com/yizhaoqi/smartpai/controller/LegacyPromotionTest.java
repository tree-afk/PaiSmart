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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧文档懒迁移的上传入口：目标解析、legacy 幂等登记与新版本创建都交给 createAndEnqueue 在同一事务完成，
 * 控制器不再分别调用 promoteLegacy/createNewVersion。
 */
class LegacyPromotionTest {

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
    void legacyFileMd5PromotesOldFileThenCreatesVersionTwo() throws Exception {
        FileUpload newFile = prepareMerge("md5-new", "new.pdf");
        when(documentVersionService.createAndEnqueue(newFile, "1",
                "https://example.com/merged/md5-new", null, "old-md5", ComplianceMetadata.empty())).thenReturn(
                new DocumentVersionService.VersionBuild("doc-9", "ver-2", "build-2", 2, 1,
                        "OLD_TEAM", true, "md5-new", "new.pdf", ComplianceMetadata.empty()));

        var response = uploadController.mergeFile(
                new UploadController.MergeRequest("md5-new", "new.pdf", null, "old-md5"), "1");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertEquals("doc-9", data.get("documentId"));
        assertEquals("ver-2", data.get("versionId"));
        assertEquals("build-2", data.get("buildId"));
        assertEquals(2, data.get("versionNo"));
        assertEquals(1, data.get("buildNo"));

        verify(documentVersionService).validateUploadTarget(newFile, "1", null, "old-md5");
        verify(documentVersionService).createAndEnqueue(newFile, "1",
                "https://example.com/merged/md5-new", null, "old-md5", ComplianceMetadata.empty());
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
}
