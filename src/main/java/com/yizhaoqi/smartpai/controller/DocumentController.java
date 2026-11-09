package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.model.ComplianceMetadataPatch;
import com.yizhaoqi.smartpai.model.ComplianceValidityStatus;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import com.yizhaoqi.smartpai.model.DocumentVersion;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.OrganizationTag;
import com.yizhaoqi.smartpai.repository.DocumentVersionRepository;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.OrganizationTagRepository;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.KnowledgeAuditService;
import com.yizhaoqi.smartpai.service.document.DocumentDeletionService;
import com.yizhaoqi.smartpai.service.document.DocumentPublicationService;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import com.yizhaoqi.smartpai.utils.LogUtils;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文档控制器类，处理文档相关操作请求
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentDeletionService documentDeletionService;
    
    @Autowired
    private FileUploadRepository fileUploadRepository;
    
    @Autowired
    private OrganizationTagRepository organizationTagRepository;

    @Autowired
    private DocumentVersionRepository documentVersionRepository;

    @Autowired
    private BusinessDocumentRepository businessDocumentRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatHandler chatHandler;

    @Autowired
    private DocumentVersionService documentVersionService;

    @Autowired
    private DocumentPublicationService documentPublicationService;

    @Autowired
    private KnowledgeAuditService knowledgeAuditService;

    @Autowired
    private com.yizhaoqi.smartpai.service.document.DocumentAccessService documentAccessService;

    /**
     * 删除文档及其相关数据
     * 版本化文档由删除服务先停用业务文档、取消未完成构建并失效检索范围，再清理物理数据；
     * 未版本化的旧文件继续按 MD5 清理。删除权限（所有者或管理员）由删除服务统一校验。
     *
     * @param fileMd5 文件MD5
     * @param userId 当前用户ID
     * @param role 用户角色
     * @return 删除结果
     */
    @DeleteMapping("/{fileMd5}")
    public ResponseEntity<?> deleteDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DELETE_DOCUMENT");
        try {
            LogUtils.logBusiness("DELETE_DOCUMENT", userId, "接收到删除文档请求: fileMd5=%s, role=%s", fileMd5, role);

            // 文件名仅用于日志：删除权限（所有者或管理员）和是否存在都由删除服务统一判断，
            // 已清理过上传记录的重复删除也会在服务内按幂等处理
            String fileName = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                    .or(() -> fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5))
                    .map(FileUpload::getFileName)
                    .orElse(fileMd5);

            // 执行删除操作（版本生命周期停用 + 尽力而为的物理清理）
            documentDeletionService.deleteByFileMd5(fileMd5, userId);

            LogUtils.logFileOperation(userId, "DELETE", fileName, fileMd5, "SUCCESS");
            monitor.end("文档删除成功");
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文档删除成功");
            return ResponseEntity.ok(response);
        } catch (CustomException e) {
            LogUtils.logBusinessError("DELETE_DOCUMENT", userId, "删除文档失败: fileMd5=%s", e, fileMd5);
            String operationResult = e.getStatus() == HttpStatus.FORBIDDEN
                    ? "FAILED_PERMISSION_DENIED"
                    : e.getStatus() == HttpStatus.NOT_FOUND ? "FAILED_NOT_FOUND" : "FAILED";
            LogUtils.logUserOperation(userId, "DELETE_DOCUMENT", fileMd5, operationResult);
            monitor.end("删除失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", e.getStatus().value());
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("DELETE_DOCUMENT", userId, "删除文档失败: fileMd5=%s", e, fileMd5);
            monitor.end("删除失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "删除文档失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/{fileMd5}/reindex")
    public ResponseEntity<?> reindexDocument(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("REINDEX_DOCUMENT");
        try {
            LogUtils.logBusiness("REINDEX_DOCUMENT", userId, "接收到重建文档索引请求: fileMd5=%s, role=%s", fileMd5, role);

            Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
            if (fileOpt.isEmpty()) {
                monitor.end("重建失败：文档不存在");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文档不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            FileUpload file = fileOpt.get();

            DocumentService.VectorizationSubmission submission =
                    documentService.enqueueAsyncReindex(fileMd5, userId);
            monitor.end("异步重建索引任务已提交");

            Map<String, Object> data = new HashMap<>();
            data.put("fileMd5", fileMd5);
            data.put("fileName", file.getFileName());
            putVersionBuild(data, submission.versionBuild());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "已提交异步重建索引任务");
            response.put("data", data);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (CustomException e) {
            LogUtils.logBusinessError("REINDEX_DOCUMENT", userId, "重建文档索引失败: fileMd5=%s", e, fileMd5);
            monitor.end("重建失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", e.getStatus().value());
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("REINDEX_DOCUMENT", userId, "重建文档索引失败: fileMd5=%s", e, fileMd5);
            monitor.end("重建失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "重建文档索引失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 规格 7.3：对指定文档的内容版本重建索引。允许文档所有者或管理员，
     * 且不再要求版本是当前 active 版本（迟到的旧构建在切换时会被标记为 SUPERSEDED）。
     */
    @PostMapping("/{documentId}/versions/{versionId}/builds")
    public ResponseEntity<?> createVersionBuild(
            @PathVariable String documentId,
            @PathVariable String versionId,
            @RequestAttribute("userId") String userId) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("CREATE_VERSION_BUILD");
        try {
            LogUtils.logBusiness("CREATE_VERSION_BUILD", userId,
                    "接收到文档版本重建请求: documentId=%s, versionId=%s", documentId, versionId);

            DocumentVersionService.VersionBuild versionBuild =
                    documentVersionService.createBuildAndEnqueueForVersion(documentId, versionId, userId);
            monitor.end("文档版本重建任务已提交");

            Map<String, Object> data = new HashMap<>();
            putVersionBuild(data, versionBuild);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "已提交重建索引任务");
            response.put("data", data);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (CustomException e) {
            LogUtils.logBusinessError("CREATE_VERSION_BUILD", userId,
                    "文档版本重建失败: documentId=%s, versionId=%s", e, documentId, versionId);
            monitor.end("文档版本重建失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", e.getStatus().value());
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("CREATE_VERSION_BUILD", userId,
                    "文档版本重建失败: documentId=%s, versionId=%s", e, documentId, versionId);
            monitor.end("文档版本重建失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "重建索引失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 修正文档元数据并重建索引。文件管理者专用；修订期间文档进入 DRAFT，旧 active 分块不被检索。
     */
    @PatchMapping("/{documentId}/metadata")
    public ResponseEntity<?> updateMetadata(@PathVariable String documentId,
                                            @RequestAttribute("userId") String userId,
                                            @RequestBody JsonNode body) {
        try {
            ComplianceMetadataPatch patch = parseMetadataPatch(body);
            DocumentVersionService.VersionBuild versionBuild =
                    documentVersionService.updateMetadataAndRebuild(documentId, patch, userId);
            knowledgeAuditService.record(userId, "DOCUMENT", documentId, "UPDATE_METADATA",
                    null, versionBuild.versionId());
            Map<String, Object> data = new HashMap<>();
            putVersionBuild(data, versionBuild);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "code", 200,
                    "message", "已提交元数据修订构建，修订完成前文档暂停检索",
                    "data", data
            ));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", 500, "message", "元数据修订失败: " + e.getMessage()));
        }
    }

    @PostMapping("/{documentId}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable String documentId,
                                      @RequestAttribute("userId") String userId) {
        try {
            var status = documentPublicationService.withdraw(documentId, userId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "文档已下架",
                    "data", Map.of("documentId", documentId, "publicationStatus", status.name())));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        }
    }

    @PostMapping("/{documentId}/restore-publication")
    public ResponseEntity<?> restorePublication(@PathVariable String documentId,
                                                @RequestAttribute("userId") String userId) {
        try {
            var status = documentPublicationService.restore(documentId, userId);
            return ResponseEntity.ok(Map.of("code", 200, "message", "文档已恢复发布",
                    "data", Map.of("documentId", documentId, "publicationStatus", status.name())));
        } catch (CustomException e) {
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getStatus().value(), "message", e.getMessage()));
        }
    }

    private ComplianceMetadataPatch parseMetadataPatch(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new CustomException("请求体必须是 JSON 对象", HttpStatus.BAD_REQUEST);
        }
        ComplianceMetadataPatch.Builder patch = ComplianceMetadataPatch.builder();
        boolean anyField = false;

        if (body.has("docType")) {
            anyField = true;
            JsonNode node = body.get("docType");
            if (node == null || node.isNull() || node.asText().isBlank()) {
                throw new CustomException("文档类型不能为空", HttpStatus.BAD_REQUEST);
            }
            DocumentComplianceType type = DocumentComplianceType.fromCode(node.asText());
            if (type == null) {
                throw new CustomException("文档类型不合法，可选值：" + DocumentComplianceType.allowedCodesWithLabels(),
                        HttpStatus.BAD_REQUEST);
            }
            patch.docType(type.name());
        }
        if (body.has("jurisdiction")) {
            anyField = true;
            patch.jurisdiction(textOrNull(body.get("jurisdiction")));
        }
        if (body.has("issuer")) {
            anyField = true;
            patch.issuer(textOrNull(body.get("issuer")));
        }
        if (body.has("applicableInstitutions")) {
            anyField = true;
            patch.applicableInstitutions(textOrNull(body.get("applicableInstitutions")));
        }
        if (body.has("effectiveAt")) {
            anyField = true;
            patch.effectiveAt(parseMetadataDate(body.get("effectiveAt"), "生效日期"));
        }
        if (body.has("expiresAt")) {
            anyField = true;
            patch.expiresAt(parseMetadataDate(body.get("expiresAt"), "失效日期"));
        }
        if (body.has("repealed")) {
            anyField = true;
            JsonNode node = body.get("repealed");
            patch.repealed(node != null && !node.isNull() && node.asBoolean());
        }
        if (!anyField) {
            throw new CustomException("没有可修改的资料信息", HttpStatus.BAD_REQUEST);
        }
        return patch.build();
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private LocalDate parseMetadataDate(JsonNode node, String label) {
        String value = textOrNull(node);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new CustomException(label + "格式不正确，应为 yyyy-MM-dd", HttpStatus.BAD_REQUEST);
        }
    }

    @PostMapping("/{fileMd5}/vectorization/retry")
    public ResponseEntity<?> retryVectorizationAsync(
            @PathVariable String fileMd5,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("role") String role) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("RETRY_VECTORIZATION_ASYNC");
        try {
            LogUtils.logBusiness("RETRY_VECTORIZATION_ASYNC", userId, "接收到异步向量化重试请求: fileMd5=%s, role=%s", fileMd5, role);

            Optional<FileUpload> fileOpt = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5);
            if (fileOpt.isEmpty()) {
                monitor.end("重试失败：文档不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", HttpStatus.NOT_FOUND.value(),
                        "message", "文档不存在"
                ));
            }

            FileUpload file = fileOpt.get();

            DocumentService.VectorizationSubmission submission =
                    documentService.enqueueAsyncVectorizationRetry(fileMd5, userId);
            monitor.end("异步向量化重试任务已提交");

            Map<String, Object> data = new HashMap<>();
            data.put("fileMd5", submission.fileUpload().getFileMd5());
            data.put("fileName", submission.fileUpload().getFileName());
            data.put("vectorizationStatus", submission.fileUpload().getVectorizationStatus());
            putVersionBuild(data, submission.versionBuild());

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "code", 200,
                    "message", "已提交异步向量化重试任务",
                    "data", data
            ));
        } catch (CustomException e) {
            LogUtils.logBusinessError("RETRY_VECTORIZATION_ASYNC", userId, "异步向量化重试失败: fileMd5=%s", e, fileMd5);
            monitor.end("异步向量化重试失败: " + e.getMessage());
            return ResponseEntity.status(e.getStatus()).body(Map.of(
                    "code", e.getStatus().value(),
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            LogUtils.logBusinessError("RETRY_VECTORIZATION_ASYNC", userId, "异步向量化重试失败: fileMd5=%s", e, fileMd5);
            monitor.end("异步向量化重试失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "message", "异步向量化重试失败: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 把版本化重建/重试的构建信息放进响应，前端据此跟踪本次构建
     */
    private void putVersionBuild(Map<String, Object> data, DocumentVersionService.VersionBuild versionBuild) {
        data.put("documentId", versionBuild.documentId());
        data.put("versionId", versionBuild.versionId());
        data.put("buildId", versionBuild.buildId());
        data.put("versionNo", versionBuild.versionNo());
        data.put("buildNo", versionBuild.buildNo());
    }

    /**
     * 获取用户可访问的所有文件列表
     * 
     * @param userId 当前用户ID
     * @param orgTags 用户所属组织标签
     * @return 可访问的文件列表
     */
    @GetMapping("/accessible")
    public ResponseEntity<?> getAccessibleFiles(
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_ACCESSIBLE_FILES");
        try {
            LogUtils.logBusiness("GET_ACCESSIBLE_FILES", userId, "接收到获取文件管理列表请求: orgTags=%s", orgTags);
            
            List<FileUpload> files = documentService.getFileManagerFiles();
            List<Map<String, Object>> fileData = convertFilesToResponse(files);
            Object data = (page != null || size != null) ? paginateList(fileData, page, size) : fileData;
            
            LogUtils.logUserOperation(userId, "GET_ACCESSIBLE_FILES", "file_list", "SUCCESS");
            LogUtils.logBusiness("GET_ACCESSIBLE_FILES", userId, "成功获取可访问文件: fileCount=%d", files.size());
            monitor.end("获取可访问文件成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取可访问文件列表成功");
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ACCESSIBLE_FILES", userId, "获取可访问文件失败", e);
            monitor.end("获取可访问文件失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取可访问文件列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private Map<String, Object> paginateList(List<Map<String, Object>> records, Integer page, Integer size) {
        int pageNumber = page == null || page < 1 ? 1 : page;
        int pageSize = size == null || size < 1 ? 10 : size;
        int total = records.size();
        int fromIndex = Math.min((pageNumber - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> pageData = records.subList(fromIndex, toIndex);

        Map<String, Object> result = new HashMap<>();
        result.put("data", pageData);
        result.put("content", pageData);
        result.put("number", pageNumber);
        result.put("size", pageSize);
        result.put("totalElements", total);
        return result;
    }
    
    /**
     * 获取用户上传的所有文件列表
     * 
     * @param userId 当前用户ID
     * @return 用户上传的文件列表
     */
    @GetMapping("/uploads")
    public ResponseEntity<?> getUserUploadedFiles(
            @RequestAttribute("userId") String userId) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_USER_UPLOADED_FILES");
        try {
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "接收到获取用户上传文件请求");
            
            List<FileUpload> files = documentService.getFileManagerFiles();

            // 添加详细日志：追踪每个文件的MD5
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "开始处理文件列表，总数: %d", files.size());
            for (int i = 0; i < files.size(); i++) {
                FileUpload file = files.get(i);
                LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId,
                    "文件[%d]: fileName=%s, fileMd5=%s, totalSize=%d",
                    i, file.getFileName(), file.getFileMd5(), file.getTotalSize());
            }

            List<Map<String, Object>> fileData = convertFilesToResponse(files);
            
            LogUtils.logUserOperation(userId, "GET_USER_UPLOADED_FILES", "file_list", "SUCCESS");
            LogUtils.logBusiness("GET_USER_UPLOADED_FILES", userId, "成功获取用户上传文件: fileCount=%d", files.size());
            monitor.end("获取用户上传文件成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取用户上传文件列表成功");
            response.put("data", fileData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_USER_UPLOADED_FILES", userId, "获取用户上传文件失败", e);
            monitor.end("获取用户上传文件失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取用户上传文件列表失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private List<Map<String, Object>> convertFilesToResponse(List<FileUpload> files) {
        return files.stream().map(file -> {
            Map<String, Object> dto = new HashMap<>();
            dto.put("id", file.getId());
            dto.put("fileMd5", file.getFileMd5());
            dto.put("fileName", file.getFileName());
            dto.put("totalSize", file.getTotalSize());
            dto.put("status", file.getStatus());
            dto.put("userId", file.getUserId());
            dto.put("orgTag", file.getOrgTag());
            dto.put("public", file.isPublic());
            dto.put("isPublic", file.isPublic());
            dto.put("createdAt", file.getCreatedAt());
            dto.put("mergedAt", file.getMergedAt());
            dto.put("estimatedEmbeddingTokens", file.getEstimatedEmbeddingTokens());
            dto.put("estimatedChunkCount", file.getEstimatedChunkCount());
            dto.put("actualEmbeddingTokens", file.getActualEmbeddingTokens());
            dto.put("actualChunkCount", file.getActualChunkCount());
            dto.put("vectorizationStatus", file.getVectorizationStatus());
            dto.put("vectorizationErrorMessage", file.getVectorizationErrorMessage());
            dto.put("orgTagName", getOrgTagName(file.getOrgTag()));
            putLatestVersionMetadata(dto, file);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 列表补充最新内容版本的标识与合规元数据。
     * 只做展示，不参与可见性判断：权限与过滤仍由 getAccessibleFiles 决定；
     * 尚未版本化的旧文件（legacy）没有版本记录，字段统一返回 null。
     */
    private void putLatestVersionMetadata(Map<String, Object> dto, FileUpload file) {
        Optional<DocumentVersion> latestVersion = file.getId() == null
                ? Optional.empty()
                : documentVersionRepository.findFirstByFileUploadIdOrderByVersionNoDesc(file.getId());

        dto.put("documentId", latestVersion.map(DocumentVersion::getDocumentId).orElse(null));
        dto.put("versionId", latestVersion.map(DocumentVersion::getVersionId).orElse(null));
        dto.put("versionNo", latestVersion.map(DocumentVersion::getVersionNo).orElse(null));
        dto.put("docType", latestVersion.map(DocumentVersion::getDocType).orElse(null));
        dto.put("jurisdiction", latestVersion.map(DocumentVersion::getJurisdiction).orElse(null));
        dto.put("jurisdictionCode", latestVersion.map(DocumentVersion::getJurisdictionCode).orElse(null));
        dto.put("issuer", latestVersion.map(DocumentVersion::getIssuer).orElse(null));
        dto.put("applicableInstitutions", latestVersion.map(DocumentVersion::getApplicableInstitutions).orElse(null));
        dto.put("effectiveAt", latestVersion.map(DocumentVersion::getEffectiveAt).orElse(null));
        dto.put("expiresAt", latestVersion.map(DocumentVersion::getExpiresAt).orElse(null));
        dto.put("repealed", latestVersion.map(DocumentVersion::isRepealed).orElse(null));
        dto.put("metadataRevisionPending", latestVersion
                .flatMap(version -> businessDocumentRepository.findById(version.getDocumentId()))
                .map(BusinessDocument::isMetadataRevisionPending)
                .orElse(null));
        dto.put("publicationStatus", latestVersion
                .flatMap(version -> businessDocumentRepository.findById(version.getDocumentId()))
                .map(document -> document.getPublicationStatus().name())
                .orElse(null));
        dto.put("validityStatus", latestVersion
                .filter(version -> version.getDocType() != null)
                .map(version -> ComplianceValidityStatus.resolve(
                        version.getEffectiveAt(), version.getExpiresAt(), version.isRepealed(), LocalDate.now()).name())
                .orElse(null));
        dto.put("validityLabel", latestVersion
                .filter(version -> version.getDocType() != null)
                .map(version -> ComplianceValidityStatus.resolve(
                        version.getEffectiveAt(), version.getExpiresAt(), version.isRepealed(), LocalDate.now()).label())
                .orElse(null));
    }
    
    /**
     * 根据文件名下载文件
     * 
     * @param fileName 文件名
     * @param token JWT token
     * @return 文件资源或错误响应
     */
    @GetMapping("/download")
    public ResponseEntity<?> downloadFileByName(
            @RequestParam String fileName,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DOWNLOAD_FILE_BY_NAME");
        try {
            // 验证token并获取用户信息
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            String userId = authContext.userId();
            String orgTags = authContext.orgTags();
            
            LogUtils.logBusiness("DOWNLOAD_FILE_BY_NAME", userId != null ? userId : "anonymous", "接收到文件下载请求: fileName=%s", fileName);
            
            // 共享库只允许已登录用户读取当前发布的资料
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "请先登录"));
            }
            Optional<FileUpload> targetFile = fileUploadRepository.findFirstByFileNameOrderByCreatedAtDesc(fileName);
            if (targetFile.isEmpty() || !documentAccessService.canRead(userId, targetFile.get().getFileMd5())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("code", 404, "message", "文件不存在或当前不可读"));
            }
            FileUpload file = targetFile.get();
            
            // 生成下载链接或返回预签名URL
            String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());
            
            if (downloadUrl == null) {
                LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "FAILED_GENERATE_URL");
                monitor.end("下载失败：无法生成下载链接");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法生成下载链接");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
            
            LogUtils.logFileOperation(userId, "DOWNLOAD", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "DOWNLOAD_FILE_BY_NAME", fileName, "SUCCESS");
            monitor.end("文件下载链接生成成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文件下载链接生成成功");
            response.put("data", Map.of(
                "fileName", file.getFileName(),
                "downloadUrl", downloadUrl,
                "fileSize", file.getTotalSize()
            ));
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}
            
            LogUtils.logBusinessError("DOWNLOAD_FILE_BY_NAME", userId, "文件下载失败: fileName=%s", e, fileName);
            monitor.end("下载失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件下载失败: " + e.getMessage()); 
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * 预览文件内容
     *
     * @param fileName 文件名
     * @param fileMd5 文件MD5（可选，用于精确定位同名文件）
     * @param token JWT token (URL参数，用于向后兼容)
     * @return 文件预览内容或错误响应
     */
    @GetMapping("/preview")
    public ResponseEntity<?> previewFileByName(
            @RequestParam String fileName,
            @RequestParam(required = false) String fileMd5,
            @RequestParam(required = false) Integer pageNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {
        
        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_FILE_BY_NAME");
        try {
            // 验证token并获取用户信息
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            String userId = authContext.userId();
            String orgTags = authContext.orgTags();
            
            LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", userId != null ? userId : "anonymous",
                    "接收到文件预览请求: fileName=%s, fileMd5=%s, pageNumber=%s", fileName, fileMd5, pageNumber);

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "请先登录"));
            }
            FileUpload file = null;

            // 如果没有提供token或token无效，只允许预览公开文件
            if (userId == null) {
                // 优先使用MD5查找（如果提供）
                if (fileMd5 != null && !fileMd5.trim().isEmpty()) {
                    Optional<FileUpload> fileByMd5 = fileUploadRepository.findFirstByFileMd5AndIsPublicTrueOrderByCreatedAtDesc(fileMd5);
                    if (fileByMd5.isPresent()) {
                        file = fileByMd5.get();
                        LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", "anonymous", "使用MD5找到公开文件: fileMd5=%s", fileMd5);
                    }
                }

                // 如果MD5未找到或未提供，降级到文件名查找
                if (file == null) {
                    Optional<FileUpload> publicFile = fileUploadRepository.findFirstByFileNameAndIsPublicTrueOrderByCreatedAtDesc(fileName);
                    if (publicFile.isPresent()) {
                        file = publicFile.get();
                        LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", "anonymous", "使用文件名找到公开文件: fileName=%s", fileName);
                    }
                }

                if (file == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.NOT_FOUND.value());
                    response.put("message", "文件不存在或需要登录访问");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }

                Map<String, Object> previewData = buildPreviewResponse(file, pageNumber, false);
                if (previewData == null) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                    response.put("message", "无法获取文件预览内容");
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("code", 200);
                response.put("message", "文件预览内容获取成功");
                response.put("data", previewData);
                LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", "anonymous",
                        "预览响应已生成: fileMd5=%s, mode=%s, pageNumber=%s",
                        file.getFileMd5(),
                        Boolean.TRUE.equals(previewData.get("singlePageMode")) ? "single-page" : "full-document",
                        pageNumber);
                return ResponseEntity.ok(response);
            }

            // 有token的情况：按 MD5 或文件名定位，再校验共享库发布/有效期状态
            Optional<FileUpload> targetFile = Optional.empty();
            if (fileMd5 != null && !fileMd5.trim().isEmpty()) {
                targetFile = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5.trim());
            }
            if (targetFile.isEmpty()) {
                targetFile = fileUploadRepository.findFirstByFileNameOrderByCreatedAtDesc(fileName);
            }

            if (targetFile.isEmpty() || !documentAccessService.canRead(userId, targetFile.get().getFileMd5())) {
                LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "FAILED_NOT_FOUND");
                monitor.end("预览失败：文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("code", 404, "message", "文件不存在或当前不可读"));
            }

            file = targetFile.get();
            
            // 获取文件预览内容
            Map<String, Object> previewData = buildPreviewResponse(file, pageNumber, true);
            if (previewData == null) {
                LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "FAILED_GET_CONTENT");
                monitor.end("预览失败：无法获取文件内容");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法获取文件预览内容");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
            
            LogUtils.logFileOperation(userId, "PREVIEW", file.getFileName(), file.getFileMd5(), "SUCCESS");
            LogUtils.logUserOperation(userId, "PREVIEW_FILE_BY_NAME", fileName, "SUCCESS");
            monitor.end("文件预览内容获取成功");
            
            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文件预览内容获取成功");
            response.put("data", previewData);
            LogUtils.logBusiness("PREVIEW_FILE_BY_NAME", userId,
                    "预览响应已生成: fileMd5=%s, mode=%s, pageNumber=%s",
                    file.getFileMd5(),
                    Boolean.TRUE.equals(previewData.get("singlePageMode")) ? "single-page" : "full-document",
                    pageNumber);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}
            
            LogUtils.logBusinessError("PREVIEW_FILE_BY_NAME", userId, "文件预览失败: fileName=%s", e, fileName);
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件预览失败: " + e.getMessage()); 
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/page-preview")
    public ResponseEntity<?> previewPdfPage(
            @RequestParam String fileMd5,
            @RequestParam Integer pageNumber,
            @RequestAttribute("userId") String userId,
            @RequestAttribute("orgTags") String orgTags) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("PREVIEW_PDF_PAGE");
        try {
            LogUtils.logBusiness("PREVIEW_PDF_PAGE", userId,
                    "接收到 PDF 单页预览请求: fileMd5=%s, pageNumber=%s", fileMd5, pageNumber);

            FileUpload file = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .filter(item -> documentAccessService.canRead(userId, item.getFileMd5()))
                    .orElse(null);

            if (file == null) {
                monitor.end("预览失败：文件不存在或无权限访问");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            if (!"pdf".equalsIgnoreCase(getFileExtension(file.getFileName()))) {
                monitor.end("预览失败：仅支持 PDF 单页预览");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.BAD_REQUEST.value());
                response.put("message", "仅支持 PDF 单页预览");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            DocumentService.PdfSinglePagePreview preview = documentService.getPdfSinglePagePreview(fileMd5, pageNumber);
            byte[] pdfBytes = preview.content();
            String encodedFileName = URLEncoder.encode(file.getFileName().replace(".pdf", "") + "-page-" + pageNumber + ".pdf",
                    StandardCharsets.UTF_8);
            String cacheStatus = preview.cacheHit() ? "HIT" : "MISS";

            LogUtils.logBusiness("PREVIEW_PDF_PAGE", userId,
                    "PDF 单页预览响应: fileMd5=%s, pageNumber=%s, cache=%s, contentLength=%s",
                    fileMd5, pageNumber, cacheStatus, pdfBytes.length);

            monitor.end("PDF 单页预览生成成功");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFileName)
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=1800")
                    .header(HttpHeaders.ETAG, "\"" + fileMd5 + ":" + pageNumber + "\"")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdfBytes.length))
                    .header("X-Preview-Mode", "single-page")
                    .header("X-Preview-Cache", cacheStatus)
                    .header("X-Preview-Page", String.valueOf(pageNumber))
                    .body(pdfBytes);
        } catch (IllegalArgumentException e) {
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.BAD_REQUEST.value());
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("PREVIEW_PDF_PAGE", userId,
                    "PDF 单页预览失败: fileMd5=%s, pageNumber=%s", e, fileMd5, pageNumber);
            monitor.end("预览失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "PDF 单页预览失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/download-by-md5")
    public ResponseEntity<?> downloadFileByMd5(
            @RequestParam String fileMd5,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String token) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("DOWNLOAD_FILE_BY_MD5");
        try {
            RequestAuthContext authContext = resolveRequestAuthContext(authorization, token);
            String userId = authContext.userId();
            String orgTags = authContext.orgTags();

            LogUtils.logBusiness("DOWNLOAD_FILE_BY_MD5", userId != null ? userId : "anonymous", "接收到文件下载请求: fileMd5=%s", fileMd5);

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "请先登录"));
            }
            FileUpload file = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .filter(item -> documentAccessService.canRead(userId, item.getFileMd5()))
                    .orElse(null);

            if (file == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "文件不存在或无权限访问");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            String downloadUrl = documentService.generateDownloadUrl(file.getFileMd5());
            if (downloadUrl == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
                response.put("message", "无法生成下载链接");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "文件下载链接生成成功");
            response.put("data", Map.of(
                    "fileName", file.getFileName(),
                    "downloadUrl", downloadUrl,
                    "fileSize", file.getTotalSize(),
                    "fileMd5", file.getFileMd5()
            ));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            String userId = "unknown";
            try {
                if (token != null && !token.trim().isEmpty()) {
                    userId = jwtUtils.extractUsernameFromToken(token);
                }
            } catch (Exception ignored) {}

            LogUtils.logBusinessError("DOWNLOAD_FILE_BY_MD5", userId, "文件下载失败: fileMd5=%s", e, fileMd5);
            monitor.end("下载失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "文件下载失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/reference-detail")
    public ResponseEntity<?> getReferenceDetail(
            @RequestParam String sessionId,
            @RequestParam Integer referenceNumber,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        LogUtils.PerformanceMonitor monitor = LogUtils.startPerformanceMonitor("GET_REFERENCE_DETAIL");
        try {
            LogUtils.logBusiness("GET_REFERENCE_DETAIL", "system",
                    "接收到获取引用详情请求: sessionId=%s, referenceNumber=%s", sessionId, referenceNumber);

            ChatHandler.ReferenceInfo detail = chatHandler.getReferenceDetail(sessionId, referenceNumber);
            if (detail == null) {
                monitor.end("未找到引用映射");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.NOT_FOUND.value());
                response.put("message", "未找到对应的文件引用");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            RequestAuthContext authContext = resolveRequestAuthContext(authorization, null);
            boolean readable = authContext.userId() != null
                    && (hasText(detail.documentId()) && hasText(detail.versionId())
                    ? documentAccessService.canRead(authContext.userId(), detail.documentId(), detail.versionId())
                    : documentAccessService.canRead(authContext.userId(), detail.fileMd5()));
            if (!readable) {
                monitor.end("获取引用详情失败：无权限访问引用文件");
                Map<String, Object> response = new HashMap<>();
                response.put("code", HttpStatus.FORBIDDEN.value());
                response.put("message", "无权限访问该引用文件");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("fileMd5", detail.fileMd5());
            data.put("fileName", detail.fileName());
            data.put("documentId", detail.documentId());
            data.put("versionId", detail.versionId());
            data.put("referenceNumber", referenceNumber);
            data.put("pageNumber", detail.pageNumber());
            data.put("anchorText", detail.anchorText());
            data.put("retrievalMode", detail.retrievalMode());
            data.put("retrievalLabel", detail.retrievalLabel());
            data.put("retrievalQuery", detail.retrievalQuery());
            data.put("matchedChunkText", detail.matchedChunkText());
            data.put("evidenceSnippet", detail.evidenceSnippet());
            data.put("score", detail.score());
            data.put("chunkId", detail.chunkId());
            data.put("articlePath", detail.articlePath());
            data.put("clauseNo", detail.clauseNo());

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", "获取引用详情成功");
            response.put("data", data);
            monitor.end("获取引用详情成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_REFERENCE_DETAIL", "system",
                    "获取引用详情失败: sessionId=%s, referenceNumber=%s", e, sessionId, referenceNumber);
            monitor.end("获取引用详情失败: " + e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.put("message", "获取引用详情失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private Map<String, Object> buildPreviewResponse(FileUpload file, Integer pageNumber, boolean preferSinglePagePreview) {
        String fileName = file.getFileName();
        String extension = getFileExtension(fileName);
        String previewType = getPreviewType(extension);

        Map<String, Object> payload = new HashMap<>();
        payload.put("fileName", fileName);
        payload.put("fileMd5", file.getFileMd5());
        payload.put("fileSize", file.getTotalSize());
        payload.put("previewType", previewType);

        if ("text".equals(previewType)) {
            String previewContent = documentService.getFilePreviewContent(file.getFileMd5(), fileName);
            if (previewContent == null) {
                return null;
            }
            payload.put("content", previewContent);
            return payload;
        }

        String previewUrl = documentService.generateDownloadUrl(file.getFileMd5());
        if (previewUrl == null) {
            return null;
        }

        if (preferSinglePagePreview && "pdf".equals(previewType) && pageNumber != null && pageNumber > 0) {
            payload.put("previewUrl", buildSinglePagePreviewUrl(file.getFileMd5(), pageNumber));
            payload.put("sourceUrl", previewUrl);
            payload.put("singlePageMode", true);
            payload.put("sourcePageNumber", pageNumber);
            return payload;
        }

        payload.put("previewUrl", previewUrl);
        return payload;
    }

    private String buildSinglePagePreviewUrl(String fileMd5, Integer pageNumber) {
        return "/api/v1/documents/page-preview?fileMd5="
                + URLEncoder.encode(fileMd5, StandardCharsets.UTF_8)
                + "&pageNumber="
                + pageNumber;
    }

    private String getPreviewType(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "download";
        }

        String lowerCaseExtension = extension.toLowerCase();
        if ("pdf".equals(lowerCaseExtension)) {
            return "pdf";
        }

        if (List.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg").contains(lowerCaseExtension)) {
            return "image";
        }

        if (List.of("txt", "md", "json", "xml", "csv", "html", "htm", "css", "js", "java", "py", "sql", "yaml", "yml").contains(lowerCaseExtension)) {
            return "text";
        }

        return "download";
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName.substring(dotIndex + 1);
    }

    private RequestAuthContext resolveRequestAuthContext(String authorization, String fallbackToken) {
        String jwtToken = extractBearerToken(authorization);
        if ((jwtToken == null || jwtToken.isBlank()) && fallbackToken != null && !fallbackToken.isBlank()) {
            jwtToken = fallbackToken.trim();
        }

        if (jwtToken == null || jwtToken.isBlank()) {
            return new RequestAuthContext(null, null);
        }

        return new RequestAuthContext(
                jwtUtils.extractUserIdFromToken(jwtToken),
                jwtUtils.extractOrgTagsFromToken(jwtToken)
        );
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null) {
            return null;
        }

        String trimmed = authorization.trim();
        if (trimmed.startsWith("Bearer ")) {
            return trimmed.substring(7);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record RequestAuthContext(String userId, String orgTags) {}
    
    /**
     * 根据tagId获取tagName
     *
     * @param tagId 组织标签ID
     * @return 组织标签名称，如果找不到则返回原tagId
     */
    private String getOrgTagName(String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return null;
        }
        
        try {
            Optional<OrganizationTag> tagOpt = organizationTagRepository.findByTagId(tagId);
            if (tagOpt.isPresent()) {
                return tagOpt.get().getName();
            } else {
                LogUtils.logBusiness("GET_ORG_TAG_NAME", "system", "找不到组织标签: tagId=%s", tagId);
                return tagId; // 如果找不到标签名称，返回原tagId
            }
        } catch (Exception e) {
            LogUtils.logBusinessError("GET_ORG_TAG_NAME", "system", "查询组织标签名称失败: tagId=%s", e, tagId);
            return tagId; // 发生错误时返回原tagId
        }
    }

} 
