package com.example.autoauction.deposit.domain;

/**
 * F6: lifecycle of a manual payout request - PENDING -> PROCESSING -> PAID, or
 * PENDING -> REJECTED. PAID and REJECTED are final.
 */
public enum WithdrawalRequestStatus {
    /** Created by the user; the amount is already debited from the deposit balance. */
    PENDING,
    /** An admin has taken the request into work; the payout is NOT done yet. */
    PROCESSING,
    /** An admin confirmed that AlSo paid the user by hand. Not a bank confirmation. */
    PAID,
    /** Refused before any payout; the amount was returned to the deposit balance. */
    REJECTED
}
