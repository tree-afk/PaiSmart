package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.service.document.DocumentVersionService;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import com.yizhaoqi.smartpai.service.reliability.FileProcessingOutboxService;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.ChunkInfoRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 文档管理服务类
 * 负责文档的删除等管理操作
 */
@Service
public class DocumentService {

    public record PdfSinglePagePreview(byte[] content, boolean cacheHit) {}
    private record InMemoryPdfPreviewCache(byte[] content, long expiresAtMillis) {}

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final String PDF_SINGLE_PAGE_CACHE_PREFIX = "preview:pdf:single-page:";
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MINUTES = 30;
    private static final long PDF_SINGLE_PAGE_CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(PDF_SINGLE_PAGE_CACHE_TTL_MINUTES);
    private static final String LEGACY_COMPLETED_WITHOUT_USAGE_MESSAGE = "历史数据未统计实际 Tokens，可按需重试以回写实际向量化结果";
    private static final String LEGACY_FAILED_MESSAGE = "历史向量化结果缺失，可点击重试向量化重新处理";
    private static final Map<String, InMemoryPdfPreviewCache> PDF_SINGLE_PAGE_LOCAL_CACHE = new ConcurrentHashMap<>();

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ChunkInfoRepository chunkInfoRepository;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private FileProcessingOutboxService fileProcessingOutboxService;

    @Autowired
    private DocumentVersionService documentVersionService;

    /**
     * 按 MD5 清理未版本化旧文档及其相关数据
     * 该方法将删除:
     * 1. FileUpload记录
     * 2. DocumentVector记录
     * 3. MinIO中的文件
     * 4. Elasticsearch中的向量数据
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    public void deleteLegacyDocument(String fileMd5, String userId) {
        logger.info("开始删除文档: {}", fileMd5);
        
        try {
            // 获取文件信息以获取文件名
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5, userId)
                    .orElseThrow(() -> new RuntimeException("文件不存在"));
            
            // 1. 删除Elasticsearch中的数据
            try {
                elasticsearchService.deleteByFileMd5(fileMd5);
                logger.info("成功从Elasticsearch删除文档: {}", fileMd5);
            } catch (Exception e) {
                logger.error("从Elasticsearch删除文档时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 2. 删除MinIO中的文件（使用MD5作为对象路径）
            try {
                String objectName = "merged/" + fileUpload.getFileMd5();
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket("uploads")
                                .object(objectName)
                                .build()
                );
                logger.info("成功从MinIO删除文件: {}", objectName);
            } catch (Exception e) {
                logger.warn("使用MD5路径删除文件失败，尝试使用文件名路径: {}", fileMd5);
                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                try {
                    String oldObjectName = "merged/" + fileUpload.getFileName();
                    minioClient.removeObject(
                            RemoveObjectArgs.builder()
                                    .bucket("uploads")
                                    .object(oldObjectName)
                                    .build()
                    );
                    logger.info("使用旧路径成功从MinIO删除文件: {}", oldObjectName);
                } catch (Exception ex) {
                    logger.error("从MinIO删除文件时出错（新旧路径都失败）: {}", fileMd5, ex);
                    // 继续删除其他数据
                }
            }

            invalidatePdfSinglePagePreviewCache(fileMd5);
            
            // 3. 删除DocumentVector记录
            try {
                documentVectorRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档向量记录: {}", fileMd5);
            } catch (Exception e) {
                logger.error("删除文档向量记录时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }

            // 删除分片元数据，避免同 MD5 文件再次上传时误判分片已经存在
            try {
                int deletedChunkRows = chunkInfoRepository.deleteByFileMd5(fileMd5);
                logger.info("成功删除文档分片元数据: fileMd5={}, deletedRows={}", fileMd5, deletedChunkRows);
            } catch (Exception e) {
                logger.error("删除文档分片元数据时出错: {}", fileMd5, e);
                // 继续删除其他数据
            }
            
            // 4. 删除FileUpload记录
            fileUploadRepository.deleteByFileMd5(fileMd5);
            logger.info("成功删除文件上传记录: {}", fileMd5);
            
            logger.info("文档删除完成: {}", fileMd5);
        } catch (Exception e) {
            logger.error("删除文档过程中发生错误: {}", fileMd5, e);
            throw new RuntimeException("删除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 异步重建：按文件的当前内容版本新建 build 并写 outbox，不在请求线程里删除任何索引/向量数据；
     * 旧 build 保持 ACTIVE，新 build 完成核对后才切换。
     */
    @Transactional
    public VectorizationSubmission enqueueAsyncReindex(String fileMd5, String requesterId) {
        VectorizationSubmission submission = enqueueBuildTask(fileMd5, requesterId, FileProcessingTask.TASK_TYPE_REINDEX);
        logger.info("已提交异步重建索引任务: fileMd5={}, requesterId={}, buildId={}",
                fileMd5, requesterId, submission.versionBuild().buildId());
        return submission;
    }

