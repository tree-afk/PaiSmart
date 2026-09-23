package com.yizhaoqi.smartpai.service.eval;

import java.util.Random;

/**
 * 人工复核抽样：评委也会有偏差，按比例随机抽一部分用例给人工复核，用来校准评委打分；
 * 评委调用失败或输出无法解析的用例一律需要人工判断。
 */
public final class HumanReviewSampler {

    public static final String REASON_JUDGE_FAILED = "JUDGE_FAILED";
    public static final String REASON_SAMPLED = "RANDOM_SAMPLE";

    private final double sampleRate;
    private final Random random;

    public HumanReviewSampler(double sampleRate, Random random) {
        this.sampleRate = Math.min(Math.max(sampleRate, 0d), 1d);
        this.random = random;
    }

    public static HumanReviewSampler of(double sampleRate, Long seed) {
        return new HumanReviewSampler(sampleRate, seed == null ? new Random() : new Random(seed));
    }

    /**
     * @return 需要复核的原因；不需要复核时返回 null
     */
    public synchronized String decide(boolean judgeFailed) {
        // 先消耗一次随机数，保证固定种子下同一个用例序号的抽样结果不受前面用例评委成败的影响
        boolean sampled = random.nextDouble() < sampleRate;
        if (judgeFailed) {
            return REASON_JUDGE_FAILED;
        }
        return sampled ? REASON_SAMPLED : null;
    }
}
