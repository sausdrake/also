package com.example.autoauction.auction.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "auction_participants")
public class JpaAuctionParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "auction_id", nullable = false)
    private Long auctionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "frozen_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal frozenAmount;

    @Column(name = "is_winner", nullable = false)
    private boolean isWinner = false;

    @Column(name = "joined_at", nullable = false)
    private OffsetDateTime joinedAt;

    protected JpaAuctionParticipantEntity() {}

    public JpaAuctionParticipantEntity(Long auctionId, Long userId, BigDecimal frozenAmount) {
        this.auctionId = auctionId;
        this.userId = userId;
        this.frozenAmount = frozenAmount;
        this.joinedAt = OffsetDateTime.now();
        this.isWinner = false;
    }

    // Геттеры и сеттеры
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAuctionId() { return auctionId; }
    public void setAuctionId(Long auctionId) { this.auctionId = auctionId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public BigDecimal getFrozenAmount() { return frozenAmount; }
    public void setFrozenAmount(BigDecimal frozenAmount) { this.frozenAmount = frozenAmount; }

    public boolean isWinner() { return isWinner; }
    public void setWinner(boolean winner) { isWinner = winner; }

    public OffsetDateTime getJoinedAt() { return joinedAt; }
    public void setJoinedAt(OffsetDateTime joinedAt) { this.joinedAt = joinedAt; }
}