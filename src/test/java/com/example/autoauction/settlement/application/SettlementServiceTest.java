package com.example.autoauction.settlement.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.SaleFinalizationMethod;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.policy.domain.PolicyVersion;
import com.example.autoauction.settlement.domain.Settlement;
import com.example.autoauction.settlement.domain.SettlementCorrection;
import com.example.autoauction.settlement.domain.SettlementCorrectionType;
import com.example.autoauction.settlement.domain.SettlementEffectiveState;
import com.example.autoauction.settlement.domain.SettlementStatus;
import com.example.autoauction.settlement.domain.port.SettlementCorrectionRepository;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Step 2C: SettlementService — creation economics, payout idempotency, correction idempotency")
class SettlementServiceTest {

    @Mock private SettlementRepository settlementRepository;
    @Mock private SettlementCorrectionRepository settlementCorrectionRepository;
    @Mock private SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    @Mock private DiagnosticRequestRepository diagnosticRequestRepository;
    @Mock private PolicyVersionService policyVersionService;

    private SettlementService service;

    private OffsetDateTime fixedTime;
    private final Long auctionId = 1L;
    private final Long sellerUserId = 77L;
    private final Long commitmentId = 555L;
    private final Long diagnosticRequestId = 888L;
    private final Long policyVersionId = 5L;

