# WP-1 复杂问题回答 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让复合问题先拆成带编号的子问题、逐项检索并由代码记录证据覆盖；让缺少关键用户条件时能提出澄清问题并结束本轮。

**Architecture:** 在现有 ReAct 工具循环上增加两个工具（`plan_question`、`ask_clarification`）和一个 `search_knowledge.subQuestionIndex` 参数。`ChatHandler` 持有每个生成任务的 `QuestionPlan` 台账，记录每个子问题的检索命中数；最终回答后由代码追加“子问题证据覆盖”。澄清复用现有“工具已流式输出 → 立即收尾”的路径，不增加模型轮次。单问题、检索、持久化和 WebSocket 协议都不变。

**Tech Stack:** Java 17、Spring Boot 3.4.2、JUnit 5、Mockito、Maven、Vue 3 + TypeScript、pnpm。

**Spec:** `docs/superpowers/specs/2026-10-19-wp1-complex-question-answering-design.md`

## Global Constraints

- 不提交、不推送，除非用户在当次任务里明确要求（AGENTS.md）。每个任务末尾只做“运行测试 + 报告”的检查点。
- 不重启后端。改完 Java 后用 `mvn -q -DskipTests compile` 触发热部署。
- 不改数据库表、ES mapping、WebSocket 消息协议、检索/重排代码和评测集。
- 单问题路径必须保持现有行为：不出现计划、不出现覆盖说明、工具调用次数不变。
- 模型不调用 `plan_question` 时，不得伪造覆盖说明。
- 所有面向用户的文本使用中文；不要添加 emoji。
- 不在任何文件、日志或回复里写入 `.env`、密钥和生产主机信息。

---

## File Structure

**生产代码**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/QuestionPlan.java` — 子问题计划、索引校验、命中台账、覆盖说明生成。
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/AgentToolRegistry.java` — 注册 `plan_question` / `ask_clarification`，给 `search_knowledge` 增加 `subQuestionIndex` schema。
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java` — 计划台账、编号校验、命中记账、澄清收尾、覆盖说明追加、清理。
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/LlmProviderRouter.java` — 系统提示词规则。
- Modify: `frontend/src/views/chat/modules/chat-message.vue` — 两个工具名标签。

**测试**
- Create: `src/test/java/com/yizhaoqi/smartpai/service/QuestionPlanTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/AgentToolRegistryPlanClarificationTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReActTestSupport.java`（测试基类，不被 surefire 直接执行）
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerPlanCoverageTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerClarificationTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerPlanValidationTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/LlmProviderRouterPromptTest.java`
- Run only: `ChatHandlerDeadlineTest`、`ChatHandlerReferenceNumberingTest`（现有回归）

---

## Task 1: 子问题计划值对象 QuestionPlan

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/QuestionPlan.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/QuestionPlanTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `static List<String> QuestionPlan.normalizeSubQuestions(Object raw)`
  - `static QuestionPlan QuestionPlan.fromToolData(Map<String, Object> data)`
  - `static Integer QuestionPlan.parseIndex(Object value)`
  - `void validateIndex(Integer index)`
  - `void recordHits(int index, int resultCount)`
  - `String buildCoverageFooter()`
  - `boolean isFooterAppended()`
  - `void markFooterAppended()`
  - `List<String> subQuestions()`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/yizhaoqi/smartpai/service/QuestionPlanTest.java`：

```java
package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionPlanTest {

    @Test
    void normalizeRejectsTooFewTooManyBlankAndDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("只有一个")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("一", "二", "三", "四")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("问题", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions(List.of("问题", "问题")));
        assertThrows(IllegalArgumentException.class,
                () -> QuestionPlan.normalizeSubQuestions("不是数组"));
    }

    @Test
    void recordHitsDistinguishesZeroFromUnsearchedAndSums() {
        QuestionPlan plan = QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("住宿标准", "超标审批", "报销流程")));

        plan.recordHits(1, 2);
        plan.recordHits(1, 3);
        plan.recordHits(2, 0);

        String footer = plan.buildCoverageFooter();
        assertTrue(footer.contains("子问题 1（住宿标准）：命中 5 条"));
        assertTrue(footer.contains("子问题 2（超标审批）：命中 0 条，资料未覆盖"));
        assertTrue(footer.contains("子问题 3（报销流程）：未检索，资料未覆盖"));
    }

    @Test
    void validateIndexRejectsNullZeroAndOutOfRange() {
        QuestionPlan plan = QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("A", "B")));
        assertThrows(IllegalArgumentException.class, () -> plan.validateIndex(null));
        assertThrows(IllegalArgumentException.class, () -> plan.validateIndex(0));
        assertThrows(IllegalArgumentException.class, () -> plan.validateIndex(3));
        assertEquals(1, QuestionPlan.parseIndex("1"));
    }

    @Test
    void footerIsAppendedOnlyOnce() {
        QuestionPlan plan = QuestionPlan.fromToolData(Map.of(
                "subQuestions", List.of("A", "B")));
        assertFalse(plan.isFooterAppended());
        plan.markFooterAppended();
        assertTrue(plan.isFooterAppended());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=QuestionPlanTest test`

