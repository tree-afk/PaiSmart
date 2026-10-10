package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.ContextualRetrievalProperties;
import com.yizhaoqi.smartpai.config.HybridSearchProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.RequestDeadline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final RerankClient rerankClient = mock(RerankClient.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrgTagCacheService orgTagCacheService = mock(OrgTagCacheService.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();
    private final ContextualRetrievalProperties contextualProperties = new ContextualRetrievalProperties();
    private final List<SearchRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());

    private HybridSearchService service;
    private List<Hit<EsDocument>> bm25Hits;
    private List<Hit<EsDocument>> knnHits;
    private RuntimeException knnFailure;

    @BeforeEach
    void setUp() throws Exception {
        service = new HybridSearchService(esClient, embeddingClient, rerankClient, properties, contextualProperties,
                userRepository, orgTagCacheService, fileUploadRepository, Runnable::run);

        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(orgTagCacheService.getUserEffectiveOrgTags("alice")).thenReturn(List.of("dept-a", "company"));
        when(embeddingClient.embed(anyList(), anyString(), eq(EmbeddingClient.UsageType.QUERY)))
                .thenReturn(List.of(new float[]{0.1f, 0.2f}));
        FileUpload upload = new FileUpload();
        upload.setFileMd5("md5");
        upload.setFileName("manual.pdf");
        when(fileUploadRepository.findByFileMd5In(anyList())).thenReturn(List.of(upload));

        // 文档 kw 只有关键词命中（BM25 第 1），vec 只有语义相近（KNN 第 2），both 两路都召回
        bm25Hits = List.of(hit("kw", 1), hit("both", 2));
        knnHits = List.of(hit("both", 2), hit("vec", 3));
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            capturedRequests.add(request);
            if (request.knn().isEmpty()) {
                return response(bm25Hits);
            }
            if (knnFailure != null) {
                throw knnFailure;
            }
            return response(knnHits);
        });
    }

    @Test
    void fusesBothPathsThenTruncatesByRerankThreshold() {
        // RRF 顺序：both(1/62+1/61) > kw(1/61) > vec(1/62)
        when(rerankClient.rerank(eq("E1024 错误码"), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(1, 0.91),
                new RerankClient.RerankHit(0, 0.55),
                new RerankClient.RerankHit(2, 0.05)
        ));

        List<SearchResult> results = service.searchWithPermission("E1024 错误码", "7", 5);

        assertEquals(List.of(1, 2), results.stream().map(SearchResult::getChunkId).toList(),
                "关键词独有的 kw 被保留，低于阈值的 vec 被丢弃，topK 只是上限");
        assertEquals(0.91, results.get(0).getScore(), 1e-9);
        assertEquals("HYBRID", results.get(0).getRetrievalMode());
        assertEquals("manual.pdf", results.get(0).getFileName());
        verify(rerankClient).rerank("E1024 错误码", List.of("text-both", "text-kw", "text-vec"));
    }

    @Test
    void topKCapsResultsEvenWhenAllPassThreshold() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new RerankClient.RerankHit(0, 0.9),
                new RerankClient.RerankHit(1, 0.8),
                new RerankClient.RerankHit(2, 0.7)
        ));

        assertEquals(2, service.searchWithPermission("q", "7", 2).size());
    }

    @Test
    void rerankFailureFallsBackToRrfOrder() {
        when(rerankClient.rerank(anyString(), anyList())).thenThrow(new IllegalStateException("rerank down"));

        List<SearchResult> results = service.searchWithPermission("q", "7", 2);

        assertEquals(List.of(2, 1), results.stream().map(SearchResult::getChunkId).toList());
        assertEquals(1.0 / 62 + 1.0 / 61, results.get(0).getScore(), 1e-12);
        assertTrue(results.stream().allMatch(r -> "HYBRID_RRF".equals(r.getRetrievalMode())),
                "RRF 分数与 rerank 分数量纲不同，单独标记检索模式");
    }

    @Test
    void rerankIsSkippedWhenRequestDeadlineIsAlmostUsedUp() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(new RerankClient.RerankHit(0, 0.99)));

        List<SearchResult> results;
        try (RequestDeadline.Scope ignored = RequestDeadline.open(RequestDeadline.after(Duration.ofMillis(1500)))) {
            results = service.searchWithPermission("q", "7", 2);
        }

        verify(rerankClient, never()).rerank(anyString(), anyList());
        assertEquals(List.of(2, 1), results.stream().map(SearchResult::getChunkId).toList());
        assertTrue(results.stream().allMatch(r -> "HYBRID_RRF".equals(r.getRetrievalMode())));
    }

    @Test
    void rerankDisabledUsesRrfTopK() {
        properties.getRerank().setEnabled(false);

        List<SearchResult> results = service.searchWithPermission("q", "7", 3);

        assertEquals(List.of(2, 1, 3), results.stream().map(SearchResult::getChunkId).toList());
        assertEquals("HYBRID_RRF", results.get(0).getRetrievalMode());
        verify(rerankClient, never()).rerank(anyString(), anyList());
    }

    @Test
    void bothPathsAreIndependentAndCarryPermissionFilter() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of());

        service.searchWithPermission("q", "7", 5);

        assertEquals(2, capturedRequests.size());
        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        SearchRequest knn = capturedRequests.stream().filter(r -> !r.knn().isEmpty()).findFirst().orElseThrow();

        assertEquals(150, bm25.size(), "每路召回窗口 = topK * 30");
        assertEquals(150, knn.size());
        assertEquals(150L, knn.knn().get(0).k());
        assertTrue(knn.query() == null, "KNN 路不再要求必须命中关键词");
        assertTrue(knn.rescore().isEmpty(), "不再使用 rescore");
        assertPermissionFilter(bm25.query().bool().filter().get(0));
        assertPermissionFilter(knn.knn().get(0).filter().get(0));
    }

    @Test
    void embeddingFailureFallsBackToTextOnlyWithPermission() {
        when(embeddingClient.embed(anyList(), anyString(), eq(EmbeddingClient.UsageType.QUERY)))
                .thenThrow(new RuntimeException("embedding down"));

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals(1, capturedRequests.size());
        SearchRequest request = capturedRequests.get(0);
        assertTrue(request.knn().isEmpty());
        assertEquals(0.3, request.minScore(), 1e-9);
        assertPermissionFilter(request.query().bool().filter().get(0));
        assertTrue(results.stream().allMatch(r -> "TEXT_ONLY".equals(r.getRetrievalMode())));
    }

    @Test
    void knnFailureFallsBackToTextOnly() {
        knnFailure = new RuntimeException("knn failed");

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> "TEXT_ONLY".equals(r.getRetrievalMode())));
    }

    @Test
    void bm25MatchesOnlyTextContentWhenContextualRetrievalDisabled() {
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of());

        service.searchWithPermission("q", "7", 5);

        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        Query keyword = bm25.query().bool().must().get(0);
        assertTrue(keyword.isMatch());
        assertEquals("textContent", keyword.match().field());
    }

    @Test
    void contextualRetrievalScoresContextPlusChunkButReturnsOriginalText() {
        contextualProperties.setEnabled(true);
        EsDocument withContext = new EsDocument("ctx", "md5", 9, "营收比上季度增长 3%", "出自 ACME 2023 年二季度财报的营收部分。",
                null, null, null, "v1", "7", "dept-a", false);
        bm25Hits = List.of(Hit.of(h -> h.index("knowledge_base_v1").id("ctx").score(1.0).source(withContext)));
        knnHits = List.of();
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of(new RerankClient.RerankHit(0, 0.9)));

        List<SearchResult> results = service.searchWithPermission("ACME 二季度营收", "7", 5);

        SearchRequest bm25 = capturedRequests.stream().filter(r -> r.knn().isEmpty()).findFirst().orElseThrow();
        Query keyword = bm25.query().bool().must().get(0);
        assertTrue(keyword.isCombinedFields(), "说明和原文当成一个字段算 BM25");
        assertEquals(List.of("chunkContext", "textContent"), keyword.combinedFields().fields());
        verify(rerankClient).rerank("ACME 二季度营收", List.of("出自 ACME 2023 年二季度财报的营收部分。\n\n营收比上季度增长 3%"));
        assertEquals("营收比上季度增长 3%", results.get(0).getTextContent(), "展示和引用只用原文");
        assertEquals("营收比上季度增长 3%", results.get(0).getMatchedChunkText());
    }

    @Test
    void recallWindowIsBoundedByConfig() {
        assertEquals(150, service.recallWindow(5));
        assertEquals(300, service.recallWindow(20));
        properties.setMaxRecallWindow(10);
        assertEquals(20, service.recallWindow(20), "窗口至少不小于 topK");
    }

    private static void assertPermissionFilter(Query filter) {
        var should = filter.bool().should();
        assertEquals(3, should.size());
        assertEquals("userId", should.get(0).term().field());
        assertEquals("7", should.get(0).term().value().stringValue());
        assertEquals("public", should.get(1).term().field());
        assertEquals("orgTag", should.get(2).terms().field());
        assertEquals(2, should.get(2).terms().terms().value().size());
        assertEquals("1", filter.bool().minimumShouldMatch());
    }

    private static Hit<EsDocument> hit(String id, int chunkId) {
        EsDocument doc = new EsDocument(id, "md5", chunkId, "text-" + id, null, null, null, "v1", "7", "dept-a", false);
        return Hit.of(h -> h.index("knowledge_base_v1").id(id).score(1.0).source(doc));
    }

    private static SearchResponse<EsDocument> response(List<Hit<EsDocument>> hits) {
        return SearchResponse.of(r -> r
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hits).total(t -> t.value(hits.size()).relation(TotalHitsRelation.Eq)).maxScore(1.0)));
    }
}
