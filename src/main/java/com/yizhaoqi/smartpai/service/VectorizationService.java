package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private ModelProviderConfigService modelProviderConfigService;

    // 每批分块数：分页读分块 → 调 embedding → 立即写 ES，整份文件的向量不在内存里攒着
    @Value("${embedding.api.batch-size:10}")
    private int batchSize = 10;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    /**
     * 按批向量化并写入 Elasticsearch。
     * 文档 ID 为 fileMd5_chunkId：中途失败后 Kafka 重试会覆盖已写入的批次，不会产生重复文档。
     */
    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                       fileMd5, userId, orgTag, isPublic);

            // 整份文件固定使用同一个模型，避免处理中途切换激活模型导致同一文件混用两套向量
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
            String modelVersion = EmbeddingClient.modelVersionOf(provider);

            int totalTokens = 0;
            int totalChunks = 0;
            Pageable page = PageRequest.of(0, Math.max(batchSize, 1));
            while (true) {
                Slice<DocumentVector> slice = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5, page);
                List<DocumentVector> chunks = slice.getContent();
                if (!chunks.isEmpty()) {
                    totalTokens += embedAndIndexBatch(fileMd5, chunks, userId, orgTag, isPublic, requesterId, provider, modelVersion);
                    totalChunks += chunks.size();
                }
                if (!slice.hasNext()) {
                    break;
                }
                page = slice.nextPageable();
            }

            if (totalChunks == 0) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
            } else {
                logger.info("向量化完成，fileMd5: {}, chunks: {}, tokens: {}", fileMd5, totalChunks, totalTokens);
            }
            return new VectorizationUsageResult(totalTokens, totalChunks, modelVersion);
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }

    private int embedAndIndexBatch(String fileMd5,
                                   List<DocumentVector> chunks,
                                   String userId,
                                   String orgTag,
                                   boolean isPublic,
                                   String requesterId,
                                   ModelProviderConfigService.ActiveProviderView provider,
                                   String modelVersion) {
        List<String> texts = chunks.stream().map(DocumentVector::getTextContent).toList();
        EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithProvider(
                texts, requesterId, EmbeddingClient.UsageType.UPLOAD, provider);
        List<float[]> vectors = embeddingResult.vectors();
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(String.format("embedding 返回数量不一致: expected=%d, actual=%d",
                    chunks.size(), vectors.size()));
        }

        List<EsDocument> esDocuments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentVector chunk = chunks.get(i);
            esDocuments.add(new EsDocument(
                    fileMd5 + "_" + chunk.getChunkId(),
                    fileMd5,
                    chunk.getChunkId(),
                    chunk.getTextContent(),
                    chunk.getPageNumber(),
                    chunk.getAnchorText(),
                    vectors.get(i),
                    modelVersion,
                    userId,
                    orgTag,
                    isPublic
            ));
        }
        elasticsearchService.bulkIndex(esDocuments);
        return embeddingResult.totalTokens();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
