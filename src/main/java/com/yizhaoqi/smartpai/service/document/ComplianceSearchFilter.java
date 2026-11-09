package com.yizhaoqi.smartpai.service.document;

import com.yizhaoqi.smartpai.model.DocumentComplianceType;
import com.yizhaoqi.smartpai.model.JurisdictionNormalizer;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 合规检索过滤条件：文档类型、适用地区，以及“当前可检索”的判断基准日。
 * <p>
 * docTypes 为空表示不限类型；jurisdictions 为空表示不限地区。地区在进入过滤前会去掉
 * “中国/全国”等别名和行政区后缀；检索地方时过滤层会自动补上全国适用资料。
 * asOf 只用于生效/失效日期的判断，不做历史版本回溯。
 */
public record ComplianceSearchFilter(Set<DocumentComplianceType> docTypes,
                                     Set<String> jurisdictions,
                                     LocalDate asOf) {

    public ComplianceSearchFilter {
        docTypes = normalizeTypes(docTypes);
        jurisdictions = normalizeJurisdictions(jurisdictions);
        asOf = asOf == null ? LocalDate.now() : asOf;
    }

    /**
     * 默认过滤：不限类型和地区，只保留按标注日期当前可检索的版本
     */
    public static ComplianceSearchFilter current() {
        return new ComplianceSearchFilter(Set.of(), Set.of(), LocalDate.now());
    }

    /**
     * 不带任何类型/地区条件的过滤；与 {@link #current()} 相同，语义上强调"用户没有指定维度"
     */
    public static ComplianceSearchFilter empty() {
        return current();
    }

    /**
     * 从工具参数的字符串代码构建。类型代码必须是合法枚举名，非法值直接抛错，由工具层作为错误返回给模型纠正。
     */
    public static ComplianceSearchFilter fromCodes(Collection<String> docTypeCodes,
                                                   Collection<String> jurisdictions,
                                                   LocalDate asOf) {
        Set<DocumentComplianceType> types = new LinkedHashSet<>();
        if (docTypeCodes != null) {
            for (String code : docTypeCodes) {
                if (code == null || code.isBlank()) {
                    continue;
                }
                DocumentComplianceType type = DocumentComplianceType.fromCode(code);
                if (type == null) {
                    throw new IllegalArgumentException("不支持的文档类型: " + code.trim()
                            + "，可选值: " + DocumentComplianceType.allowedCodesWithLabels());
                }
                types.add(type);
            }
        }
        return new ComplianceSearchFilter(types, new LinkedHashSet<>(
                jurisdictions == null ? Set.of() : jurisdictions), asOf);
    }

    /**
     * 是否没有指定任何类型/地区维度；现行有效的日期判断仍然生效
     */
    public boolean isEmpty() {
        return docTypes.isEmpty() && jurisdictions.isEmpty();
    }

    private static Set<DocumentComplianceType> normalizeTypes(Set<DocumentComplianceType> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<DocumentComplianceType> normalized = new LinkedHashSet<>();
        for (DocumentComplianceType type : source) {
            if (type != null) {
                normalized.add(type);
            }
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }

    private static Set<String> normalizeJurisdictions(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : source) {
            String display = JurisdictionNormalizer.normalizeDisplay(value);
            if (display != null) {
                normalized.add(display);
            }
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }
}
