package com.example.autoauction.auction.domain.port;

import java.util.List;
import java.util.Optional;

public interface AuctionParticipantRepository {

    void registerParticipant(Long auctionId, Long userId, java.math.BigDecimal frozenAmount);

    boolean isParticipant(Long auctionId, Long userId);

    List<Long> getParticipants(Long auctionId);

    void markAsWinner(Long auctionId, Long userId);

    void clearParticipants(Long auctionId);
}