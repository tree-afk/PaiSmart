package com.yizhaoqi.smartpai.service.eval;

import java.util.List;

/**
 * 一条评测用例：问题、期望命中的文档（MD5 或文件名，至少给一种才计算检索指标）、参考答案
 */
public record EvalCase(
        String id,
        String question,
        List<String> expectedFileMd5s,
        List<String> expectedFileNames,
        String referenceAnswer
) {

    public EvalCase {
        expectedFileMd5s = expectedFileMd5s == null ? List.of() : List.copyOf(expectedFileMd5s);
        expectedFileNames = expectedFileNames == null ? List.of() : List.copyOf(expectedFileNames);
    }

    public boolean hasExpectedDocs() {
        return !expectedFileMd5s.isEmpty() || !expectedFileNames.isEmpty();
    }
}
