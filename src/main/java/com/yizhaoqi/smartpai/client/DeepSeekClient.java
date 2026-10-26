package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import com.yizhaoqi.smartpai.utils.RequestDeadline;
import reactor.core.Disposable;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties,
                         UsageQuotaService usageQuotaService,
                         ModelProviderConfigService modelProviderConfigService) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        
        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.objectMapper = new ObjectMapper();
    }
    
    public void streamResponse(String requesterId,
                             String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
        
        try {
            webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                        chunk -> processChunk(chunk, usageTracker, onChunk),
                        error -> {
                            settleUsage(usageTracker);
                            onError.accept(error);
                        },
                        () -> settleUsage(usageTracker)
                    );
        } catch (Exception e) {
            usageQuotaService.abortReservation(reservation);
            throw e;
        }
    }

    public String summarize(String requesterId, String topic, List<SearchResult> searchResults) {
        return summarize(requesterId, topic, searchResults, null, 1);
    }

    public String summarize(String requesterId,
                            String topic,
                            List<SearchResult> searchResults,
                            Consumer<String> onChunk) {
        return summarize(requesterId, topic, searchResults, onChunk, 1);
    }

    /**
     * @param firstReferenceNumber 摘要上下文里的第一条来源编号；外层可能已经分配过检索引用，
     *                             摘要必须沿用同一套全局编号，避免 [1] 指到另一份资料。
     */
    public String summarize(String requesterId,
                            String topic,
                            List<SearchResult> searchResults,
                            Consumer<String> onChunk,
                            int firstReferenceNumber) {
        return summarize(requesterId, topic, searchResults, onChunk, firstReferenceNumber, null);
    }

    /**
     * @param cancelRegistrar 供聊天停止句柄登记内部摘要订阅；收到停止时立即 dispose 上游请求
     */
    public String summarize(String requesterId,
                            String topic,
                            List<SearchResult> searchResults,
                            Consumer<String> onChunk,
                            int firstReferenceNumber,
                            Consumer<Runnable> cancelRegistrar) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("摘要主题不能为空");
        }
        if (searchResults == null || searchResults.isEmpty()) {
            String noResult = "未检索到与主题相关的知识库文档，无法生成基于知识库的摘要。";
            if (onChunk != null) {
                onChunk.accept(noResult);
            }
            return noResult;
        }

        List<Map<String, String>> messages = buildSummaryMessages(topic, searchResults, firstReferenceNumber);
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 1600;
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = null;

        try {
            SummaryEndpoint summaryEndpoint = resolveSummaryEndpoint();
            Map<String, Object> request = new HashMap<>();
            request.put("model", summaryEndpoint.model());
            request.put("messages", messages);
            request.put("stream", true);
            request.put("stream_options", Map.of("include_usage", true));
            request.put("max_tokens", maxCompletionTokens);
            AiProperties.Generation gen = aiProperties.getGeneration();
            if (gen.getTemperature() != null) {
                request.put("temperature", Math.min(gen.getTemperature(), 0.3d));
            } else {
                request.put("temperature", 0.2d);
            }
            if (gen.getTopP() != null) {
                request.put("top_p", gen.getTopP());
            }

            usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
            String summary = executeSummaryStream(summaryEndpoint, request, usageTracker, onChunk, cancelRegistrar);
            logger.info("generate_summary 内部摘要完成: provider={}, model={}, promptTokensEstimate={}, summaryChars={}",
                    summaryEndpoint.provider(), summaryEndpoint.model(), estimatedPromptTokens, summary.length());
            return summary;
        } catch (Exception e) {
            if (usageTracker == null || !usageTracker.settled) {
                usageQuotaService.abortReservation(reservation);
            }
            throw new RuntimeException("生成知识库摘要失败", e);
        }
    }

    private String executeSummaryStream(SummaryEndpoint summaryEndpoint,
                                        Map<String, Object> request,
                                        StreamUsageTracker usageTracker,
                                        Consumer<String> onChunk,
                                        Consumer<Runnable> cancelRegistrar) {
        CompletableFuture<String> summaryFuture = new CompletableFuture<>();
        long startedAt = System.currentTimeMillis();
        AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
        AtomicInteger chunkCount = new AtomicInteger(0);
        Consumer<String> chunkConsumer = chunk -> {
            if (chunk != null && !chunk.isEmpty()) {
                int currentChunkCount = chunkCount.incrementAndGet();
                if (firstChunkLogged.compareAndSet(false, true)) {
                    logger.info("generate_summary 内部摘要收到首个流式 chunk: provider={}, model={}, elapsedMs={}, chunkChars={}",
                            summaryEndpoint.provider(), summaryEndpoint.model(), System.currentTimeMillis() - startedAt, chunk.length());
                }
                logger.debug("generate_summary 内部摘要流式 chunk: provider={}, model={}, chunkIndex={}, chunkChars={}",
                        summaryEndpoint.provider(), summaryEndpoint.model(), currentChunkCount, chunk.length());
            }
            if (onChunk != null) {
                onChunk.accept(chunk);
            }
        };

        Disposable subscription = summaryEndpoint.webClient().post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> processChunk(chunk, usageTracker, chunkConsumer),
                        error -> {
                            settleUsage(usageTracker);
                            summaryFuture.completeExceptionally(error);
                        },
                        () -> {
                            settleUsage(usageTracker);
                            logger.info("generate_summary 内部摘要流结束: provider={}, model={}, elapsedMs={}, chunks={}, summaryChars={}",
                                    summaryEndpoint.provider(),
                                    summaryEndpoint.model(),
                                    System.currentTimeMillis() - startedAt,
                                    chunkCount.get(),
                                    usageTracker.responseContent.length());
                            summaryFuture.complete(usageTracker.responseContent.toString().trim());
                        }
                );

        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (cancelRegistrar != null) {
            cancelRegistrar.accept(() -> {
                if (cancelled.compareAndSet(false, true)) {
                    subscription.dispose();
                }
            });
        }
        return awaitSummary(summaryFuture, subscription, usageTracker, cancelled);
    }

    /**
     * 摘要等待同时受两层约束：自身 90 秒上限，以及整轮请求 RequestDeadline 的剩余预算。
     * 轮询等待而不是一次 get 90 秒，确保用户点停止后能在一个轮询周期内 dispose 上游订阅。
     */
    private String awaitSummary(CompletableFuture<String> summaryFuture,
                                Disposable subscription,
                                StreamUsageTracker usageTracker,
                                AtomicBoolean cancelled) {
        long configuredDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        long deadline = RequestDeadline.currentNanos()
                .map(requestDeadline -> Math.min(requestDeadline, configuredDeadline))
                .orElse(configuredDeadline);
        while (true) {
            if (cancelled.get()) {
                subscription.dispose();
                settleUsage(usageTracker);
                throw new RuntimeException("DeepSeek 摘要流式响应已取消");
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                subscription.dispose();
                settleUsage(usageTracker);
                throw new RuntimeException("DeepSeek 摘要流式响应超时",
                        new TimeoutException("摘要超出整轮请求剩余时间"));
            }
            long waitMillis = Math.min(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos), 200L);
            try {
                String summary = summaryFuture.get(Math.max(waitMillis, 1L), TimeUnit.MILLISECONDS);
                if (summary == null || summary.isBlank()) {
                    subscription.dispose();
                    settleUsage(usageTracker);
                    throw new IllegalStateException("DeepSeek 摘要流未返回有效内容");
                }
                return summary;
            } catch (TimeoutException ignored) {
                // 短轮询用于及时响应停止和整轮预算耗尽
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subscription.dispose();
                settleUsage(usageTracker);
                throw new RuntimeException("DeepSeek 摘要流式响应被中断", e);
            } catch (ExecutionException e) {
                subscription.dispose();
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException("DeepSeek 摘要流式响应失败", cause);
            }
        }
    }

    private SummaryEndpoint resolveSummaryEndpoint() {
        try {
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
            WebClient.Builder builder = WebClient.builder()
                    .baseUrl(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()));
            if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
            }
            return new SummaryEndpoint(builder.build(), provider.model(), provider.provider());
        } catch (Exception exception) {
            logger.warn("无法读取活动 LLM Provider，generate_summary 内部摘要回退到 deepseek.* 配置: {}", exception.getMessage());
            return new SummaryEndpoint(webClient, model, "deepseek");
        }
    }
    
    private Map<String, Object> buildRequest(String userMessage, 
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}", 
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    List<Map<String, String>> buildSummaryMessages(String topic, List<SearchResult> searchResults,
                                                   int firstReferenceNumber) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "你是 generate_summary 工具内部使用的知识库摘要模型。"
                        + "只基于提供的知识库片段生成结构化摘要，不要发起工具调用，不要把自己当作外层 ReAct 循环。"
                        + "输出应包含：核心结论、关键依据、可执行建议或待确认问题。"
                        + "引用必须使用片段前面的 [N] 编号，不能重新编号或编造来源。"
                        + "文档类型只用于说明资料来源，不是效力等级；不要把 REGULATION 自动当成适用于所有机构或地区。"
                        + "冲突时列出各来源的发布机构、适用地区/适用机构、生效/失效/废止状态、条款号和差异；无法确认时保留“待核实/需法务确认”。"
                        + "validity=UNCONFIRMED 必须写成“生效日期未标注，效力待确认”，不得当作现行依据；"
                        + "validity=EFFECTIVE 只能写成“标注生效日期已到且未标注失效”，不得直接断言现行有效。"
                        + "issuer 只是发布机构，appliesTo 才是适用机构。"
        ));
        messages.add(Map.of(
                "role", "user",
                "content", "主题：" + topic + "\n\n知识库片段（编号从 [" + firstReferenceNumber + "] 开始）：\n"
                        + buildSummaryContext(searchResults, firstReferenceNumber)
        ));
        return messages;
    }

    private String buildSummaryContext(List<SearchResult> searchResults, int firstReferenceNumber) {
        StringBuilder context = new StringBuilder();
        // 父子检索：有父块时给父块，多个命中共用的父块只给一次
        java.util.Set<String> emittedParents = new java.util.HashSet<>();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            context.append("[").append(firstReferenceNumber + i).append("] ");
            if (result.getFileName() != null && !result.getFileName().isBlank()) {
                context.append("文件：").append(result.getFileName()).append("，");
            }
            context.append("fileMd5=").append(result.getFileMd5())
                    .append("，chunkId=").append(result.getChunkId());
            if (result.getPageNumber() != null) {
                context.append("，page=").append(result.getPageNumber());
            }
            if (result.getDocType() != null && !result.getDocType().isBlank()) {
                context.append("，docType=").append(result.getDocType());
            }
            if (result.getJurisdiction() != null && !result.getJurisdiction().isBlank()) {
                context.append("，jurisdiction=").append(result.getJurisdiction());
            }
            if (result.getIssuer() != null && !result.getIssuer().isBlank()) {
                context.append("，issuer=").append(result.getIssuer());
            }
            if (result.getApplicableInstitutions() != null && !result.getApplicableInstitutions().isBlank()) {
                context.append("，appliesTo=").append(result.getApplicableInstitutions());
            }
            if (result.getEffectiveAt() != null && !result.getEffectiveAt().isBlank()) {
                context.append("，effectiveAt=").append(result.getEffectiveAt());
            }
            if (result.getExpiresAt() != null && !result.getExpiresAt().isBlank()) {
                context.append("，expiresAt=").append(result.getExpiresAt());
            }
            if (result.getValidityStatus() != null && !result.getValidityStatus().isBlank()) {
                context.append("，validity=").append(result.getValidityStatus());
                if (result.getValidityLabel() != null && !result.getValidityLabel().isBlank()) {
                    context.append('(').append(result.getValidityLabel()).append(')');
                }
            }
            if (result.getArticlePath() != null && !result.getArticlePath().isBlank()) {
                context.append("，article=").append(result.getArticlePath());
            }
            if (result.getClauseNo() != null && !result.getClauseNo().isBlank()) {
                context.append("，clause=").append(result.getClauseNo());
            }
            String matched = result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent();
            boolean newParent = result.getParentContext() != null && !result.getParentContext().isBlank()
                    && result.getParentKey() != null && emittedParents.add(result.getParentKey());
            context.append("\n")
                    .append(limitText(newParent ? result.getParentContext() : matched, 1800))
                    .append("\n\n");
        }
        return context.toString();
    }

    private String extractSummaryContent(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("DeepSeek 摘要响应为空");
        }
        JsonNode node = objectMapper.readTree(responseBody);
        String summary = node.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText("")
                .trim();
        if (summary.isEmpty()) {
            throw new IllegalStateException("DeepSeek 摘要响应未包含 message.content");
        }
        return summary;
    }

    private void settleSummaryUsage(UsageQuotaService.TokenReservation reservation,
                                    String responseBody,
                                    int estimatedPromptTokens,
                                    String summary) {
        try {
            JsonNode usageNode = objectMapper.readTree(responseBody).path("usage");
            int promptTokens = usageNode.path("prompt_tokens").asInt(estimatedPromptTokens);
            int completionTokens = usageNode.path("completion_tokens").asInt(usageQuotaService.estimateTextTokens(summary));
            usageQuotaService.settleReservation(reservation, promptTokens + completionTokens);
        } catch (Exception e) {
            int actualTokens = estimatedPromptTokens + usageQuotaService.estimateTextTokens(summary);
            usageQuotaService.settleReservation(reservation, actualTokens);
        }
    }

    private String limitText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private record SummaryEndpoint(WebClient webClient, String model, String provider) {
    }
    
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    private void processChunk(String rawChunk, StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    logger.debug("对话结束");
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usageTracker.promptTokens = usageNode.path("prompt_tokens").asInt(usageTracker.promptTokens);
                    usageTracker.completionTokens = usageNode.path("completion_tokens").asInt(usageTracker.completionTokens);
                }

                String content = node.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText("");

                if (!content.isEmpty()) {
                    usageTracker.responseContent.append(content);
                    onChunk.accept(content);
                }
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    private List<String> extractPayloads(String rawChunk) {
        List<String> payloads = new ArrayList<>();
        if (rawChunk == null || rawChunk.isBlank()) {
            return payloads;
        }

        String trimmed = rawChunk.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            String payload = line.trim();
            if (payload.isEmpty() || payload.startsWith(":")) {
                continue;
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }

        if (payloads.isEmpty()) {
            payloads.add(trimmed);
        }
        return payloads;
    }

    private void settleUsage(StreamUsageTracker usageTracker) {
        if (usageTracker == null || usageTracker.settled) {
            return;
        }

        usageTracker.settled = true;
        int actualPromptTokens = usageTracker.promptTokens > 0
                ? usageTracker.promptTokens
                : usageTracker.estimatedPromptTokens;
        int actualCompletionTokens = usageTracker.completionTokens > 0
                ? usageTracker.completionTokens
                : usageQuotaService.estimateTextTokens(usageTracker.responseContent.toString());

        usageQuotaService.settleReservation(usageTracker.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private static final class StreamUsageTracker {
        private final UsageQuotaService.TokenReservation reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder responseContent = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile boolean settled;

        private StreamUsageTracker(UsageQuotaService.TokenReservation reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }
    }
}
