package com.example.autoauction.subscription.infrastructure.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "lot_subscriptions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "auction_id"}))
public class JpaLotSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public JpaLotSubscriptionEntity() {}
    public JpaLotSubscriptionEntity(Long userId, Long auctionId) {
        this.userId = userId;
        this.auctionId = auctionId;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
