package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ChatHandlerSummaryCancellationTest extends ChatHandlerReActTestSupport {

    @Test
    void stopResponseCancelsRegisteredSummarySubscription() throws Exception {
        CountDownLatch summaryStarted = new CountDownLatch(1);
        CountDownLatch summaryCancelled = new CountDownLatch(1);
        when(tools.executeSummary(anyMap(), anyString(), any(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<Runnable> registrar = invocation.getArgument(5);
                    registrar.accept(summaryCancelled::countDown);
                    summaryStarted.countDown();
                    summaryCancelled.await(2, TimeUnit.SECONDS);
                    throw new RuntimeException("summary cancelled");
                });

        ChatHandler handler = handler();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> toolCall = executor.submit(() -> {
                Method method = ChatHandler.class.getDeclaredMethod("executeToolForReAct",
                        String.class, String.class, String.class, String.class,
                        LlmProviderRouter.ToolCallDecision.class);
                method.setAccessible(true);
                method.invoke(handler, "7", "问题", "gen-1", "conv-1",
                        new LlmProviderRouter.ToolCallDecision("call_1", "generate_summary",
                                Map.of("topic", "制度")));
                return null;
            });

            assertTrue(summaryStarted.await(2, TimeUnit.SECONDS), "摘要工具应已开始执行");
            handler.stopResponse("7", "gen-1");

            assertTrue(summaryCancelled.await(500, TimeUnit.MILLISECONDS),
                    "stopResponse 应取消摘要内部订阅");
            toolCall.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
