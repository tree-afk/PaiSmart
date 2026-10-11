package com.yizhaoqi.smartpai.service.eval;

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
            modes.merge(result.retrievalMode() == null ? "NONE" : result.retrievalMode(), 1L, Long::sum);
        }

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
                diagnoses);
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
