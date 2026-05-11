package com.example.autoauction.auction.web.dto;

import com.example.autoauction.auction.domain.AuctionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuctionWebSocketMessage {

    private Long auctionId;
    private Long vehicleId;
    private String vehicleInfo;

    private BigDecimal currentPrice;
    private BigDecimal winningBid;
    private Long winnerId;
    private String winnerName;

    private Integer totalBids;
    private AuctionStatus status;

    private OffsetDateTime endTime;
    private Long timeLeftSeconds;

    private String messageType;  // BID_PLACED, AUCTION_EXTENDED, AUCTION_ENDED, BUY_NOW
    private OffsetDateTime timestamp;
}