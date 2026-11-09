package com.yizhaoqi.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索前的问题拆分判定：由模型决定要不要拆，模型说不用拆就返回空列表、按原问题检索。
 * 解析容错：允许直接给数组或 {"sub_questions": [...]}，允许 ```json 代码块和前后说明文字；
 * 少拆或多写都不强制——只认能独立检索的 2~3 个问句，1 个视为不拆。
 */
public final class QuestionPlanner {

    static final int MAX_SUB_QUESTIONS = 3;

    static final String SYSTEM_PROMPT = """
            你是 RAG 检索前的问题分析器。判断用户问题是否需要在检索前拆成多个子问题：
            - 问题同时要求从多个相互独立的文件、制度或主题里找信息，且各自能独立检索时（例如"对比 A 与 B"、"分别说明 X 和 Y"），拆成 2 到 3 个完整问句；
            - 单一事实、单一主题、或者子问题前后依赖、无法独立检索的问题不要拆，输出空数组；
            - 子问题保留原问题里的机构、产品、条款名称等限定词，不要引入原问题没有的信息。
            只输出一个 JSON 对象，不要输出其他内容：{"sub_questions": ["子问题1", "子问题2"]}
            不需要拆分时输出：{"sub_questions": []}
            """;

    private QuestionPlanner() {
    }

    /**
     * @return 子问题列表；模型判断不需要拆分、或输出无法解析时为空列表
     * @throws IllegalArgumentException 输出既不是数组也不是带 sub_questions 的对象
     */
    public static List<String> parse(String content, ObjectMapper objectMapper) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String json = extractJson(content);
        if (json == null) {
            throw new IllegalArgumentException("拆分判定输出里没有 JSON");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("拆分判定输出不是合法 JSON", e);
        }
        if (node == null) {
            return List.of();
        }
        JsonNode array = node.isArray() ? node : firstArray(node);
        if (array == null) {
            throw new IllegalArgumentException("拆分判定输出缺少 sub_questions 数组");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                continue;
            }
            String question = item.asText().trim();
            if (!question.isEmpty()) {
                unique.add(question);
            }
            if (unique.size() >= MAX_SUB_QUESTIONS) {
                break;
            }
        }
        return unique.size() >= 2 ? new ArrayList<>(unique) : List.of();
    }

    private static JsonNode firstArray(JsonNode node) {
        for (String field : List.of("sub_questions", "subQuestions", "questions")) {
            JsonNode candidate = node.get(field);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 去掉代码块标记和前后说明文字，取第一个 '[' 到最后一个 ']'；没有数组时取第一个 '{' 到最后一个 '}'
     */
    private static String extractJson(String content) {
        int arrayStart = content.indexOf('[');
        int arrayEnd = content.lastIndexOf(']');
        int objectStart = content.indexOf('{');
        int objectEnd = content.lastIndexOf('}');
        boolean hasArray = arrayStart >= 0 && arrayEnd > arrayStart;
        boolean hasObject = objectStart >= 0 && objectEnd > objectStart;
        if (hasArray && (!hasObject || arrayStart < objectStart)) {
            return content.substring(arrayStart, arrayEnd + 1);
        }
        if (hasObject) {
            return content.substring(objectStart, objectEnd + 1);
        }
        return null;
    }
}
