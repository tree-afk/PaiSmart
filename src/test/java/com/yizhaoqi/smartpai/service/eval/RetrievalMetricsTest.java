package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void evidenceNarrowsRelevanceToChunksContainingPhrase() {
        List<SearchResult> chunks = List.of(
                new SearchResult("m1", 18, "MySQL 8.0 + Spring Data JPA", 0.9, "a.pdf"),
                new SearchResult("m1", 20, "● UI组件 : Naive UI\n● 状态管理 : Pinia", 0.8, "a.pdf"));
        EvalCase evalCase = new EvalCase("1", "q", List.of(), List.of("a.pdf"), "r", List.of("naive ui", "Pinia"));

        assertEquals(2, RetrievalMetrics.firstRelevantRank(chunks, evalCase, 5));
        assertEquals(0, RetrievalMetrics.firstRelevantRank(chunks, evalCase, 1), "只召回了同文件的其他分块不算命中");
        assertTrue(RetrievalMetrics.docHit(chunks, evalCase, 1), "文件级口径仍算命中");
        assertFalse(RetrievalMetrics.isRelevant(chunks.get(0), evalCase));
    }

    @Test
    void evidenceMatchingNormalizesKangxiRadicalsAndWhitespace() {
        // PDF 抽取出的“⼤⽂件”用的是康熙部首 U+2F24 / U+2F42，还夹着换行
        SearchResult chunk = new SearchResult("m1", 1, "⽀持⼤⽂件的断点续传和分⽚\n上传", 0.9, "a.pdf");
        EvalCase evalCase = new EvalCase("1", "q", List.of("m1"), List.of(), "r", List.of("分片 上传"));

        assertTrue(RetrievalMetrics.isRelevant(chunk, evalCase));
    }

    @Test
    void evidenceCoverageCountsEachPhraseSeparately() {
        List<SearchResult> chunks = List.of(
                new SearchResult("s3", 5, "单笔贷款中合作方出资比例不得低于20%", 0.9, "s3.md"),
                new SearchResult("s3", 3, "其他条款", 0.8, "s3.md"));
        EvalCase evalCase = new EvalCase("1", "q", List.of(), List.of("s3.md", "r9.md"), "r",
                List.of("单笔贷款中合作方出资比例不得低于30%", "单笔贷款中合作方出资比例不得低于20%"));

        assertEquals(0.5, RetrievalMetrics.evidenceCoverage(chunks, evalCase, 5), 1e-9,
                "只覆盖两句中的一句时记 0.5，而不是像命中布尔那样记满分");
    }

    @Test
    void evidenceCoverageHonoursKAndReturnsNullWithoutEvidence() {
        List<SearchResult> chunks = List.of(
                new SearchResult("m1", 1, "无关内容", 0.9, "a.pdf"),
                new SearchResult("m1", 2, "单笔贷款中合作方出资比例不得低于30%", 0.8, "a.pdf"));
        EvalCase evalCase = new EvalCase("1", "q", List.of("m1"), List.of(), "r",
                List.of("单笔贷款中合作方出资比例不得低于30%"));

        assertEquals(0.0, RetrievalMetrics.evidenceCoverage(chunks, evalCase, 1), 1e-9);
        assertEquals(1.0, RetrievalMetrics.evidenceCoverage(chunks, evalCase, 2), 1e-9);
        assertNull(RetrievalMetrics.evidenceCoverage(chunks, new EvalCase("2", "q", List.of("m1"), List.of(), "r"), 5));
    }

    @Test
    void candidateEvidenceCoverageSeparatesRecallFromTopK() {
        List<SearchResult> candidates = List.of(
                new SearchResult("s3", 5, "单笔贷款中合作方出资比例不得低于20%", 0.9, "s3.md"),
                new SearchResult("s3", 3, "其他条款", 0.8, "s3.md"),
                new SearchResult("r9", 1, "单笔贷款中合作方出资比例不得低于30%", 0.7, "r9.md"));
        EvalCase evalCase = new EvalCase("1", "q", List.of(), List.of("s3.md", "r9.md"), "r",
                List.of("单笔贷款中合作方出资比例不得低于30%", "单笔贷款中合作方出资比例不得低于20%"));

        assertEquals(1.0, RetrievalMetrics.candidateEvidenceCoverage(candidates, evalCase), 1e-9);
        assertEquals(0.5, RetrievalMetrics.evidenceCoverage(candidates, evalCase, 2), 1e-9,
                "前 2 条只覆盖本行那句，监管那句在候选池里但没进 topK");
    }

    private static SearchResult result(String md5, String fileName) {
        return new SearchResult(md5, 1, "text", 0.9, fileName);
    }
}
