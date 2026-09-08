package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.PaymentEvidence;
import com.example.autoauction.sale.domain.port.PaymentEvidenceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PaymentEvidenceRepositoryAdapter implements PaymentEvidenceRepository {

    private final SpringDataPaymentEvidenceJpaRepository jpaRepository;
    private final PaymentEvidenceMapper mapper;

    public PaymentEvidenceRepositoryAdapter(SpringDataPaymentEvidenceJpaRepository jpaRepository,
                                             PaymentEvidenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public PaymentEvidence save(PaymentEvidence paymentEvidence) {
        JpaPaymentEvidenceEntity entity = mapper.toEntity(paymentEvidence);
        JpaPaymentEvidenceEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<PaymentEvidence> findByAuctionId(Long auctionId) {
        return jpaRepository.findByAuctionId(auctionId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByAuctionId(Long auctionId) {
        return jpaRepository.existsByAuctionId(auctionId);
    }
}
