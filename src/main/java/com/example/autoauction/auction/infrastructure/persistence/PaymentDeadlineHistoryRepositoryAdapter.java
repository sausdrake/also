package com.example.autoauction.auction.infrastructure.persistence;

import com.example.autoauction.auction.domain.PaymentDeadlineHistory;
import com.example.autoauction.auction.domain.port.PaymentDeadlineHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Slice 1B: unlike {@code SettlementCorrectionRepositoryAdapter.save} (which deliberately uses
 * {@code REQUIRES_NEW} to isolate an idempotency-key race), {@link #save} here joins the caller's
 * existing transaction (default {@code REQUIRED}) - a history row has no idempotency key and
 * MUST commit atomically with the same transaction that sets {@code Auction.paymentDeadline}
 * (locked business rule), never in an isolated transaction of its own.
 */
@Component
public class PaymentDeadlineHistoryRepositoryAdapter implements PaymentDeadlineHistoryRepository {

    private final SpringDataPaymentDeadlineHistoryJpaRepository jpaRepository;
    private final PaymentDeadlineHistoryMapper mapper;

    public PaymentDeadlineHistoryRepositoryAdapter(SpringDataPaymentDeadlineHistoryJpaRepository jpaRepository,
                                                    PaymentDeadlineHistoryMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PaymentDeadlineHistory save(PaymentDeadlineHistory history) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(history)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentDeadlineHistory> findByAuctionIdOrderByChangedAtAscIdAsc(Long auctionId) {
        return jpaRepository.findByAuctionIdOrderByChangedAtAscIdAsc(auctionId).stream()
                .map(mapper::toDomain).toList();
    }
}
