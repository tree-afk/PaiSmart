package com.yizhaoqi.smartpai.service.document;

import java.util.Set;

/**
 * 当前可检索的文档范围快照：
 * <ul>
 *     <li>activeBuildIds：业务文档当前生效的构建批次，检索只返回这些 build 的分块；</li>
 *     <li>retiredLegacyMd5s：已退休的 legacy 版本 md5，没有 buildId 的旧分块要排除这些文件。</li>
 * </ul>
 * 两个集合都为空表示"还没有生效构建、也没有退休 legacy"：检索仍要排除带 buildId 的未生效分块，
 * 只放行没有 buildId 的 legacy 分块（见 DocumentRetrievalFilter）。
 */
public record ActiveRetrievalScope(Set<String> activeBuildIds, Set<String> retiredLegacyMd5s) {
    public ActiveRetrievalScope {
        activeBuildIds = activeBuildIds == null ? Set.of() : Set.copyOf(activeBuildIds);
        retiredLegacyMd5s = retiredLegacyMd5s == null ? Set.of() : Set.copyOf(retiredLegacyMd5s);
    }

    public boolean isEmpty() {
        return activeBuildIds.isEmpty() && retiredLegacyMd5s.isEmpty();
    }
}
