package com.yizhaoqi.smartpai.service.document;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享库版本过滤：只放行 active build；没有 active build 时匹配不到任何文档，legacy 不再兼容放行。
 */
class DocumentRetrievalFilterTest {

    @Test
    void emptyOrNullScopeMatchesNothing() {
        for (Query filter : new Query[]{
                DocumentRetrievalFilter.build(null),
                DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of(), Set.of()))
        }) {
            assertTrue(filter.isTerms());
            assertEquals("buildId", filter.terms().field());
            assertEquals(Set.of("__no_active_build__"), values(filter));
            assertFalse(matches(filter, Map.of("fileMd5", "md5-legacy")),
                    "没有 active build 时 legacy 分块不能绕过发布范围");
            assertFalse(matches(filter, Map.of("buildId", "b-staged")));
        }
    }

    @Test
    void activeBuildsBecomeExactTermsAndLegacyIsNotIncluded() {
        Query filter = DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of("b1", "b2"), Set.of()));

        assertTrue(filter.isTerms());
        assertEquals(Set.of("b1", "b2"), values(filter));
        assertTrue(matches(filter, Map.of("buildId", "b1")));
        assertFalse(matches(filter, Map.of("buildId", "b2-other")));
        assertFalse(matches(filter, Map.of("fileMd5", "md5-legacy")));
    }

    @Test
    void retiredLegacySetNoLongerOpensTheLegacyBranch() {
        Query filter = DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of(), Set.of("md5-old")));

        assertTrue(filter.isTerms());
        assertEquals(Set.of("__no_active_build__"), values(filter));
        assertFalse(matches(filter, Map.of("fileMd5", "md5-old")));
    }

    @Test
    void combineWithoutScopeFilterReturnsPermissionUnchanged() {
        Query permission = Query.of(q -> q.term(t -> t.field("userId").value("7")));

        assertSame(permission, DocumentRetrievalFilter.combine(permission, null));
    }

    @Test
    void combineTreatsEmptyScopeFilterAsARealMustClause() {
        Query permission = Query.of(q -> q.term(t -> t.field("userId").value("7")));
        Query scope = DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of(), Set.of()));

        Query combined = DocumentRetrievalFilter.combine(permission, scope);

        assertTrue(combined.isBool());
        assertEquals(2, combined.bool().must().size());
        assertSame(permission, combined.bool().must().get(0));
        assertSame(scope, combined.bool().must().get(1));
    }

    @Test
    void combineWithoutPermissionReturnsScopeUnchanged() {
        Query scope = DocumentRetrievalFilter.build(new ActiveRetrievalScope(Set.of("b1"), Set.of()));

        assertSame(scope, DocumentRetrievalFilter.combine(null, scope));
    }

    private static Set<String> values(Query terms) {
        return terms.terms().terms().value().stream()
                .map(FieldValue::stringValue)
                .collect(Collectors.toSet());
    }

    private static boolean matches(Query query, Map<String, Object> fields) {
        if (query.isTerms()) {
            Object value = fields.get(query.terms().field());
            return value != null && values(query).contains(String.valueOf(value));
        }
        throw new IllegalArgumentException("测试只覆盖 terms 查询: " + query);
    }
}
