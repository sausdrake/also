// diagnostic/commitment/infrastructure/persistence/SellerDiagnosticCommitmentRepositoryAdapter.java
package com.example.autoauction.diagnostic.commitment.infrastructure.persistence;

import com.example.autoauction.diagnostic.commitment.domain.FinalizationOutcome;
import com.example.autoauction.diagnostic.commitment.domain.SellerDiagnosticCommitment;
import com.example.autoauction.diagnostic.commitment.domain.port.SellerDiagnosticCommitmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

@Slf4j
@Repository
@Transactional
public class SellerDiagnosticCommitmentRepositoryAdapter implements SellerDiagnosticCommitmentRepository {

    private final SpringDataSellerDiagnosticCommitmentJpaRepository jpaRepository;
    private final SellerDiagnosticCommitmentMapper mapper;

    public SellerDiagnosticCommitmentRepositoryAdapter(SpringDataSellerDiagnosticCommitmentJpaRepository jpaRepository,
                                                        SellerDiagnosticCommitmentMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public SellerDiagnosticCommitment save(SellerDiagnosticCommitment commitment) {
        JpaSellerDiagnosticCommitmentEntity entity = mapper.toEntity(commitment);
        JpaSellerDiagnosticCommitmentEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SellerDiagnosticCommitment> findById(Long id) {
        // JpaRepository already provides findById(Long) by inheritance - no new Spring Data
        // method needed on SpringDataSellerDiagnosticCommitmentJpaRepository.
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SellerDiagnosticCommitment> findByDiagnosticRequestId(Long diagnosticRequestId) {
        return jpaRepository.findByDiagnosticRequestId(diagnosticRequestId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByDiagnosticRequestId(Long diagnosticRequestId) {
        return jpaRepository.existsByDiagnosticRequestId(diagnosticRequestId);
    }

    @Override
    public void deleteByDiagnosticRequestId(Long diagnosticRequestId) {
        jpaRepository.deleteByDiagnosticRequestId(diagnosticRequestId);
        log.debug("Deleted seller diagnostic commitment for request {}", diagnosticRequestId);
    }

    @Override
    public boolean markAwaitingFinalization(Long diagnosticRequestId) {
        int updated = jpaRepository.markAwaitingFinalization(diagnosticRequestId, OffsetDateTime.now());
        return updated > 0;
    }

    @Override
    public boolean finalize(Long diagnosticRequestId, FinalizationOutcome outcome,
                             BigDecimal penaltyAmount, BigDecimal refundAmount, OffsetDateTime finalizedAt) {
        int updated = jpaRepository.finalize(diagnosticRequestId, outcome, penaltyAmount, refundAmount, finalizedAt);
        return updated > 0;
    }
}
