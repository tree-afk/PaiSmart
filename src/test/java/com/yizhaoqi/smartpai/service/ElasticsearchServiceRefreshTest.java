package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.RefreshRequest;
import co.elastic.clients.elasticsearch.indices.RefreshResponse;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 构建分块数核对前先刷新别名：否则刚 bulk 写入的分块可能还不可见，被误判成核对失败。
 */
class ElasticsearchServiceRefreshTest {

    private final ElasticsearchClient esClient = mock(ElasticsearchClient.class);
    private final ElasticsearchIndicesClient indicesClient = mock(ElasticsearchIndicesClient.class);
    private final ElasticsearchService service = new ElasticsearchService();

    @Test
    void countByBuildIdRefreshesAliasBeforeCounting() throws Exception {
        ReflectionTestUtils.setField(service, "esClient", esClient);
        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.refresh(any(RefreshRequest.class))).thenReturn(mock(RefreshResponse.class));
        when(esClient.count(any(CountRequest.class))).thenReturn(CountResponse.of(c -> c
                .count(3)
                .shards(s -> s.total(3).successful(3).failed(0))));

        assertEquals(3, service.countByBuildId("b1"));

        ArgumentCaptor<RefreshRequest> refresh = ArgumentCaptor.forClass(RefreshRequest.class);
        ArgumentCaptor<CountRequest> count = ArgumentCaptor.forClass(CountRequest.class);
        InOrder inOrder = inOrder(indicesClient, esClient);
        inOrder.verify(indicesClient).refresh(refresh.capture());
        inOrder.verify(esClient).count(count.capture());
        assertEquals(List.of(KnowledgeIndexNames.ALIAS), refresh.getValue().index());
        assertEquals(List.of(KnowledgeIndexNames.ALIAS), count.getValue().index());
    }
}
