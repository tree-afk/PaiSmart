package com.yizhaoqi.smartpai.service.eval;

import java.util.List;
import java.util.Locale;

/**
 * 一条评测用例：问题、期望命中的文档（MD5 或文件名，至少给一种才计算检索指标）、参考答案。
 * expectedEvidence 是可选的分块级标注：答案所在分块里一定会出现的关键短语，命中分块包含其中任意一个才算相关；
 * 不填时只按文件判断，同一文件的任何分块都算命中。
 * expectedBehavior=clarify_first 表示这道题的正确行为是先澄清：生成时提供 ask_clarification 工具，
 * 评委接受“先追问缺失条件”或“给数值并说明适用条件”两种回答。
 */
public record EvalCase(
        String id,
        String question,
        List<String> expectedFileMd5s,
        List<String> expectedFileNames,
        String referenceAnswer,
        List<String> expectedEvidence,
        String expectedBehavior
) {

    public EvalCase {
        expectedFileMd5s = expectedFileMd5s == null ? List.of() : List.copyOf(expectedFileMd5s);
        expectedFileNames = expectedFileNames == null ? List.of() : List.copyOf(expectedFileNames);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        expectedBehavior = expectedBehavior == null || expectedBehavior.isBlank()
                ? null : expectedBehavior.trim().toLowerCase(Locale.ROOT);
    }

    public EvalCase(String id, String question, List<String> expectedFileMd5s, List<String> expectedFileNames,
                    String referenceAnswer, List<String> expectedEvidence) {
        this(id, question, expectedFileMd5s, expectedFileNames, referenceAnswer, expectedEvidence, null);
    }

    public EvalCase(String id, String question, List<String> expectedFileMd5s, List<String> expectedFileNames,
                    String referenceAnswer) {
        this(id, question, expectedFileMd5s, expectedFileNames, referenceAnswer, List.of(), null);
    }

    public boolean hasExpectedDocs() {
        return !expectedFileMd5s.isEmpty() || !expectedFileNames.isEmpty();
    }

    public boolean hasExpectedEvidence() {
        return hasExpectedDocs() && !expectedEvidence.isEmpty();
    }

    public boolean expectsClarificationFirst() {
        return "clarify_first".equals(expectedBehavior);
    }
}
