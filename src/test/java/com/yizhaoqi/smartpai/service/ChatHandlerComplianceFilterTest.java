package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import com.yizhaoqi.smartpai.service.document.ComplianceSearchFilter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatHandlerComplianceFilterTest {

    @Test
    void restoresFilterFromSearchToolData() {
        ComplianceSearchFilter filter = ChatHandler.complianceFilterFromToolData(Map.of(
                "complianceFilter", Map.of(
                        "docTypes", List.of("REGULATION"),
                        "jurisdictions", List.of("上海市"),
                        "asOf", "2026-10-21"
                )
        ));

        assertEquals(Set.of(DocumentComplianceType.REGULATION), filter.docTypes());
        assertEquals(Set.of("上海"), filter.jurisdictions());
        assertEquals(LocalDate.of(2026, 10, 21), filter.asOf());
    }

    @Test
    void missingFilterDataReturnsNullSoSummaryCanUseDefault() {
        assertNull(ChatHandler.complianceFilterFromToolData(Map.of()));
        assertNull(ChatHandler.complianceFilterFromToolData(null));
    }
}
