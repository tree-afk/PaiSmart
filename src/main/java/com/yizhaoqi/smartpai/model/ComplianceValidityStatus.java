package com.yizhaoqi.smartpai.model;

import java.time.LocalDate;

/**
 * 资料效力状态。它由生效日期、失效日期和废止标记派生，而不是简单等同于 effectiveAt <= today：
 * <ul>
 *     <li>UNCONFIRMED：没有生效日期，只能说明“效力待确认”；</li>
 *     <li>PENDING：生效日期在未来，构建可以 READY，但不能替换当前业务生效版本；</li>
 *     <li>EFFECTIVE：标注的生效日期已到，且没有标注失效/废止；</li>
 *     <li>EXPIRED：已过标注的失效日期；</li>
 *     <li>REPEALED：已标记废止。</li>
 * </ul>
 */
public enum ComplianceValidityStatus {

    UNCONFIRMED("效力待确认"),
    PENDING("待生效"),
    EFFECTIVE("已到生效日期"),
    EXPIRED("已失效"),
    REPEALED("已废止");

    private final String label;

    ComplianceValidityStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 只有已到生效日期或效力待确认的版本可以切换为业务生效版本；未来生效的版本必须等定时任务到点再切换。
     */
    public boolean activationEligible() {
        return this == EFFECTIVE || this == UNCONFIRMED;
    }

    public static ComplianceValidityStatus resolve(LocalDate effectiveAt,
                                                   LocalDate expiresAt,
                                                   boolean repealed,
                                                   LocalDate asOf) {
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        if (repealed) {
            return REPEALED;
        }
        if (expiresAt != null && expiresAt.isBefore(today)) {
            return EXPIRED;
        }
        if (effectiveAt == null) {
            return UNCONFIRMED;
        }
        return effectiveAt.isAfter(today) ? PENDING : EFFECTIVE;
    }
}
