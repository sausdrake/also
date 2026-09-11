package com.example.autoauction.settlement.domain;

/**
 * Step 2C: Settlement's own payout lifecycle - deliberately NOT
 * {@code FinancialResolutionStatus}, which belongs exclusively to Slice 1/X3-D and is never
 * reused or reinterpreted here.
 *
 * <p>Exactly one transition exists: {@code PENDING -> PAYOUT_RECORDED}. There is no
 * {@code FAILED} state - unlike {@code FinancialResolutionStatus}, this operation is a single
 * atomic write of one immutable fact (an admin's payout attestation), not a coordination of
 * several sub-operations that could partially fail; a database write failure leaves no row to
 * recover from {@code PENDING}, because {@code PENDING} never left. {@code PAYOUT_RECORDED} is
 * terminal - the status itself never reverts; a later-discovered error is recorded as an
 * additive {@link SettlementCorrection}, never a status rollback on the original row.
 */
public enum SettlementStatus {
    PENDING,
    PAYOUT_RECORDED
}
