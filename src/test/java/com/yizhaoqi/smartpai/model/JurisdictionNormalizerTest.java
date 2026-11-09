package com.yizhaoqi.smartpai.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JurisdictionNormalizerTest {

    @Test
    void nationalAliasesBecomeOneCanonicalDisplayAndCode() {
        for (String alias : List.of("全国", "中国", "中华人民共和国", "国家", "全国适用", " China ")) {
            assertThat(JurisdictionNormalizer.normalizeDisplay(alias)).isEqualTo("全国");
            assertThat(JurisdictionNormalizer.normalizeCode(alias)).isEqualTo(JurisdictionNormalizer.NATIONAL);
        }
    }

    @Test
    void administrativeSuffixesAreRemovedForLocalRegions() {
        assertThat(JurisdictionNormalizer.normalizeCode("上海市")).isEqualTo("上海");
        assertThat(JurisdictionNormalizer.normalizeCode("北京")).isEqualTo("北京");
        assertThat(JurisdictionNormalizer.normalizeCode("广东省")).isEqualTo("广东");
        assertThat(JurisdictionNormalizer.normalizeCode("内蒙古自治区")).isEqualTo("内蒙古");
    }

    @Test
    void localQueryExpandsToLocalPlusNational() {
        assertThat(JurisdictionNormalizer.queryCodes(List.of("上海市")))
                .containsExactlyInAnyOrder("上海", JurisdictionNormalizer.NATIONAL);
        assertThat(JurisdictionNormalizer.queryCodes(List.of("上海", "北京")))
                .containsExactlyInAnyOrder("上海", "北京", JurisdictionNormalizer.NATIONAL);
    }

    @Test
    void nationwideQueryDoesNotPullEveryRegion() {
        assertThat(JurisdictionNormalizer.queryCodes(List.of("全国")))
                .containsExactly(JurisdictionNormalizer.NATIONAL);
        assertThat(JurisdictionNormalizer.queryDisplays(List.of("全国")))
                .containsExactlyInAnyOrder("全国", "中国", "中华人民共和国");
    }

    @Test
    void mixedNationalAndLocalQueryKeepsBothDimensions() {
        assertThat(JurisdictionNormalizer.queryCodes(List.of("全国", "上海")))
                .containsExactlyInAnyOrder("NATIONAL", "上海");
    }

    @Test
    void localQueryAlsoMatchesLegacyDisplayValuesIncludingNationalAliases() {
        Set<String> displays = JurisdictionNormalizer.queryDisplays(List.of("上海"));

        assertThat(displays).contains("上海", "上海市", "全国", "中国", "中华人民共和国");
    }
}
