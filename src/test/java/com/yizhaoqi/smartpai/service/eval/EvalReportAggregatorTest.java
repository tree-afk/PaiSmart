package com.yizhaoqi.smartpai.service.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvalReportAggregatorTest {

    @Test
    void aggregatesRetrievalAndGenerationMetricsSeparately() {
        List<EvalCaseResult> results = List.of(
                // 命中第 1 位，回答正确
                result("HYBRID", true, 1, 1.0, 5, true, EvalDiagnosis.OK, false),
                // 命中第 2 位，回答错误 → 提示词 / 模型问题
                result("HYBRID", true, 2, 0.5, 2, false, EvalDiagnosis.PROMPT_OR_MODEL, true),
                // 未命中 → 分块 / 检索问题
                result("HYBRID_RRF", false, null, 0.0, 1, false, EvalDiagnosis.RETRIEVAL_MISS, false),
                // 未标注期望文档，评委失败
                result("TEXT_ONLY", null, null, null, null, null, EvalDiagnosis.JUDGE_FAILED, true));

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(results);

        assertEquals(4, summary.totalCases());
        assertEquals(3, summary.labeledCases());
        assertEquals(2, summary.retrievalHits());
        assertEquals(0.6667, summary.recallAtK());
        assertEquals(0.5, summary.mrr());
        assertEquals(3, summary.judgedCases());
        assertEquals(1, summary.correctCases());
        assertEquals(2.6667, summary.meanJudgeScore());
        assertEquals(0.3333, summary.accuracy());
        assertEquals(2, summary.needsHumanReviewCount());
        assertEquals(1, summary.failedCases());
        assertEquals(2L, summary.retrievalModeCounts().get("HYBRID"));
        assertEquals(1L, summary.retrievalModeCounts().get("HYBRID_RRF"));
        assertEquals(1L, summary.retrievalModeCounts().get("TEXT_ONLY"));
        assertEquals(1L, summary.diagnosisCounts().get("PROMPT_OR_MODEL"));
        assertEquals(1L, summary.diagnosisCounts().get("RETRIEVAL_MISS"));
        assertEquals(0L, summary.diagnosisCounts().get("MISS_BUT_CORRECT"));
    }

    @Test
    void docRecallCountsFileLevelHitsSeparatelyFromEvidenceHits() {
        // 期望文件召回了，但召回的分块不含证据短语：分块级未命中，文件级命中
        EvalCaseResult wrongChunk = new EvalCaseResult("id", "q", "ref", List.of(), List.of("a.pdf"), List.of("Naive UI"),
                "HYBRID", List.of(), false, true, null, 0.0, 1.0, "answer", 2, false, null, null,
                EvalDiagnosis.RETRIEVAL_MISS, false, null, null, 1L);
        EvalCaseResult rightChunk = new EvalCaseResult("id", "q", "ref", List.of(), List.of("a.pdf"), List.of(),
                "HYBRID", List.of(), true, true, 1, 1.0, 1.0, "answer", 5, true, null, null,
                EvalDiagnosis.OK, false, null, null, 1L);

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(wrongChunk, rightChunk));

        assertEquals(0.5, summary.recallAtK());
        assertEquals(1.0, summary.docRecallAtK());
        assertEquals(1, summary.evidenceLabeledCases());
    }

    @Test
    void metricsAreNullWhenNothingToMeasure() {
        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(
                result("NONE", null, null, null, null, null, EvalDiagnosis.GENERATION_FAILED, false)));

        assertNull(summary.recallAtK());
        assertNull(summary.mrr());
        assertNull(summary.meanJudgeScore());
        assertNull(summary.accuracy());
        assertEquals(1, summary.failedCases());
    }

    @Test
    void summarizesLatencyFallbackAndCitationMetrics() {
        EvalCaseResult hybrid = new EvalCaseResult("id", "q", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(),
                true, true, 1, 1.0, 1.0, "answer [1]", 5, true, null, null, EvalDiagnosis.OK, false, null, null, 100L,
                null, new EvalCaseResult.Timings(10, 50, 20, 100, 2L, 5L, 6L, 1L, 8L),
                new CitationMetrics(1, 1, 1, 1.0, 1.0, false));
        EvalCaseResult fallback = new EvalCaseResult("id2", "q2", "ref", List.of(), List.of(), List.of(), "TEXT_ONLY", List.of(),
                null, null, null, null, null, "answer", null, null, null, null, EvalDiagnosis.GENERATION_FAILED,
                false, null, null, 200L, null,
                new EvalCaseResult.Timings(30, 0, 0, 200, null, null, null, null, null),
                new CitationMetrics(0, 0, null, null, null, true));

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(hybrid, fallback));

        assertEquals(2, summary.latency().measuredCases());
        assertEquals(100.0, summary.latency().p50TotalMs());
        assertEquals(200.0, summary.latency().p95TotalMs());
        assertEquals(1, summary.latency().fallbackCases());
        assertEquals(0.5, summary.latency().fallbackRate());

        assertEquals(2, summary.citation().citationCases());
        assertEquals(1, summary.citation().uncitedCases());
        assertEquals(1, summary.citation().traceableCases());
        assertEquals(1, summary.citation().relevanceCases());
        assertEquals(0.5, summary.citation().meanCitationsPerAnswer());
        assertEquals(1.0, summary.citation().meanTraceability());
        assertEquals(1.0, summary.citation().meanRelevance());
    }

    @Test
    void aggregatesEvidenceCoverageAtKDeliveredAndCandidatePool() {
        EvalCaseResult half = staged(0.5, 1.0, 1.0, List.of());
        EvalCaseResult full = staged(1.0, 1.0, 0.75, List.of("子问题一", "子问题二"));

        EvalReport.EvalSummary summary = EvalReportAggregator.summarize(List.of(half, full));

        assertEquals(2, summary.stages().coverageCases());
        assertEquals(0.75, summary.stages().meanEvidenceCoverageAtK());
        assertEquals(1.0, summary.stages().meanEvidenceCoverageDelivered());
        assertEquals(0.875, summary.stages().meanCandidateEvidenceCoverage());
        assertEquals(1, summary.stages().decomposedCases(), "拆出两个子问题的用例才算拆分");
    }

    @Test
    void guideDescribesEveryDiagnosis() {
        assertEquals(EvalDiagnosis.values().length, EvalReportAggregator.diagnosisGuide().size());
    }

    private static EvalCaseResult staged(Double coverageAtK, Double delivered, Double candidateCoverage,
                                        List<String> subQuestions) {
        EvalCaseResult.Stages stages = new EvalCaseResult.Stages(true, 10, 2, 0.5, true, 1, true, 1, 0, 0.4, 3, 5,
                List.of(), coverageAtK, delivered, candidateCoverage, subQuestions, null);
        return new EvalCaseResult("id", "q", "ref", List.of(), List.of(), List.of(), "HYBRID", List.of(),
                true, true, 1, 1.0, 1.0, "answer", 5, true, null, null, EvalDiagnosis.OK, false, null, null, 1L,
                stages, null, null);
    }

    private static EvalCaseResult result(String mode, Boolean hit, Integer rank, Double rr,
                                         Integer score, Boolean correct, EvalDiagnosis diagnosis, boolean review) {
        return new EvalCaseResult("id", "q", "ref", List.of(), List.of(), List.of(), mode, List.of(), hit, hit, rank, rr,
                hit == null ? null : (hit ? 1.0 : 0.0), "answer", score, correct, null, null, diagnosis, review,
                review ? "RANDOM_SAMPLE" : null, null, 1L);
    }
}
