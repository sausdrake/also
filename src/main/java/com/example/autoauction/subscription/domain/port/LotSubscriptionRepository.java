package com.example.autoauction.subscription.domain.port;

import java.util.List;

public interface LotSubscriptionRepository {
    boolean isSubscribed(Long userId, Long auctionId);
    void subscribe(Long userId, Long auctionId);
    void unsubscribe(Long userId, Long auctionId);
    List<Long> getAuctionIds(Long userId);
    List<Long> getSubscriberIds(Long auctionId);
}
