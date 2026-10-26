package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.document.DocumentDeletionService;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import com.yizhaoqi.smartpai.service.document.DocumentAccessService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档控制器路由：规格 7.3 的版本构建路由，以及 DELETE /documents/{fileMd5} 统一走删除服务。
 */
class DocumentControllerBuildRouteTest {

    private final DocumentVersionService documentVersionService = mock(DocumentVersionService.class);
    private final DocumentDeletionService documentDeletionService = mock(DocumentDeletionService.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final ChatHandler chatHandler = mock(ChatHandler.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final DocumentAccessService documentAccessService = mock(DocumentAccessService.class);

    private DocumentController documentController;

    @BeforeEach
    void setUp() {
        documentController = new DocumentController();
        ReflectionTestUtils.setField(documentController, "documentVersionService", documentVersionService);
        ReflectionTestUtils.setField(documentController, "documentDeletionService", documentDeletionService);
        ReflectionTestUtils.setField(documentController, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(documentController, "chatHandler", chatHandler);
        ReflectionTestUtils.setField(documentController, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(documentController, "documentAccessService", documentAccessService);
    }

    @Test
    void createsBuildForDocumentVersionAndReturnsAcceptedBodyCode200() {
        when(documentVersionService.createBuildAndEnqueueForVersion("doc-1", "ver-1", "owner-1"))
                .thenReturn(new DocumentVersionService.VersionBuild("doc-1", "ver-1", "build-2",
                        1, 2, "TEAM_A", false, "md5-1", "doc.pdf"));

        var response = documentController.createVersionBuild("doc-1", "ver-1", "owner-1");

        assertEquals(202, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(200, body.get("code"), "前端成功契约读 body.code=200");
        assertEquals("已提交重建索引任务", body.get("message"));
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertEquals("doc-1", data.get("documentId"));
        assertEquals("ver-1", data.get("versionId"));
        assertEquals("build-2", data.get("buildId"));
        assertEquals(1, data.get("versionNo"));
        assertEquals(2, data.get("buildNo"));
        verify(documentVersionService).createBuildAndEnqueueForVersion("doc-1", "ver-1", "owner-1");
    }

    @Test
    void mapsOwnerConflictToForbidden() {
        when(documentVersionService.createBuildAndEnqueueForVersion("doc-1", "ver-1", "intruder"))
                .thenThrow(new CustomException("没有权限为该文档创建构建", HttpStatus.FORBIDDEN));

        var response = documentController.createVersionBuild("doc-1", "ver-1", "intruder");

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(403, body.get("code"));
        assertTrue(String.valueOf(body.get("message")).contains("没有权限"));
    }

    @Test
    void mapsUnknownVersionToNotFound() {
        when(documentVersionService.createBuildAndEnqueueForVersion("doc-1", "missing", "owner-1"))
                .thenThrow(new CustomException("内容版本不存在", HttpStatus.NOT_FOUND));

        var response = documentController.createVersionBuild("doc-1", "missing", "owner-1");

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, ((Map<?, ?>) response.getBody()).get("code"));
    }

    @Test
    void referenceDetailIncludesClauseMetadata() {
        when(chatHandler.getReferenceDetail("session-1", 2)).thenReturn(new ChatHandler.ReferenceInfo(
                "md5-1", "regulation.pdf", "doc-1", "ver-1", 5, "第十二条", "HYBRID", "混合召回", "问题",
                "第十二条 银行应当建立制度。", "第十二条 银行应当建立制度。", 0.9, 0.8, 9,
                "SUPPORTED", null, "第二章 风险管理 > 第十二条", "第十二条第二款"));

        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("7");
        when(jwtUtils.extractOrgTagsFromToken("token")).thenReturn("TEAM_A");
        when(documentAccessService.canRead("7", "doc-1", "ver-1")).thenReturn(true);
        var response = documentController.getReferenceDetail("session-1", 2, "Bearer token");

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertEquals("第二章 风险管理 > 第十二条", data.get("articlePath"));
        assertEquals("第十二条第二款", data.get("clauseNo"));
        assertEquals("doc-1", data.get("documentId"));
        assertEquals("ver-1", data.get("versionId"));
        assertEquals(2, data.get("referenceNumber"));
    }

    @Test
    void deleteRoutesThroughDocumentDeletionService() {
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-1", "owner-1"))
                .thenReturn(Optional.of(upload("md5-1", "owner-1")));

        var response = documentController.deleteDocument("md5-1", "owner-1", "USER");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(200, ((Map<?, ?>) response.getBody()).get("code"));
        assertEquals("文档删除成功", ((Map<?, ?>) response.getBody()).get("message"));
        verify(documentDeletionService).deleteByFileMd5("md5-1", "owner-1");
    }

    @Test
    void deleteMapsForbiddenFromDeletionService() {
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5-1", "intruder"))
                .thenReturn(Optional.empty());
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5-1"))
                .thenReturn(Optional.of(upload("md5-1", "owner-1")));
        doThrow(new CustomException("没有权限删除此文档", HttpStatus.FORBIDDEN))
                .when(documentDeletionService).deleteByFileMd5("md5-1", "intruder");

        var response = documentController.deleteDocument("md5-1", "intruder", "USER");

        assertEquals(403, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(403, body.get("code"));
        assertTrue(String.valueOf(body.get("message")).contains("没有权限"));
    }

    @Test
    void deleteMapsNotFoundFromDeletionService() {
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(anyString()))
                .thenReturn(Optional.empty());
        doThrow(new CustomException("文档不存在", HttpStatus.NOT_FOUND))
                .when(documentDeletionService).deleteByFileMd5("missing", "owner-1");

        var response = documentController.deleteDocument("missing", "owner-1", "USER");

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, ((Map<?, ?>) response.getBody()).get("code"));
        verify(documentDeletionService).deleteByFileMd5("missing", "owner-1");
    }

    private FileUpload upload(String md5, String ownerUserId) {
        FileUpload upload = new FileUpload();
        upload.setId(1L);
        upload.setFileMd5(md5);
        upload.setFileName("doc.pdf");
        upload.setUserId(ownerUserId);
        upload.setStatus(FileUpload.STATUS_COMPLETED);
        return upload;
    }
}