Expected: 编译失败，提示找不到 `QuestionPlan`（这是预期的红灯）。

- [ ] **Step 3: 写最小实现**

创建 `src/main/java/com/yizhaoqi/smartpai/service/QuestionPlan.java`：

```java
package com.yizhaoqi.smartpai.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class QuestionPlan {

    private static final int MIN_SUB_QUESTIONS = 2;
    private static final int MAX_SUB_QUESTIONS = 3;

    private final List<String> subQuestions;
    private final int[] hits;
    private boolean footerAppended;

    private QuestionPlan(List<String> subQuestions) {
        this.subQuestions = List.copyOf(subQuestions);
        this.hits = new int[subQuestions.size()];
        Arrays.fill(this.hits, -1);
    }

    static QuestionPlan fromToolData(Map<String, Object> data) {
        Object raw = data == null ? null : data.get("subQuestions");
        return new QuestionPlan(normalizeSubQuestions(raw));
    }

    static List<String> normalizeSubQuestions(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("subQuestions 必须是数组");
        }
        if (list.size() < MIN_SUB_QUESTIONS || list.size() > MAX_SUB_QUESTIONS) {
            throw new IllegalArgumentException("subQuestions 需要 2-3 个");
        }
        List<String> normalized = new ArrayList<>(list.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String text = item instanceof String value ? value.trim() : "";
            if (text.isEmpty()) {
                throw new IllegalArgumentException("subQuestions 不能包含空问题");
            }
            if (!seen.add(text)) {
                throw new IllegalArgumentException("subQuestions 不能重复");
            }
            normalized.add(text);
        }
        return normalized;
    }

    static Integer parseIndex(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            if (number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("subQuestionIndex 必须是整数");
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("subQuestionIndex 必须是整数");
        }
    }

    void validateIndex(Integer index) {
        if (index == null) {
            throw new IllegalArgumentException("已制定子问题计划，检索时必须填写 subQuestionIndex");
        }
        if (index < 1 || index > subQuestions.size()) {
            throw new IllegalArgumentException("subQuestionIndex 必须在 1-" + subQuestions.size() + " 之间");
        }
    }

    void recordHits(int index, int resultCount) {
        validateIndex(index);
        int position = index - 1;
        if (hits[position] < 0) {
            hits[position] = 0;
        }
        hits[position] += Math.max(resultCount, 0);
    }

    String buildCoverageFooter() {
        StringBuilder footer = new StringBuilder("\n\n---\n**子问题证据覆盖**\n");
        for (int i = 0; i < subQuestions.size(); i++) {
            footer.append("- 子问题 ").append(i + 1).append("（").append(subQuestions.get(i)).append("）：");
            if (hits[i] < 0) {
                footer.append("未检索，资料未覆盖");
            } else if (hits[i] == 0) {
                footer.append("命中 0 条，资料未覆盖");
            } else {
                footer.append("命中 ").append(hits[i]).append(" 条");
            }
            footer.append('\n');
        }
        return footer.toString();
    }

    boolean isFooterAppended() {
        return footerAppended;
    }

    void markFooterAppended() {
        footerAppended = true;
    }

    List<String> subQuestions() {
        return subQuestions;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=QuestionPlanTest test`

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 编译检查**

Run: `mvn -q -DskipTests compile`

Expected: 退出码 0。

- [ ] **Step 6: 检查点**

运行测试并报告结果；不要提交。

---

## Task 2: 注册 plan_question / ask_clarification 并给 search_knowledge 加编号参数

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/AgentToolRegistry.java:17-25,55-66,191-200,234-255`
- Modify: `frontend/src/views/chat/modules/chat-message.vue:75-80`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/AgentToolRegistryPlanClarificationTest.java`

