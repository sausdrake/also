package com.example.autoauction.deposit.domain;

/**
 * Slice 2 · Step 2A: the structured, closed set of reasons an admin may cite when manually
 * adjusting a user's deposit via {@code DepositService.adminAdjust}. Replaces the previous
 * free-form comment string - no arbitrary reason text is accepted.
 *
 * <p>Deliberately does NOT include a {@code CASH_DEPOSIT} value (locked business decision - no
 * cash-funding path exists or is ever implied by this mechanism). Adding a value requires
 * re-opening the Slice 2 business lock, not a casual addition here.
 */
public enum AdminAdjustmentReason {
    RECONCILIATION,
    MANUAL_REFUND,
    CHARGEBACK,
    SUPPORT_CORRECTION,
    OTHER
}
