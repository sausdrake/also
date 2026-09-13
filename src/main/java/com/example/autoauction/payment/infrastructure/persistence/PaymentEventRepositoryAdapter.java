package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class PaymentEventRepositoryAdapter implements PaymentEventRepository {

    private final SpringDataPaymentEventJpaRepository jpaRepository;
    private final PaymentEventMapper mapper;

    public PaymentEventRepositoryAdapter(SpringDataPaymentEventJpaRepository jpaRepository, PaymentEventMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public PaymentEvent append(PaymentEvent event) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(event)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentEvent> findByPaymentId(Long paymentId) {
        return jpaRepository.findByPaymentIdOrderByOccurredAtAscIdAsc(paymentId).stream()
                .map(mapper::toDomain).toList();
    }
}
