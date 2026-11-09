package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import com.yizhaoqi.smartpai.service.reliability.FileProcessingOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 重建/重试不再投递 8 参 legacy 任务：一律经版本服务对文档 active 或最新版本建新 build，
 * 任务带完整构建上下文；尚未版本化的旧文件先懒迁移出文档与 version 1，再在该版本上建 build。
 * 允许文件所有者或管理员发起，其他用户拒绝。
 */
class DocumentServiceRetryTest {

    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final DocumentVersionService documentVersionService = mock(DocumentVersionService.class);
    private final FileProcessingOutboxService fileProcessingOutboxService = mock(FileProcessingOutboxService.class);

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService();
        ReflectionTestUtils.setField(documentService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(documentService, "userRepository", userRepository);
        ReflectionTestUtils.setField(documentService, "documentVersionService", documentVersionService);
        ReflectionTestUtils.setField(documentService, "fileProcessingOutboxService", fileProcessingOutboxService);
    }

    @Test
    void retryOfVersionedFileCreatesBuildBackedTask() {
        FileUpload fileUpload = fileUpload(2L, "md5", "doc.pdf", "owner-1");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5"))
                .thenReturn(Optional.of(fileUpload));
        when(documentVersionService.findByFileUploadId(2L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-1", "ver-2", "build-old", 2, 1,
                        "TEAM_A", false, "md5", "doc.pdf")));
        when(documentVersionService.createBuildForActiveVersion("ver-2", "owner-1", FileProcessingTask.BUILD_KIND_REINDEX))
                .thenReturn(new DocumentVersionService.VersionBuild("doc-1", "ver-2", "build-new", 2, 2,
                        "TEAM_A", false, "md5", "doc.pdf"));

        DocumentService.VectorizationSubmission submission =
                documentService.enqueueAsyncVectorizationRetry("md5", "owner-1");

        assertEquals("build-new", submission.versionBuild().buildId());
        assertEquals("ver-2", submission.versionBuild().versionId());

        FileProcessingTask task = captureTask(fileUpload);
        assertEquals("md5", task.getFileMd5());
        assertNull(task.getFilePath());
        assertEquals("doc.pdf", task.getFileName());
        assertEquals("owner-1", task.getUserId());
        assertEquals("TEAM_A", task.getOrgTag());
        assertEquals(FileProcessingTask.TASK_TYPE_REINDEX, task.getTaskType());
        assertEquals("owner-1", task.getRequesterId());
        assertEquals("doc-1", task.getDocumentId());
        assertEquals("ver-2", task.getVersionId());
        assertEquals("build-new", task.getBuildId(), "重试必须是构建任务，不能退回 8 参 legacy 任务");
        assertEquals(FileProcessingTask.BUILD_KIND_REINDEX, task.getBuildKind());
        assertEquals(2, task.getVersionNo());
        assertEquals(2, task.getBuildNo());

