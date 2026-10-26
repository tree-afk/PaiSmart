package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import com.yizhaoqi.smartpai.service.document.ComplianceSearchFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerSummaryFilterTest extends ChatHandlerReActTestSupport {

    @Test
    void summaryReceivesComplianceFilterFromPreviousSearch() {
        enqueueToolCall("search_knowledge", Map.of("query", "上海商业银行制度"));
        enqueueToolCall("generate_summary", Map.of("topic", "上海商业银行制度"));

        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, "检索完成", Map.of(
                        "results", List.of(),
                        "complianceFilter", Map.of(
                                "docTypes", List.of("REGULATION"),
                                "jurisdictions", List.of("上海市"),
                                "asOf", "2026-10-21"
                        )), false));
        when(tools.executeSummary(anyMap(), anyString(), any(), any(ComplianceSearchFilter.class), anyInt(), any()))
                .thenReturn(new AgentToolRegistry.ToolExecutionResult("generate_summary", true, "summary",
                        Map.of("sources", List.of()), false));

        handler().processMessage("7", "总结上海商业银行制度", mock(WebSocketSession.class), "conv-1");

        ArgumentCaptor<ComplianceSearchFilter> captor = ArgumentCaptor.forClass(ComplianceSearchFilter.class);
        verify(tools).executeSummary(anyMap(), eq("7"), any(), captor.capture(), anyInt(), any());
        ComplianceSearchFilter used = captor.getValue();
        assertEquals(Set.of(DocumentComplianceType.REGULATION), used.docTypes());
        assertEquals(Set.of("上海"), used.jurisdictions());
        assertEquals(LocalDate.of(2026, 10, 21), used.asOf());
    }

    @Test
    void summaryWithoutPriorSearchRegistersSourcesStartingAtOne() {
        enqueueToolCall("generate_summary", Map.of("topic", "制度"));
        SearchResult source = new SearchResult("md5-only-summary", 3, "摘要来源", 0.9, "only-summary.pdf");
        when(tools.executeSummary(anyMap(), anyString(), any(), any(), eq(1), any()))
                .thenReturn(new AgentToolRegistry.ToolExecutionResult("generate_summary", true, "摘要 [1]",
                        Map.of("sources", List.of(source)), false));

        handler().processMessage("7", "总结制度", mock(WebSocketSession.class), "conv-1");

        Map<String, Map<String, Object>> mappings = recordedReferenceMappings();
        assertEquals("md5-only-summary", mappings.get("1").get("fileMd5"));
        assertTrue(streamedChunks().contains("摘要 [1]"));
    }

    @Test
    void summaryRegistersSourcesUnderGlobalNumbersBeforeStreaming() {
        enqueueToolCall("search_knowledge", Map.of("query", "制度"));
        enqueueToolCall("generate_summary", Map.of("topic", "制度"));

        SearchResult searchSource = new SearchResult("md5-search", 1, "检索来源", 0.9, "search.pdf");
        SearchResult summarySource = new SearchResult("md5-summary", 7, "摘要来源", 0.8, "summary.pdf");
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult("search_knowledge", true, "检索完成", Map.of(
                        "results", List.of(searchSource),
                        "complianceFilter", Map.of(
                                "docTypes", List.of("REGULATION"),
                                "jurisdictions", List.of("上海"),
                                "asOf", "2026-10-21"
                        )), false));
        when(tools.executeSummary(anyMap(), anyString(), any(), any(ComplianceSearchFilter.class), eq(2), any()))
                .thenReturn(new AgentToolRegistry.ToolExecutionResult("generate_summary", true, "摘要 [1]",
                        Map.of("sources", List.of(summarySource)), false));

        handler().processMessage("7", "总结制度", mock(WebSocketSession.class), "conv-1");

        Map<String, Map<String, Object>> mappings = recordedReferenceMappings();
        assertEquals("md5-search", mappings.get("1").get("fileMd5"));
        assertEquals("md5-summary", mappings.get("2").get("fileMd5"));
        assertTrue(streamedChunks().contains("摘要 [2]"), "本地编号 [1] 必须平移到全局编号 [2]");
    }
}
