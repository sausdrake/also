package com.example.autoauction.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step 2C: SettlementEffectiveState — chronological correction fold")
class SettlementEffectiveStateTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-10T10:00:00Z");
    private static final Long SETTLEMENT_ID = 1L;

    /**
     * Uses the reconstruction constructor (id=SETTLEMENT_ID) rather than the creation
     * constructor + recordPayout - the domain never assigns its own id (that is the
     * repository's job on save), and every SettlementCorrection below needs a real,
     * non-null settlementId to satisfy its own constructor guard.
     */
    private Settlement payoutRecordedSettlement(Long sellerId, String attested) {
        return new Settlement(SETTLEMENT_ID, 1L, sellerId, new BigDecimal("1000000.00"),
                new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PAYOUT_RECORDED,
                new BigDecimal(attested), 99L, NOW.plusDays(1), NOW);
    }

    @Test
    void noCorrections_effectiveStateEqualsOriginal() {
        Settlement s = payoutRecordedSettlement(10L, "970000.00");
        SettlementEffectiveState state = SettlementEffectiveState.compute(s, List.of());
        assertThat(state.effectiveSellerId()).isEqualTo(10L);
        assertThat(state.effectivePayoutAmount()).isEqualByComparingTo("970000.00");
        assertThat(state.validPayoutExists()).isTrue();
    }

    @Test
    void pendingSettlement_noAttestationYet_invalid() {
        Settlement s = new Settlement(1L, 10L, new BigDecimal("1000000.00"),
                new BigDecimal("980000.00"), new BigDecimal("20000.00"), NOW);
        SettlementEffectiveState state = SettlementEffectiveState.compute(s, List.of());
        assertThat(state.validPayoutExists()).isFalse();
        assertThat(state.effectivePayoutAmount()).isNull();
    }

    /**
     * The exact worked example from the Step 2C Phase 4 specification:
     * <pre>
     * Original: seller=A, attested=970000
     * Correction 1: AMOUNT_CORRECTION -> 960000
     * Correction 2: SELLER_CORRECTION -> B
     * Effective: seller=B, payout=960000, valid=true
     *
     * Correction 3: PAYOUT_INVALIDATED
     * Effective: valid=false
     *
     * Correction 4: AMOUNT_CORRECTION -> 950000
     * Effective: seller=B, payout=950000, valid=true
     * </pre>
     * Expected seller amount and commission remain unchanged throughout - asserted explicitly
     * at every step via {@code s.getExpectedSellerAmount()}/{@code getExpectedCommission()}.
     */
    @Test
    void lockedWorkedExample_fourCorrectionsInSequence() {
        Long sellerA = 10L;
        Long sellerB = 20L;
        Settlement s = payoutRecordedSettlement(sellerA, "970000.00");

        SettlementCorrection c1 = new SettlementCorrection(s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("960000.00"), null, "recount of physical cash", 1L, NOW.plusDays(2), "k1");
        SettlementCorrection c2 = new SettlementCorrection(s.getId(), SettlementCorrectionType.SELLER_CORRECTION,
                null, sellerB, "cash was actually handed to B", 1L, NOW.plusDays(3), "k2");

        SettlementEffectiveState afterTwo = SettlementEffectiveState.compute(s, List.of(c1, c2));
        assertThat(afterTwo.effectiveSellerId()).isEqualTo(sellerB);
        assertThat(afterTwo.effectivePayoutAmount()).isEqualByComparingTo("960000.00");
        assertThat(afterTwo.validPayoutExists()).isTrue();
        assertThat(s.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(s.getExpectedCommission()).isEqualByComparingTo("20000.00");

        SettlementCorrection c3 = new SettlementCorrection(s.getId(), SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "original attestation was entirely erroneous", 1L, NOW.plusDays(4), "k3");

        SettlementEffectiveState afterThree = SettlementEffectiveState.compute(s, List.of(c1, c2, c3));
        assertThat(afterThree.validPayoutExists())
                .as("PAYOUT_INVALIDATED supersedes every earlier correction")
                .isFalse();
        assertThat(s.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(s.getExpectedCommission()).isEqualByComparingTo("20000.00");

        SettlementCorrection c4 = new SettlementCorrection(s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("950000.00"), null, "the true payout was finally located", 1L, NOW.plusDays(5), "k4");

        SettlementEffectiveState afterFour = SettlementEffectiveState.compute(s, List.of(c1, c2, c3, c4));
        assertThat(afterFour.effectiveSellerId())
                .as("a later correction re-establishes validity - the original row can never be recreated")
                .isEqualTo(sellerB);
        assertThat(afterFour.effectivePayoutAmount()).isEqualByComparingTo("950000.00");
        assertThat(afterFour.validPayoutExists()).isTrue();
        assertThat(s.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(s.getExpectedCommission()).isEqualByComparingTo("20000.00");
    }

    /**
     * Phase 5 corrective pass - Sequence C, explicitly tested as its own starting scenario
     * (previously only verified by hand-trace, not by an executable test):
     * <pre>
     * Original: seller=A, attested=970000
     * PAYOUT_INVALIDATED
     * AMOUNT_CORRECTION -> 950000
     * Effective: seller=A, payout=950000, valid=true
     * </pre>
     */
    @Test
    @DisplayName("Sequence C: PAYOUT_INVALIDATED затем AMOUNT_CORRECTION -> seller не тронут, payout восстановлен, valid=true")
    void sequenceC_invalidatedThenAmountCorrection_restoresValidityWithoutTouchingSeller() {
        Long sellerA = 10L;
        Settlement s = payoutRecordedSettlement(sellerA, "970000.00");

        SettlementCorrection invalidated = new SettlementCorrection(s.getId(), SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "original attestation was entirely erroneous", 1L, NOW.plusDays(1), "c-k1");
        SettlementCorrection amountFix = new SettlementCorrection(s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("950000.00"), null, "the true payout was located", 1L, NOW.plusDays(2), "c-k2");

        SettlementEffectiveState state = SettlementEffectiveState.compute(s, List.of(invalidated, amountFix));

        assertThat(state.effectiveSellerId()).as("seller was never corrected - must remain the original").isEqualTo(sellerA);
        assertThat(state.effectivePayoutAmount()).isEqualByComparingTo("950000.00");
        assertThat(state.validPayoutExists()).isTrue();
        // Original Settlement row itself is untouched.
        assertThat(s.getSellerId()).isEqualTo(sellerA);
        assertThat(s.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
        assertThat(s.getExpectedSellerAmount()).isEqualByComparingTo("980000.00");
        assertThat(s.getExpectedCommission()).isEqualByComparingTo("20000.00");
        // The invalidation remains in the correction history, unmutated, even though a later
        // correction supersedes its effect on validity.
        assertThat(invalidated.getCorrectedAmount()).isNull();
        assertThat(invalidated.getCorrectedSellerId()).isNull();
    }

    /**
     * Phase 5 corrective pass - Sequence D, explicitly tested as its own starting scenario:
     * <pre>
     * Original: seller=A, attested=970000
     * PAYOUT_INVALIDATED
     * SELLER_CORRECTION -> B
     * Effective: seller=B, payout=970000 (unchanged), valid=true
     * </pre>
     */
    @Test
    @DisplayName("Sequence D: PAYOUT_INVALIDATED затем SELLER_CORRECTION -> payout не тронут, seller восстановлен, valid=true")
    void sequenceD_invalidatedThenSellerCorrection_restoresValidityWithoutTouchingAmount() {
        Long sellerA = 10L;
        Long sellerB = 20L;
        Settlement s = payoutRecordedSettlement(sellerA, "970000.00");

        SettlementCorrection invalidated = new SettlementCorrection(s.getId(), SettlementCorrectionType.PAYOUT_INVALIDATED,
                null, null, "original attestation was entirely erroneous", 1L, NOW.plusDays(1), "d-k1");
        SettlementCorrection sellerFix = new SettlementCorrection(s.getId(), SettlementCorrectionType.SELLER_CORRECTION,
                null, sellerB, "cash was actually handed to B", 1L, NOW.plusDays(2), "d-k2");

        SettlementEffectiveState state = SettlementEffectiveState.compute(s, List.of(invalidated, sellerFix));

        assertThat(state.effectiveSellerId()).isEqualTo(sellerB);
        assertThat(state.effectivePayoutAmount())
                .as("amount was never corrected - must remain the original attested value")
                .isEqualByComparingTo("970000.00");
        assertThat(state.validPayoutExists()).isTrue();
        // Original Settlement row itself is untouched.
        assertThat(s.getSellerId()).isEqualTo(sellerA);
        assertThat(s.getAttestedPayoutAmount()).isEqualByComparingTo("970000.00");
    }

    @Test
    void foldIsOrderIndependentOfListOrder_sortsByCorrectedAtNotInsertionOrder() {
        Settlement s = payoutRecordedSettlement(10L, "970000.00");
        SettlementCorrection later = new SettlementCorrection(s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("950000.00"), null, "later fix", 1L, NOW.plusDays(5), "k-later");
        SettlementCorrection earlier = new SettlementCorrection(s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("960000.00"), null, "earlier fix", 1L, NOW.plusDays(2), "k-earlier");

        // Passed in REVERSE chronological order - compute() must re-sort, not trust list order.
        SettlementEffectiveState state = SettlementEffectiveState.compute(s, List.of(later, earlier));
        assertThat(state.effectivePayoutAmount())
                .as("the chronologically LATEST correction (950000, at +5 days) must win, " +
                        "regardless of list/insertion order")
                .isEqualByComparingTo("950000.00");
    }

    @Test
    void tieBreaksByIdWhenTimestampsEqual() {
        Settlement s = payoutRecordedSettlement(10L, "970000.00");
        SettlementCorrection first = new SettlementCorrection(1L, s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("960000.00"), null, "reason", 1L, NOW.plusDays(2), "k1");
        SettlementCorrection second = new SettlementCorrection(2L, s.getId(), SettlementCorrectionType.AMOUNT_CORRECTION,
                new BigDecimal("950000.00"), null, "reason", 1L, NOW.plusDays(2), "k2");

        SettlementEffectiveState state = SettlementEffectiveState.compute(s, List.of(first, second));
        assertThat(state.effectivePayoutAmount())
                .as("equal timestamps break the tie by id - the higher id (inserted later) wins")
                .isEqualByComparingTo("950000.00");
    }
}
