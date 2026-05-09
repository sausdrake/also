// auction/application/AuctionFinalizer.java
package com.example.autoauction.auction.application;

import com.example.autoauction.auction.domain.Auction;
import com.example.autoauction.auction.domain.AuctionStatus;
import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.deposit.application.DepositService;
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
    private final Clock clock;

    public AuctionFinalizer(AuctionRepository auctionRepository,
                            DepositService depositService,
                            AuctionParticipantRepository participantRepository,
                            Clock clock) {
        this.auctionRepository = auctionRepository;
        this.depositService = depositService;
        this.participantRepository = participantRepository;
        this.clock = clock;
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
            finalStatus = AuctionStatus.EXPIRED;
            log.info("Auction {} expired with no bids", auction.getId());

            for (Long participantId : participants) {
                unfreezeDepositSafely(participantId, auction.getId());
            }

        } else {
            boolean reserveMet = auction.getReservePrice() == null ||
                    auction.getCurrentPrice().compareTo(auction.getReservePrice()) >= 0;

            if (reserveMet) {
                finalStatus = AuctionStatus.COMPLETED;
                winnerId = auction.getWinnerId();
                winningBid = auction.getWinningBid();

                log.info("Auction {} completed successfully. Winner: {}, Winning bid: {}",
                        auction.getId(), winnerId, winningBid);

                if (winnerId != null) {
                    markWinnerSafely(winnerId, auction.getId());
                }

                for (Long participantId : participants) {
                    if (!participantId.equals(winnerId)) {
                        unfreezeDepositSafely(participantId, auction.getId());
                    }
                }

            } else {
                finalStatus = AuctionStatus.EXPIRED;
                log.info("Auction {} expired - reserve price not met", auction.getId());

                for (Long participantId : participants) {
                    unfreezeDepositSafely(participantId, auction.getId());
                }
            }
        }

        auction.setStatus(finalStatus);
        auction.setWinnerId(winnerId);
        auction.setWinningBid(winningBid);
        auction.setUpdatedAt(now());

        auctionRepository.save(auction);
        participantRepository.clearParticipants(auction.getId());

        log.info("Auction {} finalized with status: {}", auction.getId(), finalStatus);
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

        auction.setStatus(AuctionStatus.COMPLETED);
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

        log.info("Auction {} completed via Buy Now. Winner: {}", auction.getId(), winnerId);
    }

    private void unfreezeDepositSafely(Long userId, Long auctionId) {
        // Метод уже не выбрасывает исключения, так как DepositService обрабатывает их внутри
        depositService.unfreezeForAuction(userId, auctionId);
        log.debug("Deposit unfrozen for user {} on auction {}", userId, auctionId);
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