    /**
     * 清理文件已有的分块和索引，供重新解析前调用，保证重复处理不会产生重复分块。
     * ES 只清理线上别名，保留旧版本索引中的数据用于回滚；ES 清理失败直接抛出，让任务重试而不是带着旧数据继续
     */
    public void clearIndexedData(String fileMd5) {
        elasticsearchService.deleteByFileMd5(KnowledgeIndexNames.ALIAS, fileMd5);
        documentVectorRepository.deleteByFileMd5(fileMd5);
        invalidatePdfSinglePagePreviewCache(fileMd5);
        logger.info("已清理文件旧分块和索引: fileMd5={}", fileMd5);
    }

    /**
     * 清理指定构建的残留：只删除该 build 写入的 ES 分块和 document_vectors，不碰其他构建和 legacy 数据。
     * 供消费者在重跑同一构建前调用；ES 清理失败直接抛出，让任务重试而不是带着旧数据继续
     */
    public void clearBuildData(String buildId) {
        elasticsearchService.deleteByBuildId(KnowledgeIndexNames.ALIAS, buildId);
        documentVectorRepository.deleteByBuildId(buildId);
        logger.info("已清理构建旧分块和索引: buildId={}", buildId);
    }

    /**
     * 异步重试：对文件对应的内容版本新建 build；尚未版本化的旧文件先懒迁移出版本 1。
     * 不再投递按 fileMd5 清理数据的 8 参 legacy 任务。
     */
    @Transactional
    public VectorizationSubmission enqueueAsyncVectorizationRetry(String fileMd5, String requesterId) {
        VectorizationSubmission submission = enqueueBuildTask(fileMd5, requesterId, FileProcessingTask.TASK_TYPE_REINDEX);
        logger.info("已提交异步向量化重试任务: fileMd5={}, requesterId={}, buildId={}",
                fileMd5, requesterId, submission.versionBuild().buildId());
        return submission;
    }

    /**
     * 找到文件当前的内容版本，创建新 build 并投递带完整构建上下文的任务；
     * 没有版本记录的旧文件先登记为 legacy version 1（共享 md5 会被版本服务拒绝）。
     * 允许文件所有者或管理员对已完成合并的文件发起；fileMd5 重试/重建允许落在 active 或最新版本上。
     */
    private VectorizationSubmission enqueueBuildTask(String fileMd5, String requesterId, String taskType) {
        FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                .orElseThrow(() -> new CustomException("文件不存在", HttpStatus.NOT_FOUND));
        requireReindexableUpload(fileUpload, requesterId);

        DocumentVersionService.VersionBuild versionBuild;
        String buildKind;
        Optional<DocumentVersionService.VersionBuild> existingVersion =
                documentVersionService.findByFileUploadId(fileUpload.getId());
        if (existingVersion.isPresent()) {
            versionBuild = documentVersionService.createBuildForActiveVersion(
                    existingVersion.get().versionId(), requesterId, FileProcessingTask.BUILD_KIND_REINDEX);
            buildKind = FileProcessingTask.BUILD_KIND_REINDEX;
        } else {
            DocumentVersionService.VersionBuild legacy =
                    documentVersionService.promoteLegacy(fileUpload, requesterId);
            versionBuild = documentVersionService.createBuildForActiveVersion(
                    legacy.versionId(), requesterId, FileProcessingTask.BUILD_KIND_LEGACY_PROMOTION);
            buildKind = FileProcessingTask.BUILD_KIND_LEGACY_PROMOTION;
        }

        FileProcessingTask task = new FileProcessingTask(
                versionBuild.fileMd5(),
                null,
                versionBuild.fileName(),
                fileUpload.getUserId(),
                versionBuild.orgTag(),
                versionBuild.isPublic(),
                taskType,
                requesterId,
                versionBuild.documentId(),
                versionBuild.versionId(),
                versionBuild.buildId(),
                buildKind,
                versionBuild.versionNo(),
                versionBuild.buildNo(),
                versionBuild.compliance().docType(),
                versionBuild.compliance().jurisdiction(),
                versionBuild.compliance().issuer(),
                versionBuild.compliance().wireEffectiveAt(),
                versionBuild.compliance().applicableInstitutions(),
                versionBuild.compliance().wireExpiresAt(),
                versionBuild.compliance().repealed()
        );

        // 状态改成待处理和待投递消息同一事务提交，提交后再投递 Kafka；消息 key 为 buildId
        fileProcessingOutboxService.enqueueVectorization(fileUpload, task, true);
        return new VectorizationSubmission(fileUpload, versionBuild);
    }

