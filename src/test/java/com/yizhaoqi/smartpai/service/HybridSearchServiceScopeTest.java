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
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScope;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索范围接入：两条召回路径和纯文本降级都带上版本过滤，并与权限过滤同时生效；
 * 范围为空表示"还没有 active build"，仍要排除带 buildId 的未生效分块，只放行 legacy 分块。
 */
class HybridSearchServiceScopeTest {

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final RerankClient rerankClient = mock(RerankClient.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrgTagCacheService orgTagCacheService = mock(OrgTagCacheService.class);
    private final FileUploadRepository fileUploadRepository = mock(FileUploadRepository.class);
    private final ActiveRetrievalScopeService activeRetrievalScopeService = mock(ActiveRetrievalScopeService.class);
    private final HybridSearchProperties properties = new HybridSearchProperties();
    private final ContextualRetrievalProperties contextualProperties = new ContextualRetrievalProperties();
    private final List<SearchRequest> capturedRequests = Collections.synchronizedList(new ArrayList<>());

    private HybridSearchService service;
    private List<Hit<EsDocument>> bm25Hits;
    private List<Hit<EsDocument>> knnHits;

    @BeforeEach
    void setUp() throws Exception {
        service = new HybridSearchService(esClient, embeddingClient, rerankClient, properties, contextualProperties,
                userRepository, orgTagCacheService, fileUploadRepository, activeRetrievalScopeService, Runnable::run);

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
        when(rerankClient.rerank(anyString(), anyList())).thenReturn(List.of());

        bm25Hits = List.of(hit("kw", 1));
        knnHits = List.of(hit("vec", 2));
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            capturedRequests.add(request);
            return request.knn().isEmpty() ? response(bm25Hits) : response(knnHits);
        });
    }

    @Test
    void scopeFilterIsCombinedWithPermissionOnBothRecallPaths() {
        when(activeRetrievalScopeService.current())
                .thenReturn(new ActiveRetrievalScope(Set.of("b1"), Set.of("md5-old")));

        service.searchWithPermission("q", "7", 5);

        Query combined = bm25Request().query().bool().filter().get(0);
        assertTrue(combined.isBool(), "共享库版本过滤和合规过滤包在同一个 bool 里");
        assertEquals(2, combined.bool().must().size());
        Query scope = combined.bool().must().get(0);
        assertEquals("buildId", scope.terms().field());
        Query compliance = combined.bool().must().get(1);
        assertTrue(compliance.isBool(), "第二条是合规过滤");
        assertEquals("effectiveAt", compliance.bool().must().get(0).bool().should().get(0).range().field());

        Query knnFilter = knnRequest().knn().get(0).filter().get(0);
        assertSame(combined, knnFilter, "KNN 预过滤使用同一份组合过滤");
    }

    @Test
    void emptyScopeStillCombinesNoActiveBuildFilterWithCompliance() {
        when(activeRetrievalScopeService.current()).thenReturn(new ActiveRetrievalScope(Set.of(), Set.of()));

        service.searchWithPermission("q", "7", 5);

        Query combined = bm25Request().query().bool().filter().get(0);
        assertTrue(combined.isBool());
        assertEquals(2, combined.bool().must().size(), "空范围仍要匹配不到任何文档，不能取消范围限制");

        Query scope = combined.bool().must().get(0);
        assertTrue(scope.isTerms());
        assertEquals("buildId", scope.terms().field());
        assertEquals("__no_active_build__", scope.terms().terms().value().get(0).stringValue());

        Query compliance = combined.bool().must().get(1);
        assertTrue(compliance.isBool());
        assertEquals(LocalDate.now().toString(),
                compliance.bool().must().get(0).bool().should().get(0).range().lte().to(String.class),
                "默认使用今天的现行有效口径");

        Query knnFilter = knnRequest().knn().get(0).filter().get(0);
        assertSame(combined, knnFilter, "KNN 预过滤使用同一份组合过滤");
    }

    @Test
    void textOnlyFallbackAlsoCarriesScopeFilter() throws Exception {
        when(activeRetrievalScopeService.current()).thenReturn(new ActiveRetrievalScope(Set.of("b1"), Set.of()));
        when(esClient.search(any(SearchRequest.class), eq(EsDocument.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            capturedRequests.add(request);
            if (request.minScore() != null) {
                return response(List.of(hit("txt", 1)));
            }
            throw new RuntimeException("recall down");
        });

        List<SearchResult> results = service.searchWithPermission("q", "7", 5);

        assertEquals("TEXT_ONLY", results.get(0).getRetrievalMode());
        SearchRequest textOnly = capturedRequests.get(capturedRequests.size() - 1);
        assertEquals(0.3, textOnly.minScore(), 1e-9);
        Query combined = textOnly.query().bool().filter().get(0);
        assertTrue(combined.isBool());
        assertEquals(2, combined.bool().must().size(), "降级路径同样只使用共享库版本和合规过滤");
    }

    @Test
    void searchWithoutPermissionStillAppliesScopeAndComplianceFilters() {
        when(activeRetrievalScopeService.current()).thenReturn(new ActiveRetrievalScope(Set.of("b1"), Set.of()));

        service.search("q", 5);

        Query filter = bm25Request().query().bool().filter().get(0);
        assertTrue(filter.isBool());
        assertEquals(2, filter.bool().must().size(), "版本过滤 + 合规过滤，没有权限过滤");
        Query scope = filter.bool().must().get(0);
        assertEquals("buildId", scope.terms().field());
        Query compliance = filter.bool().must().get(1);
        assertEquals("effectiveAt", compliance.bool().must().get(0).bool().should().get(0).range().field());
    }

    @Test
    void searchResultCarriesVersionIdentityAndParentCopyKeepsIt() {
        when(activeRetrievalScopeService.current()).thenReturn(new ActiveRetrievalScope(Set.of(), Set.of()));
        EsDocument doc = new EsDocument("id-1", "md5-new", 1, "正文", null, null, null, "v1", "7", "dept-a", false);
        doc.setDocumentId("doc-1");
        doc.setVersionId("ver-1");
        doc.setBuildId("build-1");
        doc.setVersionNo(3);
        bm25Hits = List.of(Hit.of(h -> h.index("knowledge_base_v1").id("id-1").score(1.0).source(doc)));
        knnHits = List.of();
        when(rerankClient.rerank(anyString(), anyList()))
                .thenReturn(List.of(new RerankClient.RerankHit(0, 0.9)));

        SearchResult result = service.searchWithPermission("q", "7", 5).get(0);

        assertEquals("doc-1", result.getDocumentId());
        assertEquals("ver-1", result.getVersionId());
        assertEquals("build-1", result.getBuildId());
        assertEquals(3, result.getVersionNo());

        SearchResult copy = result.withParentContext("父块", "build-1#1-2");
        assertEquals("doc-1", copy.getDocumentId());
        assertEquals("ver-1", copy.getVersionId());
        assertEquals("build-1", copy.getBuildId(), "父块副本保留构建标识，父块扩展才能按 build 取邻居");
        assertEquals(3, copy.getVersionNo());
    }

    private SearchRequest bm25Request() {
        return capturedRequests.stream()
                .filter(r -> r.knn().isEmpty() && r.minScore() == null)
                .findFirst().orElseThrow();
    }

    private SearchRequest knnRequest() {
        return capturedRequests.stream().filter(r -> !r.knn().isEmpty()).findFirst().orElseThrow();
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
