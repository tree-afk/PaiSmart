package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScopeService;
import com.yizhaoqi.smartpai.service.document.ComplianceSearchFilter;
import com.yizhaoqi.smartpai.service.search.ParentContextExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolRegistryPlanClarificationTest {

    private AgentToolRegistry registry;
    private HybridSearchService hybridSearchService;
    private ParentContextExpander parentContextExpander;

    @BeforeEach
    void setUp() {
        hybridSearchService = mock(HybridSearchService.class);
        parentContextExpander = mock(ParentContextExpander.class);
        registry = new AgentToolRegistry(
                hybridSearchService,
                mock(DeepSeekClient.class),
                mock(StringRedisTemplate.class),
                mock(ElasticsearchClient.class),
                mock(BusinessDocumentRepository.class),
                mock(ActiveRetrievalScopeService.class),
                parentContextExpander);
    }

    @Test
    void planQuestionFormatsNumberedListAndReturnsData() {
        AgentToolRegistry.ToolExecutionResult result = registry.executeTool(
                "plan_question",
                Map.of("subQuestions", List.of("住宿标准是什么？", "超标如何审批？")),
                "7");

        assertTrue(result.success());
        assertTrue(result.content().contains("1. 住宿标准是什么？"));
        assertTrue(result.content().contains("2. 超标如何审批？"));
        assertEquals(List.of("住宿标准是什么？", "超标如何审批？"), result.data().get("subQuestions"));
        assertFalse(result.streamedToUser());
    }

    @Test
    void planQuestionRejectsInvalidLists() {
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "plan_question", Map.of("subQuestions", List.of("只有一个")), "7"));
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "plan_question", Map.of("subQuestions", List.of("A", "A")), "7"));
    }

    @Test
    void askClarificationFormatsQuestionsAndReturnsData() {
        AgentToolRegistry.ToolExecutionResult result = registry.executeTool(
                "ask_clarification",
                Map.of("questions", List.of("你所在的城市是？", "你的职级是？")),
                "7");

        assertTrue(result.success());
        assertTrue(result.content().contains("1. 你所在的城市是？"));
        assertTrue(result.content().contains("2. 你的职级是？"));
        assertEquals(List.of("你所在的城市是？", "你的职级是？"), result.data().get("questions"));
        assertFalse(result.streamedToUser());
    }

    @Test
    void askClarificationRejectsEmptyAndTooManyQuestions() {
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "ask_clarification", Map.of("questions", List.of()), "7"));
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "ask_clarification", Map.of("questions", List.of("1", "2", "3", "4")), "7"));
    }

    @Test
    void searchToolSchemaExposesSubQuestionIndex() {
        AgentToolRegistry.AgentTool tool = registry.getTool("search_knowledge").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.parameters().get("properties");
        assertTrue(properties.containsKey("subQuestionIndex"));
        assertTrue(registry.getTool("plan_question").isPresent());
        assertTrue(registry.getTool("ask_clarification").isPresent());
    }

    @Test
    void searchToolSchemaExposesDocTypesAndJurisdictions() {
        AgentToolRegistry.AgentTool tool = registry.getTool("search_knowledge").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.parameters().get("properties");
        assertTrue(properties.containsKey("docTypes"));
        assertTrue(properties.containsKey("jurisdictions"));
        assertFalse(((List<?>) tool.parameters().get("required")).contains("docTypes"),
                "docTypes 只在用户明确说明时填写");
        assertFalse(((List<?>) tool.parameters().get("required")).contains("jurisdictions"));

        @SuppressWarnings("unchecked")
        Map<String, Object> docTypes = (Map<String, Object>) properties.get("docTypes");
        assertEquals("array", docTypes.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) docTypes.get("items");
        assertEquals(List.of("REGULATION", "INTERNAL_POLICY", "PRODUCT_TERMS", "GUIDELINE"), items.get("enum"));

        @SuppressWarnings("unchecked")
        Map<String, Object> jurisdictions = (Map<String, Object>) properties.get("jurisdictions");
        assertEquals("array", jurisdictions.get("type"));
        assertTrue(tool.description().contains("不要猜测地区"));
        assertTrue(tool.description().contains("当前可检索版本"));
        assertTrue(tool.description().contains("issuer 只是发布机构"));
        assertTrue(tool.description().contains("appliesTo"));
    }

    @Test
    void searchKnowledgePassesComplianceFilterAndFormatsMetadata() {
        SearchResult result = new SearchResult("md5-a", 1, "监管要求资本充足率不低于 8%", 0.9, "regulation.pdf");
        result.setDocType("REGULATION");
        result.setJurisdiction("全国");
        result.setEffectiveAt("2026-10-01");
        result.setArticlePath("第二章 风险管理 > 第十二条");
        result.setClauseNo("第十二条第二款");
        when(parentContextExpander.expand(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(hybridSearchService.searchWithPermission(eq("资本充足率"), eq("7"), eq(5), any(ComplianceSearchFilter.class)))
                .thenReturn(List.of(result));

        AgentToolRegistry.ToolExecutionResult execution = registry.executeTool(
                "search_knowledge",
                Map.of("query", "资本充足率", "docTypes", List.of("REGULATION", "PRODUCT_TERMS"),
                        "jurisdictions", List.of(" 全国 ", "")),
                "7");

        ArgumentCaptor<ComplianceSearchFilter> captor = ArgumentCaptor.forClass(ComplianceSearchFilter.class);
        verify(hybridSearchService).searchWithPermission(eq("资本充足率"), eq("7"), eq(5), captor.capture());
        ComplianceSearchFilter filter = captor.getValue();
        assertEquals(2, filter.docTypes().size());
        assertTrue(filter.docTypes().contains(com.yizhaoqi.smartpai.model.DocumentComplianceType.REGULATION));
        assertTrue(filter.docTypes().contains(com.yizhaoqi.smartpai.model.DocumentComplianceType.PRODUCT_TERMS));
        assertEquals(List.of("全国"), List.copyOf(filter.jurisdictions()));
        assertEquals(LocalDate.now(), filter.asOf(), "工具没有 asOf 参数，固定按今天判断现行有效");

        @SuppressWarnings("unchecked")
        Map<String, Object> dataFilter = (Map<String, Object>) execution.data().get("complianceFilter");
        assertEquals(List.of("REGULATION", "PRODUCT_TERMS"), dataFilter.get("docTypes"));
        assertEquals(List.of("全国"), dataFilter.get("jurisdictions"));
        assertEquals(LocalDate.now().toString(), dataFilter.get("asOf"));

        String content = execution.content();
        assertTrue(content.contains("docType=REGULATION"));
        assertTrue(content.contains("jurisdiction=全国"));
        assertTrue(content.contains("effectiveAt=2026-10-01"));
        assertTrue(content.contains("article=第二章 风险管理 > 第十二条"));
        assertTrue(content.contains("clause=第十二条第二款"));
        assertTrue(content.contains("key=md5-a#1"), "原有证据 key 格式保持不变");
    }

    @Test
    void searchKnowledgeDefaultsToNoDimensionFilters() {
        when(parentContextExpander.expand(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(hybridSearchService.searchWithPermission(eq("q"), eq("7"), anyInt(), any(ComplianceSearchFilter.class)))
                .thenReturn(List.of());

        AgentToolRegistry.ToolExecutionResult execution = registry.executeTool(
                "search_knowledge", Map.of("query", "q", "topK", 1), "7");

        assertEquals("未检索到相关知识库片段。", execution.content());
        @SuppressWarnings("unchecked")
        Map<String, Object> dataFilter = (Map<String, Object>) execution.data().get("complianceFilter");
        assertEquals(List.of(), dataFilter.get("docTypes"));
        assertEquals(List.of(), dataFilter.get("jurisdictions"));
    }

    @Test
    void searchKnowledgeRejectsUnknownDocType() {
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "search_knowledge", Map.of("query", "q", "docTypes", List.of("BANANA")), "7"));
        verify(hybridSearchService, never()).searchWithPermission(anyString(), anyString(), anyInt(),
                any(ComplianceSearchFilter.class));
    }

    @Test
    void reportEvidenceReturnsRawAssessments() {
        List<Map<String, Object>> assessments = List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_USER_CONDITION", "evidence", List.of(), "reason", "缺城市"));

        AgentToolRegistry.ToolExecutionResult result = registry.executeTool(
                "report_evidence", Map.of("assessments", assessments), "7");

        assertTrue(result.success());
        assertEquals("report_evidence", result.toolName());
        assertEquals(assessments, result.data().get("assessments"));
        assertTrue(result.content().contains("2"));
        assertFalse(result.streamedToUser());
    }

    @Test
    void reportEvidenceRejectsEmptyAndTooManyAssessments() {
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "report_evidence", Map.of("assessments", List.of()), "7"));
        assertThrows(IllegalArgumentException.class, () -> registry.executeTool(
                "report_evidence", Map.of("assessments", List.of(
                        Map.of("index", 1, "status", "MISSING_KNOWLEDGE"),
                        Map.of("index", 2, "status", "MISSING_KNOWLEDGE"),
                        Map.of("index", 3, "status", "MISSING_KNOWLEDGE"),
                        Map.of("index", 4, "status", "MISSING_KNOWLEDGE"))), "7"));
    }

    @Test
    void reportEvidenceSchemaCoversStatusesAndIsRegisteredWithPlanTools() {
        AgentToolRegistry.AgentTool tool = registry.getTool("report_evidence").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.parameters().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> assessments = (Map<String, Object>) properties.get("assessments");
        assertEquals("array", assessments.get("type"));
        assertEquals(1, assessments.get("minItems"));
        assertEquals(3, assessments.get("maxItems"));

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) assessments.get("items");
        assertEquals("object", items.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
        assertTrue(itemProperties.containsKey("index"));
        assertTrue(itemProperties.containsKey("status"));
        assertTrue(itemProperties.containsKey("evidence"));
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) itemProperties.get("status");
        assertEquals(List.of("SUPPORTED", "PARTIAL", "MISSING_KNOWLEDGE", "MISSING_USER_CONDITION"),
                status.get("enum"));

        assertTrue(registry.getTools().stream().map(AgentToolRegistry.AgentTool::name)
                .anyMatch("report_evidence"::equals));
        assertTrue(registry.getTool("plan_question").isPresent());
        assertTrue(registry.getTool("search_knowledge").isPresent());
        assertTrue(registry.getTool("ask_clarification").isPresent());
    }
}
