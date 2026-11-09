package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.IndicesStatsRequest;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.model.BusinessDocument;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScope;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolRegistryStatsTest {

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indicesClient = mock(ElasticsearchIndicesClient.class);
    private final BusinessDocumentRepository documentRepository = mock(BusinessDocumentRepository.class);
    private final ActiveRetrievalScopeService scopeService = mock(ActiveRetrievalScopeService.class);
    private final AgentToolRegistry registry = new AgentToolRegistry(
            mock(HybridSearchService.class),
            mock(DeepSeekClient.class),
            mock(StringRedisTemplate.class),
            esClient,
            documentRepository,
            scopeService,
            null);

    @BeforeEach
    void setUp() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        IndicesStatsResponse statsResponse = IndicesStatsResponse.of(b -> b
                .indices(Map.of())
                .all(a -> a)
                .shards(s -> s.total(1).successful(1).failed(0)));
        when(indicesClient.stats(any(IndicesStatsRequest.class))).thenReturn(statsResponse);
        when(documentRepository.countByDeletedAtIsNullAndPublicationStatus(PublicationStatus.PUBLISHED))
                .thenReturn(2L);
        BusinessDocument latest = new BusinessDocument();
        latest.setUpdatedAt(LocalDateTime.of(2026, 10, 21, 9, 30));
        when(documentRepository.findFirstByDeletedAtIsNullAndPublicationStatusOrderByUpdatedAtDesc(
                PublicationStatus.PUBLISHED)).thenReturn(Optional.of(latest));
        when(esClient.count(any(CountRequest.class))).thenReturn(CountResponse.of(c -> c
                .count(12)
                .shards(s -> s.total(1).successful(1).failed(0))));
    }

    @Test
    void statsCountPublishedDocumentsAndActiveBuildChunks() throws Exception {
        when(scopeService.current()).thenReturn(new ActiveRetrievalScope(Set.of("b1"), Set.of()));

        AgentToolRegistry.ToolExecutionResult result = registry.executeTool("knowledge_stats", Map.of(), "1");

        assertEquals(2L, result.data().get("documentCount"));
        assertEquals(12L, result.data().get("fragmentCount"));
        assertTrue(result.content().contains("已发布文档数：2"));
        ArgumentCaptor<CountRequest> count = ArgumentCaptor.forClass(CountRequest.class);
        org.mockito.Mockito.verify(esClient).count(count.capture());
        assertEquals("buildId", count.getValue().query().terms().field());
        assertEquals("b1", count.getValue().query().terms().terms().value().get(0).stringValue());
    }

    @Test
    void emptyScopeCountsNothingInsteadOfFallingBackToWholeIndex() throws Exception {
        when(scopeService.current()).thenReturn(new ActiveRetrievalScope(Set.of(), Set.of()));

        registry.executeTool("knowledge_stats", Map.of(), "1");

        ArgumentCaptor<CountRequest> count = ArgumentCaptor.forClass(CountRequest.class);
        org.mockito.Mockito.verify(esClient).count(count.capture());
        assertEquals("__no_active_build__", count.getValue().query().terms().terms()
                .value().get(0).stringValue());
    }
}