    /**
     * 重试/重建允许文件所有者或管理员发起；对外入口由 KnowledgeAuthorizationFilter 限制为管理员。
     */
    private void requireReindexableUpload(FileUpload fileUpload, String requesterId) {
        if (!fileUpload.getUserId().equals(requesterId) && !isAdmin(requesterId)) {
            throw new CustomException("没有权限操作此文件", HttpStatus.FORBIDDEN);
        }
        if (fileUpload.getStatus() != FileUpload.STATUS_COMPLETED) {
            throw new CustomException("文件尚未完成合并，无法重建索引", HttpStatus.CONFLICT);
        }
    }

    /**
     * 解析请求者是否为管理员：JWT 的 userId 可能是数字主键或用户名。
     */
    private boolean isAdmin(String requesterId) {
        if (requesterId == null || requesterId.isBlank()) {
            return false;
        }
        try {
            return userRepository.findById(Long.parseLong(requesterId))
                    .map(user -> user.getRole() == com.yizhaoqi.smartpai.model.User.Role.ADMIN)
                    .orElse(false);
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(requesterId)
                    .map(user -> user.getRole() == com.yizhaoqi.smartpai.model.User.Role.ADMIN)
                    .orElse(false);
        }
    }

    public record VectorizationSubmission(FileUpload fileUpload,
                                          DocumentVersionService.VersionBuild versionBuild) {
    }

    /**
     * 把向量化异常转成给用户看的失败原因（取最深一层的异常信息，截断到字段长度）
     */
    public static String vectorizationErrorMessageOf(Throwable error) {
        return trimVectorizationErrorMessage(resolveVectorizationErrorMessage(error));
    }

