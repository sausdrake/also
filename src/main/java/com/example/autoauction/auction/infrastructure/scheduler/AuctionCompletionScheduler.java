// auction/infrastructure/scheduler/AuctionCompletionScheduler.java
package com.example.autoauction.auction.infrastructure.scheduler;

import com.example.autoauction.auction.application.AuctionFinalizer;
import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.auction.infrastructure.persistence.SpringDataAuctionJpaRepository;
import lombok.extern.slf4j.Slf4j;
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
    private final SpringDataAuctionJpaRepository jpaRepository;

    public AuctionCompletionScheduler(AuctionRepository auctionRepository,
                                      AuctionFinalizer auctionFinalizer,
                                      SpringDataAuctionJpaRepository jpaRepository) {
        this.auctionRepository = auctionRepository;
        this.auctionFinalizer = auctionFinalizer;
        this.jpaRepository = jpaRepository;
        log.info("AuctionCompletionScheduler initialized");
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void completeExpiredAuctions() {
        log.debug("Checking for expired auctions...");

        OffsetDateTime now = OffsetDateTime.now();
        List<Auction> activeAuctions = auctionRepository.findByStatusAndEndTimeBefore(AuctionStatus.ACTIVE, now);

        int completedCount = 0;
        for (Auction auction : activeAuctions) {
            log.info("Auction {} (vehicle {}) has ended", auction.getId(), auction.getVehicleId());
            if (auctionFinalizer.finalizeAuction(auction)) {
                completedCount++;
            }
        }

        if (completedCount > 0) {
            log.info("Finalized {} expired auctions", completedCount);
        }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void cancelExpiredCreatedAuctions() {
        log.debug("Checking for expired CREATED auctions...");

        OffsetDateTime now = OffsetDateTime.now();
        int cancelledCount = jpaRepository.cancelExpiredCreatedAuctions(now);

        if (cancelledCount > 0) {
            log.info("Cancelled {} expired CREATED auctions", cancelledCount);
        }
    }
}