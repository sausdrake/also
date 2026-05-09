package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.port.AuctionParticipantRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@Transactional
public class JpaAuctionParticipantRepositoryAdapter implements AuctionParticipantRepository {

    private final SpringDataAuctionParticipantJpaRepository jpaRepository;

    public JpaAuctionParticipantRepositoryAdapter(SpringDataAuctionParticipantJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void registerParticipant(Long auctionId, Long userId, BigDecimal frozenAmount) {
        if (!jpaRepository.existsByAuctionIdAndUserId(auctionId, userId)) {
            JpaAuctionParticipantEntity entity = new JpaAuctionParticipantEntity(auctionId, userId, frozenAmount);
            jpaRepository.save(entity);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isParticipant(Long auctionId, Long userId) {
        return jpaRepository.existsByAuctionIdAndUserId(auctionId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> getParticipants(Long auctionId) {
        return jpaRepository.findByAuctionId(auctionId).stream()
                .map(JpaAuctionParticipantEntity::getUserId)
                .collect(Collectors.toList());
    }

    @Override
    public void markAsWinner(Long auctionId, Long userId) {
        jpaRepository.markAsWinner(auctionId, userId);
    }

    @Override
    public void clearParticipants(Long auctionId) {
        jpaRepository.deleteByAuctionId(auctionId);
    }
}