package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerPlanValidationTest extends ChatHandlerReActTestSupport {

    @Test
    void searchIndexBeforePlanReturnsToolErrorAndDoesNotSearch() {
        enqueueToolCall("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1));
        enqueueContent("请先制定计划。");

        handler().processMessage("7", "住宿标准是什么？", mock(WebSocketSession.class), "conv-1");

        assertEquals(2, requestBodies.size());
        assertTrue(requestBodies.get(1).contains("请先调用 plan_question"));
        verify(tools, never()).executeTool(eq("search_knowledge"), anyMap(), anyString(), any());
    }

    @Test
    void duplicatePlanKeepsOriginalAndDoesNotExecuteSecond() {
        enqueueToolCall("plan_question", Map.of("subQuestions", List.of("A", "B")));
        enqueueToolCall("plan_question", Map.of("subQuestions", List.of("C", "D")));
        enqueueContent("最终回答");

        AtomicInteger planCalls = new AtomicInteger();
        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenAnswer(invocation -> {
            List<String> subQuestions = planCalls.getAndIncrement() == 0 ? List.of("A", "B") : List.of("C", "D");
            return new AgentToolRegistry.ToolExecutionResult(
                    "plan_question", true, "已记录",
                    Map.of("subQuestions", subQuestions), false);
        });

        handler().processMessage("7", "复合问题", mock(WebSocketSession.class), "conv-1");

        assertEquals(3, requestBodies.size());
        assertTrue(requestBodies.get(2).contains("不能重复制定"));
        String streamed = streamedChunks();
        assertTrue(streamed.contains("子问题 1（A）"));
        assertFalse(streamed.contains("子问题 1（C）"));
        verify(tools, times(1)).executeTool(eq("plan_question"), anyMap(), anyString(), any());
    }

    @Test
    void outOfRangeIndexReturnsToolErrorAndDoesNotSearch() {
        enqueueToolCall("plan_question", Map.of("subQuestions", List.of("A", "B")));
        enqueueToolCall("search_knowledge", Map.of("query", "C", "subQuestionIndex", 3));
        enqueueContent("最终回答");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录",
                        Map.of("subQuestions", List.of("A", "B")), false));

        handler().processMessage("7", "复合问题", mock(WebSocketSession.class), "conv-1");

        assertEquals(3, requestBodies.size());
        assertTrue(requestBodies.get(2).contains("subQuestionIndex 必须在 1-2 之间"));
        verify(tools, never()).executeTool(eq("search_knowledge"), anyMap(), anyString(), any());
    }

    @Test
    void searchWithoutSubQuestionIndexAfterPlanReturnsToolErrorAndDoesNotSearch() {
        enqueueToolCall("plan_question", Map.of("subQuestions", List.of("A", "B")));
        enqueueToolCall("search_knowledge", Map.of("query", "C"));
        enqueueContent("最终回答");

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录",
                        Map.of("subQuestions", List.of("A", "B")), false));

        handler().processMessage("7", "复合问题", mock(WebSocketSession.class), "conv-1");

        assertEquals(3, requestBodies.size());
        assertTrue(requestBodies.get(2).contains("检索时必须填写 subQuestionIndex"));
        verify(tools, never()).executeTool(eq("search_knowledge"), anyMap(), anyString(), any());
    }
}
