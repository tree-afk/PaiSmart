package com.yizhaoqi.smartpai.service.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 汇总单条结果为数据集级指标：
 * Recall@K 与 MRR 只在标注了期望文档的用例上计算；平均分与准确率只在评委成功打分的用例上计算。
 */
public final class EvalReportAggregator {

    private static final Set<EvalDiagnosis> FAILURES =
            EnumSet.of(EvalDiagnosis.RETRIEVAL_FAILED, EvalDiagnosis.GENERATION_FAILED, EvalDiagnosis.JUDGE_FAILED);

    private EvalReportAggregator() {
    }

    public static EvalReport.EvalSummary summarize(List<EvalCaseResult> results) {
        int labeled = 0;
        int hits = 0;
        int docHits = 0;
        int evidenceLabeled = 0;
        double rrSum = 0d;
        double coverageSum = 0d;
        int judged = 0;
        int correct = 0;
        double scoreSum = 0d;
        int review = 0;
        int failed = 0;
        Map<String, Long> modes = new TreeMap<>();
        int staged = 0;
        int candidateHits = 0;
        int ndcgCases = 0;
        double ndcgSum = 0d;
        int bm25Hits = 0;
        int knnHits = 0;
        int droppedCases = 0;
        int droppedResults = 0;
        int precisionCases = 0;
        double precisionSum = 0d;
        double irrelevantSum = 0d;
        int unlabeled = 0;
        double unlabeledReturned = 0d;
        int coverageCases = 0;
        double coverageAtKSum = 0d;
        int deliveredCases = 0;
        double deliveredSum = 0d;
        int candidateCoverageCases = 0;
        double candidateCoverageSum = 0d;
        int decomposedCases = 0;
        List<Long> totalLatencies = new ArrayList<>();
        List<Long> retrievalLatencies = new ArrayList<>();
        List<Long> generationLatencies = new ArrayList<>();
        List<Long> judgeLatencies = new ArrayList<>();
        int fallbackCases = 0;
        int citationCases = 0;
        int uncitedCases = 0;
        int traceableCases = 0;
        int relevanceCases = 0;
        double citationCountSum = 0d;
        double traceabilitySum = 0d;
        double relevanceSum = 0d;
        Map<String, Long> diagnoses = new LinkedHashMap<>();
        for (EvalDiagnosis diagnosis : EvalDiagnosis.values()) {
            diagnoses.put(diagnosis.name(), 0L);
        }

        for (EvalCaseResult result : results) {
            if (result.retrievalHit() != null) {
                labeled++;
                hits += result.retrievalHit() ? 1 : 0;
                docHits += Boolean.TRUE.equals(result.docHit()) ? 1 : 0;
                evidenceLabeled += result.expectedEvidence() != null && !result.expectedEvidence().isEmpty() ? 1 : 0;
                rrSum += result.reciprocalRank() == null ? 0d : result.reciprocalRank();
                coverageSum += result.expectedDocCoverage() == null ? 0d : result.expectedDocCoverage();
            }
            if (result.judged()) {
                judged++;
                scoreSum += result.judgeScore();
                correct += result.judgeCorrect() ? 1 : 0;
            }
            review += result.needsHumanReview() ? 1 : 0;
            if (result.diagnosis() != null) {
                failed += FAILURES.contains(result.diagnosis()) ? 1 : 0;
                diagnoses.merge(result.diagnosis().name(), 1L, Long::sum);
            }
            String mode = result.retrievalMode() == null ? "NONE" : result.retrievalMode();
            modes.merge(mode, 1L, Long::sum);
            if (!mode.startsWith("HYBRID")) {
                fallbackCases++;
            }
            EvalCaseResult.Timings timings = result.timings();
            if (timings != null) {
                totalLatencies.add(timings.totalMs());
                retrievalLatencies.add(timings.retrievalMs());
                if (timings.generationMs() > 0) {
                    generationLatencies.add(timings.generationMs());
                }
                if (timings.judgeMs() > 0) {
                    judgeLatencies.add(timings.judgeMs());
                }
            }
            CitationMetrics citation = result.citation();
            if (citation != null) {
                citationCases++;
                citationCountSum += citation.citationCount();
                if (citation.uncited()) {
                    uncitedCases++;
                }
                if (citation.traceabilityRate() != null) {
                    traceableCases++;
                    traceabilitySum += citation.traceabilityRate();
                }
                if (citation.relevanceRate() != null) {
                    relevanceCases++;
                    relevanceSum += citation.relevanceRate();
                }
            }
            EvalCaseResult.Stages stages = result.stages();
            if (stages != null) {
                droppedCases += stages.thresholdDroppedInTopK() > 0 ? 1 : 0;
                droppedResults += stages.thresholdDroppedInTopK();
                if (stages.candidateHit() == null) {
                    unlabeled++;
                    unlabeledReturned += stages.returnedCount();
                } else {
                    staged++;
                    irrelevantSum += stages.irrelevantInTopK();
                    if (stages.precisionAtK() != null) {
                        precisionCases++;
                        precisionSum += stages.precisionAtK();
                    }
                    candidateHits += stages.candidateHit() ? 1 : 0;
                    bm25Hits += Boolean.TRUE.equals(stages.bm25Hit()) ? 1 : 0;
                    knnHits += Boolean.TRUE.equals(stages.knnHit()) ? 1 : 0;
                    if (stages.ndcgAtK() != null) {
                        ndcgCases++;
                        ndcgSum += stages.ndcgAtK();
                    }
                }
                if (stages.evidenceCoverageAtK() != null) {
                    coverageCases++;
                    coverageAtKSum += stages.evidenceCoverageAtK();
                }
                if (stages.evidenceCoverageDelivered() != null) {
                    deliveredCases++;
                    deliveredSum += stages.evidenceCoverageDelivered();
                }
                if (stages.candidateEvidenceCoverage() != null) {
                    candidateCoverageCases++;
                    candidateCoverageSum += stages.candidateEvidenceCoverage();
                }
                if (stages.subQuestions() != null && stages.subQuestions().size() >= 2) {
                    decomposedCases++;
                }
            }
        }
        EvalReport.StageSummary stageSummary = staged == 0 && unlabeled == 0 ? null
                : new EvalReport.StageSummary(staged, ratio(candidateHits, staged), ratio(ndcgSum, ndcgCases), ndcgCases,
                ratio(bm25Hits, staged), ratio(knnHits, staged), droppedCases, droppedResults,
                ratio(precisionSum, precisionCases), ratio(irrelevantSum, staged), unlabeled,
                ratio(unlabeledReturned, unlabeled), coverageCases, ratio(coverageAtKSum, coverageCases),
                ratio(deliveredSum, deliveredCases), ratio(candidateCoverageSum, candidateCoverageCases),
                decomposedCases);

        EvalReport.LatencySummary latencySummary = totalLatencies.isEmpty() ? null
                : new EvalReport.LatencySummary(
                percentile(totalLatencies, 0.5), percentile(totalLatencies, 0.95),
                percentile(retrievalLatencies, 0.5), percentile(retrievalLatencies, 0.95),
                percentile(generationLatencies, 0.5), percentile(generationLatencies, 0.95),
                percentile(judgeLatencies, 0.5), percentile(judgeLatencies, 0.95),
                totalLatencies.size(), fallbackCases, ratio(fallbackCases, results.size()));

        EvalReport.CitationSummary citationSummary = citationCases == 0 ? null
                : new EvalReport.CitationSummary(
                citationCases,
                uncitedCases,
                traceableCases,
                relevanceCases,
                ratio(citationCountSum, citationCases),
                ratio(traceabilitySum, traceableCases),
                ratio(relevanceSum, relevanceCases));

        return new EvalReport.EvalSummary(
                results.size(),
                labeled,
                hits,
                ratio(hits, labeled),
                ratio(rrSum, labeled),
                ratio(docHits, labeled),
                evidenceLabeled,
                ratio(coverageSum, labeled),
                judged,
                correct,
                ratio(scoreSum, judged),
                ratio(correct, judged),
                review,
                failed,
                modes,
                diagnoses,
                stageSummary,
                latencySummary,
                citationSummary);
    }

    /**
     * 最近秩百分位：n=4 时 P50 取第 2 个，P95 取最大值
     */
    private static Double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return (double) sorted.get(index);
    }

    public static Map<String, String> diagnosisGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        for (EvalDiagnosis diagnosis : EvalDiagnosis.values()) {
            guide.put(diagnosis.name(), diagnosis.description());
        }
        return guide;
    }

    private static Double ratio(double numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round(numerator / denominator * 10000d) / 10000d;
    }
}
