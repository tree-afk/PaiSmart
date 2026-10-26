package com.yizhaoqi.smartpai.service.document;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合规检索过滤：docType、归一化地区（地方问题自动包含全国）、生效/失效/废止。
 */
class ComplianceRetrievalFilterTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 21);

    @Test
    void emptyFilterStillGuardsValidityAndRepeal() {
        Query filter = ComplianceRetrievalFilter.build(ComplianceSearchFilter.current());

        assertTrue(filter.isBool());
        assertEquals(2, filter.bool().must().size(), "现行可检索 + 未过失效日期");
        assertEquals(1, filter.bool().mustNot().size(), "废止标记必须排除");

        Query current = filter.bool().must().get(0);
        assertTrue(current.isBool());
        assertEquals("1", current.bool().minimumShouldMatch());
        assertEquals(2, current.bool().should().size());

        Query range = current.bool().should().get(0);
        assertTrue(range.isRange());
        assertEquals("effectiveAt", range.range().field());
        assertEquals(LocalDate.now().toString(), range.range().lte().to(String.class));

        Query legacy = current.bool().should().get(1);
        assertTrue(legacy.isBool());
        assertEquals(1, legacy.bool().mustNot().size());
        assertTrue(legacy.bool().mustNot().get(0).isExists());
        assertEquals("effectiveAt", legacy.bool().mustNot().get(0).exists().field());

        Query notExpired = filter.bool().must().get(1);
        assertTrue(notExpired.isBool());
        assertEquals("expiresAt", notExpired.bool().should().get(0).range().field());
        assertEquals(LocalDate.now().toString(), notExpired.bool().should().get(0).range().gte().to(String.class));
    }

    @Test
    void nullFilterDefaultsToToday() {
        Query filter = ComplianceRetrievalFilter.build(null);

        assertTrue(filter.isBool());
        Query range = filter.bool().must().get(0).bool().should().get(0);
        assertEquals(LocalDate.now().toString(), range.range().lte().to(String.class));
    }

    @Test
    void localJurisdictionExpandsToLocalPlusNational() {
        ComplianceSearchFilter input = ComplianceSearchFilter.fromCodes(
                List.of("REGULATION"), List.of("上海市"), AS_OF);

        Query filter = ComplianceRetrievalFilter.build(input);

        assertEquals(4, filter.bool().must().size(), "docType + 地区 + 生效 + 未过期");
        Query jurisdiction = filter.bool().must().get(1);
        assertTrue(jurisdiction.isBool());

        Map<String, Set<String>> terms = termsByField(jurisdiction);
        assertEquals(Set.of("上海", "NATIONAL"), terms.get("jurisdictionCode"));
        assertTrue(terms.get("jurisdiction").containsAll(Set.of("上海", "全国", "中国")));

        assertTrue(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdictionCode", "上海",
                "effectiveAt", "2026-01-01")));
        assertTrue(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdictionCode", "NATIONAL",
                "effectiveAt", "2026-01-01")), "地方问题必须能命中全国适用资料");
        assertFalse(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdictionCode", "北京",
                "effectiveAt", "2026-01-01")), "不能把其他地区资料带进来");
    }

    @Test
    void nationwideQueryDoesNotPullOtherRegions() {
        Query filter = ComplianceRetrievalFilter.build(ComplianceSearchFilter.fromCodes(
                List.of("REGULATION"), List.of("全国"), AS_OF));

        Map<String, Set<String>> terms = termsByField(filter.bool().must().get(1));
        assertEquals(Set.of("NATIONAL"), terms.get("jurisdictionCode"));
        assertFalse(terms.get("jurisdictionCode").contains("上海"));

        assertTrue(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdictionCode", "NATIONAL", "effectiveAt", "2026-01-01")));
        assertFalse(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdictionCode", "上海", "effectiveAt", "2026-01-01")));
    }

    @Test
    void legacyJurisdictionDisplayStillMatches() {
        Query filter = ComplianceRetrievalFilter.build(ComplianceSearchFilter.fromCodes(
                List.of("REGULATION"), List.of("上海"), AS_OF));

        assertTrue(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdiction", "上海市", "effectiveAt", "2026-01-01")),
                "旧 ES 文档只有 jurisdiction 显示值，也要能命中");
        assertTrue(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdiction", "全国", "effectiveAt", "2026-01-01")),
                "旧文档的“全国”显示值也要命中地方问题");
    }

    @Test
    void currentEffectiveMatchesMissingAndPastButRejectsFuture() {
        Query filter = ComplianceRetrievalFilter.build(
                new ComplianceSearchFilter(Set.of(), Set.of(), AS_OF));

        assertTrue(matches(filter, Map.of("effectiveAt", "2026-10-20")), "已生效版本保留");
        assertTrue(matches(filter, Map.of("effectiveAt", "2026-10-21")), "asOf 当天视为已生效");
        assertTrue(matches(filter, Map.of()), "没有 effectiveAt 的旧分块继续可检索（效力待确认）");
        assertFalse(matches(filter, Map.of("effectiveAt", "2026-10-22")), "未来生效版本不能返回");
        assertFalse(matches(filter, Map.of("effectiveAt", "2027-01-01")));
    }

    @Test
    void expiredAndRepealedDocumentsAreExcluded() {
        Query filter = ComplianceRetrievalFilter.build(
                new ComplianceSearchFilter(Set.of(), Set.of(), AS_OF));

        assertFalse(matches(filter, Map.of(
                "effectiveAt", "2025-01-01", "expiresAt", "2026-10-20")), "已过失效日期");
        assertTrue(matches(filter, Map.of(
                "effectiveAt", "2025-01-01", "expiresAt", "2026-10-21")), "失效日当天仍可检索");
        assertTrue(matches(filter, Map.of(
                "effectiveAt", "2025-01-01", "expiresAt", "2026-10-22")));
        assertFalse(matches(filter, Map.of(
                "effectiveAt", "2025-01-01", "repealed", true)), "废止资料必须排除");
        assertTrue(matches(filter, Map.of(
                "effectiveAt", "2025-01-01", "repealed", false)));
    }

    @Test
    void docTypeAndJurisdictionMustMatchTogether() {
        Query filter = ComplianceRetrievalFilter.build(ComplianceSearchFilter.fromCodes(
                List.of("REGULATION"), List.of("全国"), AS_OF));

        assertTrue(matches(filter, Map.of(
                "docType", "REGULATION", "jurisdictionCode", "NATIONAL", "effectiveAt", "2026-10-01")));
        assertFalse(matches(filter, Map.of(
                "docType", "INTERNAL_POLICY", "jurisdictionCode", "NATIONAL", "effectiveAt", "2026-10-01")));
        assertFalse(matches(filter, Map.of(
                "jurisdictionCode", "NATIONAL", "effectiveAt", "2026-10-01")), "缺少 docType 不满足指定类型");
    }

    @Test
    void combinedFilterKeepsPermissionScopeAndComplianceSemantics() {
        Query permission = Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("userId").value("7")));
            b.should(s -> s.term(t -> t.field("public").value(true)));
            return b.minimumShouldMatch("1");
        }));
        Query scope = DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of("b1"), Set.of("md5-old")));
        Query compliance = ComplianceRetrievalFilter.build(ComplianceSearchFilter.fromCodes(
                List.of("REGULATION"), List.of("上海"), AS_OF));

        Query combined = ComplianceRetrievalFilter.combine(permission, scope, compliance);

        assertTrue(combined.isBool());
        assertEquals(3, combined.bool().must().size());
        assertSame(permission, combined.bool().must().get(0));
        assertSame(scope, combined.bool().must().get(1));
        assertSame(compliance, combined.bool().must().get(2));

        Map<String, Object> allowed = Map.of(
                "buildId", "b1", "userId", "7", "public", false,
                "docType", "REGULATION", "jurisdictionCode", "上海", "effectiveAt", "2026-10-01");
        assertTrue(matches(combined, allowed), "active build + 本人 + 监管规定 + 上海/全国");

        assertFalse(matches(combined, Map.of(
                "buildId", "b1", "userId", "7", "public", false,
                "docType", "REGULATION", "jurisdictionCode", "上海", "effectiveAt", "2026-11-01")),
                "未来生效版本被排除");
        assertFalse(matches(combined, Map.of(
                "buildId", "b1", "userId", "7", "public", false,
                "docType", "PRODUCT_TERMS", "jurisdictionCode", "上海", "effectiveAt", "2026-10-01")),
                "文档类型不符");
        assertFalse(matches(combined, Map.of(
                "buildId", "b1", "userId", "8", "public", false,
                "docType", "REGULATION", "jurisdictionCode", "上海", "effectiveAt", "2026-10-01")),
                "权限过滤仍然生效");
        assertFalse(matches(combined, Map.of(
                "buildId", "b2", "fileMd5", "md5-new", "userId", "7", "public", false,
                "docType", "REGULATION", "jurisdictionCode", "上海", "effectiveAt", "2026-10-01")),
                "未激活 build 仍然排除");
    }

    @Test
    void combineIgnoresNullClausesAndKeepsSingleFilter() {
        Query compliance = ComplianceRetrievalFilter.build(ComplianceSearchFilter.current());

        assertSame(compliance, ComplianceRetrievalFilter.combine(null, null, compliance));
        Query scope = DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of("b1"), Set.of()));
        Query combined = ComplianceRetrievalFilter.combine(null, scope, compliance);
        assertTrue(combined.isBool());
        assertEquals(2, combined.bool().must().size());
    }

    @Test
    void combineWithoutAnyClauseReturnsNull() {
        assertNull(ComplianceRetrievalFilter.combine(null, null, null));
    }

    private static Map<String, Set<String>> termsByField(Query query) {
        assertTrue(query.isBool());
        return query.bool().should().stream()
                .map(Query::terms)
                .collect(Collectors.toMap(
                        terms -> terms.field(),
                        terms -> terms.terms().value().stream()
                                .map(FieldValue::stringValue)
                                .collect(Collectors.toSet())));
    }

    /**
     * 在查询结构上模拟 ES 对单个分块的匹配结果，覆盖 bool/term/terms/exists/range。
     */
    private static boolean matches(Query query, Map<String, Object> fields) {
        if (query.isBool()) {
            BoolQuery bool = query.bool();
            for (Query must : bool.must()) {
                if (!matches(must, fields)) {
                    return false;
                }
            }
            for (Query filter : bool.filter()) {
                if (!matches(filter, fields)) {
                    return false;
                }
            }
            for (Query mustNot : bool.mustNot()) {
                if (matches(mustNot, fields)) {
                    return false;
                }
            }
            if (bool.should().isEmpty()) {
                return true;
            }
            long matched = bool.should().stream().filter(clause -> matches(clause, fields)).count();
            int minimum = bool.minimumShouldMatch() == null
                    ? (bool.must().isEmpty() && bool.filter().isEmpty() ? 1 : 0)
                    : Integer.parseInt(bool.minimumShouldMatch());
            return matched >= minimum;
        }
        if (query.isExists()) {
            return fields.get(query.exists().field()) != null;
        }
        if (query.isTerms()) {
            Object value = fields.get(query.terms().field());
            return value != null && query.terms().terms().value().stream()
                    .map(FieldValue::stringValue)
                    .anyMatch(candidate -> candidate.equals(String.valueOf(value)));
        }
        if (query.isTerm()) {
            Object value = fields.get(query.term().field());
            if (value == null) {
                return false;
            }
            FieldValue expected = query.term().value();
            if (expected.isBoolean()) {
                return Boolean.parseBoolean(String.valueOf(value)) == expected.booleanValue();
            }
            return expected.isString() && expected.stringValue().equals(String.valueOf(value));
        }
        if (query.isRange()) {
            Object value = fields.get(query.range().field());
            if (value == null) {
                return false;
            }
            String lowerBound = query.range().gte() == null ? null : query.range().gte().to(String.class);
            String upperBound = query.range().lte() == null ? null : query.range().lte().to(String.class);
            String actual = String.valueOf(value);
            return (lowerBound == null || actual.compareTo(lowerBound) >= 0)
                    && (upperBound == null || actual.compareTo(upperBound) <= 0);
        }
        throw new IllegalArgumentException("测试只覆盖合规过滤用到的查询类型: " + query);
    }
}
