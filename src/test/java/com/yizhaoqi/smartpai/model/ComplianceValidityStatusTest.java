package com.yizhaoqi.smartpai.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceValidityStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @Test
    void missingEffectiveDateIsUnconfirmedEvenWhenUploadedLongAgo() {
        assertThat(ComplianceValidityStatus.resolve(null, null, false, TODAY))
                .isEqualTo(ComplianceValidityStatus.UNCONFIRMED);
        assertThat(ComplianceValidityStatus.UNCONFIRMED.activationEligible()).isTrue();
    }

    @Test
    void futureEffectiveDateIsPendingAndNotYetActivatable() {
        ComplianceValidityStatus status =
                ComplianceValidityStatus.resolve(TODAY.plusDays(1), null, false, TODAY);

        assertThat(status).isEqualTo(ComplianceValidityStatus.PENDING);
        assertThat(status.activationEligible()).isFalse();
    }

    @Test
    void effectiveDateOnOrBeforeTodayIsEffective() {
        assertThat(ComplianceValidityStatus.resolve(TODAY, null, false, TODAY))
                .isEqualTo(ComplianceValidityStatus.EFFECTIVE);
        assertThat(ComplianceValidityStatus.resolve(TODAY.minusDays(1), null, false, TODAY))
                .isEqualTo(ComplianceValidityStatus.EFFECTIVE);
        assertThat(ComplianceValidityStatus.EFFECTIVE.activationEligible()).isTrue();
    }

    @Test
    void expiryDateBeforeTodayMarksExpiredButExpiryTodayIsStillEffective() {
        assertThat(ComplianceValidityStatus.resolve(TODAY.minusDays(10), TODAY.minusDays(1), false, TODAY))
                .isEqualTo(ComplianceValidityStatus.EXPIRED);
        assertThat(ComplianceValidityStatus.resolve(TODAY.minusDays(10), TODAY, false, TODAY))
                .isEqualTo(ComplianceValidityStatus.EFFECTIVE);
    }

    @Test
    void repealedWinsOverOtherDates() {
        ComplianceValidityStatus status =
                ComplianceValidityStatus.resolve(TODAY.minusDays(10), null, true, TODAY);

        assertThat(status).isEqualTo(ComplianceValidityStatus.REPEALED);
        assertThat(status.activationEligible()).isFalse();
    }
}
