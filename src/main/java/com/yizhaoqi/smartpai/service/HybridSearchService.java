package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import com.yizhaoqi.smartpai.service.search.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 混合搜索服务：BM25 与 KNN 两路独立并行召回 → RRF 融合 → rerank 精排 → 按分数阈值截断（topK 只是上限）。
 * 两路召回都带权限过滤，确保用户只能搜到自己的、公开的、所属组织（含层级继承）的文档。
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);
    private static final String MODE_HYBRID = "HYBRID";
    // rerank 关闭或失败时按 RRF 排序兜底；RRF 分数（约 1/61）与 rerank 分数（0~1）量纲不同，单独标记避免污染得分监控
    private static final String MODE_HYBRID_RRF = "HYBRID_RRF";
    private static final String MODE_TEXT_ONLY = "TEXT_ONLY";
    private static final int ES_MAX_NUM_CANDIDATES = 10000;
    private static final double TEXT_ONLY_MIN_SCORE = 0.3d;

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final HybridSearchProperties properties;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;
    private final Executor recallExecutor;

    public HybridSearchService(ElasticsearchClient esClient,
                               EmbeddingClient embeddingClient,
                               RerankClient rerankClient,
                               HybridSearchProperties properties,
                               UserRepository userRepository,
                               OrgTagCacheService orgTagCacheService,
                               FileUploadRepository fileUploadRepository,
                               @Qualifier("searchRecallExecutor") Executor recallExecutor) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.properties = properties;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
        this.recallExecutor = recallExecutor;
    }

    /**
     * 带权限过滤的混合搜索（自己的文档、公开文档、所属组织及其上级组织的文档）
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，查询: {}, 用户ID: {}", query, userId);
        Query permissionFilter;
        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}, 有效组织标签: {}", userId, userDbId, userEffectiveTags);
            permissionFilter = buildPermissionFilter(userDbId, userEffectiveTags);
        } catch (Exception e) {
            logger.error("解析用户权限失败，拒绝搜索: userId={}", userId, e);
            return Collections.emptyList();
        }

        try {
            return hybridSearch(query, userId, topK, permissionFilter);
        } catch (Exception e) {
            logger.error("带权限的混合搜索失败，降级为纯文本搜索", e);
            return safeTextOnlySearch(query, permissionFilter, topK, TEXT_ONLY_MIN_SCORE);
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性
     */
    public List<SearchResult> search(String query, int topK) {
        logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");
        try {
            return hybridSearch(query, "system", topK, null);
        } catch (Exception e) {
            logger.error("混合搜索失败，降级为纯文本搜索", e);
            try {
                return textOnlySearch(query, null, topK, null);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    private List<SearchResult> hybridSearch(String query, String requesterId, int topK, Query filter) throws Exception {
        List<Float> queryVector = embedToVectorList(query, requesterId);
        if (queryVector == null) {
            logger.warn("向量生成失败，仅使用文本匹配进行搜索");
            return filter == null
                    ? textOnlySearch(query, null, topK, null)
                    : safeTextOnlySearch(query, filter, topK, TEXT_ONLY_MIN_SCORE);
        }

        int window = recallWindow(topK);
        CompletableFuture<List<Hit<EsDocument>>> bm25Future =
                CompletableFuture.supplyAsync(() -> recall(buildBm25Request(query, filter, window)), recallExecutor);
        CompletableFuture<List<Hit<EsDocument>>> knnFuture =
                CompletableFuture.supplyAsync(() -> recall(buildKnnRequest(queryVector, filter, window)), recallExecutor);
        CompletableFuture.allOf(bm25Future, knnFuture).get(properties.getRecallTimeoutSeconds(), TimeUnit.SECONDS);
        List<Hit<EsDocument>> bm25Hits = bm25Future.join();
        List<Hit<EsDocument>> knnHits = knnFuture.join();

        List<RrfFusion.Fused<Hit<EsDocument>>> fused =
                RrfFusion.fuse(properties.getRrfK(), Hit::id, bm25Hits, knnHits);
        logger.debug("双路召回完成: window={}, bm25={}, knn={}, fused={}", window, bm25Hits.size(), knnHits.size(), fused.size());

        List<SearchResult> results = rerankAndTruncate(query, fused, topK);
        attachFileNames(results);
        return results;
    }

    int recallWindow(int topK) {
        long window = (long) Math.max(topK, 1) * Math.max(properties.getRecallMultiplier(), 1);
        return (int) Math.max(topK, Math.min(window, properties.getMaxRecallWindow()));
    }

    private List<SearchResult> rerankAndTruncate(String query, List<RrfFusion.Fused<Hit<EsDocument>>> fused, int topK) {
        HybridSearchProperties.Rerank cfg = properties.getRerank();
        List<RrfFusion.Fused<Hit<EsDocument>>> candidates = fused.subList(0, Math.min(fused.size(), Math.max(cfg.getMaxCandidates(), topK)));
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        if (!cfg.isEnabled()) {
            return rrfTopK(candidates, topK);
        }

        try {
            List<String> documents = candidates.stream()
                    .map(c -> truncate(c.item().source().getTextContent(), cfg.getMaxDocumentChars()))
                    .toList();
            List<RerankClient.RerankHit> ranked = rerankClient.rerank(query, documents);
            List<SearchResult> results = ranked.stream()
                    .filter(hit -> hit.score() >= cfg.getScoreThreshold())
                    .limit(topK)
                    .map(hit -> toSearchResult(candidates.get(hit.index()).item().source(), hit.score(), MODE_HYBRID))
                    .collect(Collectors.toCollection(ArrayList::new));
            logger.debug("rerank 截断: candidates={}, 过阈值({})={}, 返回={}", candidates.size(), cfg.getScoreThreshold(),
                    ranked.stream().filter(hit -> hit.score() >= cfg.getScoreThreshold()).count(), results.size());
            return results;
        } catch (Exception e) {
            logger.warn("rerank 调用失败，按 RRF 排名截取 topK: {}", e.getMessage());
            return rrfTopK(candidates, topK);
        }
    }

    private List<SearchResult> rrfTopK(List<RrfFusion.Fused<Hit<EsDocument>>> candidates, int topK) {
        return candidates.stream()
                .limit(topK)
                .map(c -> toSearchResult(c.item().source(), c.score(), MODE_HYBRID_RRF))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Hit<EsDocument>> recall(SearchRequest request) {
        try {
            SearchResponse<EsDocument> response = esClient.search(request, EsDocument.class);
            return response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("召回查询失败: " + e.getMessage(), e);
        }
    }

    SearchRequest buildBm25Request(String query, Query filter, int window) {
        return SearchRequest.of(s -> s
                .index(KnowledgeIndexNames.ALIAS)
                .source(src -> src.filter(f -> f.excludes("vector")))
                .query(q -> q.bool(b -> {
                    b.must(m -> m.match(ma -> ma.field("textContent").query(query)));
                    if (filter != null) {
                        b.filter(filter);
                    }
                    return b;
                }))
                .size(window));
    }

    SearchRequest buildKnnRequest(List<Float> queryVector, Query filter, int window) {
        long numCandidates = Math.min((long) window * Math.max(properties.getKnnNumCandidatesMultiplier(), 1), ES_MAX_NUM_CANDIDATES);
        return SearchRequest.of(s -> s
                .index(KnowledgeIndexNames.ALIAS)
                .source(src -> src.filter(f -> f.excludes("vector")))
                .knn(kn -> {
                    kn.field("vector")
                            .queryVector(queryVector)
                            .k(window)
                            .numCandidates(Math.max(numCandidates, window));
                    // 过滤放在 knn 内部做预过滤，保证返回的 k 条都是有权限的文档
                    if (filter != null) {
                        kn.filter(filter);
                    }
                    return kn;
                })
                .size(window));
    }

    /**
     * 权限过滤：自己的文档 OR 公开文档 OR 组织标签命中（含层级继承后的有效标签）
     */
    public static Query buildPermissionFilter(String userDbId, List<String> userEffectiveTags) {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("userId").value(userDbId)));
            b.should(s -> s.term(t -> t.field("public").value(true)));
            if (userEffectiveTags != null && !userEffectiveTags.isEmpty()) {
                b.should(s -> s.terms(t -> t.field("orgTag")
                        .terms(tv -> tv.value(userEffectiveTags.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .toList()))));
            }
            return b.minimumShouldMatch("1");
        }));
    }

    private List<SearchResult> safeTextOnlySearch(String query, Query filter, int topK, Double minScore) {
        try {
            return textOnlySearch(query, filter, topK, minScore);
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 纯文本（BM25）搜索，embedding 失败或混合检索异常时的降级方案
     */
    private List<SearchResult> textOnlySearch(String query, Query filter, int topK, Double minScore) throws Exception {
        SearchRequest request = SearchRequest.of(s -> {
            s.index(KnowledgeIndexNames.ALIAS)
                    .source(src -> src.filter(f -> f.excludes("vector")))
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.match(ma -> ma.field("textContent").query(query)));
                        if (filter != null) {
                            b.filter(filter);
                        }
                        return b;
                    }))
                    .size(topK);
            if (minScore != null) {
                s.minScore(minScore);
            }
            return s;
        });
        List<SearchResult> results = esClient.search(request, EsDocument.class).hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> toSearchResult(hit.source(), hit.score(), MODE_TEXT_ONLY))
                .collect(Collectors.toCollection(ArrayList::new));
        logger.debug("返回纯文本搜索结果数量: {}", results.size());
        attachFileNames(results);
        return results;
    }

    private SearchResult toSearchResult(EsDocument doc, Double score, String mode) {
        return new SearchResult(
                doc.getFileMd5(),
                doc.getChunkId(),
                doc.getTextContent(),
                score,
                doc.getUserId(),
                doc.getOrgTag(),
                doc.isPublic(),
                null,
                doc.getPageNumber(),
                doc.getAnchorText(),
                mode,
                doc.getTextContent()
        );
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /**
     * 生成查询向量，返回 List<Float>，失败时返回 null
     */
    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) {
                list.add(v);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    /**
     * 获取用户的有效组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = findUser(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户的数据库ID用于权限过滤
     */
    private String getUserDbId(String userId) {
        try {
            return findUser(userId).getId().toString();
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private User findUser(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            // 如果userId不是数字格式，则假设它就是username
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName, (existing, replacement) -> existing));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
