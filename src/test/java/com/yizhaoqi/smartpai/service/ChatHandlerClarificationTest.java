package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHandlerClarificationTest extends ChatHandlerReActTestSupport {

    @Test
    void clarificationEndsTurnWithoutSecondModelCall() {
        enqueueToolCall("ask_clarification", Map.of(
                "questions", List.of("你所在的城市是哪里？（用于匹配住宿标准）")));

        when(tools.executeTool(eq("ask_clarification"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "ask_clarification", true,
                        "为了给出准确结论，还需要你确认以下信息：\n1. 你所在的城市是哪里？（用于匹配住宿标准）",
                        Map.of("questions", List.of("你所在的城市是哪里？（用于匹配住宿标准）")),
                        false));

        handler().processMessage("7", "住宿标准是多少？", mock(WebSocketSession.class), "conv-1");

        assertEquals(1, requestBodies.size());
        assertTrue(streamedChunks().contains("你所在的城市是哪里？"));
        assertTrue(recordedAnswer().contains("你所在的城市是哪里？"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, ?>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(sessionRegistry, atLeastOnce()).sendJsonToUser(eq("7"), captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(payload ->
                "completion".equals(payload.get("type")) && "finished".equals(payload.get("status"))));
    }
}
