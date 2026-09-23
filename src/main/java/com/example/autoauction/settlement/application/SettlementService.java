package com.example.autoauction.settlement.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.SaleFinalizationMethod;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import com.example.autoauction.diagnostic.domain.DiagnosticRequest;
import com.example.autoauction.diagnostic.domain.port.DiagnosticRequestRepository;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.settlement.domain.Settlement;
import com.example.autoauction.settlement.domain.SettlementCorrection;
import com.example.autoauction.settlement.domain.SettlementCorrectionType;
import com.example.autoauction.settlement.domain.SettlementEffectiveState;
import com.example.autoauction.settlement.domain.SettlementStatus;
import com.example.autoauction.settlement.domain.port.SettlementCorrectionRepository;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Step 2C: the sole cohesive owner of Settlement creation, payout recording, correction, and
 * effective-state resolution - deliberately NOT folded into {@code DepositService} (seller
 * payout is evidence-only, never a Deposit credit - no {@code DepositTransaction} of any kind is
 * created here), {@code AuctionFinalizer} (which only invokes {@link #createPending} as a narrow
 * collaborator call, exactly like its existing {@code saleCommitmentResolutionOrchestrator} call),
 * {@code SaleAdministrationService}, or any seller-diagnostic-commitment service.
 *
 * <p>Seller identity is resolved, read-only, via the proven chain
 * {@code Auction.sellerDiagnosticCommitmentId -> SellerDiagnosticCommitment.diagnosticRequestId
 * -> DiagnosticRequest.userId} - never {@code Auction.createdBy} (confirmed to be the admin who
 * created the listing, not the seller). {@code AuctionCommitmentResolver},
 * {@code SellerDiagnosticCommitment}, and {@code DiagnosticRequest} are read-only dependencies;
 * none of them is modified by this slice.
 */
@Slf4j
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final SettlementCorrectionRepository settlementCorrectionRepository;
    private final SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository;
    private final DiagnosticRequestRepository diagnosticRequestRepository;
    private final PolicyVersionService policyVersionService;
    private final Clock clock;

    public SettlementService(SettlementRepository settlementRepository,
                             SettlementCorrectionRepository settlementCorrectionRepository,
                             SellerDiagnosticCommitmentRepository sellerDiagnosticCommitmentRepository,
                             DiagnosticRequestRepository diagnosticRequestRepository,
                             PolicyVersionService policyVersionService,
                             Clock clock) {
        this.settlementRepository = settlementRepository;
        this.settlementCorrectionRepository = settlementCorrectionRepository;
        this.sellerDiagnosticCommitmentRepository = sellerDiagnosticCommitmentRepository;
        this.diagnosticRequestRepository = diagnosticRequestRepository;
        this.policyVersionService = policyVersionService;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    /**
     * Creates the {@code PENDING} Settlement for a just-completed successful sale. Must be
     * called from within the same physical transaction as the successful-sale transition
     * ({@code AuctionFinalizer.completeSale}) - this method joins the caller's ambient
     * transaction (default REQUIRED propagation), it never opens its own. Every precondition
     * that cannot be deterministically established fails closed, rolling back the whole
     * enclosing transaction - no Settlement is ever created with guessed economics or a guessed
     * seller.
     *
     * @throws IllegalStateException if the Buy Now discriminator, the seller-commitment
     *         snapshot, or the policy snapshot is missing/unresolvable for this auction
     */
    @Transactional
    public Settlement createPending(Auction auction) {
        Objects.requireNonNull(auction, "auction не может быть null");

        SaleFinalizationMethod method = auction.getSaleFinalizationMethod();
        if (method == null) {
            throw new IllegalStateException(
                    "Невозможно создать Settlement для аукциона " + auction.getId()
                            + ": saleFinalizationMethod не определён (исторический аукцион без дискриминатора "
                            + "ORDINARY_BID/BUY_NOW) - Settlement не может быть создан с угаданной экономикой");
        }

        Long commitmentId = auction.getSellerDiagnosticCommitmentId();
        if (commitmentId == null) {
            throw new IllegalStateException(
                    "Невозможно создать Settlement для аукциона " + auction.getId()
                            + ": sellerDiagnosticCommitmentId не установлен - идентичность продавца не может быть "
                            + "определена детерминированно");
        }

        if (auction.getPolicyVersionId() == null) {
            throw new IllegalStateException(
                    "Невозможно создать Settlement для аукциона " + auction.getId()
                            + ": policyVersionId не установлен - экономика комиссии не может быть определена "
                            + "(legacy аукцион без привязки к PolicyVersion)");
        }

        BigDecimal buyerPaidAmount = auction.getWinningBid();
        if (buyerPaidAmount == null) {
            throw new IllegalStateException(
                    "Невозможно создать Settlement для аукциона " + auction.getId() + ": winningBid не установлен");
        }

        Long sellerId = resolveSellerId(commitmentId);

        // Never reads a live/current global policy - always this auction's own immutable
        // PolicyVersion snapshot, exactly like AuctionFinalizer.resolveBuyerFacingReserve.
        BigDecimal commissionRate = policyVersionService.getById(auction.getPolicyVersionId()).getCommissionRate();

        BigDecimal sellerAmount;
        if (method == SaleFinalizationMethod.BUY_NOW) {
            // Locked Buy Now economics: sellerAmount = sellerBuyNowPrice (the seller's own,
            // untouched price) - never the buyer-facing markup, which winningBid already holds.
            BigDecimal buyNowPrice = auction.getBuyNowPrice();
            if (buyNowPrice == null) {
                throw new IllegalStateException(
                        "Невозможно создать Settlement для BUY_NOW аукциона " + auction.getId()
                                + ": buyNowPrice не установлен");
            }
            sellerAmount = buyNowPrice.setScale(2, RoundingMode.HALF_UP);
        } else {
            // Locked ordinary-auction economics: sellerAmount = winningBid / (1 + commissionRate).
            sellerAmount = buyerPaidAmount.divide(BigDecimal.ONE.add(commissionRate), 2, RoundingMode.HALF_UP);
        }
        // Commission is always the subtraction remainder - never independently computed - the
        // only construction that guarantees exact conservation at fixed scale.
        BigDecimal commission = buyerPaidAmount.subtract(sellerAmount);

        Settlement settlement = new Settlement(auction.getId(), sellerId, buyerPaidAmount, sellerAmount,
                commission, now());
        Settlement saved = settlementRepository.save(settlement);
        log.info("Settlement {} created PENDING for auction {}: sellerId={}, expectedSellerAmount={}, "
                        + "expectedCommission={}, method={}",
                saved.getId(), auction.getId(), sellerId, sellerAmount, commission, method);
        return saved;
    }

    /**
     * Resolves the deterministic seller identity via the proven, read-only chain. Never falls
     * back to {@code Auction.createdBy}, the current authenticated user, or any other actor.
     */
    private Long resolveSellerId(Long sellerDiagnosticCommitmentId) {
        SellerDiagnosticCommitment commitment = sellerDiagnosticCommitmentRepository.findById(sellerDiagnosticCommitmentId)
                .orElseThrow(() -> new IllegalStateException(
                        "SellerDiagnosticCommitment " + sellerDiagnosticCommitmentId + " не найден - повреждённые данные"));
        DiagnosticRequest diagnosticRequest = diagnosticRequestRepository.findById(commitment.getDiagnosticRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "DiagnosticRequest " + commitment.getDiagnosticRequestId() + " не найден - повреждённые данные"));
        return diagnosticRequest.getUserId();
    }

    /**
     * Records an admin's attestation that the seller was physically handed {@code attestedAmount}.
     * Never compares it against the Settlement's own expected economics - a discrepancy is legal
     * and preserved. Idempotent on the natural key (auction_id): an exact-amount replay is a safe
     * no-op returning the existing record unchanged (the original recording admin is never
     * overwritten); a conflicting amount fails closed. Admin identity is deliberately NOT part of
     * the replay fingerprint - a different admin submitting the identical amount is still a safe
     * replay.
     */
    @Transactional
    public Settlement recordPayout(Long auctionId, BigDecimal attestedAmount, Long actingAdminId) {
        if (attestedAmount == null) {
            throw new IllegalArgumentException("attestedAmount не может быть null");
        }
        // Canonical money normalization (scale 2, HALF_UP) - same convention createPending already
        // applies. Normalized once, here, before the positivity check and before any downstream use
        // (claimPayoutRecorded's bind parameter, the replay fingerprint comparison below) so every
        // use sees the identical canonical value PostgreSQL's NUMERIC(19,2) will actually persist -
        // never a higher-scale input that would otherwise diverge from the persisted row/response.
        attestedAmount = attestedAmount.setScale(2, RoundingMode.HALF_UP);
        if (attestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("attestedAmount должен быть больше 0: " + attestedAmount);
        }
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");

        Settlement settlement = settlementRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement не найден для аукциона " + auctionId));

        if (settlement.getStatus() == SettlementStatus.PENDING) {
            boolean claimed = settlementRepository.claimPayoutRecorded(
                    settlement.getId(), attestedAmount, actingAdminId, now());
            if (claimed) {
                Settlement recorded = settlementRepository.findById(settlement.getId()).orElseThrow();
                log.info("Settlement {} payout recorded for auction {}: attestedAmount={}, admin={}",
                        recorded.getId(), auctionId, attestedAmount, actingAdminId);
                return recorded;
            }
            // Lost the race - another request already claimed it between our read and our
            // UPDATE attempt. Re-read and fall through to the replay/conflict check below.
            settlement = settlementRepository.findByAuctionId(auctionId).orElseThrow();
        }

        // status is PAYOUT_RECORDED (either on entry, or just lost the race above) - exact
        // replay on the natural key (auctionId, already fixed), fail closed on a conflict.
        boolean sameFingerprint = settlement.getAttestedPayoutAmount().compareTo(attestedAmount) == 0;
        if (!sameFingerprint) {
            throw new IllegalStateException(
                    "Выплата для аукциона " + auctionId + " уже зафиксирована с другой суммой ("
                            + settlement.getAttestedPayoutAmount() + "); попытка записать " + attestedAmount
                            + " отклонена");
        }
        return settlement;
    }

    /**
     * Records a correction, idempotent on the mandatory client-supplied {@code idempotencyKey} -
     * not a content fingerprint, since multiple legitimately-distinct corrections with identical
     * values are explicitly permitted. A retry with the same key and an identical fingerprint is
     * a safe replay; a retry with the same key and a different fingerprint fails closed. A
     * different key always creates a new, independent correction.
     */
    @Transactional
    public SettlementCorrection recordCorrection(Long auctionId, SettlementCorrectionType correctionType,
                                                  BigDecimal correctedAmount, Long correctedSellerId,
                                                  String reason, Long actingAdminId, String idempotencyKey) {
        Objects.requireNonNull(correctionType, "correctionType не может быть null");
        Objects.requireNonNull(actingAdminId, "actingAdminId не может быть null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason обязателен для корректировки Settlement");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey обязателен для корректировки Settlement");
        }
        // Canonical money normalization (scale 2, HALF_UP) - same convention createPending already
        // applies. Normalized once, here, before ANY use - including the replay fingerprint
        // comparison in replayOrFailClosed() below - so a replayed request with the identical raw
        // input is never wrongly treated as a conflicting fingerprint merely because the earlier
        // persisted row already went through SettlementCorrection's own (separately applied, but
        // idempotent) normalization. Null stays null - only AMOUNT_CORRECTION requires a value.
        BigDecimal normalizedCorrectedAmount = correctedAmount == null
                ? null : correctedAmount.setScale(2, RoundingMode.HALF_UP);

        Settlement settlement = settlementRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement не найден для аукциона " + auctionId));

        // Fix 1 (Phase 6 corrective): a correction is an evidence layer on top of an attested
        // payout - it must never be the mechanism that FIRST establishes a payout's existence.
        // Without this guard, SettlementEffectiveState.compute() could report validPayoutExists =
        // true for a Settlement where recordPayout() was never called - fabricating an attestation
        // that never happened. Fails closed before any repository read/write beyond the Settlement
        // lookup above - no correction row is ever created, no partial write, no state touched.
        if (settlement.getStatus() != SettlementStatus.PAYOUT_RECORDED) {
            throw new IllegalStateException(
                    "Невозможно зафиксировать корректировку для Settlement " + settlement.getId()
                            + " (аукцион " + auctionId + "): текущий статус " + settlement.getStatus()
                            + " - корректировка возможна только после того как выплата зафиксирована "
                            + "(status=PAYOUT_RECORDED) через recordPayout");
        }

        Optional<SettlementCorrection> existing = settlementCorrectionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replayOrFailClosed(existing.get(), settlement.getId(), correctionType, normalizedCorrectedAmount,
                    correctedSellerId, reason, actingAdminId, idempotencyKey);
        }

        SettlementCorrection correction = new SettlementCorrection(settlement.getId(), correctionType,
                normalizedCorrectedAmount, correctedSellerId, reason, actingAdminId, now(), idempotencyKey);
        try {
            SettlementCorrection saved = settlementCorrectionRepository.save(correction);
            log.info("SettlementCorrection {} recorded for settlement {} (auction {}): type={}, admin={}",
                    saved.getId(), settlement.getId(), auctionId, correctionType, actingAdminId);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Concurrent same-key insert race - re-read and apply the same replay/conflict check.
            // Safe because the failed insert ran in its OWN transaction (REQUIRES_NEW on
            // SettlementCorrectionRepositoryAdapter.save) - this recovery read uses the current,
            // still-healthy outer transaction, never the aborted one the failed insert poisoned.
            SettlementCorrection raced = settlementCorrectionRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e);
            return replayOrFailClosed(raced, settlement.getId(), correctionType, normalizedCorrectedAmount,
                    correctedSellerId, reason, actingAdminId, idempotencyKey);
        }
    }

    private SettlementCorrection replayOrFailClosed(SettlementCorrection existing, Long settlementId,
                                                      SettlementCorrectionType correctionType,
                                                      BigDecimal correctedAmount, Long correctedSellerId,
                                                      String reason, Long actingAdminId, String idempotencyKey) {
        boolean sameFingerprint = existing.getSettlementId().equals(settlementId)
                && existing.getCorrectionType() == correctionType
                && equalsNullable(existing.getCorrectedAmount(), correctedAmount)
                && Objects.equals(existing.getCorrectedSellerId(), correctedSellerId)
                && existing.getReason().equals(reason)
                && existing.getCorrectedByAdminId().equals(actingAdminId);
        if (!sameFingerprint) {
            throw new IllegalStateException(
                    "Ключ идемпотентности '" + idempotencyKey + "' уже использован для другой корректировки");
        }
        return existing;
    }

    private static boolean equalsNullable(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    /**
     * Computes the current effective business fact for an auction's Settlement, folding its
     * full correction history over the original row. Never materializes a cached/mutable
     * "current state" anywhere - recomputed fresh on every call.
     */
    @Transactional(readOnly = true)
    public SettlementEffectiveState effectiveState(Long auctionId) {
        Settlement settlement = settlementRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement не найден для аукциона " + auctionId));
        List<SettlementCorrection> corrections =
                settlementCorrectionRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(settlement.getId());
        return SettlementEffectiveState.compute(settlement, corrections);
    }

    @Transactional(readOnly = true)
    public Settlement getByAuctionId(Long auctionId) {
        return settlementRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement не найден для аукциона " + auctionId));
    }

    /**
     * Pure read pass-through for cross-module read-model assembly (e.g. by
     * {@code SaleStatusQueryService}). Unlike {@link #getByAuctionId}, never throws when no
     * Settlement exists yet - the caller decides how to represent that absence in its own
     * response shape (e.g. a nullable field), never this service's job.
     */
    @Transactional(readOnly = true)
    public Optional<Settlement> findByAuctionId(Long auctionId) {
        return settlementRepository.findByAuctionId(auctionId);
    }

    /** Pure read pass-through - the ordered correction history for a given settlement id. */
    @Transactional(readOnly = true)
    public List<SettlementCorrection> getCorrections(Long settlementId) {
        return settlementCorrectionRepository.findBySettlementIdOrderByCorrectedAtAscIdAsc(settlementId);
    }
}