**Interfaces:**
- Consumes: `QuestionPlan.normalizeSubQuestions(Object)`（Task 1）。
- Produces:
  - 工具名 `plan_question`，`ToolExecutionResult.data().get("subQuestions")` 为 `List<String>`。
  - 工具名 `ask_clarification`，`ToolExecutionResult.data().get("questions")` 为 `List<String>`。
  - `search_knowledge` 的 JSON schema 包含 `subQuestionIndex`（integer，1-3）。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/yizhaoqi/smartpai/service/AgentToolRegistryPlanClarificationTest.java`：

```java
package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.search.ParentContextExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentToolRegistryPlanClarificationTest {

    private AgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentToolRegistry(
                mock(HybridSearchService.class),
                mock(DeepSeekClient.class),
                mock(StringRedisTemplate.class),
                mock(ElasticsearchClient.class),
                mock(FileUploadRepository.class),
                mock(ParentContextExpander.class));
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
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=AgentToolRegistryPlanClarificationTest test`

Expected: 测试失败，`executeTool("plan_question", ...)` 抛 `未注册的工具: plan_question`，schema 断言失败。

- [ ] **Step 3: 修改 AgentToolRegistry 的导入和注册表**

在 `src/main/java/com/yizhaoqi/smartpai/service/AgentToolRegistry.java` 顶部导入区加入：

```java
import java.util.ArrayList;
```

把构造器里的 `tools` 和 `handlers` 改成：

```java
        this.tools = List.of(
                planQuestionTool(),
                searchKnowledgeTool(),
                generateSummaryTool(),
                submitFeedbackTool(),
                askClarificationTool(),
                knowledgeStatsTool()
        );
        this.handlers = Map.of(
                "plan_question", this::executePlanQuestion,
                "search_knowledge", this::executeSearchKnowledge,
                "generate_summary", this::executeGenerateSummary,
                "submit_feedback", this::executeSubmitFeedback,
                "ask_clarification", this::executeAskClarification,
                "knowledge_stats", this::executeKnowledgeStats
        );
```

- [ ] **Step 4: 增加两个工具的 handler 和工厂方法**

在 `executeSearchKnowledge` 之前加入：

```java
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
```

在 `searchKnowledgeTool()` 之前或之后加入两个工厂方法：

```java
    private AgentTool planQuestionTool() {
        return new AgentTool(
                "plan_question",
                "当用户问题包含两个或以上可以独立回答的部分时，先调用本工具登记子问题清单。每个子问题应能单独检索和回答；单一问题不要调用。调用后必须为每个子问题分别调用 search_knowledge，并在 subQuestionIndex 填写对应编号。",
                objectSchema(Map.of(
                        "subQuestions", arraySchema("可以独立检索和回答的子问题，按用户关心的顺序排列；需要 2-3 个。", 2, 3)
                ), List.of("subQuestions"))
        );
    }

    private AgentTool askClarificationTool() {
        return new AgentTool(
                "ask_clarification",
                "当检索后仍缺少会改变结论的用户条件（如所在地区、员工职级、适用版本、时间范围）时调用，向用户提出 1-3 个具体问题并结束本轮。不要询问资料或对话中已有的信息，不要用“请补充更多信息”这类泛泛问题，不要用澄清代替检索。",
                objectSchema(Map.of(
                        "questions", arraySchema("向用户提出的具体问题，每个问题说明用途；需要 1-3 个。", 1, 3)
                ), List.of("questions"))
        );
    }
```

- [ ] **Step 5: 给 search_knowledge schema 加 subQuestionIndex**

把 `searchKnowledgeTool()` 的 `objectSchema` 改成：

```java
                objectSchema(Map.of(
                        "query", stringSchema("用于知识库检索的查询语句。应保留用户原话中的核心实体、缩写和限定词，可包含原始问句和必要的等价改写；不要替换成固定关键词。"),
                        "topK", integerSchema("返回的片段数量，默认 5。"),
                        "subQuestionIndex", integerSchema("如果本轮已调用 plan_question，填写当前检索对应的子问题编号（从 1 开始）；单一问题不填。", 1, 3)
                ), List.of("query"))
```

- [ ] **Step 6: 增加 schema 辅助方法**

在 `integerSchema(String description)` 之后加入：

```java
    private Map<String, Object> integerSchema(String description, int min, int max) {
        Map<String, Object> schema = integerSchema(description);
        schema.put("minimum", min);
        schema.put("maximum", max);
        return schema;
    }

    private Map<String, Object> arraySchema(String description, int minItems, int maxItems) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", Map.of("type", "string"));
        schema.put("description", description);
        schema.put("minItems", minItems);
        schema.put("maxItems", maxItems);
        return schema;
    }
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -Dtest=AgentToolRegistryPlanClarificationTest test`

Expected: `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 8: 前端加两个工具名标签**

