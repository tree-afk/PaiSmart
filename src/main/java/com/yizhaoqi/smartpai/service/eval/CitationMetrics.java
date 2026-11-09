package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答引用指标：统计回答里的引用编号，判断编号能不能落到本次检索结果上（可溯源），
 * 以及被引用的分块是不是期望证据（相关率）。这是确定性口径，不替代人工或 LLM 的语义支撑判断。
 */
public record CitationMetrics(
        int citationCount,
        int traceableCount,
        Integer relevantCount,
        Double traceabilityRate,
        Double relevanceRate,
        boolean uncited
) {

    private static final Pattern BRACKET_CITATION = Pattern.compile("\\[(\\d+)]");
    private static final Pattern SOURCE_CITATION = Pattern.compile("来源#(\\d+)");

    public static CitationMetrics evaluate(String answer, List<SearchResult> results, EvalCase evalCase) {
        Set<Integer> numbers = extractCitationNumbers(answer);
        if (numbers.isEmpty()) {
            return new CitationMetrics(0, 0, null, null, null, true);
        }

        int traceable = 0;
        int relevant = 0;
        boolean labeled = evalCase != null && evalCase.hasExpectedDocs();
        for (Integer number : numbers) {
            if (number == null || number < 1 || results == null || number > results.size()) {
                continue;
            }
            traceable++;
            if (labeled && RetrievalMetrics.isRelevant(results.get(number - 1), evalCase)) {
                relevant++;
            }
        }

        Double traceabilityRate = ratio(traceable, numbers.size());
        Double relevanceRate = traceable == 0 || !labeled ? null : ratio(relevant, traceable);
        return new CitationMetrics(numbers.size(), traceable, labeled ? relevant : null,
                traceabilityRate, relevanceRate, false);
    }

    static Set<Integer> extractCitationNumbers(String answer) {
        if (answer == null || answer.isBlank()) {
            return Set.of();
        }
        List<PositionedNumber> found = new ArrayList<>();
        Matcher bracket = BRACKET_CITATION.matcher(answer);
        while (bracket.find()) {
            found.add(new PositionedNumber(bracket.start(), Integer.parseInt(bracket.group(1))));
        }
        Matcher source = SOURCE_CITATION.matcher(answer);
        while (source.find()) {
            found.add(new PositionedNumber(source.start(), Integer.parseInt(source.group(1))));
        }
        found.sort(Comparator.comparingInt(PositionedNumber::position));
        Set<Integer> numbers = new LinkedHashSet<>();
        for (PositionedNumber item : found) {
            numbers.add(item.number());
        }
        return numbers;
    }

    private static Double ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 10000d) / 10000d;
    }

    private record PositionedNumber(int position, int number) {
    }
}
