package com.example.autoauction.subscription.application;

import com.example.autoauction.auction.domain.port.AuctionRepository;
import com.example.autoauction.subscription.domain.port.LotSubscriptionRepository;
import com.example.autoauction.subscription.web.dto.SubscribedAuctionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LotSubscriptionService {

    private final LotSubscriptionRepository subscriptionRepository;
    private final AuctionRepository auctionRepository;

    public void subscribe(Long userId, Long auctionId) {
        try {
            subscriptionRepository.subscribe(userId, auctionId);
            log.debug("User {} subscribed to auction {}", userId, auctionId);
        } catch (Exception e) {
            log.warn("Subscribe failed for user {} auction {}: {}", userId, auctionId, e.getMessage());
        }
    }

    public void unsubscribe(Long userId, Long auctionId) {
        subscriptionRepository.unsubscribe(userId, auctionId);
        log.debug("User {} unsubscribed from auction {}", userId, auctionId);
    }

    @Transactional(readOnly = true)
    public boolean isSubscribed(Long userId, Long auctionId) {
        return subscriptionRepository.isSubscribed(userId, auctionId);
    }

    @Transactional(readOnly = true)
    public List<SubscribedAuctionDto> getSubscribedAuctions(Long userId) {
        List<Long> ids = subscriptionRepository.getAuctionIds(userId);
        if (ids.isEmpty()) return Collections.emptyList();
        return auctionRepository.findAllById(ids).stream()
                .map(SubscribedAuctionDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Long> getSubscriberIds(Long auctionId) {
        return subscriptionRepository.getSubscriberIds(auctionId);
    }
}