    @BeforeEach
    void setUp() {
        Instant fixedInstant = Instant.parse("2026-09-10T10:00:00Z");
        Clock clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));
        fixedTime = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"));
        service = new SettlementService(settlementRepository, settlementCorrectionRepository,
                sellerDiagnosticCommitmentRepository, diagnosticRequestRepository, policyVersionService, clock);
    }

    private Auction auction(SaleFinalizationMethod method, BigDecimal winningBid, BigDecimal buyNowPrice,
                             Long commitmentIdOrNull, Long policyVersionIdOrNull) {
        Auction a = new Auction(2L, new BigDecimal("100000"), new BigDecimal("100000"),
                buyNowPrice, new BigDecimal("1000"), fixedTime.minusDays(1), fixedTime.minusHours(1),
                99L /* adminId, i.e. createdBy - must NEVER be used as seller */);
        a.setId(auctionId);
        a.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        a.setWinnerId(300L);
        a.setWinningBid(winningBid);
        if (commitmentIdOrNull != null) {
            a.setSellerDiagnosticCommitmentId(commitmentIdOrNull);
        }
        if (policyVersionIdOrNull != null) {
            a.setPolicyVersionId(policyVersionIdOrNull);
        }
        if (method != null) {
            a.setSaleFinalizationMethod(method);
        }
        return a;
    }

    private void stubSellerChain() {
        SellerDiagnosticCommitment commitment = new SellerDiagnosticCommitment();
        commitment.setId(commitmentId);
        commitment.setDiagnosticRequestId(diagnosticRequestId);
        when(sellerDiagnosticCommitmentRepository.findById(commitmentId)).thenReturn(Optional.of(commitment));

        DiagnosticRequest request = new DiagnosticRequest();
        request.setId(diagnosticRequestId);
        request.setUserId(sellerUserId);
        when(diagnosticRequestRepository.findById(diagnosticRequestId)).thenReturn(Optional.of(request));
    }

    @Nested
    @DisplayName("createPending")
    class CreatePending {

        @Test
        void ordinaryBid_computesLockedEconomics_andResolvesSellerViaChain_neverCreatedBy() {
            stubSellerChain();
            when(policyVersionService.getById(policyVersionId)).thenReturn(
                    new PolicyVersion(policyVersionId, 1L, new BigDecimal("0.02"), true,
                            fixedTime, 1L, fixedTime));
            Auction a = auction(SaleFinalizationMethod.ORDINARY_BID, new BigDecimal("1020000.00"), null,
                    commitmentId, policyVersionId);
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

            Settlement saved = service.createPending(a);

            // sellerAmount = 1,020,000 / 1.02 = 1,000,000.00 exactly; commission = 20,000.00.
            assertThat(saved.getSellerId())
                    .as("seller must come from the DiagnosticRequest.userId chain, never Auction.createdBy (99L)")
                    .isEqualTo(sellerUserId);
            assertThat(saved.getExpectedBuyerPaidAmount()).isEqualByComparingTo("1020000.00");
            assertThat(saved.getExpectedSellerAmount()).isEqualByComparingTo("1000000.00");
            assertThat(saved.getExpectedCommission()).isEqualByComparingTo("20000.00");
            assertThat(saved.getStatus()).isEqualTo(SettlementStatus.PENDING);
        }

        @Test
        void buyNow_sellerAmountIsRawSellerPrice_neverTheBuyerFacingMarkup() {
            stubSellerChain();
            when(policyVersionService.getById(policyVersionId)).thenReturn(
                    new PolicyVersion(policyVersionId, 1L, new BigDecimal("0.02"), true,
                            fixedTime, 1L, fixedTime));
            // winningBid already holds the buyer-facing Buy Now amount (1,530,000.00 = 1,500,000
            // * 1.02) - set by AuctionFinalizer.finalizeWithBuyNow before completeSale runs.
            Auction a = auction(SaleFinalizationMethod.BUY_NOW, new BigDecimal("1530000.00"),
                    new BigDecimal("1500000.00"), commitmentId, policyVersionId);
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

            Settlement saved = service.createPending(a);

            assertThat(saved.getExpectedSellerAmount())
                    .as("Buy Now sellerAmount = sellerBuyNowPrice (raw), never a re-derived markup")
                    .isEqualByComparingTo("1500000.00");
            assertThat(saved.getExpectedBuyerPaidAmount()).isEqualByComparingTo("1530000.00");
            assertThat(saved.getExpectedCommission()).isEqualByComparingTo("30000.00");
        }

        @Test
        void nullSaleFinalizationMethod_failsClosed_noSettlementCreated() {
            Auction a = auction(null, new BigDecimal("1000000"), null, commitmentId, policyVersionId);

            assertThatThrownBy(() -> service.createPending(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("saleFinalizationMethod");

            verify(settlementRepository, never()).save(any());
        }

        @Test
        void nullSellerDiagnosticCommitmentId_failsClosed_neverFallsBackToCreatedBy() {
            Auction a = auction(SaleFinalizationMethod.ORDINARY_BID, new BigDecimal("1000000"), null,
                    null, policyVersionId);

            assertThatThrownBy(() -> service.createPending(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sellerDiagnosticCommitmentId");

            verify(settlementRepository, never()).save(any());
            verify(sellerDiagnosticCommitmentRepository, never()).findById(any());
        }

        @Test
        void nullPolicyVersionId_failsClosed() {
            Auction a = auction(SaleFinalizationMethod.ORDINARY_BID, new BigDecimal("1000000"), null,
                    commitmentId, null);

            assertThatThrownBy(() -> service.createPending(a))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("policyVersionId");

            verify(settlementRepository, never()).save(any());
        }

        @Test
        void danglingCommitmentReference_failsClosed() {
            // resolveSellerId runs before the policyVersionService lookup - this stub would
            // never be reached, so it is deliberately omitted rather than stubbed unnecessarily.
            when(sellerDiagnosticCommitmentRepository.findById(commitmentId)).thenReturn(Optional.empty());
            Auction a = auction(SaleFinalizationMethod.ORDINARY_BID, new BigDecimal("1020000.00"), null,
                    commitmentId, policyVersionId);

            assertThatThrownBy(() -> service.createPending(a)).isInstanceOf(IllegalStateException.class);
            verify(settlementRepository, never()).save(any());
        }

        @Test
        void buyNow_neverAppliesOrdinaryFormula_resultDiffersFromWhatOrdinaryWouldProduce() {
            // Phase 5 corrective pass (§6, §9): picks numbers where applying the ORDINARY formula
            // (winningBid / (1+rate)) to a BUY_NOW sale would produce a visibly DIFFERENT, wrong
            // sellerAmount than the correct one (the raw buyNowPrice) - proving the implementation
            // actually branches correctly, not merely that its own formula is internally
            // consistent.
            stubSellerChain();
            when(policyVersionService.getById(policyVersionId)).thenReturn(
                    new PolicyVersion(policyVersionId, 1L, new BigDecimal("0.02"), true,
                            fixedTime, 1L, fixedTime));
            // Deliberately chosen so the forward markup's rounding does NOT round-trip back to
            // buyNowPrice when divided by 1.02 - 999999.99*1.02=1019999.9898 -> rounds to
            // 1020000.00, but 1020000.00/1.02=1000000.00 EXACTLY (no rounding needed on the way
            // back) - a real, visible 0.01 divergence from the correct answer if the wrong
            // (ordinary) formula were ever applied.
            BigDecimal buyNowPrice = new BigDecimal("999999.99");
            BigDecimal winningBid = new BigDecimal("1020000.00");
            Auction a = auction(SaleFinalizationMethod.BUY_NOW, winningBid, buyNowPrice, commitmentId, policyVersionId);
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));

            Settlement saved = service.createPending(a);

            BigDecimal whatOrdinaryFormulaWouldWronglyProduce =
                    winningBid.divide(new BigDecimal("1.02"), 2, java.math.RoundingMode.HALF_UP);
            assertThat(saved.getExpectedSellerAmount())
                    .as("correct Buy Now sellerAmount must be the raw buyNowPrice")
                    .isEqualByComparingTo(buyNowPrice);
            assertThat(saved.getExpectedSellerAmount())
                    .as("the implementation must NOT have produced what the ordinary-auction "
                            + "formula would have wrongly computed (%s)", whatOrdinaryFormulaWouldWronglyProduce)
                    .isNotEqualByComparingTo(whatOrdinaryFormulaWouldWronglyProduce);
            assertThat(saved.getExpectedSellerAmount().add(saved.getExpectedCommission()))
                    .isEqualByComparingTo(winningBid);
        }

        @Test
        void danglingDiagnosticRequestReference_failsClosed() {
            // Phase 5 corrective pass: the commitment IS found, but its own diagnosticRequestId
            // points to no row - the second hop of the seller-resolution chain, previously
            // untested independently of the first hop's own dangling-reference test above.
            stubSellerChain();
            when(diagnosticRequestRepository.findById(diagnosticRequestId)).thenReturn(Optional.empty());
            Auction a = auction(SaleFinalizationMethod.ORDINARY_BID, new BigDecimal("1020000.00"), null,
                    commitmentId, policyVersionId);

            assertThatThrownBy(() -> service.createPending(a)).isInstanceOf(IllegalStateException.class);
            verify(settlementRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("recordPayout")
    class RecordPayout {

        private Settlement pendingSettlement() {
            return new Settlement(10L, auctionId, sellerUserId, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PENDING,
                    null, null, null, fixedTime);
        }

        private Settlement payoutRecordedSettlement(BigDecimal attested, Long admin) {
            return new Settlement(10L, auctionId, sellerUserId, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PAYOUT_RECORDED,
                    attested, admin, fixedTime, fixedTime);
        }

        @Test
        void claimSucceeds_returnsRecordedSettlement() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(pendingSettlement()));
            when(settlementRepository.claimPayoutRecorded(10L, new BigDecimal("980000.00"), 99L, fixedTime))
                    .thenReturn(true);
            when(settlementRepository.findById(10L))
                    .thenReturn(Optional.of(payoutRecordedSettlement(new BigDecimal("980000.00"), 99L)));

            Settlement result = service.recordPayout(auctionId, new BigDecimal("980000.00"), 99L);

            assertThat(result.getStatus()).isEqualTo(SettlementStatus.PAYOUT_RECORDED);
            assertThat(result.getAttestedPayoutAmount()).isEqualByComparingTo("980000.00");
        }

        @Test
        void exactReplay_sameAmount_safeNoOp_neverAttemptsClaim() {
            when(settlementRepository.findByAuctionId(auctionId))
                    .thenReturn(Optional.of(payoutRecordedSettlement(new BigDecimal("970000.00"), 1L)));

            // A different admin (2L) replaying the exact same amount is still a safe replay -
            // admin identity is never part of the fingerprint.
            Settlement result = service.recordPayout(auctionId, new BigDecimal("970000.00"), 2L);

            assertThat(result.getRecordedByAdminId())
                    .as("the original recording admin must never be overwritten by a later caller")
                    .isEqualTo(1L);
            verify(settlementRepository, never()).claimPayoutRecorded(any(), any(), any(), any());
        }

        @Test
        void conflictingAmount_failsClosed() {
            when(settlementRepository.findByAuctionId(auctionId))
                    .thenReturn(Optional.of(payoutRecordedSettlement(new BigDecimal("970000.00"), 1L)));

            assertThatThrownBy(() -> service.recordPayout(auctionId, new BigDecimal("970000.01"), 2L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void lostRace_claimFails_reReadsAndReplays() {
            Settlement pending = pendingSettlement();
            when(settlementRepository.findByAuctionId(auctionId))
                    .thenReturn(Optional.of(pending))
                    .thenReturn(Optional.of(payoutRecordedSettlement(new BigDecimal("980000.00"), 1L)));
            when(settlementRepository.claimPayoutRecorded(eq(10L), any(), any(), any())).thenReturn(false);

            // Second caller attempts the SAME amount that the race's winner (admin 1L) recorded.
            Settlement result = service.recordPayout(auctionId, new BigDecimal("980000.00"), 2L);

            assertThat(result.getRecordedByAdminId()).isEqualTo(1L);
            verify(settlementRepository, never()).findById(any());
        }

        @Test
        void nonPositiveAmount_rejectedBeforeAnyRepositoryCall() {
            assertThatThrownBy(() -> service.recordPayout(auctionId, BigDecimal.ZERO, 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(settlementRepository, never()).findByAuctionId(any());
        }

        @Test
        void negativeAmount_rejectedBeforeAnyRepositoryCall() {
            // Phase 5 corrective pass: zero was already tested; negative shares the same
            // compareTo(ZERO)<=0 guard but was not itself independently exercised.
            assertThatThrownBy(() -> service.recordPayout(auctionId, new BigDecimal("-970000.00"), 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(settlementRepository, never()).findByAuctionId(any());
        }

        @Test
        void noSettlementForAuction_rejected() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.recordPayout(auctionId, new BigDecimal("1"), 1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Phase 6 corrective pass (Fix 2): attestedAmount must reach claimPayoutRecorded already
         * normalized to scale 2/HALF_UP - the production claim-first path bypasses
         * Settlement.recordPayout() entirely (it calls the atomic repository UPDATE directly), so
         * normalization MUST happen here, at the service boundary, not only in the domain method.
         */
        @Test
        void attestedAmount_normalizedBeforeClaimAttempt() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(pendingSettlement()));
            when(settlementRepository.claimPayoutRecorded(eq(10L), eq(new BigDecimal("970000.01")), eq(99L), any()))
                    .thenReturn(true);
            when(settlementRepository.findById(10L))
                    .thenReturn(Optional.of(payoutRecordedSettlement(new BigDecimal("970000.01"), 99L)));

            Settlement result = service.recordPayout(auctionId, new BigDecimal("970000.005"), 99L);

            assertThat(result.getAttestedPayoutAmount()).isEqualByComparingTo("970000.01");
            verify(settlementRepository).claimPayoutRecorded(eq(10L), eq(new BigDecimal("970000.01")), eq(99L), any());
        }
    }

    @Nested
    @DisplayName("recordCorrection")
    class RecordCorrection {

        private Settlement settlement() {
            return new Settlement(10L, auctionId, sellerUserId, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PAYOUT_RECORDED,
                    new BigDecimal("970000.00"), 1L, fixedTime, fixedTime);
        }

        @Test
        void newCorrection_saved() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            when(settlementCorrectionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
            when(settlementCorrectionRepository.save(any(SettlementCorrection.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SettlementCorrection result = service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                    "recount", 1L, "key-1");

            assertThat(result.getCorrectionType()).isEqualTo(SettlementCorrectionType.AMOUNT_CORRECTION);
            assertThat(result.getSettlementId()).isEqualTo(10L);
        }

        @Test
        void exactReplay_sameKey_sameFingerprint_safeNoOp() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            SettlementCorrection existing = new SettlementCorrection(10L, SettlementCorrectionType.AMOUNT_CORRECTION,
                    new BigDecimal("960000.00"), null, "recount", 1L, fixedTime, "key-1");
            when(settlementCorrectionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            SettlementCorrection result = service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                    "recount", 1L, "key-1");

            assertThat(result).isSameAs(existing);
            verify(settlementCorrectionRepository, never()).save(any());
        }

        @Test
        void sameKey_differentFingerprint_failsClosed() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            SettlementCorrection existing = new SettlementCorrection(10L, SettlementCorrectionType.AMOUNT_CORRECTION,
                    new BigDecimal("960000.00"), null, "recount", 1L, fixedTime, "key-1");
            when(settlementCorrectionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("950000.00"), null,
                    "recount", 1L, "key-1"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void sameKey_differentCorrectionType_failsClosed() {
            // Phase 5 corrective pass: §6's matrix explicitly names "same key + different
            // correction type" as its own case, distinct from "different amount"/"different
            // reason" - the fingerprint check's `existing.getCorrectionType() == correctionType`
            // comparison is exercised here specifically, not merely incidentally.
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            SettlementCorrection existing = new SettlementCorrection(10L, SettlementCorrectionType.AMOUNT_CORRECTION,
                    new BigDecimal("960000.00"), null, "recount", 1L, fixedTime, "key-1");
            when(settlementCorrectionRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.recordCorrection(auctionId,
                    SettlementCorrectionType.PAYOUT_INVALIDATED, null, null,
                    "recount", 1L, "key-1"))
                    .isInstanceOf(IllegalStateException.class);
            verify(settlementCorrectionRepository, never()).save(any());
        }

        @Test
        void differentKey_identicalContent_createsDistinctCorrection_noAccidentalDeduplication() {
            // Phase 5 corrective pass: §6 explicitly requires proving that identical content
            // under a DIFFERENT key is never silently deduplicated - two genuinely separate
            // correction attempts that happen to use the same wording must both persist.
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            when(settlementCorrectionRepository.findByIdempotencyKey("key-X")).thenReturn(Optional.empty());
            when(settlementCorrectionRepository.save(any(SettlementCorrection.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SettlementCorrection result = service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                    "recount", 1L, "key-X");

            assertThat(result.getIdempotencyKey()).isEqualTo("key-X");
            verify(settlementCorrectionRepository).save(any(SettlementCorrection.class));
            // The content is identical to exactReplay_sameKey_sameFingerprint_safeNoOp's own
            // correction above, by design - proving the key, not the content, is what a replay
            // check keys off.
        }

        @Test
        void concurrentInsertRace_dataIntegrityViolation_reReadsAndReplays() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            when(settlementCorrectionRepository.findByIdempotencyKey("key-1"))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(new SettlementCorrection(10L, SettlementCorrectionType.AMOUNT_CORRECTION,
                            new BigDecimal("960000.00"), null, "recount", 1L, fixedTime, "key-1")));
            when(settlementCorrectionRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));

            SettlementCorrection result = service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                    "recount", 1L, "key-1");

            assertThat(result.getCorrectedAmount()).isEqualByComparingTo("960000.00");
        }

        @Test
        void blankReasonOrIdempotencyKey_rejectedBeforeAnyRepositoryCall() {
            assertThatThrownBy(() -> service.recordCorrection(auctionId, SettlementCorrectionType.PAYOUT_INVALIDATED,
                    null, null, "  ", 1L, "key-1"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.recordCorrection(auctionId, SettlementCorrectionType.PAYOUT_INVALIDATED,
                    null, null, "reason", 1L, "  "))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(settlementRepository, never()).findByAuctionId(any());
        }

        private Settlement pendingSettlement() {
            return new Settlement(10L, auctionId, sellerUserId, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PENDING,
                    null, null, null, fixedTime);
        }

        /**
         * Phase 6 corrective pass (Fix 1): a correction must never be the mechanism that FIRST
         * establishes a payout's existence. Without this guard, SettlementEffectiveState.compute()
         * could report validPayoutExists = true for a Settlement where recordPayout() was never
         * called - fabricating an attestation that never happened.
         */
        @Test
        void pendingSettlement_amountCorrection_rejected_noCorrectionPersisted() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(pendingSettlement()));

            assertThatThrownBy(() -> service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                    "recount", 1L, "key-1"))
                    .isInstanceOf(IllegalStateException.class);

            verify(settlementCorrectionRepository, never()).findByIdempotencyKey(any());
            verify(settlementCorrectionRepository, never()).save(any());
        }

        @Test
        void pendingSettlement_sellerCorrection_rejected_noCorrectionPersisted() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(pendingSettlement()));

            assertThatThrownBy(() -> service.recordCorrection(auctionId,
                    SettlementCorrectionType.SELLER_CORRECTION, null, 2L,
                    "wrong seller", 1L, "key-1"))
                    .isInstanceOf(IllegalStateException.class);

            verify(settlementCorrectionRepository, never()).findByIdempotencyKey(any());
            verify(settlementCorrectionRepository, never()).save(any());
        }

        @Test
        void payoutRecordedSettlement_amountCorrection_stillSucceeds() {
            // Regression: the PENDING guard must not affect the already-locked, already-tested
            // PAYOUT_RECORDED path - newCorrection_saved above already proves this, this test
            // makes the "PENDING guard does not over-fire" contrast explicit in one place.
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            when(settlementCorrectionRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
            when(settlementCorrectionRepository.save(any(SettlementCorrection.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SettlementCorrection result = service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("960000.00"), null,
                    "recount", 1L, "key-2");

            assertThat(result.getCorrectionType()).isEqualTo(SettlementCorrectionType.AMOUNT_CORRECTION);
        }

        /**
         * Phase 6 corrective pass (Fix 2): correctedAmount must reach the repository already
         * normalized to scale 2/HALF_UP - the canonical value PostgreSQL's NUMERIC(19,2) will
         * actually persist, not the raw higher-scale client input.
         */
        @Test
        void correctedAmount_normalizedBeforePersistence() {
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement()));
            when(settlementCorrectionRepository.findByIdempotencyKey("key-3")).thenReturn(Optional.empty());
            when(settlementCorrectionRepository.save(any(SettlementCorrection.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            SettlementCorrection result = service.recordCorrection(auctionId,
                    SettlementCorrectionType.AMOUNT_CORRECTION, new BigDecimal("970000.005"), null,
                    "recount", 1L, "key-3");

            assertThat(result.getCorrectedAmount()).isEqualByComparingTo("970000.01");
        }
    }

    @Nested
    @DisplayName("effectiveState")
    class EffectiveState {

        @Test
        void delegatesToComputeWithSettlementAndItsOrderedCorrections() {
            Settlement settlement = new Settlement(10L, auctionId, sellerUserId, new BigDecimal("1000000.00"),
                    new BigDecimal("980000.00"), new BigDecimal("20000.00"), SettlementStatus.PAYOUT_RECORDED,
                    new BigDecimal("970000.00"), 1L, fixedTime, fixedTime);
            SettlementCorrection correction = new SettlementCorrection(10L, SettlementCorrectionType.AMOUNT_CORRECTION,
                    new BigDecimal("960000.00"), null, "recount", 1L, fixedTime.plusDays(1), "key-1");
            when(settlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));
            when(settlementCorrectionRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(10L))
                    .thenReturn(List.of(correction));

            SettlementEffectiveState state = service.effectiveState(auctionId);

            assertThat(state.effectivePayoutAmount()).isEqualByComparingTo("960000.00");
            assertThat(state.validPayoutExists()).isTrue();
        }
    }
}