        verify(documentVersionService, never()).promoteLegacy(any(FileUpload.class), any());
    }

    @Test
    void retryOfLegacyFilePromotesThenCreatesBuildForLegacyActiveVersion() {
        FileUpload fileUpload = fileUpload(3L, "old-md5", "old.pdf", "owner-1");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("old-md5"))
                .thenReturn(Optional.of(fileUpload));
        when(documentVersionService.findByFileUploadId(3L)).thenReturn(Optional.empty());
        when(documentVersionService.promoteLegacy(fileUpload, "owner-1")).thenReturn(
                new DocumentVersionService.VersionBuild("doc-2", "ver-1", null, 1, null,
                        "OLD_TEAM", true, "old-md5", "old.pdf"));
        when(documentVersionService.createBuildForActiveVersion(
                "ver-1", "owner-1", FileProcessingTask.BUILD_KIND_LEGACY_PROMOTION)).thenReturn(
                new DocumentVersionService.VersionBuild("doc-2", "ver-1", "build-1", 1, 1,
                        "OLD_TEAM", true, "old-md5", "old.pdf"));

        documentService.enqueueAsyncVectorizationRetry("old-md5", "owner-1");

        verify(documentVersionService).promoteLegacy(fileUpload, "owner-1");
        FileProcessingTask task = captureTask(fileUpload);
        assertEquals("old-md5", task.getFileMd5());
        assertEquals("doc-2", task.getDocumentId());
        assertEquals("ver-1", task.getVersionId());
        assertEquals("build-1", task.getBuildId());
        assertEquals(FileProcessingTask.BUILD_KIND_LEGACY_PROMOTION, task.getBuildKind());
        assertEquals(1, task.getVersionNo());
        assertEquals(1, task.getBuildNo());
        assertEquals("OLD_TEAM", task.getOrgTag());
        assertEquals("owner-1", task.getUserId());
        assertEquals(FileProcessingTask.TASK_TYPE_REINDEX, task.getTaskType());
    }

    @Test
    void reindexEnqueuesBuildForTheFilesActiveVersion() {
        FileUpload fileUpload = fileUpload(4L, "md5-reindex", "manual.pdf", "owner-1");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5-reindex"))
                .thenReturn(Optional.of(fileUpload));
        when(documentVersionService.findByFileUploadId(4L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-4", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-reindex", "manual.pdf")));
        when(documentVersionService.createBuildForActiveVersion(
                "ver-1", "owner-1", FileProcessingTask.BUILD_KIND_REINDEX))
                .thenReturn(new DocumentVersionService.VersionBuild("doc-4", "ver-1", "build-2", 1, 2,
                        "TEAM_A", false, "md5-reindex", "manual.pdf"));

        DocumentService.VectorizationSubmission submission =
                documentService.enqueueAsyncReindex("md5-reindex", "owner-1");

        assertEquals("build-2", submission.versionBuild().buildId());
        FileProcessingTask task = captureTask(fileUpload);
        assertEquals("build-2", task.getBuildId());
        assertEquals(1, task.getVersionNo(), "reindex 不增加内容版本号");
        assertEquals(2, task.getBuildNo());
        assertEquals("ver-1", task.getVersionId());
    }

    @Test
    void retryRejectsForeignRequester() {
        FileUpload fileUpload = fileUpload(5L, "md5", "doc.pdf", "owner-1");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5"))
                .thenReturn(Optional.of(fileUpload));

        CustomException error = assertThrows(CustomException.class,
                () -> documentService.enqueueAsyncVectorizationRetry("md5", "intruder"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verifyNoInteractions(documentVersionService, fileProcessingOutboxService);
    }

    @Test
    void fileManagerCanRetryAnotherUsersFile() {
        FileUpload fileUpload = fileUpload(8L, "md5", "doc.pdf", "owner-1");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5"))
                .thenReturn(Optional.of(fileUpload));
        User admin = new User();
        admin.setUsername("file-manager");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findByUsername("file-manager")).thenReturn(Optional.of(admin));
        when(documentVersionService.findByFileUploadId(8L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-1", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5", "doc.pdf")));
        when(documentVersionService.createBuildForActiveVersion("ver-1", "file-manager", FileProcessingTask.BUILD_KIND_REINDEX))
                .thenReturn(new DocumentVersionService.VersionBuild("doc-1", "ver-1", "build-2", 1, 2,
                        "TEAM_A", false, "md5", "doc.pdf"));

        DocumentService.VectorizationSubmission submission =
                documentService.enqueueAsyncVectorizationRetry("md5", "file-manager");

        assertEquals("build-2", submission.versionBuild().buildId());
        assertEquals("owner-1", submission.fileUpload().getUserId());
        FileProcessingTask task = captureTask(fileUpload);
        assertEquals("file-manager", task.getRequesterId());
        assertEquals("owner-1", task.getUserId());
        assertEquals("build-2", task.getBuildId());
    }

    @Test
    void retryRejectsUploadThatIsNotCompleted() {
        FileUpload fileUpload = fileUpload(6L, "md5", "doc.pdf", "owner-1");
        fileUpload.setStatus(FileUpload.STATUS_UPLOADING);
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5"))
                .thenReturn(Optional.of(fileUpload));

        CustomException error = assertThrows(CustomException.class,
                () -> documentService.enqueueAsyncReindex("md5", "owner-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verifyNoInteractions(fileProcessingOutboxService);
    }

    @Test
    void reindexConflictWhenFileIsNotTheActiveVersionIsPropagated() {
        FileUpload fileUpload = fileUpload(7L, "md5-old", "old.pdf", "owner-1");
        when(fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc("md5-old"))
                .thenReturn(Optional.of(fileUpload));
        when(documentVersionService.findByFileUploadId(7L)).thenReturn(Optional.of(
                new DocumentVersionService.VersionBuild("doc-1", "ver-1", "build-1", 1, 1,
                        "TEAM_A", false, "md5-old", "old.pdf")));
        when(documentVersionService.createBuildForActiveVersion(
                "ver-1", "owner-1", FileProcessingTask.BUILD_KIND_REINDEX))
                .thenThrow(new CustomException("该文件不是文档的当前版本，无法重建索引", HttpStatus.CONFLICT));

        CustomException error = assertThrows(CustomException.class,
                () -> documentService.enqueueAsyncReindex("md5-old", "owner-1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verifyNoInteractions(fileProcessingOutboxService);
    }

    private FileProcessingTask captureTask(FileUpload fileUpload) {
        ArgumentCaptor<FileProcessingTask> captor = ArgumentCaptor.forClass(FileProcessingTask.class);
        verify(fileProcessingOutboxService).enqueueVectorization(eq(fileUpload), captor.capture(), eq(true));
        return captor.getValue();
    }

    private FileUpload fileUpload(Long id, String md5, String fileName, String userId) {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setId(id);
        fileUpload.setFileMd5(md5);
        fileUpload.setFileName(fileName);
        fileUpload.setUserId(userId);
        fileUpload.setOrgTag("FILE_TEAM");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);
        return fileUpload;
    }
}
