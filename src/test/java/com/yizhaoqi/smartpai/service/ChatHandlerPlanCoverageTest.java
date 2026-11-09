package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHandlerPlanCoverageTest extends ChatHandlerReActTestSupport {

    @Test
    void appendsRetrievalRecordFooterWithDedupedCountsWhenNotAssessed() {
        enqueueToolCall("plan_question", Map.of(
                "subQuestions", List.of("住宿标准是什么？", "超标如何审批？")));
        enqueueToolCalls(List.of(
                new ToolCallSpec("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1)),
                new ToolCallSpec("search_knowledge", Map.of("query", "住宿标准 再查", "subQuestionIndex", 1)),
                new ToolCallSpec("search_knowledge", Map.of("query", "超标审批", "subQuestionIndex", 2))
        ));
        enqueueContent("最终回答：标准见资料。");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录 2 个子问题",
                        Map.of("subQuestions", List.of("住宿标准是什么？", "超标如何审批？")),
                        false));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> arguments = invocation.getArgument(1);
            int index = ((Number) arguments.get("subQuestionIndex")).intValue();
            if (index == 1) {
                SearchResult result = new SearchResult("md5-a", 1, "住宿标准 500 元", 0.9, "policy.pdf");
                return new AgentToolRegistry.ToolExecutionResult(
                        "search_knowledge", true,
                        AgentToolRegistry.formatSearchResults(List.of(result)),
                        Map.of("results", List.of(result)), false);
            }
            return new AgentToolRegistry.ToolExecutionResult(
                    "search_knowledge", true, "未检索到相关知识库片段。",
                    Map.of("results", List.of()), false);
        });

        handler().processMessage("7", "住宿标准是什么？超标怎么审批？",
                mock(WebSocketSession.class), "conv-1");

        assertEquals(3, requestBodies.size());
        String streamed = streamedChunks();
        assertTrue(streamed.contains("最终回答：标准见资料。"));
        assertTrue(streamed.contains("**子问题检索记录（未做证据充分性判断）**"));
        assertTrue(streamed.contains("子问题 1（住宿标准是什么？）：已检索，去重证据 1 条"));
        assertTrue(streamed.contains("子问题 2（超标如何审批？）：已检索，去重证据 0 条"));
        assertFalse(streamed.contains("**子问题证据充分性"));
        assertFalse(streamed.contains("已支撑"));
        assertTrue(recordedAnswer().contains("**子问题检索记录（未做证据充分性判断）**"));
        assertEquals(Set.of("1", "2"), recordedReferenceMappings().keySet());
        int firstFooter = streamed.indexOf("**子问题检索记录");
        int lastFooter = streamed.lastIndexOf("**子问题检索记录");
        assertTrue(firstFooter >= 0 && firstFooter == lastFooter,
                "检索记录说明在流式内容中只能出现一次");
    }

    @Test
    void appendsAssessedFooterAfterValidReportEvidence() {
        enqueueToolCall("plan_question", Map.of(
                "subQuestions", List.of("住宿标准是什么？", "超标如何审批？")));
        enqueueToolCalls(List.of(
                new ToolCallSpec("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1)),
                new ToolCallSpec("search_knowledge", Map.of("query", "超标审批", "subQuestionIndex", 2))
        ));
        List<Map<String, Object>> assessments = List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_USER_CONDITION", "evidence", List.of(),
                        "reason", "缺少职级"));
        enqueueToolCall("report_evidence", Map.of("assessments", assessments));
        enqueueContent("最终回答：住宿标准见资料，超标审批需补充职级。");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录 2 个子问题",
                        Map.of("subQuestions", List.of("住宿标准是什么？", "超标如何审批？")),
                        false));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> arguments = invocation.getArgument(1);
            int index = ((Number) arguments.get("subQuestionIndex")).intValue();
            SearchResult result = index == 1
                    ? new SearchResult("md5-a", 1, "住宿标准 500 元", 0.9, "policy.pdf")
                    : new SearchResult("md5-b", 7, "审批需要职级", 0.8, "approval.pdf");
            return new AgentToolRegistry.ToolExecutionResult(
                    "search_knowledge", true,
                    AgentToolRegistry.formatSearchResults(List.of(result)),
                    Map.of("results", List.of(result)), false);
        });
        when(tools.executeTool(eq("report_evidence"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "report_evidence", true, "已记录 2 个子问题的证据判断",
                        Map.of("assessments", assessments), false));

        handler().processMessage("7", "住宿标准是什么？超标怎么审批？",
                mock(WebSocketSession.class), "conv-1");

        assertEquals(4, requestBodies.size());
        String streamed = streamedChunks();
        assertTrue(streamed.contains("**子问题证据充分性（模型判断 + 代码校验）**"));
        assertTrue(streamed.contains("子问题 1（住宿标准是什么？）：已支撑，去重证据 1 条"));
        assertTrue(streamed.contains("子问题 2（超标如何审批？）：缺少用户条件，去重证据 1 条（说明：缺少职级）"));
        assertFalse(streamed.contains("**子问题检索记录"));
        assertTrue(recordedAnswer().contains("**子问题证据充分性（模型判断 + 代码校验）**"));
    }

    @Test
    void invalidReportEvidenceReturnsToolErrorAndKeepsRetrievalRecordFooter() {
        enqueueToolCall("plan_question", Map.of(
                "subQuestions", List.of("住宿标准是什么？", "超标如何审批？")));
        enqueueToolCalls(List.of(
                new ToolCallSpec("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1)),
                new ToolCallSpec("search_knowledge", Map.of("query", "超标审批", "subQuestionIndex", 2))
        ));
        List<Map<String, Object>> invalidAssessments = List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-x#99")),
                Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()));
        enqueueToolCall("report_evidence", Map.of("assessments", invalidAssessments));
        enqueueContent("最终回答：只回答已检索到的内容。");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录 2 个子问题",
                        Map.of("subQuestions", List.of("住宿标准是什么？", "超标如何审批？")),
                        false));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> arguments = invocation.getArgument(1);
            int index = ((Number) arguments.get("subQuestionIndex")).intValue();
            if (index == 1) {
                SearchResult result = new SearchResult("md5-a", 1, "住宿标准 500 元", 0.9, "policy.pdf");
                return new AgentToolRegistry.ToolExecutionResult(
                        "search_knowledge", true,
                        AgentToolRegistry.formatSearchResults(List.of(result)),
                        Map.of("results", List.of(result)), false);
            }
            return new AgentToolRegistry.ToolExecutionResult(
                    "search_knowledge", true, "未检索到相关知识库片段。",
                    Map.of("results", List.of()), false);
        });
        when(tools.executeTool(eq("report_evidence"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "report_evidence", true, "已收到证据判断",
                        Map.of("assessments", invalidAssessments), false));

        handler().processMessage("7", "住宿标准是什么？超标怎么审批？",
                mock(WebSocketSession.class), "conv-1");

        assertEquals(4, requestBodies.size());
        assertTrue(requestBodies.get(3).contains("md5-x#99"),
                "非法证据应以 tool 错误返回模型，便于模型纠正");
        String streamed = streamedChunks();
        assertTrue(streamed.contains("**子问题检索记录（未做证据充分性判断）**"));
        assertFalse(streamed.contains("**子问题证据充分性"));
        assertFalse(streamed.contains("已支撑"));
    }

    @Test
    void formattedSearchResultKeyCanBeCopiedIntoReportEvidence() {
        SearchResult first = new SearchResult("md5-a", 1, "住宿标准 500 元", 0.9, "policy.pdf");
        SearchResult second = new SearchResult("md5-b", 7, "超标需部门负责人审批", 0.8, "approval.pdf");
        String firstFormatted = AgentToolRegistry.formatSearchResults(List.of(first));
        String secondFormatted = AgentToolRegistry.formatSearchResults(List.of(second));

        assertTrue(firstFormatted.contains("[1] policy.pdf (fileMd5=md5-a, chunkId=1, key=md5-a#1, score=0.9000)"),
                "每条检索结果都要给出可直接复制的 fileMd5#chunkId");
        assertTrue(secondFormatted.contains("[1] approval.pdf (fileMd5=md5-b, chunkId=7, key=md5-b#7, score=0.8000)"),
                "编号仍从 [1] 开始，key 与 fileMd5/chunkId 一致");
        String firstKey = copiedKey(firstFormatted);
        String secondKey = copiedKey(secondFormatted);
        assertEquals("md5-a#1", firstKey);
        assertEquals("md5-b#7", secondKey);

        List<Map<String, Object>> assessments = List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of(firstKey)),
                Map.of("index", 2, "status", "PARTIAL", "evidence", List.of(secondKey)));
        enqueueToolCall("plan_question", Map.of(
                "subQuestions", List.of("住宿标准是什么？", "超标如何审批？")));
        enqueueToolCalls(List.of(
                new ToolCallSpec("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1)),
                new ToolCallSpec("search_knowledge", Map.of("query", "超标审批", "subQuestionIndex", 2))
        ));
        enqueueToolCall("report_evidence", Map.of("assessments", assessments));
        enqueueContent("最终回答：住宿标准 500 元，超标需审批。");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录 2 个子问题",
                        Map.of("subQuestions", List.of("住宿标准是什么？", "超标如何审批？")),
                        false));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> arguments = invocation.getArgument(1);
            int index = ((Number) arguments.get("subQuestionIndex")).intValue();
            SearchResult result = index == 1 ? first : second;
            return new AgentToolRegistry.ToolExecutionResult(
                    "search_knowledge", true,
                    AgentToolRegistry.formatSearchResults(List.of(result)),
                    Map.of("results", List.of(result)), false);
        });
        when(tools.executeTool(eq("report_evidence"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "report_evidence", true, "已记录 2 个子问题的证据判断",
                        Map.of("assessments", assessments), false));

        handler().processMessage("7", "住宿标准是什么？超标怎么审批？",
                mock(WebSocketSession.class), "conv-1");

        assertEquals(4, requestBodies.size());
        assertTrue(requestBodies.get(2).contains("key=md5-a#1"), "模型能在 search_knowledge 结果里看到 key");
        assertTrue(requestBodies.get(2).contains("key=md5-b#7"), "第二个子问题的 key 同样可见");
        String streamed = streamedChunks();
        assertTrue(streamed.contains("**子问题证据充分性（模型判断 + 代码校验）**"),
                "模型复制的 key 必须能通过 report_evidence 校验");
        assertTrue(streamed.contains("子问题 1（住宿标准是什么？）：已支撑，去重证据 1 条"));
        assertTrue(streamed.contains("子问题 2（超标如何审批？）：部分支撑，去重证据 1 条"));
    }

    @Test
    void missingKnowledgeForUnsearchedSubQuestionReturnsToolError() {
        enqueueToolCall("plan_question", Map.of(
                "subQuestions", List.of("住宿标准是什么？", "超标如何审批？")));
        enqueueToolCall("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1));
        List<Map<String, Object>> assessments = List.of(
                Map.of("index", 1, "status", "SUPPORTED", "evidence", List.of("md5-a#1")),
                Map.of("index", 2, "status", "MISSING_KNOWLEDGE", "evidence", List.of()));
        enqueueToolCall("report_evidence", Map.of("assessments", assessments));
        enqueueContent("最终回答：只回答已检索到的内容。");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录 2 个子问题",
                        Map.of("subQuestions", List.of("住宿标准是什么？", "超标如何审批？")),
                        false));
        when(tools.executeTool(eq("search_knowledge"), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            SearchResult result = new SearchResult("md5-a", 1, "住宿标准 500 元", 0.9, "policy.pdf");
            return new AgentToolRegistry.ToolExecutionResult(
                    "search_knowledge", true,
                    AgentToolRegistry.formatSearchResults(List.of(result)),
                    Map.of("results", List.of(result)), false);
        });
        when(tools.executeTool(eq("report_evidence"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "report_evidence", true, "已收到证据判断",
                        Map.of("assessments", assessments), false));

        handler().processMessage("7", "住宿标准是什么？超标怎么审批？",
                mock(WebSocketSession.class), "conv-1");

        assertEquals(4, requestBodies.size());
        assertTrue(requestBodies.get(3).contains("还没有检索"),
                "未检索的子问题报 MISSING_KNOWLEDGE 应以 tool 错误返回模型");
        String streamed = streamedChunks();
        assertTrue(streamed.contains("**子问题检索记录（未做证据充分性判断）**"));
        assertTrue(streamed.contains("子问题 2（超标如何审批？）：未检索"));
        assertFalse(streamed.contains("**子问题证据充分性"));
        assertFalse(streamed.contains("资料未覆盖"));
    }

    /**
     * 模拟模型从检索结果里复制 key：取 key= 到下一个分隔符之间的原文
     */
    private static String copiedKey(String formatted) {
        int start = formatted.indexOf("key=");
        assertTrue(start >= 0, "检索结果行必须包含 key=");
        start += "key=".length();
        int comma = formatted.indexOf(',', start);
        int paren = formatted.indexOf(')', start);
        int end = comma < 0 ? paren : (paren < 0 ? comma : Math.min(comma, paren));
        return formatted.substring(start, end < 0 ? formatted.length() : end);
    }
}
