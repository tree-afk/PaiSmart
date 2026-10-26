package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.resilience.ModelCallGuard;
import com.yizhaoqi.smartpai.client.resilience.ModelCallRateLimiter;
import com.yizhaoqi.smartpai.client.resilience.ModelWebClientFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ChatDeadlineProperties;
import com.yizhaoqi.smartpai.config.ModelCallProperties;
import com.yizhaoqi.smartpai.service.citation.CitationVerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

abstract class ChatHandlerReActTestSupport {

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final List<String> requestBodies = new CopyOnWriteArrayList<>();
    protected final List<String> responseBodies = new CopyOnWriteArrayList<>();
    protected final AgentToolRegistry tools = mock(AgentToolRegistry.class);
    protected final ChatSessionRegistry sessionRegistry = mock(ChatSessionRegistry.class);
    protected final ConversationService conversationService = mock(ConversationService.class);
    protected HttpServer modelServer;

    @BeforeEach
    void startModelServer() throws Exception {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = requestBodies.size();
            requestBodies.add(request);
            String response = index < responseBodies.size()
                    ? responseBodies.get(index)
                    : responseBodies.get(responseBodies.size() - 1);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        modelServer.start();
    }

    @AfterEach
    void stopModelServer() {
        modelServer.stop(0);
    }

    protected void enqueueContent(String content) {
        try {
            Map<String, Object> payload = Map.of("choices", List.of(Map.of(
                    "delta", Map.of("content", content),
                    "finish_reason", "stop")));
            responseBodies.add("data: " + objectMapper.writeValueAsString(payload) + "\n\ndata: [DONE]\n\n");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    protected void enqueueToolCall(String name, Map<String, Object> arguments) {
        enqueueToolCalls(List.of(new ToolCallSpec(name, arguments)));
    }

    protected void enqueueToolCalls(List<ToolCallSpec> specs) {
        try {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                ToolCallSpec spec = specs.get(i);
                toolCalls.add(Map.of(
                        "index", i,
                        "id", "call_" + (i + 1),
                        "type", "function",
                        "function", Map.of(
                                "name", spec.name(),
                                "arguments", objectMapper.writeValueAsString(spec.arguments())
                        )
                ));
            }
            Map<String, Object> payload = Map.of("choices", List.of(Map.of(
                    "delta", Map.of("tool_calls", toolCalls),
                    "finish_reason", "tool_calls")));
            responseBodies.add("data: " + objectMapper.writeValueAsString(payload) + "\n\ndata: [DONE]\n\n");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    protected record ToolCallSpec(String name, Map<String, Object> arguments) {
    }

    @SuppressWarnings("unchecked")
    protected ChatHandler handler() {
        ModelCallProperties modelCallProperties = new ModelCallProperties();
        ModelProviderConfigService providerConfig = mock(ModelProviderConfigService.class);
        when(providerConfig.getLlmFailoverChain(any())).thenReturn(List.of(
                new ModelProviderConfigService.ActiveProviderView(
                        "local", "local", ModelProviderConfigService.API_STYLE_OPENAI,
                        "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1",
                        "test-model", "test-key", null)));
        LlmProviderRouter router = new LlmProviderRouter(
                new AiProperties(),
                mock(RateLimitService.class),
                mock(UsageQuotaService.class),
                providerConfig,
                new ObjectMapper(),
                new ModelWebClientFactory(modelCallProperties),
                new ModelCallGuard(modelCallProperties, mock(ModelCallRateLimiter.class)),
                modelCallProperties);

        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(hash.entries(anyString())).thenReturn(Map.of());
        when(redis.opsForHash()).thenReturn((HashOperations) hash);

        ChatGenerationStateService state = mock(ChatGenerationStateService.class);
        when(state.createGeneration(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new ChatGenerationStateService.GenerationSnapshot(invocation.getArgument(0), "7", "conv-1", "q",
                        ChatGenerationStateService.GenerationStatus.STREAMING, "", "", "", null, Map.of()));

        when(tools.getTools()).thenReturn(new ArrayList<>(List.of(
                new AgentToolRegistry.AgentTool("plan_question", "登记复合问题子问题计划", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("search_knowledge", "检索知识库", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("report_evidence", "记录证据判断", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("generate_summary", "生成总结", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("submit_feedback", "提交反馈", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("ask_clarification", "向用户澄清", Map.of("type", "object")),
                new AgentToolRegistry.AgentTool("knowledge_stats", "查看知识库统计", Map.of("type", "object")))));

        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        return new ChatHandler(redis, mock(HybridSearchService.class), router, mock(RateLimitService.class),
                conversationService, state, sessionRegistry, tools,
                mock(CitationVerificationService.class), new ObjectMapper(), executor, new ChatDeadlineProperties());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected String streamedChunks() {
        ArgumentCaptor<Map<String, ?>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(sessionRegistry, atLeastOnce()).sendJsonToUser(eq("7"), captor.capture());
        return captor.getAllValues().stream()
                .filter(payload -> "chunk".equals(payload.get("type")))
                .map(payload -> String.valueOf(payload.get("chunk")))
                .collect(Collectors.joining());
    }

    protected String recordedAnswer() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationService).recordConversation(eq(7L), anyString(), captor.capture(), anyString(), any());
        return captor.getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Map<String, Map<String, Object>> recordedReferenceMappings() {
        ArgumentCaptor<Map<String, Map<String, Object>>> captor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(conversationService).recordConversation(eq(7L), anyString(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }
}
