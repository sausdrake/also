package com.example.autoauction.settlement.infrastructure.persistence;

import com.example.autoauction.settlement.domain.Settlement;
import com.example.autoauction.settlement.domain.SettlementStatus;
import com.example.autoauction.settlement.domain.port.SettlementRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

@Component
public class SettlementRepositoryAdapter implements SettlementRepository {

    private final SpringDataSettlementJpaRepository jpaRepository;
    private final SettlementMapper mapper;

    public SettlementRepositoryAdapter(SpringDataSettlementJpaRepository jpaRepository, SettlementMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Settlement save(Settlement settlement) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(settlement)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Settlement> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Settlement> findByAuctionId(Long auctionId) {
        return jpaRepository.findByAuctionId(auctionId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByAuctionId(Long auctionId) {
        return jpaRepository.existsByAuctionId(auctionId);
    }

    @Override
    @Transactional
    public boolean claimPayoutRecorded(Long settlementId, BigDecimal attestedPayoutAmount,
                                        Long recordedByAdminId, OffsetDateTime recordedAt) {
        return jpaRepository.claimPayoutRecordedTransition(settlementId, SettlementStatus.PENDING,
                SettlementStatus.PAYOUT_RECORDED, attestedPayoutAmount, recordedByAdminId, recordedAt) > 0;
    }
}
