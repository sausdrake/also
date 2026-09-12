package com.example.autoauction.identity.application;

import com.example.autoauction.identity.domain.IdentityVerification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Phase 4.2: extracted from {@link AdminIdentityVerificationService} specifically so that
 * {@code approve()} and {@code recordFailedApprovalAttempt()} are invoked through that bean's own
 * Spring transactional PROXY, never via same-bean self-invocation. Phase 4.1's real-PostgreSQL
 * transaction-trace audit proved that a same-bean {@code this.approve(...)} call silently bypasses
 * {@code @Transactional} - this class being a genuinely SEPARATE bean, called only via its own
 * injected reference to {@link AdminIdentityVerificationService}, is what makes the proxy apply.
 *
 * <p>Deliberately NOT {@code @Transactional} itself - if it were, the call into {@code approve()}
 * would join this same (REQUIRED) transaction instead of running in its own, defeating the
 * isolation {@code recordFailedApprovalAttempt()}'s {@code REQUIRES_NEW} exists to provide.
 *
 * <p>Catches exactly the three concrete conflict shapes {@code approve()}'s call can ever
 * actually produce, each confirmed by a real-PostgreSQL transaction trace (Phase 4.1/4.2), not
 * assumed from documentation:
 * <ul>
 *   <li>{@link DataIntegrityViolationException} - the cross-account partial-unique-index
 *       violation (two different rows, same canonical identity).</li>
 *   <li>{@link IllegalStateException} - the same-row race, <strong>once the proxy fix correctly
 *       restored the pessimistic lock's serialization power</strong>: the losing admin's
 *       {@code findByIdWithLock} genuinely blocks until the winner commits, then reads the
 *       already-VERIFIED row and {@code IdentityVerification.approve()}'s own
 *       {@code requireStatus} guard throws - confirmed by trace to be the actual mechanism now,
 *       superseding the pre-fix behavior where this same race was (incorrectly) resolved by an
 *       unguarded {@code @Version} collision.</li>
 *   <li>{@link ObjectOptimisticLockingFailureException} - kept as a defensive second path for the
 *       same-row race in case two {@code findByIdWithLock} calls are ever not fully serialized by
 *       the pessimistic lock (e.g. a future isolation-level change); still a genuine "someone
 *       else changed this row first" condition, never an unrelated failure.</li>
 * </ul>
 * Deliberately scoped to wrap only the single call to {@code approve()} - {@code approve()}'s own
 * method body has no other source of {@code IllegalStateException}, so this catch cannot
 * accidentally swallow an unrelated business-rule violation from elsewhere. Never a broader
 * {@code DataAccessException}/{@code Throwable} catch - an unrelated persistence failure (a
 * connection error, an unrelated constraint) must propagate as a genuine failure, not be
 * misclassified as an identity-approval conflict.
 */
@Slf4j
@Component
public class AdminIdentityApprovalOrchestrator {

    private final AdminIdentityVerificationService adminIdentityVerificationService;

    public AdminIdentityApprovalOrchestrator(AdminIdentityVerificationService adminIdentityVerificationService) {
        this.adminIdentityVerificationService = adminIdentityVerificationService;
    }

    public IdentityVerification approveWithConflictHandling(Long id, Long adminId) {
        try {
            return adminIdentityVerificationService.approve(id, adminId);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException | IllegalStateException e) {
            // Fresh, separate read - the prior transaction is already fully rolled back by now.
            // The row's own canonicalDocumentNumber was never changed by the failed write (only
            // its status attempted to change and failed), so this value is still accurate.
            IdentityVerification stale = adminIdentityVerificationService.inspect(id);
            adminIdentityVerificationService.recordFailedApprovalAttempt(id, stale.getCanonicalDocumentNumber(), adminId);
            log.warn("Admin {} lost a concurrent approval race for identity verification {}", adminId, id);
            throw new IllegalStateException("Эта личность уже подтверждена для другого аккаунта", e);
        }
    }
}
