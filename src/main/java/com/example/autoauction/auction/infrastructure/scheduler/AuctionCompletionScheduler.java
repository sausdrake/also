package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@EnableScheduling
public class AuctionCompletionScheduler {

    private final AuctionRepository auctionRepository;
    private final AuctionFinalizer auctionFinalizer;

    @Value("${auction.confirm.hours:2}")
    private int confirmHours;

    public AuctionCompletionScheduler(AuctionRepository auctionRepository,
                                      AuctionFinalizer auctionFinalizer) {
        this.auctionRepository = auctionRepository;
        this.auctionFinalizer = auctionFinalizer;
        log.info("AuctionCompletionScheduler initialized");
    }

    /** Каждую минуту: завершаем истёкшие ACTIVE-аукционы */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void completeExpiredAuctions() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Auction> expired = auctionRepository.findByStatusAndEndTimeBefore(AuctionStatus.ACTIVE, now);

        if (expired.isEmpty()) return;

        int count = 0;
        for (Auction auction : expired) {
            log.info("Finalizing expired auction {} (vehicle {})", auction.getId(), auction.getVehicleId());
            if (auctionFinalizer.finalizeAuction(auction)) count++;
        }
        log.info("Finalized {} expired auctions", count);
    }

    /** Каждую минуту: запускаем подтверждённые CREATED-аукционы, у которых startTime <= now */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void startConfirmedAuctions() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Auction> ready = auctionRepository.findConfirmedAuctionsReadyToStart(now);

        if (ready.isEmpty()) return;

        for (Auction auction : ready) {
            try {
                auction.start();
                auctionRepository.save(auction);
                log.info("Auction {} auto-started (confirmed)", auction.getId());
            } catch (Exception e) {
                log.error("Failed to auto-start auction {}: {}", auction.getId(), e.getMessage());
            }
        }
    }

    /**
     * Каждую минуту: отменяем CREATED-аукционы без подтверждения,
     * у которых дедлайн (startTime - confirmHours) уже прошёл.
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cancelUnconfirmedAuctions() {
        OffsetDateTime deadline = OffsetDateTime.now().plusHours(confirmHours);
        List<Auction> overdue = auctionRepository.findUnconfirmedAuctionsPastDeadline(deadline);

        if (overdue.isEmpty()) return;

        for (Auction auction : overdue) {
            try {
                auction.cancel();
                auctionRepository.save(auction);
                log.info("Auction {} auto-cancelled (no confirmation within {} hours of start)",
                        auction.getId(), confirmHours);
            } catch (Exception e) {
                log.error("Failed to auto-cancel auction {}: {}", auction.getId(), e.getMessage());
            }
        }
    }
}
