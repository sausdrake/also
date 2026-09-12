package com.example.autoauction.identity.infrastructure.persistence;

import com.example.autoauction.identity.domain.IdentityVerificationHistory;
import com.example.autoauction.identity.domain.port.IdentityVerificationHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Deliberately default {@code REQUIRED} propagation, not {@code REQUIRES_NEW} - a history row
 * has no idempotency key and must commit atomically with whatever caller transaction is writing
 * the corresponding current-state change (Phase 3 ADR-10). {@code REQUIRES_NEW} is used instead
 * at the APPLICATION layer, only for the one deliberate exception - recording a
 * {@code FAILED_APPROVAL_ATTEMPT} after the triggering transaction has already rolled back
 * (Phase 3.1 §1) - never inside this adapter itself.
 */
@Component
public class IdentityVerificationHistoryRepositoryAdapter implements IdentityVerificationHistoryRepository {

    private final SpringDataIdentityVerificationHistoryJpaRepository jpaRepository;
    private final IdentityVerificationHistoryMapper mapper;

    public IdentityVerificationHistoryRepositoryAdapter(SpringDataIdentityVerificationHistoryJpaRepository jpaRepository,
                                                          IdentityVerificationHistoryMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public IdentityVerificationHistory save(IdentityVerificationHistory history) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(history)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityVerificationHistory> findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(Long identityVerificationId) {
        return jpaRepository.findByIdentityVerificationIdOrderByOccurredAtAscIdAsc(identityVerificationId).stream()
                .map(mapper::toDomain).toList();
    }
}
