package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 混合检索配置：BM25 与 KNN 双路独立召回 → RRF 融合 → rerank 精排 → 分数阈值截断。
 * topK 只作为最终返回条数的上限，噪声靠 rerank 阈值挡住。
 */
@Data
@Component
@ConfigurationProperties(prefix = "search.hybrid")
public class HybridSearchProperties {

    /** 每一路召回窗口 = topK * recallMultiplier */
    private int recallMultiplier = 30;
    /** 每一路召回窗口上限，防止 topK 过大时单路拉回过多文档 */
    private int maxRecallWindow = 300;
    /** KNN num_candidates = 召回窗口 * 该倍数（ES 上限 10000） */
    private int knnNumCandidatesMultiplier = 2;
    /** RRF 平滑常数 k，score = Σ 1 / (k + rank) */
    private int rrfK = 60;
    /** 双路召回的等待超时 */
    private int recallTimeoutSeconds = 10;

    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {
        /** 关闭后按 RRF 顺序直接截取 topK（不做阈值截断） */
        private boolean enabled = true;
        /** 百炼 / DashScope 文本排序接口 */
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private String model = "gte-rerank-v2";
        /** 为空时复用激活的阿里云 embedding provider 的 API Key */
        private String apiKey = "";
        /** 只对 RRF 融合后的前 N 条候选做精排 */
        private int maxCandidates = 50;
        /** rerank 相关性分数低于该阈值的候选直接丢弃 */
        private double scoreThreshold = 0.2;
        /** 单个候选送入 rerank 的最大字符数 */
        private int maxDocumentChars = 2000;
        private int timeoutSeconds = 10;
        /** 问答请求剩余时间低于该值时跳过 rerank，直接按 RRF 顺序返回 */
        private long skipBelowMillis = 2000L;
    }
}
