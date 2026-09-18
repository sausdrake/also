package com.example.autoauction.payment.infrastructure.persistence;

import com.example.autoauction.payment.domain.ReconciliationCaseStatus;
import com.example.autoauction.payment.domain.ReconciliationCaseType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataReconciliationCaseJpaRepository extends JpaRepository<JpaReconciliationCaseEntity, Long> {

    Optional<JpaReconciliationCaseEntity> findByPaymentIdAndType(Long paymentId, ReconciliationCaseType type);

    List<JpaReconciliationCaseEntity> findByStatus(ReconciliationCaseStatus status);

    List<JpaReconciliationCaseEntity> findAllByOrderByOpenedAtDescIdDesc();
}
