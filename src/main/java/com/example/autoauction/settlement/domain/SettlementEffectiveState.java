package com.example.autoauction.settlement.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Step 2C: the current effective business fact for one Settlement, computed fresh on every read
 * by folding its correction history over the original row - never materialized as a mutable
 * field on {@link Settlement} itself (locked decision: a mutable "current state" field would be
 * a second, can-drift-from-the-truth source of truth).
 *
 * <p>Fold rule, applied in chronological order ({@code correctedAt} then {@code id} ascending -
 * re-sorted here defensively even though the repository is expected to already return corrections
 * in this order):
 * <ul>
 *   <li>{@code SELLER_CORRECTION} overrides the effective seller and marks a valid payout as
 *       re-established;</li>
 *   <li>{@code AMOUNT_CORRECTION} overrides the effective payout amount and likewise
 *       re-establishes validity;</li>
 *   <li>{@code PAYOUT_INVALIDATED} marks the payout invalid, superseding every earlier
 *       correction, but is itself supersedable by any later correction of either other type -
 *       this is intentional: the original Settlement can never be recreated
 *       ({@code UNIQUE(auction_id)}), so invalidation must never be an unrecoverable dead end.</li>
 * </ul>
 * This rule is total - defined for every possible ordering and combination of correction types,
 * so no correction sequence is ever rejected as "invalid."
 */
public record SettlementEffectiveState(Long effectiveSellerId, BigDecimal effectivePayoutAmount,
                                        boolean validPayoutExists) {

    private static final Comparator<SettlementCorrection> CHRONOLOGICAL =
            Comparator.comparing(SettlementCorrection::getCorrectedAt)
                    .thenComparing(SettlementCorrection::getId);

    public static SettlementEffectiveState compute(Settlement original, List<SettlementCorrection> corrections) {
        Long effectiveSellerId = original.getSellerId();
        BigDecimal effectivePayoutAmount = original.getAttestedPayoutAmount();
        boolean validPayoutExists = original.getStatus() == SettlementStatus.PAYOUT_RECORDED;

        List<SettlementCorrection> ordered = corrections.stream().sorted(CHRONOLOGICAL).toList();
        for (SettlementCorrection correction : ordered) {
            switch (correction.getCorrectionType()) {
                case SELLER_CORRECTION -> {
                    effectiveSellerId = correction.getCorrectedSellerId();
                    validPayoutExists = true;
                }
                case AMOUNT_CORRECTION -> {
                    effectivePayoutAmount = correction.getCorrectedAmount();
                    validPayoutExists = true;
                }
                case PAYOUT_INVALIDATED -> validPayoutExists = false;
            }
        }
        return new SettlementEffectiveState(effectiveSellerId, effectivePayoutAmount, validPayoutExists);
    }
}
