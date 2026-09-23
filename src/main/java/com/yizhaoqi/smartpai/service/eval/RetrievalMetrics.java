package com.yizhaoqi.smartpai.service.eval;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索指标：
 * - Recall@K（本项目口径）：前 K 条结果里只要出现任一期望文档即记为命中，数据集上取命中率；
 * - 倒数排名 RR：第一条期望文档排在第 r 位时为 1/r，前 K 条都没有时为 0，数据集上取平均即 MRR；
 * - 期望文档覆盖率：前 K 条覆盖了多少比例的期望文档（一道题需要多篇文档时参考）。
 * 期望文档优先按 MD5 匹配，其次按文件名（忽略大小写）匹配。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * @return 第一条相关结果的排名（从 1 开始），前 K 条都不相关时返回 0
     */
    public static int firstRelevantRank(List<SearchResult> results, EvalCase evalCase, int k) {
        Set<String> md5s = lower(evalCase.expectedFileMd5s());
        Set<String> names = lower(evalCase.expectedFileNames());
        int limit = Math.min(results == null ? 0 : results.size(), Math.max(k, 0));
        for (int i = 0; i < limit; i++) {
            if (isRelevant(results.get(i), md5s, names)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static double reciprocalRank(int rank) {
        return rank > 0 ? 1d / rank : 0d;
    }

    /**
     * 前 K 条结果覆盖的期望文档比例；一个期望项（MD5 或文件名）被任一结果命中即算覆盖
     */
    public static double expectedDocCoverage(List<SearchResult> results, EvalCase evalCase, int k) {
        int expectedCount = evalCase.expectedFileMd5s().size() + evalCase.expectedFileNames().size();
        if (expectedCount == 0) {
            return 0d;
        }
        List<SearchResult> top = results == null ? List.of() : results.subList(0, Math.min(results.size(), Math.max(k, 0)));
        Set<String> retrievedMd5s = top.stream().map(SearchResult::getFileMd5).map(RetrievalMetrics::lower).collect(Collectors.toSet());
        Set<String> retrievedNames = top.stream().map(SearchResult::getFileName).map(RetrievalMetrics::lower).collect(Collectors.toSet());
        long covered = evalCase.expectedFileMd5s().stream().map(RetrievalMetrics::lower).filter(retrievedMd5s::contains).count()
                + evalCase.expectedFileNames().stream().map(RetrievalMetrics::lower).filter(retrievedNames::contains).count();
        return (double) covered / expectedCount;
    }

    static boolean isRelevant(SearchResult result, Set<String> expectedMd5s, Set<String> expectedNames) {
        String md5 = lower(result.getFileMd5());
        String name = lower(result.getFileName());
        return (md5 != null && expectedMd5s.contains(md5)) || (name != null && expectedNames.contains(name));
    }

    public static boolean isRelevant(SearchResult result, EvalCase evalCase) {
        return isRelevant(result, lower(evalCase.expectedFileMd5s()), lower(evalCase.expectedFileNames()));
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : values) {
            out.add(lower(value));
        }
        return out;
    }

    private static String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
