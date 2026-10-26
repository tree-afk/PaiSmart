package com.yizhaoqi.smartpai.model;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 合规文档元数据值对象：文档类型、适用地区、发布机构、适用机构、生效/失效日期和废止标记。
 * <p>
 * docType 只接受 {@link DocumentComplianceType} 的枚举名；jurisdiction 统一规范成“全国/上海”等显示值，
 * 编码由 {@link #jurisdictionCode()} 派生。issuer 只表示谁发布资料，applicableInstitutions 才表示资料适用于谁，
 * 两者不能互相替代。effectiveAt 缺失时保持为空，表示“效力待确认”，不再按上传日期补齐。
 * <p>
 * 上传新版本时 docType/jurisdiction/issuer/applicableInstitutions 未填则继承当前 active 版本；
 * 生效日期、失效日期和废止标记属于本次版本的有效性声明，不继承。
 */
public record ComplianceMetadata(String docType,
                                 String jurisdiction,
                                 String issuer,
                                 LocalDate effectiveAt,
                                 String applicableInstitutions,
                                 LocalDate expiresAt,
                                 boolean repealed) {

    private static final ComplianceMetadata EMPTY =
            new ComplianceMetadata(null, null, null, null, null, null, false);

    public ComplianceMetadata {
        docType = DocumentComplianceType.normalize(docType);
        jurisdiction = JurisdictionNormalizer.normalizeDisplay(jurisdiction);
        issuer = trimToNull(issuer);
        applicableInstitutions = trimToNull(applicableInstitutions);
    }

    /**
     * 兼容旧调用方/旧测试的四字段入口；有效期扩展字段默认为空。
     */
    public ComplianceMetadata(String docType, String jurisdiction, String issuer, LocalDate effectiveAt) {
        this(docType, jurisdiction, issuer, effectiveAt, null, null, false);
    }

    public static ComplianceMetadata empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return docType == null
                && jurisdiction == null
                && issuer == null
                && effectiveAt == null
                && applicableInstitutions == null
                && expiresAt == null
                && !repealed;
    }

    public String jurisdictionCode() {
        return JurisdictionNormalizer.normalizeCode(jurisdiction);
    }

    public ComplianceValidityStatus validityStatus(LocalDate asOf) {
        return ComplianceValidityStatus.resolve(effectiveAt, expiresAt, repealed, asOf);
    }

    public boolean activationEligible(LocalDate asOf) {
        return validityStatus(asOf).activationEligible();
    }

    /**
     * 新版本继承旧版本的分类/适用范围：请求里已填的覆盖，未填的沿用父版本；
     * 有效期三字段始终取本次请求，不继承旧版本。
     */
    public ComplianceMetadata inheritFrom(ComplianceMetadata parent) {
        if (parent == null) {
            return this;
        }
        return new ComplianceMetadata(
                docType != null ? docType : parent.docType(),
                jurisdiction != null ? jurisdiction : parent.jurisdiction(),
                issuer != null ? issuer : parent.issuer(),
                effectiveAt,
                applicableInstitutions != null ? applicableInstitutions : parent.applicableInstitutions(),
                expiresAt,
                repealed);
    }

    /**
     * 消息/ES 里的日期统一用 ISO yyyy-MM-dd 字符串，避免 JSON 日期格式依赖序列化器配置
     */
    public String wireEffectiveAt() {
        return effectiveAt == null ? null : effectiveAt.toString();
    }

    public String wireExpiresAt() {
        return expiresAt == null ? null : expiresAt.toString();
    }

    /**
     * 解析 ISO 日期，空白或格式错误返回 null（旧消息、历史数据都可能为空）
     */
    public static LocalDate parseEffectiveAt(String value) {
        return parseDate(value);
    }

    public static LocalDate parseExpiresAt(String value) {
        return parseDate(value);
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
