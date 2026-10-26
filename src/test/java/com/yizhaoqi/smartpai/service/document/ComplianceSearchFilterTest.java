package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplianceSearchFilterTest {

    @Test
    void constructorNormalizesNullsBlankValuesAndDuplicates() {
        Set<DocumentComplianceType> types = new LinkedHashSet<>(Arrays.asList(
                DocumentComplianceType.REGULATION, null, DocumentComplianceType.PRODUCT_TERMS));
        Set<String> jurisdictions = new LinkedHashSet<>(Arrays.asList("  全国 ", "", null, " 全国 ", "北京"));

        ComplianceSearchFilter filter = new ComplianceSearchFilter(types, jurisdictions, null);

        assertEquals(Set.of(DocumentComplianceType.REGULATION, DocumentComplianceType.PRODUCT_TERMS),
                filter.docTypes());
        assertEquals(new LinkedHashSet<>(List.of("全国", "北京")), filter.jurisdictions());
        assertEquals(LocalDate.now(), filter.asOf(), "asOf 缺省为今天");
        assertTrue(!filter.isEmpty(), "指定了维度时不算空过滤");
    }

    @Test
    void nullSetsBecomeEmptyAndEmptyFilterKeepsToday() {
        ComplianceSearchFilter filter = new ComplianceSearchFilter(null, null, null);

        assertTrue(filter.docTypes().isEmpty());
        assertTrue(filter.jurisdictions().isEmpty());
        assertEquals(LocalDate.now(), filter.asOf());
        assertTrue(filter.isEmpty());
    }

    @Test
    void fromCodesTrimsAndDeduplicatesEnumCodes() {
        ComplianceSearchFilter filter = ComplianceSearchFilter.fromCodes(
                Arrays.asList(" regulation ", "PRODUCT_TERMS", "", null, "regulation"),
                Arrays.asList(" 全国 ", null, "", "全国", "上海"),
                LocalDate.of(2026, 10, 1));

        assertEquals(List.of(DocumentComplianceType.REGULATION, DocumentComplianceType.PRODUCT_TERMS),
                List.copyOf(filter.docTypes()), "枚举去重并保留输入顺序");
        assertEquals(List.of("全国", "上海"), List.copyOf(filter.jurisdictions()));
        assertEquals(LocalDate.of(2026, 10, 1), filter.asOf());
    }

    @Test
    void fromCodesRejectsUnknownEnum() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ComplianceSearchFilter.fromCodes(List.of("BANANA"), List.of(), LocalDate.now()));

        assertTrue(error.getMessage().contains("BANANA"));
        assertTrue(error.getMessage().contains("REGULATION"));
    }

    @Test
    void fromCodesAllowsNullAndBlankCollections() {
        ComplianceSearchFilter filter = ComplianceSearchFilter.fromCodes(null, null, null);

        assertTrue(filter.docTypes().isEmpty());
        assertTrue(filter.jurisdictions().isEmpty());
        assertEquals(LocalDate.now(), filter.asOf());
    }

    @Test
    void currentAndEmptyFactoriesHaveNoDimensionFiltersAndUseToday() {
        ComplianceSearchFilter current = ComplianceSearchFilter.current();
        ComplianceSearchFilter empty = ComplianceSearchFilter.empty();

        assertEquals(Set.of(), current.docTypes());
        assertEquals(Set.of(), current.jurisdictions());
        assertEquals(LocalDate.now(), current.asOf());
        assertEquals(current, empty, "empty 与 current 都是无维度、按现行有效过滤");
        assertTrue(empty.isEmpty());
    }
}
