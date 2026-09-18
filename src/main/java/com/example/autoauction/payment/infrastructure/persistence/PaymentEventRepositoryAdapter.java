package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.PaymentEvent;
import com.example.autoauction.payment.domain.PaymentEventType;
import com.example.autoauction.payment.domain.port.PaymentEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

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

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentEvent> findLatestByPaymentId(Long paymentId) {
        return jpaRepository.findTopByPaymentIdOrderByOccurredAtDescIdDesc(paymentId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentEvent> findByPaymentIdAndType(Long paymentId, PaymentEventType type) {
        return jpaRepository.findByPaymentIdAndTypeOrderByOccurredAtAscIdAsc(paymentId, type).stream()
                .map(mapper::toDomain).toList();
    }
}
