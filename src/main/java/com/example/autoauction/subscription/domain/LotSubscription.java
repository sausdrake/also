package com.example.autoauction.subscription.domain;

import java.time.OffsetDateTime;

public class LotSubscription {
    private Long id;
    private Long userId;
    private Long auctionId;
    private OffsetDateTime createdAt;

    public LotSubscription() {}
    public LotSubscription(Long userId, Long auctionId) {
        this.userId = userId;
        this.auctionId = auctionId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
