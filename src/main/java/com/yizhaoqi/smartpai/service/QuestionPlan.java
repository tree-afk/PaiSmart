package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class QuestionPlan {

    private static final int MIN_SUB_QUESTIONS = 2;
    private static final int MAX_SUB_QUESTIONS = 3;

    enum EvidenceStatus {
        SUPPORTED("已支撑"),
        PARTIAL("部分支撑"),
        MISSING_KNOWLEDGE("资料未覆盖"),
        MISSING_USER_CONDITION("缺少用户条件");

        private final String label;

        EvidenceStatus(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        static EvidenceStatus parse(Object value) {
            String text = value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            for (EvidenceStatus status : values()) {
                if (status.name().equals(text)) {
                    return status;
                }
            }
            throw new IllegalArgumentException(
                    "status 只允许 SUPPORTED / PARTIAL / MISSING_KNOWLEDGE / MISSING_USER_CONDITION");
        }
    }

    record EvidenceAssessment(int index, EvidenceStatus status, List<String> evidence, String reason) {
        EvidenceAssessment {
            evidence = List.copyOf(evidence);
        }
    }

    private final List<String> subQuestions;
    private final List<Set<String>> evidenceKeys;
    private final boolean[] searched;
    private final Map<Integer, EvidenceAssessment> assessments = new LinkedHashMap<>();
    private boolean footerAppended;

    private QuestionPlan(List<String> subQuestions) {
        this.subQuestions = List.copyOf(subQuestions);
        List<Set<String>> keys = new ArrayList<>(subQuestions.size());
        for (int i = 0; i < subQuestions.size(); i++) {
            keys.add(new LinkedHashSet<>());
        }
        this.evidenceKeys = List.copyOf(keys);
        this.searched = new boolean[subQuestions.size()];
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

    /**
     * 记录某个子问题本轮检索到的证据：按 fileMd5#chunkId 去重，并标记该子问题已检索。
     *
     * @return 该子问题累计的去重证据数
     */
    int recordSearch(int index, List<SearchResult> results) {
        validateIndex(index);
        int position = index - 1;
        searched[position] = true;
        Set<String> keys = evidenceKeys.get(position);
        if (results != null) {
            for (SearchResult result : results) {
                String key = evidenceKey(result);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys.size();
    }

    private static String evidenceKey(SearchResult result) {
        if (result == null || result.getFileMd5() == null || result.getFileMd5().isBlank()
                || result.getChunkId() == null) {
            return null;
        }
        return result.getFileMd5() + "#" + result.getChunkId();
    }

    /**
     * 校验并记录模型给出的证据充分性判断。一次性校验全部条目，任何一条不合法都不落库，
     * 让模型通过 tool 错误自行纠正后再报一次。
     */
    List<EvidenceAssessment> recordAssessments(List<?> rawAssessments) {
        if (rawAssessments == null) {
            throw new IllegalArgumentException("assessments 不能为空");
        }
        if (rawAssessments.size() != subQuestions.size()) {
            throw new IllegalArgumentException(
                    "assessments 需要覆盖全部 " + subQuestions.size() + " 个子问题，每个子问题恰好一条");
        }

        Map<Integer, EvidenceAssessment> validated = new LinkedHashMap<>();
        for (Object item : rawAssessments) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("assessments 的每一项必须是对象");
            }
            Integer index;
            try {
                index = parseIndex(map.get("index"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "assessments 的 index 必须是 1-" + subQuestions.size() + " 之间的整数");
            }
            if (index == null) {
                throw new IllegalArgumentException("assessments 的每一项必须包含 index");
            }
            if (index < 1 || index > subQuestions.size()) {
                throw new IllegalArgumentException(
                        "assessments 的 index 必须在 1-" + subQuestions.size() + " 之间");
            }
            EvidenceStatus status = EvidenceStatus.parse(map.get("status"));
            // 资料未覆盖是检索后的结论：没检索就报 MISSING_KNOWLEDGE 无法区分"没查"和"查不到"。
            // 缺少用户条件不依赖检索（问题本身就可能缺条件），不在此限制。
            if (status == EvidenceStatus.MISSING_KNOWLEDGE && !searched[index - 1]) {
                throw new IllegalArgumentException("子问题 " + index
                        + " 还没有检索，不能报告 MISSING_KNOWLEDGE；请先检索该子问题，"
                        + "如果缺少的是用户条件请用 MISSING_USER_CONDITION");
            }
            List<String> evidence = normalizeEvidence(map.get("evidence"), index);
            if ((status == EvidenceStatus.SUPPORTED || status == EvidenceStatus.PARTIAL) && evidence.isEmpty()) {
                throw new IllegalArgumentException(
                        "status=" + status.name() + " 时 evidence 至少需要提供 1 个真实证据（fileMd5#chunkId）");
            }
            Set<String> retrieved = evidenceKeys.get(index - 1);
            for (String key : evidence) {
                if (!retrieved.contains(key)) {
                    throw new IllegalArgumentException("子问题 " + index + " 的证据 " + key
                            + " 不在本次检索结果中；evidence 只能填写检索结果里的 fileMd5#chunkId");
                }
            }
            String reason = optionalReason(map.get("reason"));
            if (validated.put(index, new EvidenceAssessment(index, status, evidence, reason)) != null) {
                throw new IllegalArgumentException("子问题 " + index + " 的充分性判断重复");
            }
        }
        for (int i = 1; i <= subQuestions.size(); i++) {
            if (!validated.containsKey(i)) {
                throw new IllegalArgumentException("缺少子问题 " + i + " 的充分性判断");
            }
        }

        assessments.clear();
        assessments.putAll(validated);
        return List.copyOf(validated.values());
    }

    private static List<String> normalizeEvidence(Object raw, int index) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("子问题 " + index + " 的 evidence 必须是数组");
        }
        List<String> keys = new ArrayList<>(list.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            String key = item == null ? "" : String.valueOf(item).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("子问题 " + index + " 的 evidence 不能包含空值");
            }
            if (seen.add(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String optionalReason(Object raw) {
        if (raw == null) {
            return null;
        }
        String reason = String.valueOf(raw).trim();
        return reason.isEmpty() ? null : reason;
    }

    boolean hasAssessments() {
        return !assessments.isEmpty();
    }

    /**
     * 模型做过证据判断时输出"证据充分性"，否则只输出检索记录，不声称资料已覆盖。
     */
    String buildEvidenceFooter() {
        StringBuilder footer = new StringBuilder("\n\n---\n");
        if (assessments.isEmpty()) {
            footer.append("**子问题检索记录（未做证据充分性判断）**\n");
            for (int i = 0; i < subQuestions.size(); i++) {
                footer.append("- 子问题 ").append(i + 1).append("（").append(subQuestions.get(i)).append("）：");
                if (!searched[i]) {
                    footer.append("未检索");
                } else {
                    footer.append("已检索，去重证据 ").append(evidenceKeys.get(i).size()).append(" 条");
                }
                footer.append('\n');
            }
            return footer.toString();
        }

        footer.append("**子问题证据充分性（模型判断 + 代码校验）**\n");
        for (int i = 0; i < subQuestions.size(); i++) {
            EvidenceAssessment assessment = assessments.get(i + 1);
            footer.append("- 子问题 ").append(i + 1).append("（").append(subQuestions.get(i)).append("）：")
                    .append(assessment.status().label())
                    .append("，去重证据 ").append(evidenceKeys.get(i).size()).append(" 条");
            if (assessment.reason() != null) {
                footer.append("（说明：").append(assessment.reason()).append("）");
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
