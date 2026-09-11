package com.example.autoauction.settlement.domain;

/**
 * Step 2C: the three locked categories a {@link SettlementCorrection} may record. Each requires
 * a different, mutually exclusive value combination - see
 * {@link SettlementCorrection}'s constructor for the enforced rule.
 */
public enum SettlementCorrectionType {
    /** Corrects only the effective attested physical payout amount. Requires {@code correctedAmount}. */
    AMOUNT_CORRECTION,
    /** Corrects only the effective seller identity. Requires {@code correctedSellerId}. */
    SELLER_CORRECTION,
    /**
     * States that the original payout attestation is currently recognized as invalid and that
     * there is currently no valid payout for this auction. Does NOT mean cash was physically
     * returned - that is a real-world fact this slice has no mechanism to verify or represent.
     * Requires neither {@code correctedAmount} nor {@code correctedSellerId}.
     */
    PAYOUT_INVALIDATED
}
