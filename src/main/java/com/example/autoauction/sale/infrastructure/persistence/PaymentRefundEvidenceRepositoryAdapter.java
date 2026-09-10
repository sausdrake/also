package com.example.autoauction.sale.infrastructure.persistence;

import com.example.autoauction.sale.domain.PaymentRefundEvidence;
import com.example.autoauction.sale.domain.port.PaymentRefundEvidenceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PaymentRefundEvidenceRepositoryAdapter implements PaymentRefundEvidenceRepository {

    private final SpringDataPaymentRefundEvidenceJpaRepository jpaRepository;
    private final PaymentRefundEvidenceMapper mapper;

    public PaymentRefundEvidenceRepositoryAdapter(SpringDataPaymentRefundEvidenceJpaRepository jpaRepository,
                                                  PaymentRefundEvidenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public PaymentRefundEvidence save(PaymentRefundEvidence paymentRefundEvidence) {
        JpaPaymentRefundEvidenceEntity entity = mapper.toEntity(paymentRefundEvidence);
        JpaPaymentRefundEvidenceEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<PaymentRefundEvidence> findByAuctionId(Long auctionId) {
        return jpaRepository.findByAuctionId(auctionId).map(mapper::toDomain);
    }

    @Override
    public Optional<PaymentRefundEvidence> findByPaymentEvidenceId(Long paymentEvidenceId) {
        return jpaRepository.findByPaymentEvidenceId(paymentEvidenceId).map(mapper::toDomain);
    }

    @Override
    public boolean existsByAuctionId(Long auctionId) {
        return jpaRepository.existsByAuctionId(auctionId);
    }

    @Override
    public boolean existsByPaymentEvidenceId(Long paymentEvidenceId) {
        return jpaRepository.existsByPaymentEvidenceId(paymentEvidenceId);
    }
}