把 `frontend/src/views/chat/modules/chat-message.vue` 的 `toolNameLabels` 改成：

```ts
const toolNameLabels: Record<string, string> = {
  plan_question: '拆解问题',
  search_knowledge: '检索知识库',
  generate_summary: '生成知识摘要',
  submit_feedback: '记录反馈',
  ask_clarification: '请求补充信息',
  knowledge_stats: '读取知识库统计'
};
```

- [ ] **Step 9: 前端 lint 与类型检查**

Run:

```bash
cd frontend && pnpm exec eslint src/views/chat/modules/chat-message.vue
cd frontend && pnpm typecheck
```

Expected: 两条命令退出码 0。

- [ ] **Step 10: 检查点**

运行测试并报告结果；不要提交。

---

## Task 3: ChatHandler 计划台账、编号校验与覆盖说明

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:79-83,285-290,323-338,356-407,668-680`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReActTestSupport.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerPlanCoverageTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerPlanValidationTest.java`

**Interfaces:**
- Consumes:
  - `QuestionPlan.fromToolData(Map<String,Object>)`
  - `QuestionPlan.parseIndex(Object)`
  - `QuestionPlan.validateIndex(Integer)`
  - `QuestionPlan.recordHits(int,int)`
  - `QuestionPlan.buildCoverageFooter()`
  - `QuestionPlan.isFooterAppended()` / `markFooterAppended()`
- Produces:
  - `ChatHandler` 内部 `generationPlans` 台账。
  - 最终回答末尾的 `**子问题证据覆盖**` 段落。
  - 非法编号返回工具错误，不执行检索。

- [ ] **Step 1: 创建测试基类**

创建 `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReActTestSupport.java`：

```java
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
import org.springframework.web.socket.WebSocketSession;

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
                new AgentToolRegistry.AgentTool("search_knowledge", "检索知识库", Map.of("type", "object")))));

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
}
```

- [ ] **Step 2: 写覆盖说明的失败测试**

创建 `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerPlanCoverageTest.java`：

```java
package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatHandlerPlanCoverageTest extends ChatHandlerReActTestSupport {

    @Test
    void appendsCoverageFooterWithHitsAndUncoveredSubQuestions() {
        enqueueToolCall("plan_question", Map.of(
                "subQuestions", List.of("住宿标准是什么？", "超标如何审批？")));
        enqueueToolCalls(List.of(
                new ToolCallSpec("search_knowledge", Map.of("query", "住宿标准", "subQuestionIndex", 1)),
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
        assertTrue(streamed.contains("**子问题证据覆盖**"));
        assertTrue(streamed.contains("子问题 1（住宿标准是什么？）：命中 1 条"));
        assertTrue(streamed.contains("子问题 2（超标如何审批？）：命中 0 条，资料未覆盖"));
        assertTrue(recordedAnswer().contains("**子问题证据覆盖**"));
    }
}
```

- [ ] **Step 3: 写编号校验的失败测试**

创建 `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerPlanValidationTest.java`：

```java
package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        when(tools.executeTool(eq("plan_question"), anyMap(), anyString(), any())).thenReturn(
                new AgentToolRegistry.ToolExecutionResult(
                        "plan_question", true, "已记录",
                        Map.of("subQuestions", List.of("A", "B")), false));

        handler().processMessage("7", "复合问题", mock(WebSocketSession.class), "conv-1");

        assertEquals(3, requestBodies.size());
        assertTrue(requestBodies.get(2).contains("不能重复制定"));
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
}
```

- [ ] **Step 4: 运行测试确认失败**

Run:

```bash
mvn -Dtest=ChatHandlerPlanCoverageTest test
mvn -Dtest=ChatHandlerPlanValidationTest test
```

Expected: 覆盖说明测试失败（最终内容没有 `**子问题证据覆盖**`）；校验测试失败（非法编号仍执行了检索，或错误文案没出现）。

- [ ] **Step 5: 在 ChatHandler 增加台账字段**

在 `ChatHandler` 的 `generationParentContexts` 字段之后加入：

```java
    // 复合问题的子问题计划与检索命中台账：generationId -> plan
    private final Map<String, QuestionPlan> generationPlans = new ConcurrentHashMap<>();
```

- [ ] **Step 6: 在最终回答分支追加覆盖说明**

把 `runReActLoop` 中 `if (turn.toolCalls().isEmpty())` 分支改成：

