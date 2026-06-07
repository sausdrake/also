package com.example.autoauction.auction.infrastructure.scheduler;

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
import org.springframework.transaction.annotation.Transactional;

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

    private final Set<String> sentEndingWarnings = ConcurrentHashMap.newKeySet();

    @Value("${auction.confirm.hours:2}")
    private int confirmHours;

    public AuctionCompletionScheduler(AuctionRepository auctionRepository,
                                      AuctionFinalizer auctionFinalizer,
                                      AuctionParticipantRepository participantRepository,
                                      NotificationService notificationService) {
        this.auctionRepository = auctionRepository;
        this.auctionFinalizer = auctionFinalizer;
        this.participantRepository = participantRepository;
        this.notificationService = notificationService;
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
