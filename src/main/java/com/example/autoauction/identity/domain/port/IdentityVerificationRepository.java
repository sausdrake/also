package com.example.autoauction.identity.domain.port;

import com.example.autoauction.identity.domain.IdentityVerification;
import com.example.autoauction.identity.domain.IdentityVerificationStatus;

import java.util.List;
import java.util.Optional;

/**
 * Phase 3 ADR-06: persistence port for the current-state aggregate. Implementations must ensure
 * {@link #save} flushes synchronously (never deferring to an implicit end-of-transaction flush),
 * so that the database's {@code UNIQUE(canonical_document_number, issuing_country, document_type)
 * WHERE status = 'VERIFIED'} partial index - the sole authoritative enforcement of the core
 * business invariant - is checked at a point in the call stack the caller can actually catch
 * (Phase 3.1 §1). This port makes no promise about HOW that is achieved; callers must not rely
 * on any particular flush timing beyond "synchronous within this call."
 */
public interface IdentityVerificationRepository {

    IdentityVerification save(IdentityVerification identityVerification);

    Optional<IdentityVerification> findById(Long id);

    /** Pessimistic write lock by the row's own id - used by admin approve/reject/revoke. */
    Optional<IdentityVerification> findByIdWithLock(Long id);

    Optional<IdentityVerification> findByUserId(Long userId);

    /** Pessimistic write lock by user id - used by self-service submission (Phase 3 §13). */
    Optional<IdentityVerification> findByUserIdWithLock(Long userId);

    /** Admin pending queue. */
    List<IdentityVerification> findByStatusOrderByCreatedAtAsc(IdentityVerificationStatus status);
}