```java
            if (turn.toolCalls().isEmpty()) {
                appendCoverageFooterIfNeeded(userId, generationId, conversationId, responseBuilders.get(generationId));
                finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                        responseBuilders.get(generationId),
                        new LlmProviderRouter.StreamCompletion(turn.finishReason(), totalPromptTokens, totalCompletionTokens, turn.content().length()));
                return;
            }
```

把轮次用尽后的 `finalizeResponse` 之前也加上：

```java
        appendCoverageFooterIfNeeded(userId, generationId, conversationId, responseBuilders.get(generationId));
        finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                responseBuilders.get(generationId),
                new LlmProviderRouter.StreamCompletion(finalTurn.finishReason(), totalPromptTokens, totalCompletionTokens, finalTurn.content().length()));
```

- [ ] **Step 7: 在 executeToolForReAct 增加校验、记账和覆盖方法**

在 `executeToolForReAct` 的 `try {` 之后、`logger.info(...)` 之前加入。必须放在 try 内，让参数错误走现有的 catch 分支、作为工具错误返回给模型：

```java
        try {
            validatePlanToolCall(generationId, toolCall);
            logger.info("ReAct 执行 Agent Tool: ...");
```

即把 `validatePlanToolCall` 作为 try 块的第一条语句；`sendToolCallStatus(..., "executing")` 和 `AtomicBoolean summaryStreamStarted` 保持在 try 外不动。

在 `AgentToolRegistry.ToolExecutionResult toolResult = ...;` 之后加入：

```java
            if ("plan_question".equals(toolCall.name())) {
                QuestionPlan plan = QuestionPlan.fromToolData(toolResult.data());
                generationPlans.put(generationId, plan);
                logger.info("已登记子问题计划: generationId={}, count={}", generationId, plan.subQuestions().size());
            } else if ("search_knowledge".equals(toolCall.name())) {
                QuestionPlan plan = generationPlans.get(generationId);
                if (plan != null) {
                    Integer index = QuestionPlan.parseIndex(toolCall.arguments().get("subQuestionIndex"));
                    int hitCount = countSearchResults(toolResult);
                    plan.recordHits(index, hitCount);
                    logger.info("子问题检索命中: generationId={}, index={}, hits={}", generationId, index, hitCount);
                }
            }
```

本任务不改 return 部分，保持现有：

```java
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "success");
            return new ExecutedToolResult(content, toolResult.streamedToUser());
```

澄清收尾放到 Task 4，保证 Task 4 的测试先红后绿。

在 `executeToolForReAct` 之后加入三个私有方法：

```java
    private void appendCoverageFooterIfNeeded(String userId,
                                              String generationId,
                                              String conversationId,
                                              StringBuilder responseBuilder) {
        QuestionPlan plan = generationPlans.get(generationId);
        if (plan == null || plan.isFooterAppended()) {
            return;
        }
        if (responseBuilder == null || responseBuilder.toString().isBlank()) {
            return;
        }
        String footer = plan.buildCoverageFooter();
        appendStreamChunk(userId, generationId, conversationId, footer);
        plan.markFooterAppended();
        logger.info("已追加子问题证据覆盖: generationId={}, footerChars={}", generationId, footer.length());
    }

    private void validatePlanToolCall(String generationId, LlmProviderRouter.ToolCallDecision toolCall) {
        QuestionPlan plan = generationPlans.get(generationId);
        if ("plan_question".equals(toolCall.name())) {
            if (plan != null) {
                throw new IllegalArgumentException("本轮已制定子问题计划，不能重复制定；请继续检索或直接回答");
            }
            return;
        }
        if (!"search_knowledge".equals(toolCall.name())) {
            return;
        }
        Integer index = QuestionPlan.parseIndex(toolCall.arguments().get("subQuestionIndex"));
        if (plan == null) {
            if (index != null) {
                throw new IllegalArgumentException("请先调用 plan_question 制定子问题计划，再填写 subQuestionIndex");
            }
            return;
        }
        plan.validateIndex(index);
    }

    private int countSearchResults(AgentToolRegistry.ToolExecutionResult result) {
        Object raw = result.data() == null ? null : result.data().get("results");
        return raw instanceof List<?> list ? list.size() : 0;
    }
```

- [ ] **Step 8: 清理台账**

在 `cleanupGenerationState` 里加入：

```java
        generationPlans.remove(generationId);
```

- [ ] **Step 9: 运行测试确认通过**

Run:

```bash
mvn -Dtest=ChatHandlerPlanCoverageTest test
mvn -Dtest=ChatHandlerPlanValidationTest test
```

Expected: 两个测试类的用例全部通过。

- [ ] **Step 10: 编译检查**

Run: `mvn -q -DskipTests compile`

Expected: 退出码 0。

