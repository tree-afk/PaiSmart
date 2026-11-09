package com.yizhaoqi.smartpai.model;

import java.time.LocalDate;

/**
 * 元数据修订的字段级 patch：只有显式出现在请求里的字段才会生效。
 * null/空白文本和 null 日期表示“明确清空”，字段缺失表示“保持不变”；
 * 不能复用新版本的 inheritFrom 规则，否则清空会被旧值覆盖、未提交字段会被清空。
 */
public record ComplianceMetadataPatch(
        boolean docTypeSet, String docType,
        boolean jurisdictionSet, String jurisdiction,
        boolean issuerSet, String issuer,
        boolean applicableInstitutionsSet, String applicableInstitutions,
        boolean effectiveAtSet, LocalDate effectiveAt,
        boolean expiresAtSet, LocalDate expiresAt,
        boolean repealedSet, boolean repealed) {

    public static ComplianceMetadataPatch full(ComplianceMetadata metadata) {
        ComplianceMetadata value = metadata == null ? ComplianceMetadata.empty() : metadata;
        return builder()
                .docType(value.docType())
                .jurisdiction(value.jurisdiction())
                .issuer(value.issuer())
                .applicableInstitutions(value.applicableInstitutions())
                .effectiveAt(value.effectiveAt())
                .expiresAt(value.expiresAt())
                .repealed(value.repealed())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return !docTypeSet && !jurisdictionSet && !issuerSet && !applicableInstitutionsSet
                && !effectiveAtSet && !expiresAtSet && !repealedSet;
    }

    public ComplianceMetadata applyTo(ComplianceMetadata current) {
        ComplianceMetadata base = current == null ? ComplianceMetadata.empty() : current;
        return new ComplianceMetadata(
                docTypeSet ? docType : base.docType(),
                jurisdictionSet ? jurisdiction : base.jurisdiction(),
                issuerSet ? issuer : base.issuer(),
                effectiveAtSet ? effectiveAt : base.effectiveAt(),
                applicableInstitutionsSet ? applicableInstitutions : base.applicableInstitutions(),
                expiresAtSet ? expiresAt : base.expiresAt(),
                repealedSet ? repealed : base.repealed());
    }

    public static final class Builder {
        private boolean docTypeSet;
        private String docType;
        private boolean jurisdictionSet;
        private String jurisdiction;
        private boolean issuerSet;
        private String issuer;
        private boolean applicableInstitutionsSet;
        private String applicableInstitutions;
        private boolean effectiveAtSet;
        private LocalDate effectiveAt;
        private boolean expiresAtSet;
        private LocalDate expiresAt;
        private boolean repealedSet;
        private boolean repealed;

        public Builder docType(String value) {
            this.docType = value;
            this.docTypeSet = true;
            return this;
        }

        public Builder jurisdiction(String value) {
            this.jurisdiction = value;
            this.jurisdictionSet = true;
            return this;
        }

        public Builder issuer(String value) {
            this.issuer = value;
            this.issuerSet = true;
            return this;
        }

        public Builder applicableInstitutions(String value) {
            this.applicableInstitutions = value;
            this.applicableInstitutionsSet = true;
            return this;
        }

        public Builder effectiveAt(LocalDate value) {
            this.effectiveAt = value;
            this.effectiveAtSet = true;
            return this;
        }

        public Builder expiresAt(LocalDate value) {
            this.expiresAt = value;
            this.expiresAtSet = true;
            return this;
        }

        public Builder repealed(boolean value) {
            this.repealed = value;
            this.repealedSet = true;
            return this;
        }

        public ComplianceMetadataPatch build() {
            return new ComplianceMetadataPatch(
                    docTypeSet, docType,
                    jurisdictionSet, jurisdiction,
                    issuerSet, issuer,
                    applicableInstitutionsSet, applicableInstitutions,
                    effectiveAtSet, effectiveAt,
                    expiresAtSet, expiresAt,
                    repealedSet, repealed);
        }
    }
}
