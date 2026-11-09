package com.yizhaoqi.smartpai.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceMetadataTest {

    @Test
    void missingEffectiveDateIsKeptEmptyInsteadOfBeingFilledWithUploadDate() {
        ComplianceMetadata metadata = new ComplianceMetadata(
                "REGULATION", "上海市", "国家金融监督管理总局", null, "商业银行", null, false);

        assertThat(metadata.effectiveAt()).isNull();
        assertThat(metadata.validityStatus(LocalDate.of(2026, 10, 21)))
                .isEqualTo(ComplianceValidityStatus.UNCONFIRMED);
        assertThat(metadata.jurisdiction()).isEqualTo("上海");
        assertThat(metadata.jurisdictionCode()).isEqualTo("上海");
    }

    @Test
    void newVersionInheritsClassificationButNotValidityDates() {
        ComplianceMetadata parent = new ComplianceMetadata(
                "REGULATION", "全国", "监管总局", LocalDate.of(2025, 1, 1),
                "商业银行", LocalDate.of(2026, 12, 31), false);
        ComplianceMetadata requested = new ComplianceMetadata(
                null, null, null, LocalDate.of(2026, 11, 1), null, null, false);

        ComplianceMetadata inherited = requested.inheritFrom(parent);

        assertThat(inherited.docType()).isEqualTo("REGULATION");
        assertThat(inherited.jurisdiction()).isEqualTo("全国");
        assertThat(inherited.issuer()).isEqualTo("监管总局");
        assertThat(inherited.applicableInstitutions()).isEqualTo("商业银行");
        assertThat(inherited.effectiveAt()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(inherited.expiresAt()).isNull();
        assertThat(inherited.repealed()).isFalse();
    }

    @Test
    void issuerAndApplicableInstitutionsAreDifferentFields() {
        ComplianceMetadata metadata = new ComplianceMetadata(
                "INTERNAL_POLICY", "全国", "某银行总行", LocalDate.of(2026, 1, 1),
                "某银行上海分行", null, false);

        assertThat(metadata.issuer()).isEqualTo("某银行总行");
        assertThat(metadata.applicableInstitutions()).isEqualTo("某银行上海分行");
    }
}