    private static String resolveVectorizationErrorMessage(Throwable error) {
        Throwable current = error;
        String deepestMessage = null;

        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                deepestMessage = message;
                if (message.contains("余额不足")) {
                    return message;
                }
            }
            current = current.getCause();
        }

        if (deepestMessage == null || deepestMessage.isBlank()) {
            return "向量化失败，请稍后重试";
        }

        if ("向量化失败".equals(deepestMessage) || "Error processing task".equals(deepestMessage)) {
            return "向量化失败，请稍后重试";
        }

        return deepestMessage;
    }

    private static String trimVectorizationErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "向量化失败，请稍后重试";
        }
        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }
    
    /**
     * 获取用户可访问的所有文件列表
     * 包括用户自己的文件、公开文件和用户所属组织的文件（支持层级权限）
     *
     * @param userId 用户ID
     * @param orgTags 用户所属的组织标签（逗号分隔的字符串，仅供兼容性使用）
     * @return 用户可访问的文件列表
     */
    public List<FileUpload> getAccessibleFiles(String userId, String orgTags) {
        logger.info("获取用户可访问文件列表: userId={}", userId);
        
        try {
            backfillLegacyVectorizationStatuses();

            User user = resolveUser(userId);
            String userDbId = String.valueOf(user.getId());
            
            List<String> userEffectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户有效组织标签: {}", userEffectiveTags);
            
            // 使用有效标签查询文件
            List<FileUpload> files;
            if (userEffectiveTags.isEmpty()) {
                // 如果用户没有任何组织标签，只返回自己的文件和公开文件
                files = fileUploadRepository.findByUserIdOrIsPublicTrue(userDbId);
                logger.debug("用户无组织标签，仅返回个人和公开文件");
            } else {
                // 查询用户可访问的所有文件（考虑层级标签）
                files = fileUploadRepository.findAccessibleFilesWithTags(userDbId, userEffectiveTags);
                logger.debug("使用有效组织标签查询文件");
            }

            files = deduplicateFileUploads(files);
            logger.info("成功获取用户可访问文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户可访问文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取可访问文件列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 文件管理者看到的共享库完整列表：不再按上传者/组织过滤。
     */
    public List<FileUpload> getFileManagerFiles() {
        backfillLegacyVectorizationStatuses();
        return deduplicateFileUploads(fileUploadRepository.findAll());
    }

    /**
     * 获取用户上传的所有文件列表
     *
     * @param userId 用户ID
     * @return 用户上传的文件列表
     */
    public List<FileUpload> getUserUploadedFiles(String userId) {
        logger.info("获取用户上传的文件列表: userId={}", userId);
        
        try {
            backfillLegacyVectorizationStatuses();
            List<FileUpload> files = fileUploadRepository.findByUserId(userId);
            files = deduplicateFileUploads(files);
            logger.info("成功获取用户上传的文件列表: userId={}, fileCount={}", userId, files.size());
            return files;
        } catch (Exception e) {
            logger.error("获取用户上传的文件列表失败: userId={}", userId, e);
            throw new RuntimeException("获取用户上传的文件列表失败: " + e.getMessage(), e);
        }
    }

    private User resolveUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        } catch (NumberFormatException ignored) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));
        }
    }

    private List<FileUpload> deduplicateFileUploads(List<FileUpload> files) {
        if (files == null || files.size() < 2) {
            return files;
        }

        Map<String, FileUpload> deduplicated = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (FileUpload file : files) {
            if (file == null) {
                continue;
            }

            String key = file.getFileMd5() + ":" + file.getUserId();
            FileUpload existing = deduplicated.get(key);
            if (existing == null) {
                deduplicated.put(key, file);
                continue;
            }

            duplicateCount++;
            deduplicated.put(key, choosePreferredFileUpload(existing, file));
        }

        if (duplicateCount > 0) {
            logger.warn("检测到重复文件记录，列表返回前已合并: duplicateCount={}, uniqueCount={}",
                    duplicateCount, deduplicated.size());
        }

        return new ArrayList<>(deduplicated.values());
    }

    private FileUpload choosePreferredFileUpload(FileUpload current, FileUpload candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }

        boolean currentCompleted = current.getStatus() == FileUpload.STATUS_COMPLETED;
        boolean candidateCompleted = candidate.getStatus() == FileUpload.STATUS_COMPLETED;
        if (currentCompleted != candidateCompleted) {
            return candidateCompleted ? candidate : current;
        }

        int mergedAtCompare = compareNullableDateTime(candidate.getMergedAt(), current.getMergedAt());
        if (mergedAtCompare > 0) {
            return candidate;
        }

        int createdAtCompare = compareNullableDateTime(candidate.getCreatedAt(), current.getCreatedAt());
        if (createdAtCompare > 0) {
            return candidate;
        }

        if (candidate.getId() != null && current.getId() != null && candidate.getId() > current.getId()) {
            return candidate;
        }

        return current;
    }

    private int compareNullableDateTime(java.time.LocalDateTime left, java.time.LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private void backfillLegacyVectorizationStatuses() {
        backfillLegacyVectorizationStatuses(fileUploadRepository.findAllByVectorizationStatusIsNull());
    }

    private void backfillLegacyVectorizationStatuses(List<FileUpload> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        Map<String, Long> vectorCountCache = new HashMap<>();
        for (FileUpload file : files) {
            if (file == null || file.getVectorizationStatus() != null) {
                continue;
            }

            boolean changed = false;
            if (file.getStatus() != FileUpload.STATUS_COMPLETED) {
                continue;
            }

            if (file.getActualEmbeddingTokens() != null || file.getActualChunkCount() != null) {
                file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
                file.setVectorizationErrorMessage(null);
                changed = true;
            } else {
                long vectorCount = vectorCountCache.computeIfAbsent(
                        file.getFileMd5(),
                        documentVectorRepository::countByFileMd5
                );
                if (vectorCount > 0) {
                    file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_COMPLETED);
                    file.setVectorizationErrorMessage(LEGACY_COMPLETED_WITHOUT_USAGE_MESSAGE);
                    changed = true;
                } else if (file.getEstimatedEmbeddingTokens() != null || file.getEstimatedChunkCount() != null) {
                    file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);
                    file.setVectorizationErrorMessage(LEGACY_FAILED_MESSAGE);
                    changed = true;
                }
            }

            if (changed) {
                fileUploadRepository.save(file);
            }
        }
    }
    
    /**
     * 生成文件下载链接
     * 
     * @param fileMd5 文件MD5
     * @return 预签名下载URL
     */
    public String generateDownloadUrl(String fileMd5) {
        logger.info("生成文件下载链接: fileMd5={}", fileMd5);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 优先使用新的MD5路径
            String objectName = "merged/" + fileMd5;

            try {
                // 尝试使用新路径（MD5）
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(objectName)
                                .expiry(3600)
                                .build()
                );
                logger.info("成功生成文件下载链接（新路径）: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), objectName);

                // 使用 publicUrl 公开域名来替换原始域名
                presignedUrl = uploadService.transToPublicUrl(presignedUrl);
                return presignedUrl;
            } catch (Exception e) {
                logger.warn("使用新路径生成下载链接失败，尝试使用旧路径（文件名）: fileMd5={}", fileMd5);
                // 降级：尝试使用旧的文件名路径（兼容旧数据）
                String oldObjectName = "merged/" + fileUpload.getFileName();
                String presignedUrl = minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket("uploads")
                                .object(oldObjectName)
                                .expiry(3600)
                                .build()
                );
                logger.info("成功生成文件下载链接（旧路径）: fileMd5={}, fileName={}, objectName={}",
                        fileMd5, fileUpload.getFileName(), oldObjectName);
                presignedUrl = uploadService.transToPublicUrl(presignedUrl);
                return presignedUrl;
            }
        } catch (Exception e) {
            logger.error("生成文件下载链接失败: fileMd5={}", fileMd5, e);
            return null;
        }
    }
    
    /**
     * 获取文件预览内容
     * 
     * @param fileMd5 文件MD5
     * @param fileName 文件名
     * @return 文件预览内容，对于文本文件返回前几KB内容，非文本文件返回文件信息
     */
    public String getFilePreviewContent(String fileMd5, String fileName) {
        logger.info("获取文件预览内容: fileMd5={}, fileName={}", fileMd5, fileName);

        try {
            // 从数据库获取文件信息
            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            // 判断文件类型
            String fileExtension = getFileExtension(fileName).toLowerCase();
            boolean isTextFile = isTextFile(fileExtension);

            if (isTextFile) {
                // 对于文本文件，读取前10KB内容
                try (InputStream inputStream = openFileStream(fileUpload);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                    StringBuilder content = new StringBuilder();
                    String line;
                    int bytesRead = 0;
                    int maxBytes = 10240; // 10KB

                    while ((line = reader.readLine()) != null && bytesRead < maxBytes) {
                        content.append(line).append("\n");
                        bytesRead += line.getBytes("UTF-8").length + 1;
                    }

                    String result = content.toString();
                    if (bytesRead >= maxBytes) {
                        result += "\n... (内容已截断，仅显示前10KB)";
                    }

                    logger.info("成功获取文本文件预览内容: fileMd5={}, contentLength={}, 内容前50字符={}",
                        fileMd5, result.length(), result.substring(0, Math.min(50, result.length())));
                    return result;
                }
            } else {
                // 对于非文本文件，返回文件信息
                String fileInfo = String.format(
                    "文件名: %s\n" +
                    "文件大小: %s\n" +
                    "文件类型: %s\n" +
                    "上传时间: %s\n\n" +
                    "此文件类型不支持预览，请下载后查看。",
                    fileName,
                    formatFileSize(fileUpload.getTotalSize()),
                    fileExtension.toUpperCase(),
                    fileUpload.getCreatedAt()
                );

                logger.info("返回非文本文件信息: fileMd5={}", fileMd5);
                return fileInfo;
            }

        } catch (Exception e) {
            logger.error("获取文件预览内容失败: fileMd5={}, fileName={}", fileMd5, fileName, e);
            return "预览失败: " + e.getMessage();
        }
    }

    public PdfSinglePagePreview getPdfSinglePagePreview(String fileMd5, int pageNumber) {
        logger.info("生成 PDF 单页预览: fileMd5={}, pageNumber={}", fileMd5, pageNumber);

        try {
            String cacheKey = buildPdfSinglePageCacheKey(fileMd5, pageNumber);
            byte[] localPreview = getLocalPdfSinglePagePreview(cacheKey);
            if (localPreview != null) {
                logger.info("命中 PDF 单页预览本地缓存: fileMd5={}, pageNumber={}, previewSize={}",
                        fileMd5, pageNumber, localPreview.length);
                return new PdfSinglePagePreview(localPreview, true);
            }

            byte[] cachedPreview = getCachedPdfSinglePagePreview(cacheKey);
            if (cachedPreview != null) {
                cacheLocalPdfSinglePagePreview(cacheKey, cachedPreview);
                logger.info("命中 PDF 单页预览缓存: fileMd5={}, pageNumber={}, previewSize={}",
                        fileMd5, pageNumber, cachedPreview.length);
                return new PdfSinglePagePreview(cachedPreview, true);
            }

            FileUpload fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElseThrow(() -> new RuntimeException("文件不存在: " + fileMd5));

            try (InputStream inputStream = openFileStream(fileUpload);
                 PDDocument sourceDocument = PDDocument.load(inputStream);
                 PDDocument singlePageDocument = new PDDocument();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

                int totalPages = sourceDocument.getNumberOfPages();
                if (pageNumber < 1 || pageNumber > totalPages) {
                    throw new IllegalArgumentException("页码超出范围: " + pageNumber + "/" + totalPages);
                }

                singlePageDocument.importPage(sourceDocument.getPage(pageNumber - 1));
                singlePageDocument.save(outputStream);

                byte[] previewBytes = outputStream.toByteArray();
                cacheLocalPdfSinglePagePreview(cacheKey, previewBytes);
                cachePdfSinglePagePreview(cacheKey, previewBytes);
                logger.info("成功生成 PDF 单页预览: fileMd5={}, pageNumber={}, previewSize={}",
                        fileMd5, pageNumber, outputStream.size());
                return new PdfSinglePagePreview(previewBytes, false);
            }
        } catch (Exception e) {
            logger.error("生成 PDF 单页预览失败: fileMd5={}, pageNumber={}", fileMd5, pageNumber, e);
            throw new RuntimeException("生成 PDF 单页预览失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }

    private InputStream openFileStream(FileUpload fileUpload) throws Exception {
        String objectName = "merged/" + fileUpload.getFileMd5();

        try {
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket("uploads")
                            .object(objectName)
                            .build());
            logger.info("使用新路径（MD5）获取文件流: fileMd5={}, objectName={}", fileUpload.getFileMd5(), objectName);
            return inputStream;
        } catch (Exception e) {
            logger.warn("使用新路径获取文件失败，尝试使用旧路径（文件名）: fileMd5={}, error={}",
                    fileUpload.getFileMd5(), e.getMessage());
            String oldObjectName = "merged/" + fileUpload.getFileName();
            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket("uploads")
                            .object(oldObjectName)
                            .build());
            logger.info("使用旧路径（文件名）获取文件流: fileMd5={}, objectName={}", fileUpload.getFileMd5(), oldObjectName);
            return inputStream;
        }
    }

    private String buildPdfSinglePageCacheKey(String fileMd5, int pageNumber) {
        return PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":" + pageNumber;
    }

    private byte[] getLocalPdfSinglePagePreview(String cacheKey) {
        InMemoryPdfPreviewCache cached = PDF_SINGLE_PAGE_LOCAL_CACHE.get(cacheKey);
        if (cached == null) {
            return null;
        }

        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            PDF_SINGLE_PAGE_LOCAL_CACHE.remove(cacheKey);
            return null;
        }

        return cached.content();
    }

    private void cacheLocalPdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        PDF_SINGLE_PAGE_LOCAL_CACHE.put(
                cacheKey,
                new InMemoryPdfPreviewCache(previewBytes, System.currentTimeMillis() + PDF_SINGLE_PAGE_CACHE_TTL_MILLIS)
        );
    }

    private byte[] getCachedPdfSinglePagePreview(String cacheKey) {
        try {
            String normalizedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            if (normalizedValue != null && !normalizedValue.isBlank()) {
                normalizedValue = normalizedValue.trim();
                if (normalizedValue.startsWith("\"") && normalizedValue.endsWith("\"") && normalizedValue.length() >= 2) {
                    normalizedValue = normalizedValue.substring(1, normalizedValue.length() - 1);
                }
                logger.info("命中 PDF 单页预览缓存 key: cacheKey={}, encodedLength={}", cacheKey, normalizedValue.length());
                return Base64.getDecoder().decode(normalizedValue);
            }
            logger.info("未命中 PDF 单页预览缓存 key: cacheKey={}", cacheKey);
        } catch (Exception e) {
            logger.warn("读取 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
        return null;
    }

    private void cachePdfSinglePagePreview(String cacheKey, byte[] previewBytes) {
        try {
            String encodedPreview = Base64.getEncoder().encodeToString(previewBytes);
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    encodedPreview,
                    PDF_SINGLE_PAGE_CACHE_TTL_MINUTES,
                    TimeUnit.MINUTES
            );
            String storedValue = stringRedisTemplate.opsForValue().get(cacheKey);
            Long ttlSeconds = stringRedisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
            logger.info("写入 PDF 单页预览缓存完成: cacheKey={}, encodedLength={}, storedLength={}, ttlSeconds={}",
                    cacheKey,
                    encodedPreview.length(),
                    storedValue != null ? storedValue.length() : 0,
                    ttlSeconds);
        } catch (Exception e) {
            logger.warn("写入 PDF 单页预览缓存失败: cacheKey={}, error={}", cacheKey, e.getMessage());
        }
    }

    /**
     * 清理指定文件的所有 PDF 单页预览缓存；版本化文档删除后也需要调用，避免继续返回已删除内容
     */
    public void invalidatePdfSinglePagePreviewCache(String fileMd5) {
        try {
            PDF_SINGLE_PAGE_LOCAL_CACHE.keySet().removeIf(key -> key.startsWith(PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":"));
            Set<String> cacheKeys = stringRedisTemplate.keys(PDF_SINGLE_PAGE_CACHE_PREFIX + fileMd5 + ":*");
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                stringRedisTemplate.delete(cacheKeys);
                logger.info("删除 PDF 单页预览缓存: fileMd5={}, cacheCount={}", fileMd5, cacheKeys.size());
            }
        } catch (Exception e) {
            logger.warn("删除 PDF 单页预览缓存失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
    }
    
    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(String extension) {
        String[] textExtensions = {
            "txt", "md", "html", "htm", "xml", "json",
            "csv", "log", "java", "js", "ts", "py", "cpp", "c", "h", "css",
            "scss", "less", "sql", "yml", "yaml", "properties", "conf", "config"
        };
        
        return Arrays.stream(textExtensions)
                .anyMatch(ext -> ext.equalsIgnoreCase(extension));
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(Long size) {
        if (size == null) return "未知";
        
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
} 