- [ ] **Step 11: 检查点**

运行测试并报告结果；不要提交。

---

## Task 4: 澄清工具立即收尾

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:356-407`（Task 3 已改过的方法）
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerClarificationTest.java`

**Interfaces:**
- Consumes: `AgentToolRegistry` 的 `ask_clarification`（Task 2）；`ChatHandlerReActTestSupport`（Task 3）。
- Produces: `ask_clarification` 只触发一次模型请求，问题文本作为助手消息持久化，完成事件为 `finished`。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerClarificationTest.java`：

```java
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=ChatHandlerClarificationTest test`

Expected: 失败。`ask_clarification` 没有被提前收尾，循环继续发起第二次模型请求，`requestBodies.size()` 不等于 1。

- [ ] **Step 3: 实现澄清收尾**

把 `executeToolForReAct` 中现有的：

```java
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "success");
            return new ExecutedToolResult(content, toolResult.streamedToUser());
```

改成：

```java
            boolean clarify = "ask_clarification".equals(toolCall.name());
            if (clarify) {
                appendStreamChunk(userId, generationId, conversationId, content);
                logger.info("已输出澄清问题: generationId={}", generationId);
            }
            sendToolCallStatus(userId, generationId, conversationId, toolCall, "success");
            return new ExecutedToolResult(content, toolResult.streamedToUser() || clarify);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=ChatHandlerClarificationTest test`

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 回归现有 ChatHandler 测试**

Run:

```bash
mvn -Dtest=ChatHandlerDeadlineTest,ChatHandlerReferenceNumberingTest test
```

Expected: 现有用例全部通过。

- [ ] **Step 6: 检查点**

运行测试并报告结果；不要提交。

---

## Task 5: 系统提示词规则

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/LlmProviderRouter.java:150-167`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/LlmProviderRouterPromptTest.java`

**Interfaces:**
- Consumes: 工具名 `plan_question`、`search_knowledge.subQuestionIndex`、`ask_clarification`。
- Produces: ReAct 系统提示词包含复合问题、覆盖和澄清规则。

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/yizhaoqi/smartpai/service/LlmProviderRouterPromptTest.java`：

```java
package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.resilience.ModelCallGuard;
import com.yizhaoqi.smartpai.client.resilience.ModelWebClientFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ModelCallProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LlmProviderRouterPromptTest {

    @Test
    void reactPromptDescribesPlanCoverageAndClarification() {
        ModelCallProperties modelCallProperties = new ModelCallProperties();
        LlmProviderRouter router = new LlmProviderRouter(
                new AiProperties(),
                mock(RateLimitService.class),
                mock(UsageQuotaService.class),
                mock(ModelProviderConfigService.class),
                new ObjectMapper(),
                mock(ModelWebClientFactory.class),
                mock(ModelCallGuard.class),
                modelCallProperties);

        List<Map<String, Object>> messages = router.buildReActMessages("复合问题", "", List.of(), "");
        String system = String.valueOf(messages.get(0).get("content"));

        assertTrue(system.contains("plan_question"));
        assertTrue(system.contains("subQuestionIndex"));
        assertTrue(system.contains("ask_clarification"));
        assertTrue(system.contains("部分子问题没有证据时"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -Dtest=LlmProviderRouterPromptTest test`

Expected: 失败，提示 `plan_question` 等字符串不在系统提示词里。

- [ ] **Step 3: 修改提示词**

把 `LlmProviderRouter.buildReActMessages` 中这段：

```java
        sysBuilder.append("本系统是「知识库优先」的问答助手：你的首要职责是基于本系统已收录的资料回答用户。除非命中下方明确的白名单，否则**每一个用户问题都必须先调用 search_knowledge**，再基于检索结果作答。\n\n")
```

改成：

```java
        sysBuilder.append("本系统是「知识库优先」的问答助手：你的首要职责是基于本系统已收录的资料回答用户。除非命中下方明确的白名单，否则**每一个用户问题都必须先调用 search_knowledge**，再基于检索结果作答；复合问题先调用 plan_question 制定子问题计划，再逐项调用 search_knowledge。\n\n")
```

把“回答与异常处理”这一段：

