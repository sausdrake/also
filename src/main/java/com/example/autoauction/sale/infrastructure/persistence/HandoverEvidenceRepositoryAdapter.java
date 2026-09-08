package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.HandoverEvidence;
import com.example.autoauction.sale.domain.port.HandoverEvidenceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class HandoverEvidenceRepositoryAdapter implements HandoverEvidenceRepository {

    private final SpringDataHandoverEvidenceJpaRepository jpaRepository;
    private final HandoverEvidenceMapper mapper;

    public HandoverEvidenceRepositoryAdapter(SpringDataHandoverEvidenceJpaRepository jpaRepository,
                                              HandoverEvidenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public HandoverEvidence save(HandoverEvidence handoverEvidence) {
        JpaHandoverEvidenceEntity entity = mapper.toEntity(handoverEvidence);
        JpaHandoverEvidenceEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<HandoverEvidence> findByAuctionId(Long auctionId) {
        return jpaRepository.findByAuctionId(auctionId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByAuctionId(Long auctionId) {
        return jpaRepository.existsByAuctionId(auctionId);
    }
}
