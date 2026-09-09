package com.example.autoauction.policy.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * X3-E corrective (BLOCKER 5): domain-level proof of PolicyVersionViolation's own penalty-value
 * validation - the first (and, together with V38's DB CHECK constraint, the only) layer that
 * guards against an invalid percentage or fixed amount ever being configured at all. Not
 * duplicated at the service layer (PolicyVersionService.updateDraftViolation constructs this
 * object and lets its own constructor enforce the rule - one validation point, not two).
 */
@DisplayName("PolicyVersionViolation — X3-E domain unit tests")
class PolicyVersionViolationTest {

    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    @DisplayName("PERCENTAGE: valid boundary values (0 and 100) are accepted")
    void constructor_PercentageBoundaryValues_Accepted() {
        assertDoesNotThrow(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.PERCENTAGE, BigDecimal.ZERO, now));
        assertDoesNotThrow(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100"), now));
    }

    @Test
    @DisplayName("PERCENTAGE: negative value is rejected")
    void constructor_PercentageNegative_Rejected() {
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("-1"), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PERCENTAGE: value above 100 is rejected")
    void constructor_PercentageAbove100_Rejected() {
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.PERCENTAGE, new BigDecimal("100.01"), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FIXED_AMOUNT: zero is accepted")
    void constructor_FixedAmountZero_Accepted() {
        assertDoesNotThrow(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.FIXED_AMOUNT, BigDecimal.ZERO, now));
    }

    @Test
    @DisplayName("FIXED_AMOUNT: negative value is rejected")
    void constructor_FixedAmountNegative_Rejected() {
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.FIXED_AMOUNT, new BigDecimal("-0.01"), now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FIXED_AMOUNT: a value above 100 (invalid for PERCENTAGE) is perfectly valid here - " +
            "the two penalty types have independent bounds")
    void constructor_FixedAmountAbove100_Accepted() {
        assertDoesNotThrow(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.FIXED_AMOUNT, new BigDecimal("50000"), now));
    }

    @Test
    @DisplayName("null policyVersionId, violationDefinitionId, penaltyType, penaltyValue, createdAt are all rejected")
    void constructor_NullRequiredFields_Rejected() {
        assertThatThrownBy(() -> new PolicyVersionViolation(null, 1L, PenaltyType.PERCENTAGE, BigDecimal.TEN, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, null, PenaltyType.PERCENTAGE, BigDecimal.TEN, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, 1L, null, BigDecimal.TEN, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.PERCENTAGE, null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PolicyVersionViolation(1L, 1L, PenaltyType.PERCENTAGE, BigDecimal.TEN, null))
                .isInstanceOf(NullPointerException.class);
    }
}
