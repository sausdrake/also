package com.example.autoauction.settlement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataSettlementCorrectionJpaRepository extends JpaRepository<JpaSettlementCorrectionEntity, Long> {

    Optional<JpaSettlementCorrectionEntity> findByIdempotencyKey(String idempotencyKey);

    List<JpaSettlementCorrectionEntity> findBySettlementIdOrderByCorrectedAtAscIdAsc(Long settlementId);
}
