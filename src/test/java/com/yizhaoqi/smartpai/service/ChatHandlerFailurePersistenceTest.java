package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatHandlerFailurePersistenceTest extends ChatHandlerReActTestSupport {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failedTurnIsPersistedWithActionableReason() {
        forcedFailureStatus = 400;

        handler().processMessage("7", "现在知识库是否有数据", mock(WebSocketSession.class), "conv-1");

        ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
        verify(conversationService).recordConversation(eq(7L), eq("现在知识库是否有数据"), answer.capture(), eq("conv-1"), any());
        assertTrue(answer.getValue().contains("模型服务账户欠费"),
                "失败轮次应持久化可行动的原因，实际: " + answer.getValue());

        ArgumentCaptor<Map<String, ?>> payloads = ArgumentCaptor.forClass((Class) Map.class);
        verify(sessionRegistry, atLeastOnce()).sendJsonToUser(eq("7"), payloads.capture());
        boolean actionableErrorSent = payloads.getAllValues().stream()
                .filter(payload -> "error".equals(payload.get("type")))
                .map(payload -> String.valueOf(payload.get("error")))
                .anyMatch(message -> message.contains("模型服务账户欠费"));
        assertTrue(actionableErrorSent, "用户应看到真实失败原因而不是通用提示");
    }
}
