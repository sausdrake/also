package com.example.autoauction.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataPaymentEventJpaRepository extends JpaRepository<JpaPaymentEventEntity, Long> {

    List<JpaPaymentEventEntity> findByPaymentIdOrderByOccurredAtAscIdAsc(Long paymentId);

    /** Phase 31: narrow "latest event" read - see {@code PaymentEventRepository.findLatestByPaymentId}. */
    Optional<JpaPaymentEventEntity> findTopByPaymentIdOrderByOccurredAtDescIdDesc(Long paymentId);
}
