package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.admin.application.SchedulerHealthSignal;
import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.notification.application.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@EnableScheduling
public class AuctionCompletionScheduler {

    private final AuctionRepository auctionRepository;
    private final AuctionFinalizer auctionFinalizer;
    private final AuctionParticipantRepository participantRepository;
    private final NotificationService notificationService;
    private final SchedulerHealthSignal schedulerHealthSignal;

    private final Set<String> sentEndingWarnings = ConcurrentHashMap.newKeySet();

    @Value("${auction.confirm.hours:2}")
    private int confirmHours;

    public AuctionCompletionScheduler(AuctionRepository auctionRepository,
                                      AuctionFinalizer auctionFinalizer,
                                      AuctionParticipantRepository participantRepository,
                                      NotificationService notificationService,
                                      SchedulerHealthSignal schedulerHealthSignal) {
        this.auctionRepository = auctionRepository;
        this.auctionFinalizer = auctionFinalizer;
        this.participantRepository = participantRepository;
        this.notificationService = notificationService;
        this.schedulerHealthSignal = schedulerHealthSignal;
        log.info("AuctionCompletionScheduler initialized");
    }

    /**
     * Каждую минуту: завершаем истёкшие ACTIVE-аукционы.
     *
     * R-3: deliberately NOT @Transactional at this level. AuctionFinalizer.finalizeAuction()
     * is independently @Transactional (REQUIRED) and is called here as a genuine external
     * bean call, so each auction gets its OWN physical transaction. If this method were
     * @Transactional REQUIRED, every finalizeAuction() call in the loop would join the SAME
     * physical transaction; one auction's optimistic-lock conflict on save() would then mark
     * that shared transaction rollback-only, silently reverting every other auction already
     * processed in the same invocation when the outer commit converts to a rollback (proven
     * empirically in AuctionCompletionSchedulerTransactionIsolationIntegrationTest). Removing
     * this annotation makes each finalizeAuction() call the transaction owner for its own
     * auction, isolating failures to that single auction.
     */
    @Scheduled(fixedDelay = 60_000)
    public void completeExpiredAuctions() {
        // Phase 73 (G-N4): the try/catch below exists ONLY to report the operational signal -
        // it re-throws unconditionally, preserving this method's deliberate R-3 propagation
        // design exactly (see class Javadoc above) rather than swallowing anything new here.
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<Auction> expired = auctionRepository.findByStatusAndEndTimeBefore(AuctionStatus.ACTIVE, now);

            if (expired.isEmpty()) {
                schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.completeExpiredAuctions");
                return;
            }

            int count = 0;
            for (Auction auction : expired) {
                log.info("Finalizing expired auction {} (vehicle {})", auction.getId(), auction.getVehicleId());
                if (auctionFinalizer.finalizeAuction(auction)) count++;
            }
            log.info("Finalized {} expired auctions", count);
            schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.completeExpiredAuctions");
        } catch (RuntimeException e) {
            schedulerHealthSignal.reportFailure("AuctionCompletionScheduler.completeExpiredAuctions");
            throw e;
        }
    }

    /**
     * Каждую минуту: запускаем подтверждённые CREATED-аукционы, у которых startTime <= now.
     *
     * Vehicle Auction-State Wiring: deliberately NOT @Transactional at this level, for the
     * same R-3 reason as completeExpiredAuctions()/cancelUnconfirmedAuctions() above -
     * AuctionFinalizer.startAuction() is independently @Transactional (REQUIRED) and is called
     * here as a genuine external bean call, so each auction (and its atomic Vehicle
     * APPROVED->IN_AUCTION transition) gets its own physical transaction. Without this, every
     * startAuction() call in the loop would join the SAME physical transaction under the
     * previous method-level @Transactional, and one auction's optimistic-lock conflict on
     * save() could mark that shared transaction rollback-only, silently reverting every other
     * auction already started in the same invocation - the exact defect already fixed for the
     * two sibling methods (see AuctionCompletionSchedulerTransactionIsolationIntegrationTest /
     * AuctionFinalizationSchedulerTransactionIsolationIntegrationTest), now closed here too.
     */
    @Scheduled(fixedDelay = 60_000)
    public void startConfirmedAuctions() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Auction> ready = auctionRepository.findConfirmedAuctionsReadyToStart(now);

        if (ready.isEmpty()) {
            schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.startConfirmedAuctions");
            return;
        }

        for (Auction auction : ready) {
            try {
                auctionFinalizer.startAuction(auction);
                log.info("Auction {} auto-started (confirmed)", auction.getId());
            } catch (Exception e) {
                log.error("Failed to auto-start auction {}: {}", auction.getId(), e.getMessage());
            }
        }
        schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.startConfirmedAuctions");
    }

    /** Каждую минуту: предупреждения за 1 час, 15 минут, 5 минут до окончания */
    @Scheduled(fixedDelay = 60_000)
    public void sendEndingSoonWarnings() {
        OffsetDateTime now = OffsetDateTime.now();

        int[]    minuteOffsets = {60,         15,                        5};
        String[] titles        = {"Аукцион заканчивается через 1 час",
                                   "Аукцион заканчивается через 15 минут",
                                   "Аукцион заканчивается через 5 минут"};
        String[] labels        = {"1 час", "15 минут", "5 минут"};

        for (int i = 0; i < minuteOffsets.length; i++) {
            int mins = minuteOffsets[i];
            OffsetDateTime from = now.plusSeconds((long) mins * 60 - 30);
            OffsetDateTime to   = now.plusSeconds((long) mins * 60 + 30);

            List<Auction> ending = auctionRepository.findActiveEndingBetween(from, to);
            for (Auction auction : ending) {
                String key = auction.getId() + "_" + mins;
                if (!sentEndingWarnings.add(key)) continue;

                List<Long> participants = participantRepository.getParticipants(auction.getId());
                for (Long userId : participants) {
                    try {
                        notificationService.send(userId, "AUCTION_ENDING_SOON", titles[i],
                                String.format("Аукцион \"%s\" завершается через %s. Успейте сделать ставку!",
                                        auction.getVehicleInfo(), labels[i]),
                                auction.getId(), "AUCTION");
                    } catch (Exception e) {
                        log.warn("Failed to send ending-soon warning to user {}: {}", userId, e.getMessage());
                    }
                }
            }
        }
        schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.sendEndingSoonWarnings");
    }

    /**
     * Каждую минуту: отменяем CREATED-аукционы без подтверждения,
     * у которых дедлайн (startTime - confirmHours) уже прошёл.
     *
     * R-3: deliberately NOT @Transactional at this level, for the same reason as
     * completeExpiredAuctions() above - AuctionFinalizer.cancelAuction() is independently
     * @Transactional (REQUIRED) and called here as a genuine external bean call, so each
     * auction gets its own physical transaction instead of all auctions in the batch sharing
     * (and being able to poison) one.
     */
    @Scheduled(fixedDelay = 60_000)
    public void cancelUnconfirmedAuctions() {
        OffsetDateTime deadline = OffsetDateTime.now().plusHours(confirmHours);
        List<Auction> overdue = auctionRepository.findUnconfirmedAuctionsPastDeadline(deadline);

        if (overdue.isEmpty()) {
            schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.cancelUnconfirmedAuctions");
            return;
        }

        for (Auction auction : overdue) {
            try {
                // R-1.5: route through AuctionFinalizer.cancelAuction() rather than calling
                // auction.cancel()+save() directly — this is the canonical, single place that
                // unfreezes every participant's deposit before cancelling. CREATED-status
                // auctions should never have frozen participants under the current call graph
                // (freezing only ever happens together, atomically, with a transition away
                // from CREATED — see BidService.placeBid/buyNow), but routing through the
                // finalizer removes the duplicated cancellation logic and closes that gap
                // permanently rather than relying on that invariant holding forever.
                if (auctionFinalizer.cancelAuction(auction)) {
                    log.info("Auction {} auto-cancelled (no confirmation within {} hours of start)",
                            auction.getId(), confirmHours);
                }
            } catch (Exception e) {
                log.error("Failed to auto-cancel auction {}: {}", auction.getId(), e.getMessage());
            }
        }
        schedulerHealthSignal.reportSuccess("AuctionCompletionScheduler.cancelUnconfirmedAuctions");
    }
}
