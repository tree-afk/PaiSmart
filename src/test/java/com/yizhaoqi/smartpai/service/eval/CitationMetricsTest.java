package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationMetricsTest {

    private final EvalCase evalCase = new EvalCase("c1", "端口是多少", List.of(), List.of("config.pdf"),
            "8080", List.of("8080"));

    @Test
    void countsBracketAndSourceCitations() {
        List<SearchResult> results = List.of(new SearchResult("m1", 1, "端口是 8080", 0.9, "config.pdf"));

        CitationMetrics bracket = CitationMetrics.evaluate("端口是 8080 [1]", results, evalCase);
        CitationMetrics source = CitationMetrics.evaluate("端口是 8080（来源#1: config.pdf）", results, evalCase);

        assertEquals(1, bracket.citationCount());
        assertEquals(1, source.citationCount());
        assertEquals(1, bracket.traceableCount());
        assertEquals(1.0, bracket.traceabilityRate());
        assertEquals(1.0, bracket.relevanceRate());
        assertTrue(!bracket.uncited());
    }

    @Test
    void outOfRangeCitationIsNotTraceable() {
        List<SearchResult> results = List.of(new SearchResult("m1", 1, "端口是 8080", 0.9, "config.pdf"));

        CitationMetrics metrics = CitationMetrics.evaluate("端口是 8080 [2]", results, evalCase);

        assertEquals(1, metrics.citationCount());
        assertEquals(0, metrics.traceableCount());
        assertEquals(0.0, metrics.traceabilityRate());
        assertNull(metrics.relevanceRate(), "没有可溯源引用时无法计算相关率");
    }

    @Test
    void citationToIrrelevantChunkCountsAsTraceableButNotRelevant() {
        List<SearchResult> results = List.of(
                new SearchResult("m2", 1, "其他配置", 0.9, "other.pdf"),
                new SearchResult("m1", 2, "端口是 8080", 0.9, "config.pdf"));

        CitationMetrics metrics = CitationMetrics.evaluate("端口是 8080 [1]", results, evalCase);

        assertEquals(1, metrics.traceableCount());
        assertEquals(1.0, metrics.traceabilityRate());
        assertEquals(0.0, metrics.relevanceRate());
    }

    @Test
    void uncitedAnswerIsMarked() {
        CitationMetrics metrics = CitationMetrics.evaluate("端口是 8080", List.of(), evalCase);

        assertTrue(metrics.uncited());
        assertEquals(0, metrics.citationCount());
        assertNull(metrics.traceabilityRate());
        assertNull(metrics.relevanceRate());
    }
}
