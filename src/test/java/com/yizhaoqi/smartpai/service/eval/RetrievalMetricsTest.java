package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsTest {

    private final List<SearchResult> results = List.of(
            result("m1", "a.pdf"),
            result("m2", "B.docx"),
            result("m3", "c.md"));

    @Test
    void firstRelevantRankMatchesByMd5() {
        EvalCase evalCase = new EvalCase("1", "q", List.of("m3"), List.of(), "r");
        assertEquals(3, RetrievalMetrics.firstRelevantRank(results, evalCase, 5));
        assertEquals(1d / 3, RetrievalMetrics.reciprocalRank(3), 1e-9);
    }

    @Test
    void firstRelevantRankMatchesByFileNameIgnoringCase() {
        EvalCase evalCase = new EvalCase("1", "q", List.of(), List.of("b.DOCX"), "r");
        assertEquals(2, RetrievalMetrics.firstRelevantRank(results, evalCase, 5));
    }

    @Test
    void onlyTopKResultsCount() {
        EvalCase evalCase = new EvalCase("1", "q", List.of("m3"), List.of(), "r");
        assertEquals(0, RetrievalMetrics.firstRelevantRank(results, evalCase, 2));
        assertEquals(0d, RetrievalMetrics.reciprocalRank(0), 1e-9);
    }

    @Test
    void missReturnsZero() {
        EvalCase evalCase = new EvalCase("1", "q", List.of("zzz"), List.of("none.pdf"), "r");
        assertEquals(0, RetrievalMetrics.firstRelevantRank(results, evalCase, 5));
        assertEquals(0, RetrievalMetrics.firstRelevantRank(List.of(), evalCase, 5));
        assertEquals(0, RetrievalMetrics.firstRelevantRank(null, evalCase, 5));
    }

    @Test
    void expectedDocCoverageCountsEachExpectedDoc() {
        EvalCase evalCase = new EvalCase("1", "q", List.of("m1", "missing"), List.of("c.md"), "r");
        assertEquals(2d / 3, RetrievalMetrics.expectedDocCoverage(results, evalCase, 5), 1e-9);
        assertEquals(1d / 3, RetrievalMetrics.expectedDocCoverage(results, evalCase, 1), 1e-9);
    }

    @Test
    void isRelevantUsesMd5OrName() {
        EvalCase evalCase = new EvalCase("1", "q", List.of("m1"), List.of("c.md"), "r");
        assertTrue(RetrievalMetrics.isRelevant(results.get(0), evalCase));
        assertFalse(RetrievalMetrics.isRelevant(results.get(1), evalCase));
        assertTrue(RetrievalMetrics.isRelevant(results.get(2), evalCase));
    }

    private static SearchResult result(String md5, String fileName) {
        return new SearchResult(md5, 1, "text", 0.9, fileName);
    }
}
