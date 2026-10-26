package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScopeService;
import com.yizhaoqi.smartpai.service.document.ComplianceSearchFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolRegistrySummaryFilterTest {

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final DeepSeekClient deepSeekClient = mock(DeepSeekClient.class);
    private final AgentToolRegistry registry = new AgentToolRegistry(
            searchService,
            deepSeekClient,
            mock(StringRedisTemplate.class),
            mock(ElasticsearchClient.class),
            mock(BusinessDocumentRepository.class),
            mock(ActiveRetrievalScopeService.class),
            null);

    @Test
    void summaryUsesInheritedComplianceFilterWhenModelDoesNotRepeatIt() {
        ComplianceSearchFilter inherited = new ComplianceSearchFilter(
                Set.of(DocumentComplianceType.REGULATION), Set.of("上海"), LocalDate.of(2026, 10, 21));
        when(searchService.searchWithPermission("上海制度", "user-1", 5, inherited)).thenReturn(List.of());
        when(deepSeekClient.summarize(eq("user-1"), eq("上海制度"), any(), any(), anyInt())).thenReturn("摘要");

        AgentToolRegistry.ToolExecutionResult result = registry.executeTool(
                "generate_summary", Map.of("topic", "上海制度"), "user-1", null, inherited);

        verify(searchService).searchWithPermission("上海制度", "user-1", 5, inherited);
        @SuppressWarnings("unchecked")
        Map<String, Object> filterData = (Map<String, Object>) result.data().get("complianceFilter");
        assertEquals(List.of("REGULATION"), filterData.get("docTypes"));
        assertEquals(List.of("上海"), filterData.get("jurisdictions"));
        assertEquals("2026-10-21", filterData.get("asOf"));
    }

    @Test
    void explicitSummaryFiltersOverrideInheritedFiltersButKeepInheritedAsOf() {
        ComplianceSearchFilter inherited = new ComplianceSearchFilter(
                Set.of(DocumentComplianceType.REGULATION), Set.of("上海"), LocalDate.of(2026, 10, 21));
        when(searchService.searchWithPermission(eq("北京制度"), eq("user-1"), anyInt(), any()))
                .thenReturn(List.of());
        when(deepSeekClient.summarize(eq("user-1"), eq("北京制度"), any(), any(), anyInt())).thenReturn("摘要");

        registry.executeTool("generate_summary", Map.of(
                "topic", "北京制度",
                "docTypes", List.of("INTERNAL_POLICY"),
                "jurisdictions", List.of("北京")
        ), "user-1", null, inherited);

        ArgumentCaptor<ComplianceSearchFilter> captor = ArgumentCaptor.forClass(ComplianceSearchFilter.class);
        verify(searchService).searchWithPermission(eq("北京制度"), eq("user-1"), eq(5), captor.capture());
        ComplianceSearchFilter used = captor.getValue();
        assertEquals(Set.of(DocumentComplianceType.INTERNAL_POLICY), used.docTypes());
        assertEquals(Set.of("北京"), used.jurisdictions());
        assertEquals(LocalDate.of(2026, 10, 21), used.asOf());
    }

    @Test
    void partialExplicitOverrideKeepsInheritedOtherDimension() {
        ComplianceSearchFilter inherited = new ComplianceSearchFilter(
                Set.of(DocumentComplianceType.REGULATION), Set.of("上海"), LocalDate.of(2026, 10, 21));
        when(searchService.searchWithPermission(eq("上海制度"), eq("user-1"), eq(5), any()))
                .thenReturn(List.of());
        when(deepSeekClient.summarize(eq("user-1"), eq("上海制度"), any(), any(), anyInt())).thenReturn("摘要");

        registry.executeTool("generate_summary", Map.of(
                "topic", "上海制度",
                "docTypes", List.of("INTERNAL_POLICY")
        ), "user-1", null, inherited);

        ArgumentCaptor<ComplianceSearchFilter> captor = ArgumentCaptor.forClass(ComplianceSearchFilter.class);
        verify(searchService).searchWithPermission(eq("上海制度"), eq("user-1"), eq(5), captor.capture());
        assertEquals(Set.of(DocumentComplianceType.INTERNAL_POLICY), captor.getValue().docTypes());
        assertEquals(Set.of("上海"), captor.getValue().jurisdictions(), "未显式覆盖的地区继续继承");
    }

    @Test
    void summaryCancellationRegistrarIsForwardedToClient() {
        when(searchService.searchWithPermission(eq("制度"), eq("user-1"), eq(5), any()))
                .thenReturn(List.of());
        Consumer<Runnable> registrar = action -> { };
        when(deepSeekClient.summarize(eq("user-1"), eq("制度"), any(), any(), eq(1), same(registrar)))
                .thenReturn("摘要");

        registry.executeSummary(Map.of("topic", "制度"), "user-1", null, null, 1, registrar);

        verify(deepSeekClient).summarize(eq("user-1"), eq("制度"), any(), isNull(), eq(1), same(registrar));
    }

    @Test
    void summaryWithoutInheritedFilterFallsBackToCurrentDefaults() {
        when(searchService.searchWithPermission(eq("制度"), eq("user-1"), eq(5), any()))
                .thenReturn(List.of());
        when(deepSeekClient.summarize(eq("user-1"), eq("制度"), any(), any(), anyInt())).thenReturn("摘要");

        registry.executeTool("generate_summary", Map.of("topic", "制度"), "user-1", null, null);

        ArgumentCaptor<ComplianceSearchFilter> captor = ArgumentCaptor.forClass(ComplianceSearchFilter.class);
        verify(searchService).searchWithPermission(eq("制度"), eq("user-1"), eq(5), captor.capture());
        assertEquals(Set.of(), captor.getValue().docTypes());
        assertEquals(Set.of(), captor.getValue().jurisdictions());
        assertEquals(LocalDate.now(), captor.getValue().asOf());
    }
}
