package com.yizhaoqi.smartpai.service.eval;

import java.util.List;

/**
 * 单条用例的评测明细
 */
public record EvalCaseResult(
        String caseId,
        String question,
        String referenceAnswer,
        List<String> expectedFileMd5s,
        List<String> expectedFileNames,
        List<String> expectedEvidence,
        /* 检索 */
        String retrievalMode,
        List<RetrievedChunk> retrieved,
        /* 标注了 expectedEvidence 时按分块判断，否则按文件判断 */
        Boolean retrievalHit,
        /* 只按文件判断的命中，用来和分块级命中对比 */
        Boolean docHit,
        Integer firstRelevantRank,
        Double reciprocalRank,
        Double expectedDocCoverage,
        /* 生成 */
        String answer,
        /* 评委 */
        Integer judgeScore,
        Boolean judgeCorrect,
        String judgeReason,
        String judgeRawOutput,
        /* 结论 */
        EvalDiagnosis diagnosis,
        boolean needsHumanReview,
        String reviewReason,
        String error,
        long latencyMs
) {

    public record RetrievedChunk(int rank, String fileMd5, String fileName, Integer chunkId, Double score, boolean relevant) {
    }

    public boolean judged() {
        return judgeScore != null && judgeCorrect != null;
    }
}
