// auction/application/AuctionFinalizer.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.web.AuctionWebSocketController;
import com.example.autoauction.deposit.application.DepositService;
import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.notification.application.NotificationService;
import com.example.autoauction.policy.application.PolicyVersionService;
import com.example.autoauction.sale.application.SaleResolutionService;
import com.example.autoauction.sale.domain.SaleResolution;
import com.example.autoauction.vehicle.application.VehicleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
public class AuctionFinalizer {

    private final AuctionRepository auctionRepository;
    private final DepositService depositService;
    private final AuctionParticipantRepository participantRepository;
    private final AuctionWebSocketController webSocketController;
    private final NotificationService notificationService;
    private final Clock clock;
    private final PolicyVersionService policyVersionService;
    private final VehicleService vehicleService;
    private final SaleResolutionService saleResolutionService;
    private final SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator;

    public AuctionFinalizer(AuctionRepository auctionRepository,
                            DepositService depositService,
                            AuctionParticipantRepository participantRepository,
                            AuctionWebSocketController webSocketController,
                            NotificationService notificationService,
                            Clock clock,
                            PolicyVersionService policyVersionService,
                            VehicleService vehicleService,
                            SaleResolutionService saleResolutionService,
                            SaleCommitmentResolutionOrchestrator saleCommitmentResolutionOrchestrator) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        this.participantRepository = participantRepository;
        this.webSocketController = webSocketController;
        this.notificationService = notificationService;
        this.clock = clock;
        this.policyVersionService = policyVersionService;
        this.vehicleService = vehicleService;
        this.saleResolutionService = saleResolutionService;
        this.saleCommitmentResolutionOrchestrator = saleCommitmentResolutionOrchestrator;
    }

    /**
     * Slice 1b-2: resolves the buyer-facing reserve price this specific auction must be checked
     * against. For a legacy auction (policyVersionId == null) this is the raw stored value
     * unchanged. For a policy-bound auction it is resolved via THIS auction's own fixed
     * policyVersionId - never PolicyVersionService.getCurrentPublished() - an auction's
     * commission is fixed forever at creation time regardless of what is published later.
     */
    private BigDecimal resolveBuyerFacingReserve(Auction auction) {
        if (auction.getReservePrice() == null) {
            return null;
        }
        if (auction.getPolicyVersionId() == null) {
            return auction.getReservePrice();
        }
        BigDecimal commissionRate = policyVersionService.getById(auction.getPolicyVersionId()).getCommissionRate();
        return auction.resolveBuyerFacingReservePrice(commissionRate);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    @Transactional
    public boolean finalizeAuction(Auction auction) {
        if (auction.isFinished()) {
            log.debug("Auction {} is already finished with status {}", auction.getId(), auction.getStatus());
            return false;
        }

        log.info("Finalizing auction {} (vehicle {})", auction.getId(), auction.getVehicleId());

        AuctionStatus finalStatus;
        Long winnerId = null;
        BigDecimal winningBid = null;
        List<Long> participants = participantRepository.getParticipants(auction.getId());

        if (auction.getTotalBids() == null || auction.getTotalBids() == 0) {
            finalStatus = AuctionStatus.EXPIRED; // no-bids branch, unaffected by Slice 1a
            log.info("Auction {} expired with no bids", auction.getId());

            for (Long participantId : participants) {
                unfreezeDepositSafely(participantId, auction.getId());
            }

            // ✅ Отправляем уведомление об истечении
            try {
                webSocketController.sendAuctionEnded(auction.getId(), null, null);
            } catch (Exception e) {
                log.warn("Failed to send WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
            }

        } else {
            BigDecimal buyerFacingReserve = resolveBuyerFacingReserve(auction);
            boolean reserveMet = buyerFacingReserve == null ||
                    auction.getCurrentPrice().compareTo(buyerFacingReserve) >= 0;

            if (reserveMet) {
                // Slice 1a: bidding concluding with a winner no longer means SOLD - it means the
                // sale is now awaiting the confirmed-sale workflow (payment + handover + admin
                // confirmation), which is outside this slice's scope.
                finalStatus = AuctionStatus.WAITING_FOR_PAYMENT;
                winnerId = auction.getWinnerId();
                winningBid = auction.getWinningBid();

                log.info("Auction {} finished bidding with a winner, awaiting payment confirmation. Winner: {}, Winning bid: {}",
                        auction.getId(), winnerId, winningBid);

                if (winnerId != null) {
                    markWinnerSafely(winnerId, auction.getId());
                    try {
                        webSocketController.sendPersonalUpdate(winnerId, auction, "YOU_WON");
                    } catch (Exception e) {
                        log.warn("Failed to send personal WebSocket notification to user {}: {}", winnerId, e.getMessage());
                    }
                    try {
                        notificationService.send(winnerId, "AUCTION_WON",
                                "Вы выиграли аукцион!",
                                String.format("Поздравляем! Вы выиграли аукцион \"%s\" с ценой %s сом.",
                                        auction.getVehicleInfo(), winningBid.toPlainString()),
                                auction.getId(), "AUCTION");
                    } catch (Exception e) {
                        log.warn("Failed to send win notification to user {}: {}", winnerId, e.getMessage());
                    }
                }

                for (Long participantId : participants) {
                    if (!participantId.equals(winnerId)) {
                        unfreezeDepositSafely(participantId, auction.getId());
                    }
                }

                // Notify each loser
                for (Long participantId : participants) {
                    if (!participantId.equals(winnerId)) {
                        try {
                            notificationService.send(participantId, "AUCTION_LOST",
                                    "Вы не выиграли аукцион",
                                    String.format("Аукцион \"%s\" завершён. Победителем стал другой участник. Ваши средства разблокированы.",
                                            auction.getVehicleInfo()),
                                    auction.getId(), "AUCTION");
                        } catch (Exception e) {
                            log.warn("Failed to send AUCTION_LOST to user {}: {}", participantId, e.getMessage());
                        }
                    }
                }
                // Notify seller about auction completion
                Long sellerId = auction.getCreatedBy();
                if (sellerId != null) {
                    try {
                        notificationService.send(sellerId, "AUCTION_COMPLETED",
                                "Аукцион завершён",
                                String.format("Аукцион \"%s\" завершён. Победитель определён.", auction.getVehicleInfo()),
                                auction.getId(), "AUCTION");
                    } catch (Exception e) {
                        log.warn("Failed to send AUCTION_COMPLETED to seller {}: {}", sellerId, e.getMessage());
                    }
                }

                // ✅ Отправляем уведомление о завершении
                try {
                    webSocketController.sendAuctionEnded(auction.getId(), winnerId, winningBid);
                } catch (Exception e) {
                    log.warn("Failed to send WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
                }

            } else {
                finalStatus = AuctionStatus.EXPIRED;
                log.info("Auction {} expired - reserve price not met", auction.getId());

                for (Long participantId : participants) {
                    unfreezeDepositSafely(participantId, auction.getId());
                }

                // ✅ Отправляем уведомление об истечении
                try {
                    webSocketController.sendAuctionEnded(auction.getId(), null, null);
                } catch (Exception e) {
                    log.warn("Failed to send WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
                }
            }
        }

        auction.setStatus(finalStatus);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(winningBid);
        auction.setUpdatedAt(now());

        auctionRepository.save(auction);
        participantRepository.clearParticipants(auction.getId());

        // ✅ Отправляем финальное обновление
        try {
            webSocketController.sendAuctionUpdate(auction, "AUCTION_FINALIZED");
        } catch (Exception e) {
            log.warn("Failed to send final WebSocket update for auction {}: {}", auction.getId(), e.getMessage());
        }

        log.info("Auction {} finalized with status: {}", auction.getId(), finalStatus);
        return true;
    }

    /**
     * Vehicle Auction-State Wiring: the single-auction, atomic Auction+Vehicle transition for
     * CREATED -> ACTIVE. Called from both production paths that ever call Auction.start()
     * (AdminAuctionService.startAuction, which fetches with a pessimistic lock via
     * findByIdWithLock before calling this; AuctionCompletionScheduler.startConfirmedAuctions,
     * which passes an Auction from its own unlocked bulk read) - each call gets its own
     * independent transaction, since neither caller wraps this call in an ambient transaction
     * of its own spanning multiple auctions. This mirrors the R-3 precedent already
     * established for finalizeAuction/cancelAuction, called the same way from
     * completeExpiredAuctions/cancelUnconfirmedAuctions.
     *
     * Ordering is mandatory and enforced by plain statement order, not a new guard:
     * auction.start() runs first and throws its own existing IllegalStateException, unchanged,
     * if the auction is not CREATED - vehicleService.markInAuction() is never reached in that
     * case. Vehicle.markInAuction() is deliberately left non-idempotent (its own existing
     * guard, unchanged) - a second start attempt against an already-ACTIVE auction fails at
     * the very first line, before ever touching Vehicle, so no idempotency is needed on the
     * Vehicle side to satisfy that requirement.
     *
     * Both mutations and both saves happen inside this one @Transactional (REQUIRED) boundary:
     * a failure on either side - the domain guard throwing, or either save() throwing (e.g. an
     * optimistic-lock conflict on Auction's own @Version) - rolls back the whole transaction,
     * so Auction and Vehicle can never diverge. No REQUIRES_NEW, no Vehicle-side locking - both
     * deliberately out of scope for this slice; safety comes entirely from sharing one
     * transaction plus Auction's existing @Version check.
     */
    @Transactional
    public void startAuction(Auction auction) {
        auction.start();
        vehicleService.markInAuction(auction.getVehicleId());
        auctionRepository.save(auction);
        log.info("Auction {} started, vehicle {} marked IN_AUCTION", auction.getId(), auction.getVehicleId());
    }

    /**
     * X2 (Authoritative Sale Completion): the single-auction, atomic transition
     * WAITING_FOR_PAYMENT -> SOLD, together with its mandatory Vehicle IN_AUCTION -> SOLD
     * counterpart. Mirrors startAuction()'s established shape and reasoning exactly: the caller
     * (application-layer, not yet wired to any controller in this slice) is responsible for
     * fetching {@code auction} via {@code auctionRepository.findByIdWithLock(auctionId)} BEFORE
     * calling this method - completeSale() deliberately does NOT re-fetch or re-lock, to avoid
     * duplicating (and potentially diverging from) the caller's own lock acquisition. This one
     * @Transactional (REQUIRED) boundary covers the full authoritative sale: evidence
     * validation + SaleResolution creation, the Auction transition, and the Vehicle transition
     * all commit or roll back together.
     *
     * Ordering is mandatory and enforced by plain statement order, not a new guard:
     * <ol>
     *   <li>Guard: auction must be WAITING_FOR_PAYMENT, else throws IllegalStateException before
     *       anything else runs - this is what makes a repeat completion (already SOLD, or any
     *       other status) fail closed without ever touching evidence/SaleResolution/Vehicle.</li>
     *   <li>{@link SaleResolutionService#createSuccess(Long, Long)} is called first, as a
     *       genuine external bean call. It is plain {@code @Transactional} (default REQUIRED
     *       propagation, not REQUIRES_NEW), so it joins THIS method's own physical transaction -
     *       no new transaction boundary is introduced. It performs every evidence/cross-field
     *       validation (payment/handover existence, auction-id/buyer-id/vehicle-id/amount
     *       matching, SaleResolution cardinality) already locked for X1 - none of that is
     *       duplicated here. If it throws, this method's transaction rolls back with nothing
     *       persisted and Auction/Vehicle both untouched.</li>
     *   <li>auction.markSold() runs only after step 2 succeeds - its own domain guard is
     *       redundant with the pre-check above but kept as the same defense-in-depth pattern
     *       already used by every other Auction transition in this class.</li>
     *   <li>vehicleService.markAsSold(auction.getVehicleId()) runs after the Auction has already
     *       been mutated in memory but NOT yet saved - if this throws (Vehicle not IN_AUCTION),
     *       the whole transaction rolls back: neither the SaleResolution row nor the Auction
     *       save become durable, satisfying the required atomic-rollback invariant
     *       (Auction.SOLD => Vehicle.SOLD, never a partially-committed pair).</li>
     *   <li>auctionRepository.save(auction) persists the Auction transition, relying on its
     *       existing @Version optimistic lock exactly as every other save() in this class does.</li>
     * </ol>
     *
     * No REQUIRES_NEW, no Vehicle-side locking, no compensating transaction - all deliberately
     * out of scope for this slice; safety comes entirely from sharing one transaction plus the
     * caller's pessimistic lock on the Auction row (identical reasoning to startAuction()).
     */
    @Transactional
    public SaleResolution completeSale(Auction auction, Long resolvedBy) {
        if (auction.getStatus() != AuctionStatus.WAITING_FOR_PAYMENT) {
            throw new IllegalStateException(
                    String.format("Невозможно завершить продажу аукциона %d в статусе '%s'. Только аукционы со статусом 'WAITING_FOR_PAYMENT' могут быть проданы",
                            auction.getId(), auction.getStatus())
            );
        }

        SaleResolution resolution = saleResolutionService.createSuccess(auction.getId(), resolvedBy);
        auction.markSold();
        vehicleService.markAsSold(auction.getVehicleId());

        // X3-D: Outcome A buyer-deposit consequence - reuses the existing releaseWinnerDeposit
        // primitive (structurally identical to unblockForSaleFailure, just under its older,
        // pre-X3-A name), not a new "unblockForSuccess" method.
        depositService.releaseWinnerDeposit(auction.getWinnerId(), auction.getId());

        // X3-D: seller commitment consequence, only if this auction has one (historical
        // auctions may not). Uses ONLY auction.getSellerDiagnosticCommitmentId() - never
        // Vehicle/DiagnosticRequest/VIN/latest-commitment re-resolution.
        if (auction.getSellerDiagnosticCommitmentId() != null) {
            saleCommitmentResolutionOrchestrator.resolve(
                    auction.getSellerDiagnosticCommitmentId(), FinalizationOutcome.A);
        }

        auctionRepository.save(auction);

        // X3-D: financialResolutionStatus now reflects that every financial consequence this
        // slice owns (buyer deposit + commitment, if any) has succeeded within this same
        // transaction.
        saleResolutionService.markFinancialResolutionDone(resolution.getId());

        log.info("Auction {} sale completed, vehicle {} marked SOLD", auction.getId(), auction.getVehicleId());
        return resolution;
    }

    @Transactional
    public boolean cancelAuction(Auction auction) {
        if (!auction.isCancellable()) {
            log.debug("Auction {} cannot be cancelled from status {}, skipping cancellation", auction.getId(), auction.getStatus());
            return false;
        }

        log.info("Cancelling auction {} (vehicle {})", auction.getId(), auction.getVehicleId());

        List<Long> participants = participantRepository.getParticipants(auction.getId());
        for (Long participantId : participants) {
            unfreezeDepositSafely(participantId, auction.getId());
        }

        auction.cancel();
        auctionRepository.save(auction);
        participantRepository.clearParticipants(auction.getId());

        try {
            webSocketController.sendAuctionEnded(auction.getId(), null, null);
        } catch (Exception e) {
            log.warn("Failed to send WebSocket notification for cancelled auction {}: {}", auction.getId(), e.getMessage());
        }

        for (Long participantId : participants) {
            try {
                notificationService.send(participantId, "AUCTION_CANCELLED",
                        "Аукцион отменён",
                        String.format("Аукцион \"%s\" был отменён администратором. Ваши средства разблокированы.",
                                auction.getVehicleInfo()),
                        auction.getId(), "AUCTION");
            } catch (Exception e) {
                log.warn("Failed to send AUCTION_CANCELLED to user {}: {}", participantId, e.getMessage());
            }
        }

        log.info("Auction {} cancelled, {} participant(s) unfrozen", auction.getId(), participants.size());
        return true;
    }

    @Transactional
    public boolean finalizeAuctionById(Long auctionId) {
        Auction auction = auctionRepository.findByIdWithLock(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Auction not found: " + auctionId));

        return finalizeAuction(auction);
    }

    @Transactional
    public void finalizeWithBuyNow(Auction auction, Long winnerId, BigDecimal buyNowAmount) {
        if (auction.isFinished()) {
            log.warn("Auction {} is already finished with status {}, skipping Buy Now finalization",
                    auction.getId(), auction.getStatus());
            return;
        }

        log.info("Finalizing auction {} with Buy Now by user {}", auction.getId(), winnerId);

        // Slice 1a: Buy Now no longer produces SOLD directly - it produces the same
        // payment-pending state as a normal auction win.
        auction.setStatus(AuctionStatus.WAITING_FOR_PAYMENT);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(buyNowAmount);
        auction.setCurrentPrice(buyNowAmount);
        auction.setUpdatedAt(now());

        auctionRepository.save(auction);
        markWinnerSafely(winnerId, auction.getId());

        List<Long> participants = participantRepository.getParticipants(auction.getId());
        for (Long participantId : participants) {
            if (!participantId.equals(winnerId)) {
                unfreezeDepositSafely(participantId, auction.getId());
            }
        }

        participantRepository.clearParticipants(auction.getId());

        // Уведомление победителю о Buy Now
        try {
            notificationService.send(winnerId, "AUCTION_WON",
                    "Вы выиграли аукцион!",
                    String.format("Поздравляем! Вы купили \"%s\" по цене %s сом (Buy Now).",
                            auction.getVehicleInfo(), buyNowAmount.toPlainString()),
                    auction.getId(), "AUCTION");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now win notification to user {}: {}", winnerId, e.getMessage());
        }

        // ✅ Отправляем уведомление о Buy Now
        try {
            webSocketController.sendBuyNowNotification(auction.getId(), winnerId, buyNowAmount);
            webSocketController.sendAuctionUpdate(auction, "BUY_NOW");
        } catch (Exception e) {
            log.warn("Failed to send Buy Now WebSocket notification for auction {}: {}", auction.getId(), e.getMessage());
        }

        log.info("Auction {} completed via Buy Now. Winner: {}", auction.getId(), winnerId);
    }

    private void unfreezeDepositSafely(Long userId, Long auctionId) {
        try {
            depositService.unfreezeForAuction(userId, auctionId);
            log.debug("Deposit unfrozen for user {} on auction {}", userId, auctionId);
        } catch (Exception e) {
            log.error("Failed to unfreeze deposit for user {} on auction {}: {}", userId, auctionId, e.getMessage());
        }
    }

    private void markWinnerSafely(Long userId, Long auctionId) {
        try {
            depositService.markAsWinner(userId, auctionId);
            log.debug("User {} marked as winner of auction {}", userId, auctionId);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to mark user {} as winner of auction {}: {}",
                    userId, auctionId, e.getMessage(), e);
        }
    }
}