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
            Map<String, Long> diagnosisCounts,
            /* 分阶段检索指标，旧报告没有这一项 */
            StageSummary stages,
            /* 延迟与兜底率，旧报告没有这一项 */
            LatencySummary latency,
            /* 引用可溯源率与相关率，旧报告没有这一项 */
            CitationSummary citation
    ) {

        public EvalSummary(int totalCases, int labeledCases, int retrievalHits, Double recallAtK, Double mrr,
                           Double docRecallAtK, int evidenceLabeledCases, Double meanExpectedDocCoverage, int judgedCases,
                           int correctCases, Double meanJudgeScore, Double accuracy, int needsHumanReviewCount,
                           int failedCases, Map<String, Long> retrievalModeCounts, Map<String, Long> diagnosisCounts) {
            this(totalCases, labeledCases, retrievalHits, recallAtK, mrr, docRecallAtK, evidenceLabeledCases,
                    meanExpectedDocCoverage, judgedCases, correctCases, meanJudgeScore, accuracy, needsHumanReviewCount,
                    failedCases, retrievalModeCounts, diagnosisCounts, null, null, null);
        }

        public EvalSummary(int totalCases, int labeledCases, int retrievalHits, Double recallAtK, Double mrr,
                           Double docRecallAtK, int evidenceLabeledCases, Double meanExpectedDocCoverage, int judgedCases,
                           int correctCases, Double meanJudgeScore, Double accuracy, int needsHumanReviewCount,
                           int failedCases, Map<String, Long> retrievalModeCounts, Map<String, Long> diagnosisCounts,
                           StageSummary stages) {
            this(totalCases, labeledCases, retrievalHits, recallAtK, mrr, docRecallAtK, evidenceLabeledCases,
                    meanExpectedDocCoverage, judgedCases, correctCases, meanJudgeScore, accuracy, needsHumanReviewCount,
                    failedCases, retrievalModeCounts, diagnosisCounts, stages, null, null);
        }
    }

    /**
     * 分阶段检索汇总，只在标注了期望文档的用例上计算
     *
     * @param candidateRecall           召回阶段：RRF 候选（rerank 输入，约 50 条）里有相关分块的比例
     * @param meanNdcgAtK               精排阶段：候选里有相关分块的用例上，最终 topK 的平均 NDCG
     * @param ndcgCases                 参与 NDCG 平均的用例数
     * @param bm25Recall                BM25 这一路前 50 条的命中率
     * @param knnRecall                 KNN 这一路前 50 条的命中率
     * @param thresholdDroppedCases     前 topK 名里有结果因低于阈值被丢掉的用例数（为 0 说明阈值只过滤了 topK 之外的结果）
     * @param thresholdDroppedResults   前 topK 名里因低于阈值被丢掉的结果总条数
     * @param meanPrecisionAtK          有返回结果的标注用例上，返回结果中相关的比例（分母是实际返回条数）
     * @param meanIrrelevantInTopK      标注用例平均每题返回几条不相关结果
     * @param unlabeledCases            无答案（未标注期望文档）用例数
     * @param unlabeledMeanReturned     无答案用例平均返回条数，越少越好
     * @param coverageCases             标注了期望证据的用例数（证据覆盖率的分母）
     * @param meanEvidenceCoverageAtK   前 topK 条平均覆盖了多少比例的期望证据短语（逐条计数，不是"任意一条即满分"）
     * @param meanEvidenceCoverageDelivered 实际交给生成的片段平均覆盖率；拆分子问题后条数多于 topK 时用于和 topK 口径对比
     * @param meanCandidateEvidenceCoverage 重排前候选池的平均证据覆盖率，与 topK 口径的差值就是精排丢掉的证据
     * @param decomposedCases           模型判断需要拆分、真的拆出 2 个及以上子问题的用例数
     */
    public record StageSummary(int stagedCases,
                               Double candidateRecall,
                               Double meanNdcgAtK,
                               int ndcgCases,
                               Double bm25Recall,
                               Double knnRecall,
                               int thresholdDroppedCases,
                               int thresholdDroppedResults,
                               Double meanPrecisionAtK,
                               Double meanIrrelevantInTopK,
                               int unlabeledCases,
                               Double unlabeledMeanReturned,
                               int coverageCases,
                               Double meanEvidenceCoverageAtK,
                               Double meanEvidenceCoverageDelivered,
                               Double meanCandidateEvidenceCoverage,
                               int decomposedCases) {

        public StageSummary(int stagedCases, Double candidateRecall, Double meanNdcgAtK, int ndcgCases,
                            Double bm25Recall, Double knnRecall, int thresholdDroppedCases, int thresholdDroppedResults,
                            Double meanPrecisionAtK, Double meanIrrelevantInTopK, int unlabeledCases,
                            Double unlabeledMeanReturned) {
            this(stagedCases, candidateRecall, meanNdcgAtK, ndcgCases, bm25Recall, knnRecall, thresholdDroppedCases,
                    thresholdDroppedResults, meanPrecisionAtK, meanIrrelevantInTopK, unlabeledCases,
                    unlabeledMeanReturned, 0, null, null, null, 0);
        }
    }

    /**
     * 体验层汇总：总耗时、检索、生成、评委分别取 P50/P95；降级模式（非 HYBRID*）单独计数。
     * 两路召回并行，分阶段耗时不等于总耗时。
     */
    public record LatencySummary(Double p50TotalMs,
                                 Double p95TotalMs,
                                 Double p50RetrievalMs,
                                 Double p95RetrievalMs,
                                 Double p50GenerationMs,
                                 Double p95GenerationMs,
                                 Double p50JudgeMs,
                                 Double p95JudgeMs,
                                 int measuredCases,
                                 int fallbackCases,
                                 Double fallbackRate) {
    }

    /**
     * 引用层汇总：平均每条回答引用几个来源、可溯源率（编号能落到本次检索结果）和相关率（引用分块命中期望证据）。
     * 相关率只在标注了期望文档且至少有 1 个可溯源引用时计算。
     */
    public record CitationSummary(int citationCases,
                                  int uncitedCases,
                                  int traceableCases,
                                  int relevanceCases,
                                  Double meanCitationsPerAnswer,
                                  Double meanTraceability,
                                  Double meanRelevance) {
    }
}
