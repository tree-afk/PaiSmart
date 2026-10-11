package com.yizhaoqi.smartpai.service.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评测报告。label 用来区分同一数据集的多次评测（例如调整分块前后各跑一次）。
 */
public record EvalReport(
        String runId,
        String label,
        String datasetSource,
        int topK,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> config,
        EvalSummary summary,
        Map<String, String> diagnosisGuide,
        List<EvalCaseResult> cases
) {

    /**
     * 汇总指标。没有可计算的样本时对应指标为 null（例如没有任何用例标注期望文档时 recallAtK 为 null）。
     */
    public record EvalSummary(
            int totalCases,
            int labeledCases,
            int retrievalHits,
            Double recallAtK,
            Double mrr,
            /* 只按文件判断的 Recall@K；evidenceLabeledCases 为 0 时与 recallAtK 相同 */
            Double docRecallAtK,
            /* 标注了 expectedEvidence、按分块判断命中的用例数 */
            int evidenceLabeledCases,
            Double meanExpectedDocCoverage,
            int judgedCases,
            int correctCases,
            Double meanJudgeScore,
            Double accuracy,
            int needsHumanReviewCount,
            int failedCases,
            Map<String, Long> retrievalModeCounts,
            Map<String, Long> diagnosisCounts
    ) {
    }
}
