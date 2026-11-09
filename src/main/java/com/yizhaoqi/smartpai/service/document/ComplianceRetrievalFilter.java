package com.yizhaoqi.smartpai.service.document;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.yizhaoqi.smartpai.model.JurisdictionNormalizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 合规检索过滤：docType / jurisdictionCode，加上"当前可检索"判断。
 * <p>
 * 当前可检索的口径：
 * <ul>
 *     <li>effectiveAt 缺失（效力待确认）或 effectiveAt <= asOf；</li>
 *     <li>expiresAt 缺失或 expiresAt >= asOf；</li>
 *     <li>repealed 不为 true。</li>
 * </ul>
 * 这只说明“按已标注日期可以检索”，不把 effectiveAt <= today 直接当成“现行有效”。
 * 地区查询地方时自动包含全国适用资料；全国查询只返回全国资料。
 */
public final class ComplianceRetrievalFilter {

    static final String FIELD_DOC_TYPE = "docType";
    static final String FIELD_JURISDICTION = "jurisdiction";
    static final String FIELD_JURISDICTION_CODE = "jurisdictionCode";
    static final String FIELD_EFFECTIVE_AT = "effectiveAt";
    static final String FIELD_EXPIRES_AT = "expiresAt";
    static final String FIELD_REPEALED = "repealed";

    private ComplianceRetrievalFilter() {
    }

    /**
     * 合规过滤为空时仍会生成有效期与废止判断，不能返回 null
     */
    public static Query build(ComplianceSearchFilter filter) {
        ComplianceSearchFilter effective = filter == null ? ComplianceSearchFilter.current() : filter;
        return Query.of(q -> q.bool(b -> {
            if (!effective.docTypes().isEmpty()) {
                List<FieldValue> docTypes = effective.docTypes().stream()
                        .map(type -> FieldValue.of(type.name()))
                        .toList();
                b.must(m -> m.terms(t -> t.field(FIELD_DOC_TYPE)
                        .terms(tv -> tv.value(docTypes))));
            }
            appendJurisdictionFilter(b, effective);
            b.must(currentEffective(effective.asOf()));
            b.must(notExpired(effective.asOf()));
            b.mustNot(m -> m.term(t -> t.field(FIELD_REPEALED).value(true)));
            return b;
        }));
    }

    private static void appendJurisdictionFilter(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
                                                 ComplianceSearchFilter filter) {
        if (filter.jurisdictions().isEmpty()) {
            return;
        }
        Set<String> codes = JurisdictionNormalizer.queryCodes(filter.jurisdictions());
        Set<String> displays = JurisdictionNormalizer.queryDisplays(filter.jurisdictions());
        if (codes.isEmpty() && displays.isEmpty()) {
            return;
        }
        b.must(m -> m.bool(j -> {
            if (!codes.isEmpty()) {
                List<FieldValue> codeValues = codes.stream().map(FieldValue::of).toList();
                j.should(s -> s.terms(t -> t.field(FIELD_JURISDICTION_CODE)
                        .terms(tv -> tv.value(codeValues))));
            }
            if (!displays.isEmpty()) {
                List<FieldValue> displayValues = displays.stream().map(FieldValue::of).toList();
                j.should(s -> s.terms(t -> t.field(FIELD_JURISDICTION)
                        .terms(tv -> tv.value(displayValues))));
            }
            j.minimumShouldMatch("1");
            return j;
        }));
    }

    /**
     * effectiveAt <= asOf OR effectiveAt 缺失；未来生效的文档被排除，legacy 文档继续可检索
     */
    static Query currentEffective(LocalDate asOf) {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.range(r -> r.field(FIELD_EFFECTIVE_AT)
                    .lte(JsonData.of(asOf.toString()))));
            b.should(s -> s.bool(legacy -> legacy.mustNot(m -> m.exists(e -> e.field(FIELD_EFFECTIVE_AT)))));
            return b.minimumShouldMatch("1");
        }));
    }

    /**
     * expiresAt >= asOf OR expiresAt 缺失；已过失效日期的文档排除，legacy 文档继续可检索
     */
    static Query notExpired(LocalDate asOf) {
        return Query.of(q -> q.bool(b -> {
            b.should(s -> s.range(r -> r.field(FIELD_EXPIRES_AT)
                    .gte(JsonData.of(asOf.toString()))));
            b.should(s -> s.bool(legacy -> legacy.mustNot(m -> m.exists(e -> e.field(FIELD_EXPIRES_AT)))));
            return b.minimumShouldMatch("1");
        }));
    }

    /**
     * 把权限过滤、版本范围过滤和合规过滤合并成同一个 bool must；null 子句忽略，只有一个时原样返回
     */
    public static Query combine(Query permission, Query scopeFilter, Query complianceFilter) {
        List<Query> clauses = new ArrayList<>(3);
        if (permission != null) {
            clauses.add(permission);
        }
        if (scopeFilter != null) {
            clauses.add(scopeFilter);
        }
        if (complianceFilter != null) {
            clauses.add(complianceFilter);
        }
        if (clauses.isEmpty()) {
            return null;
        }
        if (clauses.size() == 1) {
            return clauses.get(0);
        }
        return Query.of(q -> q.bool(b -> b.must(clauses)));
    }
}
