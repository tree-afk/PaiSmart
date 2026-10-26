package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.exception.InsufficientBalanceException;
import com.yizhaoqi.smartpai.model.DocumentBuildContext;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.VectorizationService;
import com.yizhaoqi.smartpai.service.conflict.DocumentVectorizedEvent;
import com.yizhaoqi.smartpai.service.document.DocumentBuildService;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLease;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseLostException;
import com.yizhaoqi.smartpai.service.reliability.VectorizationLeaseService;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final VectorizationService vectorizationService;
    private final DocumentService documentService;
    private final VectorizationLeaseService leaseService;
    private final UploadService uploadService;
    private final DocumentBuildService buildService;
    @Autowired
    private KafkaConfig kafkaConfig;
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;


    public FileProcessingConsumer(
            ParseService parseService,
            VectorizationService vectorizationService,
            DocumentService documentService,
            VectorizationLeaseService leaseService,
            UploadService uploadService,
            DocumentBuildService buildService
    ) {
        this.parseService = parseService;
        this.vectorizationService = vectorizationService;
        this.documentService = documentService;
        this.leaseService = leaseService;
        this.uploadService = uploadService;
        this.buildService = buildService;
    }

    // id 供 FileProcessingListenerGate 在堆内存背压 / 索引切换时暂停、恢复消费
    @KafkaListener(id = FileProcessingListenerGate.LISTENER_ID,
            topics = "#{kafkaConfig.getFileProcessingTopic()}",
            groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        String buildId = normalizeBuildId(task);
        // 构建已就绪或已到终态：迟到/重复的消息不认领、不改文件状态，文件由恢复任务按构建结果收尾
        if (buildId != null && buildService.shouldSkipProcessing(buildService.statusOf(buildId))) {
            log.warn("构建已处于就绪或终态，跳过本次处理且不改写文件状态: buildId={}, fileMd5={}",
                    buildId, task.getFileMd5());
            return;
        }

        // 认领：上传任务已完成直接跳过（Kafka 重复投递），已有消费者在处理且心跳未超时也跳过
        Optional<VectorizationLease> claimed = leaseService.claim(task);
        if (claimed.isEmpty()) {
            return;
        }
        VectorizationLease lease = claimed.get();
        DocumentBuildContext buildContext = buildId == null
                ? null
                : new DocumentBuildContext(task.getDocumentId(), task.getVersionId(), buildId,
                        task.getVersionNo(), task.getBuildNo(), task.complianceMetadata());

        try {
            // 只有 PENDING → PROCESSING 的构建才能写入；预检后构建被并发改成就绪/终态时直接跳过，
            // 不打开文件、不重新解析，也不改写构建状态，只结束文件租约
            if (buildContext != null && !buildService.markProcessing(buildId)) {
                log.warn("构建已处于就绪或终态，跳过本次处理且不改写文件状态: buildId={}, fileMd5={}",
                        buildId, task.getFileMd5());
                leaseService.release(lease);
                return;
            }

            try (InputStream fileStream = openSourceStream(task)) {
                if (buildContext == null) {
                    // 旧任务：先清理上一次处理留下的分块和索引，重复投递、中途失败重试都从干净状态重新解析
                    documentService.clearIndexedData(task.getFileMd5());
                } else {
                    documentService.clearBuildData(buildId);
                }

                if (buildContext == null) {
                    parseService.parseAndSave(task.getFileMd5(), fileStream,
                            task.getUserId(), task.getOrgTag(), task.isPublic());
                } else {
                    parseService.parseAndSave(task.getFileMd5(), fileStream,
                            task.getUserId(), task.getOrgTag(), task.isPublic(), buildContext);
                }
                log.info("文件解析完成，fileMd5: {}", task.getFileMd5());
                leaseService.heartbeat(lease);

                String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                        ? task.getUserId()
                        : task.getRequesterId();
                VectorizationService.VectorizationUsageResult vectorizationResult = buildContext == null
                        ? vectorizationService.vectorizeWithUsage(
                                task.getFileMd5(),
                                task.getUserId(),
                                task.getOrgTag(),
                                task.isPublic(),
                                requesterId,
                                () -> leaseService.heartbeat(lease))
                        : vectorizationService.vectorizeWithUsage(
                                task.getFileMd5(),
                                task.getUserId(),
                                task.getOrgTag(),
                                task.isPublic(),
                                requesterId,
                                buildContext,
                                () -> leaseService.heartbeat(lease));
                if (buildContext == null) {
                    completeLegacy(task, lease, vectorizationResult);
                } else {
                    completeBuild(task, buildId, lease, vectorizationResult);
                }
            }
        } catch (VectorizationLeaseLostException e) {
            // 任务已被定时任务改回待处理并重新投递，交给新的认领者，这条消息正常提交；
            // 构建仍保持 PROCESSING，由文件租约恢复任务重投同一 build
            log.warn("向量化认领已失效，放弃本次处理: fileMd5={}", task.getFileMd5());
        } catch (Exception e) {
            if (isInsufficientBalance(e)) {
                // 余额不足重试多少次结果都一样，每次重试还要重新解析整份文件、占住消费线程：
                // 构建与文件都标失败并提交消息，不再重试
                if (buildContext != null) {
                    markBuildFailed(buildContext.buildId(), e);
                }
                leaseService.fail(lease, e);
                log.warn("用户余额不足，向量化失败且不重试: fileMd5={}, userId={}, reason={}",
                        task.getFileMd5(), task.getUserId(), e.getMessage());
                return;
            }
            // 可重试异常（解析 / 向量化 / MinIO / ES 清理等）：构建保持 PROCESSING，不提前标 FAILED；
            // 文件租约虽然写成 FAILED，但 claimVectorization 只拒绝 PROCESSING 且要求心跳未超时，
            // 下一次投递仍能重新认领。抛出交给 Kafka 重试，重试耗尽由死信消费者兜底标构建失败
            leaseService.fail(lease, e);
            log.error("文件处理失败，构建保持 PROCESSING 等待 Kafka 重试: buildId={}, fileMd5={}",
                    buildContext == null ? null : buildContext.buildId(), task.getFileMd5(), e);
            throw new RuntimeException("Error processing task", e);
        }
    }

    /**
     * 旧任务：文件租约写完成状态后才算成功，成功后才发布向量化完成事件
     */
    private void completeLegacy(FileProcessingTask task,
                                VectorizationLease lease,
                                VectorizationService.VectorizationUsageResult result) {
        if (!leaseService.complete(lease, result)) {
            log.warn("向量化完成但认领已失效，不写完成状态: fileMd5={}", task.getFileMd5());
            return;
        }
        log.info("向量化完成，fileMd5: {}, chunkCount: {}", task.getFileMd5(), result.actualChunkCount());
        publishVectorized(task.getFileMd5(), task.getDocumentId());
    }

    /**
     * 构建任务收尾：核对分块数 → 标 READY → 写文件完成 → 切换。
     * 核对失败把构建与文件都标失败；切换异常只保留 READY 交给恢复任务重试，绝不把已就绪的构建标 FAILED
     */
    private void completeBuild(FileProcessingTask task,
                               String buildId,
                               VectorizationLease lease,
                               VectorizationService.VectorizationUsageResult result) {
        int chunkCount = result.actualChunkCount();
        if (!buildService.chunkCountMatches(buildId, chunkCount)) {
            markBuildFailed(buildId, "分块数核对失败");
            leaseService.fail(lease, new IllegalStateException("分块数核对失败"));
            log.warn("构建分块数核对失败，构建与文件已标记失败，旧 active 保持不变: buildId={}, expected={}",
                    buildId, chunkCount);
            return;
        }
        boolean ready = buildService.markReady(buildId, chunkCount);
        if (!ready) {
            log.warn("构建未能进入 READY（可能已被取消），不切换: buildId={}", buildId);
        }
        if (!leaseService.complete(lease, result)) {
            // 认领已失效：不切换构建，READY 由 DocumentBuildRecoveryJob 兜底切换
            log.warn("向量化完成但认领已失效，不切换构建: buildId={}, fileMd5={}", buildId, task.getFileMd5());
            return;
        }
        log.info("向量化完成，fileMd5: {}, chunkCount: {}", task.getFileMd5(), chunkCount);
        if (!ready) {
            return;
        }
        DocumentBuildService.BuildActivation activation = null;
        try {
            activation = buildService.activate(buildId);
            log.info("构建分块数核对通过，切换结果: buildId={}, chunkCount={}, activation={}",
                    buildId, chunkCount, activation);
        } catch (Exception e) {
            log.warn("构建切换失败，保持 READY 等待恢复任务重试: buildId={}", buildId, e);
        }
        if (activation == DocumentBuildService.BuildActivation.ACTIVE) {
            publishVectorized(task.getFileMd5(), task.getDocumentId());
        }
    }

    private void markBuildFailed(String buildId, String error) {
        try {
            buildService.markFailed(buildId, error);
        } catch (Exception markingError) {
            log.warn("标记构建失败时出错，忽略: buildId={}, error={}", buildId, markingError.getMessage());
        }
    }

    /**
     * 入库失败时把构建标 FAILED；标记本身失败只记日志，不影响文件租约和 Kafka 重试
     */
    private void markBuildFailed(String buildId, Exception cause) {
        markBuildFailed(buildId, cause.getMessage());
    }

    /**
     * 空白 buildId 按 legacy 任务处理，与 outbox 消息 key 的口径保持一致
     */
    private static String normalizeBuildId(FileProcessingTask task) {
        String buildId = task.getBuildId();
        return buildId == null || buildId.isBlank() ? null : buildId;
    }

    private static boolean isInsufficientBalance(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InsufficientBalanceException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通知冲突检测等后置任务。后置任务异步执行，这里任何异常都不能影响入库结果和 Kafka 提交。
     */
    private void publishVectorized(String fileMd5, String documentId) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new DocumentVectorizedEvent(fileMd5, documentId));
        } catch (Exception e) {
            log.warn("发布向量化完成事件失败，忽略: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
    }

    /**
     * 优先直接读 MinIO 里的合并文件：预签名 URL 一小时就过期，outbox 补发、卡住重投、重建任务都可能晚于这个时间。
     * 合并对象读不到时再退回消息里的路径（兼容旧消息和本地文件路径）
     */
    private InputStream openSourceStream(FileProcessingTask task) throws Exception {
        try {
            return uploadService.getMergedFileStream(task.getFileMd5());
        } catch (Exception e) {
            if (task.getFilePath() == null || task.getFilePath().isBlank()) {
                throw e;
            }
            log.warn("读取 MinIO 合并文件失败，改用消息中的文件路径: fileMd5={}, error={}", task.getFileMd5(), e.getMessage());
        }
        InputStream fileStream = downloadFileFromStorage(task.getFilePath());
        if (fileStream == null) {
            throw new IOException("流为空");
        }
        return fileStream.markSupported() ? fileStream : new BufferedInputStream(fileStream);
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

}