```java
                .append("回答与异常处理：\n")
                .append("- 只要 search_knowledge 返回了片段，必须基于片段作答并按来源编号标注，禁止回答「知识库暂无相关信息」。\n")
                .append("- 片段只覆盖了问题的一部分时，只回答片段能支撑的内容；片段没有覆盖的部分明确告诉用户「资料中没有相关说明」，不要为缺乏依据的句子硬凑来源编号。\n")
                .append("- 只有工具明确返回零片段时，才说明暂无相关材料并提示用户补充线索。\n")
                .append("- 工具失败时根据错误信息决定下一步（重试 / 换 query / 继续推理），不要直接中断。\n")
                .append("- 如需记录反馈或查看知识库统计，通过 tool_calls 调用对应工具。\n")
                .append("拿到 tool 结果后继续推理并给出最终回答。\n\n");
```

改成：

```java
                .append("回答与异常处理：\n")
                .append("- 只要 search_knowledge 返回了片段，必须基于片段作答并按来源编号标注，禁止回答「知识库暂无相关信息」。\n")
                .append("- 片段只覆盖了问题的一部分时，只回答片段能支撑的内容；片段没有覆盖的部分明确告诉用户「资料中没有相关说明」，不要为缺乏依据的句子硬凑来源编号。\n")
                .append("- 只有所有检索都返回零片段时，才说明暂无相关材料；部分子问题没有证据时，只回答有证据的部分，并明确说明其余部分资料未覆盖。\n")
                .append("- 工具失败时根据错误信息决定下一步（重试 / 换 query / 继续推理），不要直接中断。\n")
                .append("- 如需记录反馈或查看知识库统计，通过 tool_calls 调用对应工具。\n")
                .append("复合问题与证据覆盖：\n")
                .append("- 如果用户问题包含两个及以上可以独立回答的部分，先调用 plan_question 登记 2-3 个子问题；不要把多个子问题塞进一次检索。\n")
                .append("- 制定计划后，必须为每个子问题分别调用 search_knowledge，并在 subQuestionIndex 填写对应编号；全部子问题检索后再给出最终回答。\n")
                .append("- 单一问题不要调用 plan_question。\n")
                .append("- 没有检索到片段或没有检索的子问题，必须在最终回答中明确说明「这部分资料未覆盖」，不得说成已解决，也不得硬凑来源编号。\n")
                .append("澄清：\n")
                .append("- 检索后如果仍缺少会改变结论的用户条件（如所在地区、员工职级、适用版本、时间范围），调用 ask_clarification 提出 1-3 个具体问题，并在问题里说明用途。\n")
                .append("- 不要询问资料或对话里已有的信息；不要用「请补充更多信息」这类泛泛问题；不要用澄清代替检索。\n")
                .append("- 调用 ask_clarification 后本轮结束，等待用户回答；不要在同一轮继续输出答案。\n")
                .append("拿到 tool 结果后继续推理并给出最终回答。\n\n");
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -Dtest=LlmProviderRouterPromptTest test`

Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`。

- [ ] **Step 5: 编译检查**

Run: `mvn -q -DskipTests compile`

Expected: 退出码 0。

- [ ] **Step 6: 检查点**

运行测试并报告结果；不要提交。

---

## Task 6: 全量验证与浏览器验收

**Files:**
- 不新增文件；运行测试、前端检查和浏览器验证。

**Interfaces:**
- Consumes: Task 1-5 的全部产物。
- Produces: 可复现的验证记录。

- [ ] **Step 1: 运行全部新增测试与现有回归**

Run:

```bash
mvn -Dtest=QuestionPlanTest,AgentToolRegistryPlanClarificationTest,ChatHandlerPlanCoverageTest,ChatHandlerClarificationTest,ChatHandlerPlanValidationTest,LlmProviderRouterPromptTest,ChatHandlerDeadlineTest,ChatHandlerReferenceNumberingTest test
```

Expected: 所有用例通过，0 failures、0 errors。

- [ ] **Step 2: 编译后端触发热部署**

Run: `mvn -q -DskipTests compile`

Expected: 退出码 0。按 AGENTS.md 不重启后端；如果运行中的后端没有热部署，先向用户确认，不要自行重启。

- [ ] **Step 3: 前端 lint 与类型检查**

Run:

```bash
cd frontend && pnpm exec eslint src/views/chat/modules/chat-message.vue
cd frontend && pnpm typecheck
```

Expected: 两条命令退出码 0。

- [ ] **Step 4: 确认本地运行状态**

检查：

- 前端 `http://localhost:9527` 可访问；
- 后端 `http://localhost:8081` 可访问；
- 浏览器已登录。

如果任意一个没有运行，先问用户是否需要启动；不要自行重启后端。

- [ ] **Step 5: 浏览器验证复合问题**

打开 `http://localhost:9527/#/chat`，提问一个复合问题，例如：

```text
住宿标准是什么？超标怎么审批？
```

Expected（同时满足）：

