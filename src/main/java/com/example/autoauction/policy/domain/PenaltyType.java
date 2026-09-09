package com.example.autoauction.policy.domain;

/**
 * X3-E: how a {@link PolicyVersionViolation}'s configured penalty value is interpreted.
 *
 * PERCENTAGE: value is 0-100 (this module's own scale - deliberately NOT the 0-1 fraction
 * convention {@link PolicyVersion#getCommissionRate()} uses; the two are independent
 * columns/values on independent tables). The penalty is computed against the ORIGINAL frozen
 * seller commitment (never a remaining balance) - see SellerViolationResolutionEngine.
 *
 * FIXED_AMOUNT: value is an absolute currency amount, used as-is (no percentage math).
 *
 * All six initial violations use PERCENTAGE - FIXED_AMOUNT exists so the model does not need
 * to change if a future policy version ever configures a fixed-amount penalty.
 */
public enum PenaltyType {
    PERCENTAGE,
    FIXED_AMOUNT
}
