package com.yizhaoqi.smartpai.service.document;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享库版本过滤：只保留当前发布文档的 active build 分块。
 * 没有 active build 时返回匹配不到任何文档的查询，不能退化成“不过滤”；
 * 旧的无 buildId 分块也不再兼容放行，必须先登记为版本并发布。
 */
public final class DocumentRetrievalFilter {

    private static final String NO_ACTIVE_BUILD = "__no_active_build__";

    private DocumentRetrievalFilter() {
    }

    public static Query build(ActiveRetrievalScope scope) {
        List<FieldValue> values = new ArrayList<>();
        if (scope != null) {
            scope.activeBuildIds().forEach(value -> values.add(FieldValue.of(value)));
        }
        if (values.isEmpty()) {
            values.add(FieldValue.of(NO_ACTIVE_BUILD));
        }
        return Query.of(q -> q.terms(t -> t.field("buildId").terms(tv -> tv.value(values))));
    }

    public static Query combine(Query permission, Query scopeFilter) {
        if (scopeFilter == null) {
            return permission;
        }
        if (permission == null) {
            return scopeFilter;
        }
        return Query.of(q -> q.bool(b -> b.must(permission).must(scopeFilter)));
    }
}