- 工具事件出现“拆解问题”；
- 每个子问题各出现一次“检索知识库”；
- 最终回答以“**子问题证据覆盖**”结尾，列出每个子问题的命中数；
- 引用编号可点击，引用详情能打开；
- 浏览器 console 无新增错误。
- 后端日志出现 `已登记子问题计划`、`子问题检索命中`、`已追加子问题证据覆盖`。

如果模型没有调用 `plan_question`，记录实际工具调用；把 `plan_question` 规则移到“强制检索原则”之前，重新编译后再测。不要在没有观察到计划的情况下声称通过。

- [ ] **Step 6: 浏览器验证澄清**

提问一个缺少关键条件的问题，例如：

```text
住宿标准是多少？
```

前提是知识库资料按城市或职级区分。如果本地没有这类资料，先问用户是否允许上传一份临时测试文档；用户同意后再上传，内容示例：

```text
《差旅报销制度》
北京/上海：住宿上限 600 元/晚；其他城市：400 元/晚。
P6 及以上可乘高铁一等座；其他职级二等座。
超标需部门负责人审批。
```

Expected：

- 工具事件出现“请求补充信息”；
- 助手只输出 1-3 个具体问题，本轮结束，不继续生成答案；
- 用户回答后能继续正常问答；
- 后端日志出现 `已输出澄清问题`。

如果模型没有调用 `ask_clarification`，记录实际输出；检查资料是否真的需要用户条件，再调整提示词。不要把“模型直接猜了一个答案”当作通过。

- [ ] **Step 7: 浏览器验证单问题回归**

提问一个单一问题，例如：

```text
这个项目的重排序用的是什么模型？
```

Expected：

- 不出现“拆解问题”工具事件；
- 不出现“**子问题证据覆盖**”；
- 回答、引用和引用详情与改动前一致；
- 工具调用次数与现有行为一致。

- [ ] **Step 8: 全量测试（按仓库现有方式）**

Run: `mvn test`

Expected: 现有测试全部通过。Spring 上下文测试需要 MinIO 密钥；从本机 `.env` 取值映射到 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`，不要在日志或回复里打印密钥。若出现与本次改动无关的既有失败，记录失败用例名和改动前的基线，不要把它们算作本次通过。

- [ ] **Step 9: 检查点**

汇总以下证据，不要提交：

- 新增测试与回归测试的命令和结果；
- `mvn test` 结果或既有失败的对照；
- 前端 lint / typecheck 结果；
- 三个浏览器场景的实际观察（工具事件、最终消息、日志、console）；
- 如果真实模型没有按预期触发，明确写“未通过”和实际行为。

---

## Self-Review

**Spec coverage**
- 3.1 三个工具：Task 2（schema、handler、注册）。
- 3.2 计划与覆盖台账：Task 1（QuestionPlan）、Task 3（ChatHandler 台账、编号校验）。
- 3.3 澄清立即收尾：Task 4。
- 3.4 覆盖说明：Task 3。
- 3.5 提示词：Task 5。
- 3.6 前端标签：Task 2 Step 8-9。
- 5 边界与失败处理：Task 1 的校验、Task 3 的非法编号/空回答检查、Task 4 的提前收尾。
- 6 测试与验收：Task 3/4/5 的测试 + Task 6 的浏览器和全量验证。
- 非目标：Global Constraints 明确不碰表、ES、协议、评测集、检索。

**Placeholder scan**
- 没有 TBD/TODO/“自行补充”；每个代码步骤都有完整代码；每条命令都有预期结果。
- 浏览器验收给了具体问题、预期事件、日志关键字和失败处理。

**Type consistency**
- `QuestionPlan.normalizeSubQuestions(Object)`、`fromToolData(Map<String,Object>)`、`parseIndex(Object)`、`validateIndex(Integer)`、`recordHits(int,int)`、`buildCoverageFooter()`、`isFooterAppended()`、`markFooterAppended()`、`subQuestions()` 在 Task 1 定义，Task 2/3 按同名调用。
- `ToolExecutionResult.data()` 的键统一为 `subQuestions`、`questions`、`results`。
- `ChatHandler` 的 `generationPlans` 类型为 `Map<String, QuestionPlan>`。
- 覆盖说明中的编号从 1 开始，与 `subQuestionIndex` 一致。

**与 spec 的差异**
- spec 里写的是 `ChatHandler` 内部类 `GenerationPlan`；计划里拆成同包的 `QuestionPlan` 文件。职责和字段一致，便于独立测试。
- spec 的子问题上限是 3；计划沿用 3，并在工具 schema 的 `maximum` 里固定。
