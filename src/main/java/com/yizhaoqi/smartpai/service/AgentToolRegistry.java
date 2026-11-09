package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.DocStats;
import co.elastic.clients.elasticsearch._types.StoreStats;
import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.stats.IndicesStats;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import com.yizhaoqi.smartpai.model.PublicationStatus;
import com.yizhaoqi.smartpai.repository.BusinessDocumentRepository;
import com.yizhaoqi.smartpai.service.document.ActiveRetrievalScopeService;
import com.yizhaoqi.smartpai.service.document.ComplianceSearchFilter;
import com.yizhaoqi.smartpai.service.document.DocumentRetrievalFilter;
import com.yizhaoqi.smartpai.service.index.KnowledgeIndexNames;
import com.yizhaoqi.smartpai.service.search.ParentContextExpander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class AgentToolRegistry {

    private static final String KNOWLEDGE_INDEX = KnowledgeIndexNames.ALIAS;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_SEARCH_DOCS = 20;
    private static final Logger logger = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final HybridSearchService hybridSearchService;
    private final DeepSeekClient deepSeekClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchClient elasticsearchClient;
    private final BusinessDocumentRepository businessDocumentRepository;
    private final ActiveRetrievalScopeService activeRetrievalScopeService;
    private final ParentContextExpander parentContextExpander;
    private final AnswerFeedbackService answerFeedbackService;
    private final List<AgentTool> tools;
    private final Map<String, ToolHandler> handlers;

    @Autowired
    public AgentToolRegistry(HybridSearchService hybridSearchService,
                             DeepSeekClient deepSeekClient,
                             StringRedisTemplate stringRedisTemplate,
                             ElasticsearchClient elasticsearchClient,
                             BusinessDocumentRepository businessDocumentRepository,
                             ActiveRetrievalScopeService activeRetrievalScopeService,
                             ParentContextExpander parentContextExpander,
                             AnswerFeedbackService answerFeedbackService) {
        this.hybridSearchService = hybridSearchService;
        this.deepSeekClient = deepSeekClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.elasticsearchClient = elasticsearchClient;
        this.businessDocumentRepository = businessDocumentRepository;
        this.activeRetrievalScopeService = activeRetrievalScopeService;
        this.parentContextExpander = parentContextExpander;
        this.answerFeedbackService = answerFeedbackService;
        this.tools = List.of(
                planQuestionTool(),
                searchKnowledgeTool(),
                reportEvidenceTool(),
                generateSummaryTool(),
                submitFeedbackTool(),
                askClarificationTool(),
                knowledgeStatsTool()
        );
        this.handlers = Map.of(
                "plan_question", this::executePlanQuestion,
                "search_knowledge", this::executeSearchKnowledge,
                "report_evidence", this::executeReportEvidence,
                "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback,
                "ask_clarification", this::executeAskClarification,
                "knowledge_stats", this::executeKnowledgeStats
        );
    }

    /**
     * 兼容测试和旧装配：不落 MySQL 反馈，只保留 Redis 行为
     */
    public AgentToolRegistry(HybridSearchService hybridSearchService,
                             DeepSeekClient deepSeekClient,
                             StringRedisTemplate stringRedisTemplate,
                             ElasticsearchClient elasticsearchClient,
                             BusinessDocumentRepository businessDocumentRepository,
                             ActiveRetrievalScopeService activeRetrievalScopeService,
                             ParentContextExpander parentContextExpander) {
        this(hybridSearchService, deepSeekClient, stringRedisTemplate, elasticsearchClient,
                businessDocumentRepository, activeRetrievalScopeService, parentContextExpander, null);
    }

    public List<AgentTool> getTools() {
        return tools;
    }

    public Optional<AgentTool> getTool(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public ToolExecutionResult executeTool(String name, Map<String, Object> arguments, String userId) {
        return executeTool(name, arguments, userId, null);
    }

    public ToolExecutionResult executeTool(String name,
                                           Map<String, Object> arguments,
                                           String userId,
                                           Consumer<String> onChunk) {
        return executeTool(name, arguments, userId, onChunk, null);
    }

    /**
     * @param inheritedComplianceFilter generate_summary 未显式给出类型/地区时沿用的检索条件；
     *                                  由 ChatHandler 传入最近一次 search_knowledge 的条件
     */
    public ToolExecutionResult executeTool(String name,
                                           Map<String, Object> arguments,
                                           String userId,
                                           Consumer<String> onChunk,
                                           ComplianceSearchFilter inheritedComplianceFilter) {
        Map<String, Object> effectiveArguments = arguments == null ? Collections.emptyMap() : arguments;
        if ("generate_summary".equals(name)) {
            return executeSummary(effectiveArguments, userId, onChunk, inheritedComplianceFilter, 1);
        }
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        return handler.execute(effectiveArguments, userId, onChunk);
    }

    /**
     * 摘要专用入口：外层已经分配了全局引用编号时，摘要提示词和返回的来源必须从同一个编号开始；
     * 同时摘要不再边生成边输出，先由调用方登记来源映射，再统一输出。
     */
    public ToolExecutionResult executeSummary(Map<String, Object> arguments,
                                              String userId,
                                              Consumer<String> onChunk,
                                              ComplianceSearchFilter inheritedComplianceFilter,
                                              int firstReferenceNumber) {
        return executeSummary(arguments, userId, onChunk, inheritedComplianceFilter, firstReferenceNumber, null);
    }

    public ToolExecutionResult executeSummary(Map<String, Object> arguments,
                                              String userId,
                                              Consumer<String> onChunk,
                                              ComplianceSearchFilter inheritedComplianceFilter,
                                              int firstReferenceNumber,
                                              Consumer<Runnable> cancelRegistrar) {
        return executeGenerateSummary(arguments == null ? Collections.emptyMap() : arguments,
                userId, onChunk, inheritedComplianceFilter, Math.max(firstReferenceNumber, 1), cancelRegistrar);
    }

    private ToolExecutionResult executePlanQuestion(Map<String, Object> arguments,
                                                    String userId,
                                                    Consumer<String> onChunk) {
        List<String> subQuestions = QuestionPlan.normalizeSubQuestions(arguments.get("subQuestions"));
        StringBuilder content = new StringBuilder("已记录 ")
                .append(subQuestions.size()).append(" 个子问题：\n");
        for (int i = 0; i < subQuestions.size(); i++) {
            content.append(i + 1).append(". ").append(subQuestions.get(i)).append('\n');
        }
        content.append("\n请分别调用 search_knowledge 检索每个子问题，并在 subQuestionIndex 填写对应编号；")
                .append("全部子问题检索后再给出最终回答。");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subQuestions", subQuestions);
        return new ToolExecutionResult("plan_question", true, content.toString(), data, false);
    }

    private ToolExecutionResult executeAskClarification(Map<String, Object> arguments,
                                                        String userId,
                                                        Consumer<String> onChunk) {
        Object raw = arguments.get("questions");
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 3) {
            throw new IllegalArgumentException("questions 需要 1-3 个");
        }
        List<String> questions = new ArrayList<>(list.size());
        for (Object item : list) {
            String text = item instanceof String value ? value.trim() : "";
            if (text.isEmpty()) {
                throw new IllegalArgumentException("questions 不能包含空问题");
            }
            questions.add(text);
        }
        StringBuilder content = new StringBuilder("为了给出准确结论，还需要你确认以下信息：\n");
        for (int i = 0; i < questions.size(); i++) {
            content.append(i + 1).append(". ").append(questions.get(i)).append('\n');
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("questions", questions);
        return new ToolExecutionResult("ask_clarification", true, content.toString().trim(), data, false);
    }

    private ToolExecutionResult executeSearchKnowledge(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        requireUserId(userId);
        String query = getRequiredString(arguments, "query");
        int topK = getInt(arguments, "topK", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);
        ComplianceSearchFilter complianceFilter = buildComplianceFilter(arguments);

        // 小分块检索、父块交给模型：引用和证据片段仍指向命中的小分块
        List<SearchResult> results = expandParents(
                hybridSearchService.searchWithPermission(query, userId, topK, complianceFilter));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("topK", topK);
        data.put("complianceFilter", complianceFilterData(complianceFilter));
        data.put("results", results);
        return new ToolExecutionResult("search_knowledge", true, formatSearchResults(results), data);
    }

    /**
     * 工具只接受用户明确给出的文档类型和地区；没有 asOf 参数，现行有效日期固定取今天
     */
    private ComplianceSearchFilter buildComplianceFilter(Map<String, Object> arguments) {
        return ComplianceSearchFilter.fromCodes(
                getOptionalStringList(arguments, "docTypes"),
                getOptionalStringList(arguments, "jurisdictions"),
                LocalDate.now());
    }

    private Map<String, Object> complianceFilterData(ComplianceSearchFilter filter) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docTypes", filter.docTypes().stream().map(DocumentComplianceType::name).toList());
        data.put("jurisdictions", List.copyOf(filter.jurisdictions()));
        data.put("jurisdictionCodes", List.copyOf(
                com.yizhaoqi.smartpai.model.JurisdictionNormalizer.queryCodes(filter.jurisdictions())));
        data.put("asOf", filter.asOf().toString());
        return data;
    }

    /**
     * 只回传模型的原始判断，编号覆盖、状态、证据真实性由 ChatHandler 结合 QuestionPlan 校验
     */
    private ToolExecutionResult executeReportEvidence(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        Object raw = arguments.get("assessments");
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 3) {
            throw new IllegalArgumentException("assessments 需要 1-3 个");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assessments", raw);
        return new ToolExecutionResult(
                "report_evidence",
                true,
                "已收到 " + list.size() + " 个子问题的证据判断，代码将校验编号、状态和证据是否来自本次检索。",
                data,
                false);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk) {
        return executeGenerateSummary(arguments, userId, onChunk, null, 1);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk,
                                                       ComplianceSearchFilter inheritedFilter,
                                                       int firstReferenceNumber) {
        return executeGenerateSummary(arguments, userId, onChunk, inheritedFilter, firstReferenceNumber, null);
    }

    private ToolExecutionResult executeGenerateSummary(Map<String, Object> arguments,
                                                       String userId,
                                                       Consumer<String> onChunk,
                                                       ComplianceSearchFilter inheritedFilter,
                                                       int firstReferenceNumber,
                                                       Consumer<Runnable> cancelRegistrar) {
        requireUserId(userId);
        String topic = getRequiredString(arguments, "topic");
        int maxDocs = getInt(arguments, "maxDocs", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);
        ComplianceSearchFilter filter = resolveSummaryFilter(arguments, inheritedFilter);

        List<SearchResult> results = expandParents(
                hybridSearchService.searchWithPermission(topic, userId, maxDocs, filter));
        // 不在这里流式输出：ChatHandler 先登记 sources -> [N] 映射，再把完整摘要发出去
        String summary = cancelRegistrar == null
                ? deepSeekClient.summarize(userId, topic, results, null, firstReferenceNumber)
                : deepSeekClient.summarize(userId, topic, results, null, firstReferenceNumber, cancelRegistrar);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("maxDocs", maxDocs);
        data.put("sourceCount", results.size());
        data.put("sources", results);
        data.put("complianceFilter", complianceFilterData(filter));
        data.put("firstReferenceNumber", firstReferenceNumber);

        String content = "主题：" + topic + "\n"
                + "检索片段数：" + results.size() + "\n\n"
                + summary;
        return new ToolExecutionResult("generate_summary", true, content, data, false);
    }

    /**
     * 摘要默认沿用最近一次检索的条件；docTypes / jurisdictions 分别覆盖，
     * 只传一个维度时另一个维度继续继承，不能被空集合重置成“不限”。
     */
    private ComplianceSearchFilter resolveSummaryFilter(Map<String, Object> arguments,
                                                         ComplianceSearchFilter inheritedFilter) {
        boolean explicitDocTypes = hasAnyValue(arguments.get("docTypes"));
        boolean explicitJurisdictions = hasAnyValue(arguments.get("jurisdictions"));
        if (!explicitDocTypes && !explicitJurisdictions) {
            return inheritedFilter == null ? ComplianceSearchFilter.current() : inheritedFilter;
        }

        LocalDate asOf = inheritedFilter == null ? LocalDate.now() : inheritedFilter.asOf();
        List<String> docTypes = explicitDocTypes
                ? getOptionalStringList(arguments, "docTypes")
                : inheritedFilter == null
                ? List.of()
                : inheritedFilter.docTypes().stream().map(DocumentComplianceType::name).toList();
        List<String> jurisdictions = explicitJurisdictions
                ? getOptionalStringList(arguments, "jurisdictions")
                : inheritedFilter == null
                ? List.of()
                : List.copyOf(inheritedFilter.jurisdictions());
        return ComplianceSearchFilter.fromCodes(docTypes, jurisdictions, asOf);
    }

    private boolean hasAnyValue(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> item != null && !String.valueOf(item).isBlank());
        }
        return !String.valueOf(raw).isBlank();
    }

    private List<SearchResult> expandParents(List<SearchResult> results) {
        return parentContextExpander == null ? results : parentContextExpander.expand(results);
    }

    private ToolExecutionResult executeSubmitFeedback(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        requireUserId(userId);
        String rating = getRequiredString(arguments, "rating").toLowerCase(Locale.ROOT);
        if (!"good".equals(rating) && !"bad".equals(rating)) {
            throw new IllegalArgumentException("rating 只允许 good 或 bad");
        }
        String reason = getOptionalString(arguments, "reason");
        String key = "feedback:" + userId;
        String field = String.valueOf(System.currentTimeMillis());
        String value = reason == null || reason.isBlank()
                ? "rating=" + rating
                : "rating=" + rating + "; reason=" + reason;
        stringRedisTemplate.opsForHash().put(key, field, value);

        if (answerFeedbackService != null) {
            try {
                answerFeedbackService.record(userId,
                        getOptionalString(arguments, "conversationId"),
                        getOptionalString(arguments, "generationId"),
                        rating,
                        reason);
            } catch (Exception e) {
                // Redis 已经记下反馈，MySQL 失败不能让用户看到提交失败
                logger.warn("反馈落库失败，Redis 记录已保留: userId={}, error={}", userId, e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("field", field);
        data.put("rating", rating);
        data.put("reason", reason);
        return new ToolExecutionResult("submit_feedback", true, "已记录用户反馈: " + value, data);
    }

    private ToolExecutionResult executeKnowledgeStats(Map<String, Object> arguments,
                                                      String userId,
                                                      Consumer<String> onChunk) {
        try {
            IndicesStatsResponse statsResponse = elasticsearchClient.indices().stats(s -> s.index(KNOWLEDGE_INDEX));
            // 别名下 stats 按物理索引名返回，取别名当前指向的那一个
            IndicesStats indexStats = statsResponse.indices().values().stream().findFirst().orElse(null);
            DocStats docStats = indexStats != null && indexStats.total() != null ? indexStats.total().docs() : null;
            StoreStats storeStats = indexStats != null && indexStats.total() != null ? indexStats.total().store() : null;

            long documentCount = businessDocumentRepository
                    .countByDeletedAtIsNullAndPublicationStatus(PublicationStatus.PUBLISHED);
            // 片段数与检索口径一致：只统计已发布文档当前 active build 的分块，空范围自然为 0
            long fragmentCount = elasticsearchClient.count(c -> c
                            .index(KNOWLEDGE_INDEX)
                            .query(DocumentRetrievalFilter.build(activeRetrievalScopeService.current())))
                    .count();
            Long deletedFragmentCount = docStats != null ? docStats.deleted() : null;
            Long storeSizeInBytes = storeStats != null ? storeStats.sizeInBytes() : null;
            LocalDateTime latestUpdatedAt = businessDocumentRepository
                    .findFirstByDeletedAtIsNullAndPublicationStatusOrderByUpdatedAtDesc(PublicationStatus.PUBLISHED)
                    .map(document -> document.getUpdatedAt())
                    .orElse(null);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("index", KNOWLEDGE_INDEX);
            data.put("documentCount", documentCount);
            data.put("fragmentCount", fragmentCount);
            data.put("deletedFragmentCount", deletedFragmentCount);
            data.put("storeSizeInBytes", storeSizeInBytes);
            data.put("latestUpdatedAt", latestUpdatedAt);

            return new ToolExecutionResult("knowledge_stats", true, formatKnowledgeStats(data), data);
        } catch (Exception e) {
            throw new RuntimeException("获取知识库统计信息失败", e);
        }
    }

    private AgentTool planQuestionTool() {
        return new AgentTool(
                "plan_question",
                "当用户问题包含两个或以上可以独立回答的部分时，先调用本工具登记子问题清单。每个子问题应能单独检索和回答；单一问题不要调用。调用后必须为每个子问题分别调用 search_knowledge，并在 subQuestionIndex 填写对应编号。",
                objectSchema(Map.of(
                        "subQuestions", arraySchema("可以独立检索和回答的子问题，按用户关心的顺序排列；需要 2-3 个。", 2, 3)
                ), List.of("subQuestions"))
        );
    }

    private AgentTool reportEvidenceTool() {
        Map<String, Object> statusSchema = stringSchema(
                "该子问题的证据充分性：SUPPORTED=证据足以回答；PARTIAL=证据只支撑一部分；"
                        + "MISSING_KNOWLEDGE=已检索但资料未覆盖（要求该子问题已经检索过）；"
                        + "MISSING_USER_CONDITION=缺少会改变结论的用户条件（未检索也可用）。");
        statusSchema.put("enum", List.of("SUPPORTED", "PARTIAL", "MISSING_KNOWLEDGE", "MISSING_USER_CONDITION"));
        Map<String, Object> assessmentItem = objectSchema(Map.of(
                "index", integerSchema("子问题编号（从 1 开始），与 plan_question 的顺序一致。", 1, 3),
                "status", statusSchema,
                "evidence", arraySchema(
                        "该子问题实际依据的证据，直接复制 search_knowledge 结果行里的 key=<fileMd5>#<chunkId>；"
                                + "SUPPORTED / PARTIAL 至少 1 个，MISSING_* 可以为空。", 0, 20),
                "reason", stringSchema("可选说明，例如缺少哪项用户条件或哪部分资料未覆盖。")
        ), List.of("index", "status"));
        return new AgentTool(
                "report_evidence",
                "为已登记的子问题记录证据充分性判断。必须在最终回答前调用，并且每个子问题恰好报告一次；"
                        + "evidence 只能填写 search_knowledge 结果行里 key= 后面的 fileMd5#chunkId。"
                        + "缺少用户条件用 MISSING_USER_CONDITION，检索过但缺少资料用 MISSING_KNOWLEDGE，"
                        + "没有证据时不要声称 SUPPORTED。",
                objectSchema(Map.of(
                        "assessments", arraySchema(
                                "每个已登记子问题一条判断，按子问题编号覆盖 1 到 N，不能遗漏或重复。",
                                assessmentItem, 1, 3)
                ), List.of("assessments"))
        );
    }

    public static AgentTool askClarificationTool() {
        return new AgentTool(
                "ask_clarification",
                "当检索后仍缺少会改变结论的用户条件（如所在地区、员工职级、适用版本、时间范围）时调用，向用户提出 1-3 个具体问题并结束本轮。不要询问资料或对话中已有的信息，不要用“请补充更多信息”这类泛泛问题，不要用澄清代替检索。",
                objectSchema(Map.of(
                        "questions", arraySchema("向用户提出的具体问题，每个问题说明用途；需要 1-3 个。", 1, 3)
                ), List.of("questions"))
        );
    }

    private AgentTool searchKnowledgeTool() {
        Map<String, Object> docTypeItem = stringSchema("文档类型枚举值。");
        docTypeItem.put("enum", DocumentComplianceType.allowedCodes());
        return new AgentTool(
                "search_knowledge",
                "在知识库中搜索与用户问题相关的文档片段。当用户问题的答案可能依赖已上传资料、企业/项目/产品/系统内部信息、专有名词、事实依据、定义、功能、使用方式、实现细节、背景、流程或引用来源时应调用；即使用户没有明确说“查询知识库”，只要问题不像纯通用常识也应先检索。普通问候、闲聊、纯创作、翻译、通用代码/常识问题，或用户明确要求不要查知识库时不要调用。"
                        + "银行/保险合规场景下，只有当用户明确说明文档类型或适用地区时才填写 docTypes/jurisdictions；没有明确说明时留空，不要猜测地区。"
                        + "检索只返回当前可检索版本（按生效日期、失效日期和废止标记过滤），不支持历史版本。"
                        + "检索结果里的 issuer 只是发布机构，appliesTo 才是资料适用机构；不要用 issuer 推断适用范围。",
                objectSchema(Map.of(
                        "query", stringSchema("用于知识库检索的查询语句。应保留用户原话中的核心实体、缩写和限定词，可包含原始问句和必要的等价改写；不要替换成固定关键词。"),
                        "topK", integerSchema("返回的片段数量，默认 5。"),
                        "subQuestionIndex", integerSchema("如果本轮已调用 plan_question，填写当前检索对应的子问题编号（从 1 开始）；单一问题不填。", 1, 3),
                        "docTypes", arraySchema(
                                "可选。用户明确要求的文档类型，只能从枚举中选择；没有明确要求时不要填写。",
                                docTypeItem, 0, 4),
                        "jurisdictions", arraySchema(
                                "可选。用户明确给出的适用地区（如上海、北京）。填地方地区时检索层会自动包含全国适用资料，不要额外把全国加进来；用户问全国范围时才填全国。没有明确给出时不要填写，不要猜测。",
                                0, 10)
                ), List.of("query"))
        );
    }

    private AgentTool generateSummaryTool() {
        Map<String, Object> docTypeItem = stringSchema(
                "可选。摘要要沿用的文档类型；默认继承上一次 search_knowledge，只有用户明确改变类型时才填写。");
        docTypeItem.put("enum", DocumentComplianceType.allowedCodes());
        return new AgentTool(
                "generate_summary",
                "对指定主题的知识库文档生成结构化摘要。适合用户要求整理、总结、归纳、提炼知识库内容时调用；"
                        + "内部检索默认沿用上一次 search_knowledge 的地区和文档类型过滤，避免摘要混入其他范围；"
                        + "本工具内部会二次调用大模型完成摘要，外层 ReAct 循环只应接收结果，不要把内部摘要过程当作新的工具计划。",
                objectSchema(Map.of(
                        "topic", stringSchema("需要从知识库中整理和总结的主题。"),
                        "maxDocs", integerSchema("用于生成摘要的最多相关片段数量，默认 5。"),
                        "docTypes", arraySchema(
                                "可选。摘要要沿用的文档类型；不填则继承上一次 search_knowledge 的条件。",
                                docTypeItem, 0, 4),
                        "jurisdictions", arraySchema(
                                "可选。摘要要沿用的适用地区；不填则继承上一次 search_knowledge 的条件。",
                                0, 10)
                ), List.of("topic"))
        );
    }

    private AgentTool submitFeedbackTool() {
        Map<String, Object> ratingSchema = stringSchema("用户对当前回答的评价，只能是 good 或 bad。");
        ratingSchema.put("enum", List.of("good", "bad"));
        return new AgentTool(
                "submit_feedback",
                "当用户明确表达对回答满意、不满意、点赞、点踩、纠错或要求记录反馈时调用，用于记录反馈以优化后续回答质量；不要在没有明确评价意图时推断调用。",
                objectSchema(Map.of(
                        "rating", ratingSchema,
                        "reason", stringSchema("用户给出的满意或不满意原因，可为空。")
                ), List.of("rating"))
        );
    }

    private AgentTool knowledgeStatsTool() {
        return new AgentTool(
                "knowledge_stats",
                "返回当前知识库的统计信息，包括 MySQL 文档总数、Elasticsearch 片段总数、索引存储量和最近更新时间。仅当用户询问知识库规模、文档数量、片段数量、更新时间或索引状态时调用。",
                objectSchema(Collections.emptyMap(), Collections.emptyList())
        );
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    private Map<String, Object> integerSchema(String description, int min, int max) {
        Map<String, Object> schema = integerSchema(description);
        schema.put("minimum", min);
        schema.put("maximum", max);
        return schema;
    }

    private static Map<String, Object> arraySchema(String description, int minItems, int maxItems) {
        return arraySchema(description, Map.of("type", "string"), minItems, maxItems);
    }

    private static Map<String, Object> arraySchema(String description, Map<String, Object> itemSchema,
                                                   int minItems, int maxItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", itemSchema);
        schema.put("description", description);
        schema.put("minItems", minItems);
        schema.put("maxItems", maxItems);
        return schema;
    }

    /**
     * 检索片段在提示词里的统一格式（search_knowledge 工具结果，离线评测的生成环节也复用这一格式）
     */
    public static String formatSearchResults(List<SearchResult> results) {
        return formatSearchResults(results, 1);
    }

    public static String formatSearchResults(List<SearchResult> results, int firstReferenceNumber) {
        if (results == null || results.isEmpty()) {
            return "未检索到相关知识库片段。";
        }

        StringBuilder output = new StringBuilder("检索到 ").append(results.size()).append(" 个知识库片段。")
                .append("请基于这些片段回答用户问题；不得声称知识库暂无相关信息。")
                .append("如果片段信息不足，请说明“基于已检索片段只能确认……”并标注来源编号；")
                .append("片段没有覆盖的内容明确说明资料中没有，不要为缺乏依据的句子硬凑来源编号。");
        if (results.stream().anyMatch(r -> r != null && hasParent(r))) {
            output.append("标有“上下文”的片段包含命中分块及其前后相邻内容，引用其中任何内容都使用该片段的编号。");
        }
        // 多个命中共用同一个父块时，父块只在第一次出现时给出
        Map<String, Integer> emittedParents = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            int referenceNumber = firstReferenceNumber + i;
            output.append("\n\n[").append(referenceNumber).append("] ");
            if (result.getFileName() != null && !result.getFileName().isBlank()) {
                output.append(result.getFileName()).append(" ");
            }
            output.append("(fileMd5=").append(result.getFileMd5())
                    .append(", chunkId=").append(result.getChunkId());
            // report_evidence 的证据要求是 fileMd5#chunkId：直接给出可复制的 key，避免模型自己拼分隔符被拒
            if (result.getFileMd5() != null && !result.getFileMd5().isBlank() && result.getChunkId() != null) {
                output.append(", key=").append(result.getFileMd5()).append('#').append(result.getChunkId());
            }
            if (result.getPageNumber() != null) {
                output.append(", page=").append(result.getPageNumber());
            }
            // 合规元数据供模型判断类型、适用地区和效力；旧资料没有标注时省略
            boolean complianceDoc = result.getDocType() != null && !result.getDocType().isBlank();
            if (complianceDoc) {
                output.append(", docType=").append(result.getDocType());
            }
            if (result.getJurisdiction() != null && !result.getJurisdiction().isBlank()) {
                output.append(", jurisdiction=").append(result.getJurisdiction());
            }
            if (result.getIssuer() != null && !result.getIssuer().isBlank()) {
                output.append(", issuer=").append(result.getIssuer());
            }
            if (result.getApplicableInstitutions() != null && !result.getApplicableInstitutions().isBlank()) {
                output.append(", appliesTo=").append(result.getApplicableInstitutions());
            }
            if (result.getEffectiveAt() != null && !result.getEffectiveAt().isBlank()) {
                output.append(", effectiveAt=").append(result.getEffectiveAt());
            }
            if (result.getExpiresAt() != null && !result.getExpiresAt().isBlank()) {
                output.append(", expiresAt=").append(result.getExpiresAt());
            }
            if (complianceDoc && result.getValidityStatus() != null && !result.getValidityStatus().isBlank()) {
                output.append(", validity=").append(result.getValidityStatus());
                if (result.getValidityLabel() != null && !result.getValidityLabel().isBlank()) {
                    output.append('(').append(result.getValidityLabel()).append(')');
                }
            }
            // 条款级切分结果供模型在回答中引用具体条款；历史/非合规资料没有条款信息时省略
            if (result.getArticlePath() != null && !result.getArticlePath().isBlank()) {
                output.append(", article=").append(result.getArticlePath());
            }
            if (result.getClauseNo() != null && !result.getClauseNo().isBlank()) {
                output.append(", clause=").append(result.getClauseNo());
            }
            if (result.getScore() != null) {
                output.append(", score=").append(String.format(Locale.ROOT, "%.4f", result.getScore()));
            }
            output.append(")\n");
            String matched = result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent();
            if (!hasParent(result)) {
                output.append(limitText(matched, 1200));
                continue;
            }
            Integer earlier = emittedParents.putIfAbsent(result.getParentKey(), referenceNumber);
            if (earlier == null) {
                output.append("上下文（命中分块及前后相邻内容）：\n").append(result.getParentContext());
            } else {
                output.append("命中分块：").append(limitText(matched, 300))
                        .append("\n（该分块所在的上下文已在 [").append(earlier).append("] 中给出）");
            }
        }
        return output.toString();
    }

    private static boolean hasParent(SearchResult result) {
        return result.getParentContext() != null && !result.getParentContext().isBlank() && result.getParentKey() != null;
    }

    private String formatKnowledgeStats(Map<String, Object> data) {
        return "知识库统计（共享库已发布范围，不按具体查询地区/日期过滤）："
                + "\n- 已发布文档数：" + data.get("documentCount")
                + "\n- 可检索片段数：" + data.get("fragmentCount")
                + "\n- ES 已删除片段数（全索引）：" + nullToDash(data.get("deletedFragmentCount"))
                + "\n- ES 存储大小(bytes，全索引)：" + nullToDash(data.get("storeSizeInBytes"))
                + "\n- 最近更新时间：" + nullToDash(data.get("latestUpdatedAt"));
    }

    private String getRequiredString(Map<String, Object> arguments, String name) {
        String value = getOptionalString(arguments, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private String getOptionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    /**
     * 可选字符串数组参数：null 返回空列表，数组里的 null/空白项忽略；模型偶尔传单个字符串时也按一项处理
     */
    private List<String> getOptionalStringList(Map<String, Object> arguments, String name) {
        Object raw = arguments.get(name);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            String single = String.valueOf(raw).trim();
            return single.isEmpty() ? List.of() : List.of(single);
        }
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private int getInt(Map<String, Object> arguments, String name, int defaultValue, int min, int max) {
        Object raw = arguments.get(name);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return defaultValue;
        }

        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            value = Integer.parseInt(String.valueOf(raw));
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String limitText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("工具调用缺少 userId，无法执行带权限的知识库或反馈操作");
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        ToolExecutionResult execute(Map<String, Object> arguments, String userId, Consumer<String> onChunk);
    }

    public record AgentTool(
            String name,
            String description,
            Map<String, Object> parameters
    ) {
    }

    public record ToolExecutionResult(
            String toolName,
            boolean success,
            String content,
            Map<String, Object> data,
            boolean streamedToUser
    ) {
        public ToolExecutionResult(String toolName,
                                   boolean success,
                                   String content,
                                   Map<String, Object> data) {
            this(toolName, success, content, data, false);
        }
    }
}
