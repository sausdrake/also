package com.example.autoauction.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataPaymentEventJpaRepository extends JpaRepository<JpaPaymentEventEntity, Long> {

    List<JpaPaymentEventEntity> findByPaymentIdOrderByOccurredAtAscIdAsc(Long paymentId);
}
